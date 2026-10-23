package fr.acinq.lightning.payment

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.utils.Either
import fr.acinq.bitcoin.utils.Try
import fr.acinq.bitcoin.utils.flatMap
import fr.acinq.bitcoin.utils.runTrying
import fr.acinq.lightning.wire.*
import fr.acinq.lightning.wire.OfferTypes.InvoiceAmount
import fr.acinq.lightning.wire.OfferTypes.InvoiceBlindedPay
import fr.acinq.lightning.wire.OfferTypes.InvoiceCreatedAt
import fr.acinq.lightning.wire.OfferTypes.InvoiceFallbacks
import fr.acinq.lightning.wire.OfferTypes.InvoiceFeatures
import fr.acinq.lightning.wire.OfferTypes.InvoiceNodeId
import fr.acinq.lightning.wire.OfferTypes.InvoicePaths
import fr.acinq.lightning.wire.OfferTypes.InvoicePaymentHash
import fr.acinq.lightning.wire.OfferTypes.InvoicePreimage
import fr.acinq.lightning.wire.OfferTypes.InvoiceRelativeExpiry
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestAmount
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestChain
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestFeatures
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestMetadata
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestPayerId
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestPayerNote
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestQuantity
import fr.acinq.lightning.wire.OfferTypes.InvoiceTlv
import fr.acinq.lightning.wire.OfferTypes.LeafHashes
import fr.acinq.lightning.wire.OfferTypes.MissingHashes
import fr.acinq.lightning.wire.OfferTypes.OfferAbsoluteExpiry
import fr.acinq.lightning.wire.OfferTypes.OfferAmount
import fr.acinq.lightning.wire.OfferTypes.OfferChains
import fr.acinq.lightning.wire.OfferTypes.OfferCurrency
import fr.acinq.lightning.wire.OfferTypes.OfferDescription
import fr.acinq.lightning.wire.OfferTypes.OfferFeatures
import fr.acinq.lightning.wire.OfferTypes.OfferIssuer
import fr.acinq.lightning.wire.OfferTypes.OfferIssuerId
import fr.acinq.lightning.wire.OfferTypes.OfferMetadata
import fr.acinq.lightning.wire.OfferTypes.OfferPaths
import fr.acinq.lightning.wire.OfferTypes.OfferQuantityMax
import fr.acinq.lightning.wire.OfferTypes.OmittedTlvs
import fr.acinq.lightning.wire.OfferTypes.PayerProofTlv
import fr.acinq.lightning.wire.OfferTypes.ProofNote
import fr.acinq.lightning.wire.OfferTypes.ProofSignature
import fr.acinq.lightning.wire.OfferTypes.Signature

/**
 * Bolt12 payer proofs provide a cryptographic proof that a given Bolt12 invoice was paid.
 * It doesn't reveal all the fields of the invoice, only the necessary ones.
 */
data class PayerProof(val records: TlvStream<PayerProofTlv>) {
    val paymentHash: ByteVector32 = records.get<InvoicePaymentHash>()!!.hash
    val preimage: ByteVector32 = records.get<InvoicePreimage>()!!.preimage
    val invoiceNodeId: PublicKey = records.get<InvoiceNodeId>()!!.nodeId
    val payerId: PublicKey = records.get<InvoiceRequestPayerId>()!!.publicKey

    fun verifySigs(): Boolean {
        when (validate(records)) {
            is Either.Left -> return false
            is Either.Right -> {
                // The proof signature must be valid: it signs all non-signature TLVs using the invreq_payer_id.
                val payerSig = records.get<ProofSignature>()!!.signature
                val payerId = records.get<InvoiceRequestPayerId>()!!.publicKey
                val proofRootHash = OfferTypes.rootHash(TlvStream(records.records.filterNot { it is Signature || it is ProofSignature }.toSet(), records.unknown))
                if (!OfferTypes.verifySchnorr(signatureTag, proofRootHash, payerSig, payerId)) {
                    return false
                }
                // The proof must contain enough data to reconstruct the invoice merkle root.
                val leafNonces = records.get<LeafHashes>()?.hashes ?: listOf()
                val markers = records.get<OmittedTlvs>()?.missing ?: listOf()
                val missingHashes = records.get<MissingHashes>()?.missing ?: listOf()
                // Disclosed invoice merkle-tree leaves: invoice fields (excluding the invoice signature) and unknown TLVs.
                val knownLeaves = records.records
                    .filterNot { it is Signature }
                    .filterIsInstance<InvoiceTlv>()
                    .map { EncodedTlv(it.tag, it.write().byteVector()) }
                val disclosedLeaves = (knownLeaves + records.unknown.map { EncodedTlv(it.tag, it.value) }).sortedBy { it.tag }
                val tagSet = disclosedLeaves.map { it.tag }.toSet()
                // The omitted_tlvs field cannot contain the number of an included invoice TLV field.
                // The leaf_hashes field must contain exactly one hash for each non-signature invoice TLV field.
                if (leafNonces.size != disclosedLeaves.size || markers.any { tagSet.contains(it) }) {
                    return false
                }
                // We combine disclosed and omitted leaves and sort them.
                val allLeavesExceptMetadata = run {
                    val disclosedWithNonce = disclosedLeaves
                        .zip(leafNonces)
                        .map { (tlv, nonce) -> Pair(tlv.tag, LeafWithNonce(tlv, nonce)) }
                    val undisclosed: List<Pair<Long, LeafWithNonce?>> = markers.map { Pair(it, null) }
                    (disclosedWithNonce + undisclosed).sortedBy { it.first }
                }
                // Each omitted leaf's marker must equal the previous leaf's value + 1 (with 0 implied for the always-omitted invreq_metadata at tag 0).
                val omittedLeavesOk = allLeavesExceptMetadata.withIndex().all { (i, leaf) ->
                    when {
                        leaf.second != null -> true
                        leaf.second == null && i == 0 -> leaf.first == 1L
                        else -> {
                            val previous = allLeavesExceptMetadata[i - 1].first
                            val expected = if (previous == 239L) 1_000_000_000L else previous + 1
                            leaf.first == expected
                        }
                    }
                }
                if (!omittedLeavesOk) {
                    return false
                }
                // The first leaf is always the implicitly-omitted invreq_metadata.
                val allLeaves = (listOf(null) + allLeavesExceptMetadata.map { it.second })
                return when (val invoiceRootHash = PayerProofTree.merkleRoot(allLeaves, missingHashes)) {
                    is Either.Left -> false
                    is Either.Right -> {
                        // The invoice signature must be valid against invoice_node_id over the reconstructed merkle root.
                        val invoiceSig = records.get<Signature>()!!.signature
                        val invoiceNodeId = records.get<InvoiceNodeId>()!!.nodeId
                        OfferTypes.verifySchnorr(Bolt12Invoice.signatureTag, invoiceRootHash.value, invoiceSig, invoiceNodeId)
                    }
                }
            }
        }
    }

    override fun toString(): String {
        val data = tlvSerializer.write(records)
        return Bech32.encodeBytes(hrp, data, Bech32.Encoding.Beck32WithoutChecksum)
    }

    companion object {
        val hrp = "lnp"
        val signatureTag: ByteVector = ByteVector(("lightning" + "payer_proof" + "proof_signature").encodeToByteArray())

        /** Specifies which optional fields from the invoice should be included in the payer proof. */
        data class IncludedFields(
            val offerChains: Boolean = false,
            val offerMetadata: Boolean = false,
            val offerCurrency: Boolean = false,
            val offerAmount: Boolean = false,
            val offerDescription: Boolean = false,
            val offerFeatures: Boolean = false,
            val offerAbsoluteExpiry: Boolean = false,
            val offerPaths: Boolean = false,
            val offerIssuer: Boolean = false,
            val offerQuantityMax: Boolean = false,
            val offerNodeId: Boolean = false,
            val invoiceRequestChain: Boolean = false,
            val invoiceRequestAmount: Boolean = false,
            val invoiceRequestFeatures: Boolean = false,
            val invoiceRequestQuantity: Boolean = false,
            val invoiceRequestPayerNote: Boolean = false,
            val invoicePaths: Boolean = false,
            val invoiceBlindedPay: Boolean = false,
            val invoiceCreatedAt: Boolean = false,
            val invoiceRelativeExpiry: Boolean = false,
            val invoiceAmount: Boolean = false,
            val invoiceFallbacks: Boolean = false,
            val unknown: Set<Long> = emptySet()
        )

        fun create(invoice: Bolt12Invoice, preimage: ByteVector32, payerKey: PrivateKey, fields: IncludedFields, note: String?): PayerProof {
            // Valid Bolt12 invoices always contain the invreq_metadata field: it is used as a hashing nonce to prevent brute-forcing private fields.
            val invreqMetadata = invoice.records.get<InvoiceRequestMetadata>()!!
            // We select invoice fields that we want to include in our payer proof.
            val knownLeaves: Set<Pair<InvoiceTlv, Boolean>> = invoice.records.records
                .filterNot { it is Signature }
                .map { tlv ->
                    when (tlv) {
                        is OfferChains -> Pair(tlv, fields.offerChains)
                        is OfferMetadata -> Pair(tlv, fields.offerMetadata)
                        is OfferCurrency -> Pair(tlv, fields.offerCurrency)
                        is OfferAmount -> Pair(tlv, fields.offerAmount)
                        is OfferDescription -> Pair(tlv, fields.offerDescription)
                        is OfferFeatures -> Pair(tlv, fields.offerFeatures)
                        is OfferAbsoluteExpiry -> Pair(tlv, fields.offerAbsoluteExpiry)
                        is OfferPaths -> Pair(tlv, fields.offerPaths)
                        is OfferIssuer -> Pair(tlv, fields.offerIssuer)
                        is OfferQuantityMax -> Pair(tlv, fields.offerQuantityMax)
                        is OfferIssuerId -> Pair(tlv, fields.offerNodeId)
                        is InvoiceRequestMetadata -> Pair(tlv, false)
                        is InvoiceRequestChain -> Pair(tlv, fields.invoiceRequestChain)
                        is InvoiceRequestAmount -> Pair(tlv, fields.invoiceRequestAmount)
                        is InvoiceRequestFeatures -> Pair(tlv, fields.invoiceRequestFeatures)
                        is InvoiceRequestQuantity -> Pair(tlv, fields.invoiceRequestQuantity)
                        is InvoiceRequestPayerId -> Pair(tlv, true)
                        is InvoiceRequestPayerNote -> Pair(tlv, fields.invoiceRequestPayerNote)
                        is InvoicePaths -> Pair(tlv, fields.invoicePaths)
                        is InvoiceBlindedPay -> Pair(tlv, fields.invoiceBlindedPay)
                        is InvoiceCreatedAt -> Pair(tlv, fields.invoiceCreatedAt)
                        is InvoiceRelativeExpiry -> Pair(tlv, fields.invoiceRelativeExpiry)
                        is InvoicePaymentHash -> Pair(tlv, true)
                        is InvoiceAmount -> Pair(tlv, fields.invoiceAmount)
                        is InvoiceFallbacks -> Pair(tlv, fields.invoiceFallbacks)
                        is InvoiceFeatures -> Pair(tlv, true)
                        is InvoiceNodeId -> Pair(tlv, true)
                        is Signature -> Pair(tlv, false) // already filtered out above
                    }
                }.toSet()
            // We must also include unknown TLVs, otherwise the merkle tree will be incomplete.
            val unknownLeaves: Set<Pair<GenericTlv, Boolean>> = invoice.records.unknown.map { tlv ->
                Pair(tlv, fields.unknown.contains(tlv.tag))
            }.toSet()
            // We convert everything to generic TLVs to combine known and unknown leaves.
            val leaves = (knownLeaves + unknownLeaves)
                .sortedBy { it.first.tag }
                .map { (tlv, included) -> PayerProofTree.Leaf(EncodedTlv(tlv.tag, tlv.write().byteVector()), included) }
            // We include the leaf nonce for each invoice TLV we include in the payer proof.
            val leafNonces = leaves.filter { it.included }.map { PayerProofTree.leafNonce(invreqMetadata, it.tlv) }
            val omittedTlvs = leaves.fold(Pair<List<Long>, Long?>(listOf(), null)) { (omitted, previousTlv), leaf ->
                when {
                    // This TLV is included in the payer proof, so we don't add an entry (it is not omitted).
                    // However, we tell the next TLV that they need to use a marker higher than the current TLV tag.
                    leaf.included -> Pair(omitted, leaf.tlv.tag)
                    // The invreq_metadata field is always implicitly omitted (not included in omitted fields).
                    leaf.tlv.tag == 0L -> Pair(omitted, previousTlv)
                    else -> {
                        // This TLV is not included in the payer proof: we add an entry with a marker.
                        val markerNumber = when {
                            previousTlv != null -> previousTlv + 1
                            omitted.isEmpty() -> 1L
                            else -> when {
                                // We skip other the range [240;1_000_000_000[
                                omitted.last() == 239L -> 1_000_000_000L
                                else -> omitted.last() + 1
                            }
                        }
                        Pair(omitted + listOf(markerNumber), null)
                    }
                }
            }.first
            val proofTree = PayerProofTree.create(leaves)
            val missingHashes = PayerProofTree.computeMissingHashes(proofTree, invreqMetadata)
            val includedInvoiceTlvs = knownLeaves.filter { it.second }.map { it.first }.toSet<PayerProofTlv>()
            val payerProofTlvs = setOfNotNull(
                InvoicePreimage(preimage),
                LeafHashes(leafNonces),
                if (omittedTlvs.isNotEmpty()) OmittedTlvs(omittedTlvs) else null,
                if (missingHashes.isNotEmpty()) MissingHashes(missingHashes) else null,
                note?.let { ProofNote(it) },
            )
            val includedUnknownTlvs = unknownLeaves.filter { it.second }.map { it.first }.toSet()
            // The invoice signature is included in the final payer proof but is excluded from the proof merkle root (signature fields are never signed).
            // Note that the invreq_metadata isn't disclosed in the proof: we will use the first TLV as a hashing nonce instead.
            val proofSig = OfferTypes.signSchnorr(signatureTag, OfferTypes.rootHash(TlvStream(includedInvoiceTlvs + payerProofTlvs, includedUnknownTlvs)), payerKey)
            val finalTlvs = TlvStream(includedInvoiceTlvs + setOfNotNull(invoice.records.get<Signature>()) + payerProofTlvs + setOf(ProofSignature(proofSig)), includedUnknownTlvs)
            return PayerProof(finalTlvs)
        }

        /** We combine known and unknown TLVs into a generic form to aggregate them. */
        data class EncodedTlv(val tag: Long, val value: ByteVector) {
            fun write(): ByteArray {
                val out = ByteArrayOutput()
                LightningCodecs.writeBigSize(tag, out)
                LightningCodecs.writeBigSize(value.size().toLong(), out)
                LightningCodecs.writeBytes(value, out)
                return out.toByteArray()
            }
        }

        /** When validating a proof, the leaf nonce is directly provided to avoid brute-forcing omitted fields. */
        data class LeafWithNonce(val tlv: EncodedTlv, val nonce: ByteVector32)

        sealed class PayerProofTree {
            /** True if all leaves of that subtree are included. */
            abstract val included: Boolean
            /** The invreq_metadata (mandatory) field is used as a nonce to provide randomness. */
            abstract fun hash(invreqMetadata: InvoiceRequestMetadata): ByteVector32

            data class Leaf(val tlv: EncodedTlv, override val included: Boolean) : PayerProofTree() {
                override fun hash(invreqMetadata: InvoiceRequestMetadata): ByteVector32 {
                    val nonce = leafNonce(invreqMetadata, tlv)
                    return combine(nonce, OfferTypes.hash("LnLeaf".encodeToByteArray(), tlv.write()).byteVector32())
                }
            }

            data class Branch(val left: PayerProofTree, val right: PayerProofTree, override val included: Boolean) : PayerProofTree() {
                override fun hash(invreqMetadata: InvoiceRequestMetadata): ByteVector32 {
                    return combine(left.hash(invreqMetadata), right.hash(invreqMetadata))
                }
            }

            companion object {
                fun create(leaves: List<Leaf>): PayerProofTree = merkleTree(leaves, 0, leaves.size)

                fun computeMissingHashes(tree: PayerProofTree, invreqMetadata: InvoiceRequestMetadata): List<ByteVector32> {
                    return when {
                        !tree.included -> listOf(tree.hash(invreqMetadata))
                        tree is Leaf -> listOf()
                        tree is Branch -> when {
                            // Post-order DFS: at each internal node with exactly one fully-omitted branch, emit that branch's hash
                            // *after* recursing into the other (mixed) branch.
                            tree.left.included && tree.right.included -> computeMissingHashes(tree.left, invreqMetadata) + computeMissingHashes(tree.right, invreqMetadata)
                            !tree.left.included && tree.right.included -> computeMissingHashes(tree.right, invreqMetadata) + listOf(tree.left.hash(invreqMetadata))
                            tree.left.included && !tree.right.included -> computeMissingHashes(tree.left, invreqMetadata) + listOf(tree.right.hash(invreqMetadata))
                            else -> listOf() // unreachable: parent's `included` would be false
                        }
                        else -> listOf()
                    }
                }

                /** Leaves are combined with a nonce acting as a neighbor leaf. */
                fun leafNonce(invreqMetadata: InvoiceRequestMetadata, tlv: EncodedTlv): ByteVector32 {
                    val encodedMetadata = EncodedTlv(invreqMetadata.tag, invreqMetadata.write().byteVector()).write()
                    val encodedTlvTag = run {
                        val out = ByteArrayOutput()
                        LightningCodecs.writeBigSize(tlv.tag, out)
                        out.toByteArray()
                    }
                    return OfferTypes.hash("LnNonce".encodeToByteArray() + encodedMetadata, encodedTlvTag).byteVector32()
                }

                fun combine(h1: ByteVector32, h2: ByteVector32): ByteVector32 = when {
                    LexicographicalOrdering.isLessThan(h1, h2) -> OfferTypes.hash("LnBranch".encodeToByteArray(), h1.toByteArray() + h2.toByteArray()).byteVector32()
                    else -> OfferTypes.hash("LnBranch".encodeToByteArray(), h2.toByteArray() + h1.toByteArray()).byteVector32()
                }

                fun merkleTree(leaves: List<Leaf>, i: Int, j: Int): PayerProofTree {
                    return when {
                        j - i == 1 -> leaves[i]
                        else -> {
                            val k = i + previousPowerOfTwo(j - i)
                            val left = merkleTree(leaves, i, k)
                            val right = merkleTree(leaves, k, j)
                            Branch(left, right, left.included || right.included)
                        }
                    }
                }

                fun merkleRoot(leaves: List<LeafWithNonce?>, missingHashes: List<ByteVector32>): Either<String, ByteVector32> {
                    return merkleRoot(leaves, missingHashes, 0, leaves.size).flatMap { (root, remainingHashes) ->
                        when {
                            remainingHashes.isNotEmpty() -> Either.Left("invalid payer proof: missing_hashes not empty after recomputing invoice merkle root")
                            root == null -> Either.Left("invalid payer proof: cannot recompute invoice merkle root")
                            else -> Either.Right(root)
                        }

                    }
                }

                /** Recompute the merkle root, consuming missing_hashes in post-order DFS (smallest TLV first). */
                private fun merkleRoot(leaves: List<LeafWithNonce?>, missingHashes: List<ByteVector32>, i: Int, j: Int): Either<String, Pair<ByteVector32?, List<ByteVector32>>> {
                    return if (j - i == 1) {
                        // We've reached a leaf: it is either included or omitted, but doesn't consume any missing hash yet.
                        when (val leaf = leaves[i]) {
                            null -> Either.Right(Pair(null, missingHashes))
                            else -> {
                                val hash = combine(leaf.nonce, OfferTypes.hash("LnLeaf".encodeToByteArray(), leaf.tlv.write()).byteVector32())
                                Either.Right(Pair(hash, missingHashes))
                            }
                        }
                    } else {
                        val k = i + previousPowerOfTwo(j - i)
                        when (val leftSide = merkleRoot(leaves, missingHashes, i, k)) {
                            is Either.Left -> leftSide
                            is Either.Right -> {
                                val (leftRoot, remainingHashesAfterLeft) = leftSide.value
                                when (val rightSide = merkleRoot(leaves, remainingHashesAfterLeft, k, j)) {
                                    is Either.Left -> rightSide
                                    is Either.Right -> {
                                        val (rightRoot, remainingHashes) = rightSide.value
                                        when {
                                            // Both subtrees are (at least partially) included.
                                            leftRoot != null && rightRoot != null -> Either.Right(Pair(combine(leftRoot, rightRoot), remainingHashes))
                                            // Both subtrees are fully omitted: the parent tree is fully omitted as well.
                                            leftRoot == null && rightRoot == null -> Either.Right(Pair(null, remainingHashes))
                                            // One of the subtrees is omitted: we use missing_hashes to get its merkle root.
                                            leftRoot != null && rightRoot == null && remainingHashes.isNotEmpty() -> Either.Right(Pair(combine(leftRoot, remainingHashes.first()), remainingHashes.drop(1)))
                                            leftRoot == null && rightRoot != null && remainingHashes.isNotEmpty() -> Either.Right(Pair(combine(remainingHashes.first(), rightRoot), remainingHashes.drop(1)))
                                            // One of the subtrees is omitted, but we don't have missing hashes to consume left.
                                            else -> Either.Left("cannot recompute invoice merkle root: missing_hashes is incomplete")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                fun previousPowerOfTwo(n: Int): Int {
                    var p = 1
                    while (p < n) {
                        p = p shl 1
                    }
                    return p shr 1
                }
            }
        }

        val tlvSerializer = TlvStreamSerializer(
            false, @Suppress("UNCHECKED_CAST") mapOf(
                // Invoice part that must be copy-pasted from the Bolt12 invoice serializer.
                InvoiceRequestMetadata.tag to InvoiceRequestMetadata as TlvValueReader<PayerProofTlv>,
                OfferChains.tag to OfferChains as TlvValueReader<PayerProofTlv>,
                OfferMetadata.tag to OfferMetadata as TlvValueReader<PayerProofTlv>,
                OfferCurrency.tag to OfferCurrency as TlvValueReader<PayerProofTlv>,
                OfferAmount.tag to OfferAmount as TlvValueReader<PayerProofTlv>,
                OfferDescription.tag to OfferDescription as TlvValueReader<PayerProofTlv>,
                OfferFeatures.tag to OfferFeatures as TlvValueReader<PayerProofTlv>,
                OfferAbsoluteExpiry.tag to OfferAbsoluteExpiry as TlvValueReader<PayerProofTlv>,
                OfferPaths.tag to OfferPaths as TlvValueReader<PayerProofTlv>,
                OfferIssuer.tag to OfferIssuer as TlvValueReader<PayerProofTlv>,
                OfferQuantityMax.tag to OfferQuantityMax as TlvValueReader<PayerProofTlv>,
                OfferIssuerId.tag to OfferIssuerId as TlvValueReader<PayerProofTlv>,
                InvoiceRequestChain.tag to InvoiceRequestChain as TlvValueReader<PayerProofTlv>,
                InvoiceRequestAmount.tag to InvoiceRequestAmount as TlvValueReader<PayerProofTlv>,
                InvoiceRequestFeatures.tag to InvoiceRequestFeatures as TlvValueReader<PayerProofTlv>,
                InvoiceRequestQuantity.tag to InvoiceRequestQuantity as TlvValueReader<PayerProofTlv>,
                InvoiceRequestPayerId.tag to InvoiceRequestPayerId as TlvValueReader<PayerProofTlv>,
                InvoiceRequestPayerNote.tag to InvoiceRequestPayerNote as TlvValueReader<PayerProofTlv>,
                InvoicePaths.tag to InvoicePaths as TlvValueReader<PayerProofTlv>,
                InvoiceBlindedPay.tag to InvoiceBlindedPay as TlvValueReader<PayerProofTlv>,
                InvoiceCreatedAt.tag to InvoiceCreatedAt as TlvValueReader<PayerProofTlv>,
                InvoiceRelativeExpiry.tag to InvoiceRelativeExpiry as TlvValueReader<PayerProofTlv>,
                InvoicePaymentHash.tag to InvoicePaymentHash as TlvValueReader<PayerProofTlv>,
                InvoiceAmount.tag to InvoiceAmount as TlvValueReader<PayerProofTlv>,
                InvoiceFallbacks.tag to InvoiceFallbacks as TlvValueReader<PayerProofTlv>,
                InvoiceFeatures.tag to InvoiceFeatures as TlvValueReader<PayerProofTlv>,
                InvoiceNodeId.tag to InvoiceNodeId as TlvValueReader<PayerProofTlv>,
                Signature.tag to Signature as TlvValueReader<PayerProofTlv>,
                // Payer proof part.
                ProofSignature.tag to ProofSignature as TlvValueReader<PayerProofTlv>,
                InvoicePreimage.tag to InvoicePreimage as TlvValueReader<PayerProofTlv>,
                OmittedTlvs.tag to OmittedTlvs as TlvValueReader<PayerProofTlv>,
                MissingHashes.tag to MissingHashes as TlvValueReader<PayerProofTlv>,
                LeafHashes.tag to LeafHashes as TlvValueReader<PayerProofTlv>,
                ProofNote.tag to ProofNote as TlvValueReader<PayerProofTlv>,
            )
        )

        private fun validate(records: TlvStream<PayerProofTlv>): Either<InvalidTlvPayload, PayerProof> {
            if (records.get<InvoiceRequestPayerId>() == null) return Either.Left(MissingRequiredTlv(InvoiceRequestPayerId.tag))
            if (records.get<InvoicePaymentHash>() == null) return Either.Left(MissingRequiredTlv(InvoicePaymentHash.tag))
            if (records.get<InvoiceNodeId>() == null) return Either.Left(MissingRequiredTlv(InvoiceNodeId.tag))
            if (records.get<Signature>() == null) return Either.Left(MissingRequiredTlv(Signature.tag))
            if (records.get<ProofSignature>() == null) return Either.Left(MissingRequiredTlv(ProofSignature.tag))
            if (records.get<InvoicePreimage>() == null) return Either.Left(MissingRequiredTlv(InvoicePreimage.tag))
            // The preimage must match the invoice's payment_hash.
            if (Crypto.sha256(records.get<InvoicePreimage>()!!.preimage).byteVector32() != records.get<InvoicePaymentHash>()!!.hash) return Either.Left(InvalidTlvValue(InvoicePreimage.tag))
            val omittedTlvs = records.get<OmittedTlvs>()?.missing ?: listOf()
            if (omittedTlvs.size != omittedTlvs.distinct().size) return Either.Left(InvalidTlvValue(OmittedTlvs.tag))
            if (omittedTlvs.sorted() != omittedTlvs) return Either.Left(InvalidTlvValue(OmittedTlvs.tag))
            if (!omittedTlvs.all { (it in 1..239) || (it in 1_000_000_000L..3_999_999_999L) }) return Either.Left(InvalidTlvValue(OmittedTlvs.tag))
            return Either.Right(PayerProof(records))
        }

        fun decode(input: String): Try<PayerProof> = runTrying {
            val (prefix, encoded, encoding) = Bech32.decodeBytes(input.lowercase(), true)
            require(prefix == hrp)
            require(encoding == Bech32.Encoding.Beck32WithoutChecksum)
            val tlvs = tlvSerializer.read(encoded)
            when (val proof = validate(tlvs)) {
                is Either.Left -> throw IllegalArgumentException(proof.value.toString())
                is Either.Right -> proof.value
            }
        }
    }

}