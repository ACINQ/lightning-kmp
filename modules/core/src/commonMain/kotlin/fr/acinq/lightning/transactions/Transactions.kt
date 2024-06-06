/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.lightning.transactions

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.crypto.musig2.Musig2
import fr.acinq.bitcoin.crypto.musig2.SecretNonce
import fr.acinq.bitcoin.utils.Either
import fr.acinq.bitcoin.utils.Try
import fr.acinq.bitcoin.utils.runTrying
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.Commitments
import fr.acinq.lightning.io.*
import fr.acinq.lightning.transactions.CommitmentOutput.InHtlc
import fr.acinq.lightning.transactions.CommitmentOutput.OutHtlc
import fr.acinq.lightning.transactions.Scripts.Taproot
import fr.acinq.lightning.transactions.Scripts.witnessToLocalDelayedAfterDelay
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.UpdateAddHtlc
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Type alias for a collection of commitment output links */
typealias TransactionsCommitmentOutputs = List<Transactions.CommitmentOutputLink<CommitmentOutput>>

/**
 * Created by PM on 15/12/2016.
 */
object Transactions {

    const val MAX_STANDARD_TX_WEIGHT = 400_000
    val NUMS_POINT = PublicKey.fromHex("02dca094751109d0bd055d03565874e8276dd53e926b44e3bd1bb6bf4bc130a279")

    /**
     * to spend the output of a taproot transactions, we need to know the script tree and internal key used to build this output
     */
    @Serializable
    data class ScriptTreeAndInternalKey(
        @Contextual val scriptTree: ScriptTree,
        @Contextual val internalKey: XonlyPublicKey
    ) {
        val publicKeyScript: ByteVector get() = Script.write(Script.pay2tr(internalKey, scriptTree)).byteVector()
    }

    @Serializable
    data class InputInfo constructor(
        @Contextual val outPoint: OutPoint,
        @Contextual val txOut: TxOut,
        @Contextual val redeemScript: ByteVector,
        @Transient val scriptTreeAndInternalKey: ScriptTreeAndInternalKey? = null
    ) {
        init {
            require(redeemScript.isEmpty() == (scriptTreeAndInternalKey != null))
        }
        constructor(outPoint: OutPoint, txOut: TxOut, scriptTreeAndInternalKey: ScriptTreeAndInternalKey) : this(outPoint, txOut, ByteVector.empty, scriptTreeAndInternalKey)
        constructor(outPoint: OutPoint, txOut: TxOut, redeemScript: List<ScriptElt>) : this(outPoint, txOut, ByteVector(Script.write(redeemScript)), null)
    }

    @Serializable
    sealed class TransactionWithInputInfo {
        abstract val input: InputInfo
        abstract val tx: Transaction
        val fee: Satoshi get() = input.txOut.amount - tx.txOut.map { it.amount }.sum()
        val minRelayFee: Satoshi
            get() {
                val vsize = (tx.weight() + 3) / 4
                return (FeeratePerKw.MinimumRelayFeeRate * vsize / 1000).sat
            }

        open fun sign(key: PrivateKey, sigHash: Int = SigHash.SIGHASH_ALL): ByteVector64 {
            val inputIndex = tx.txIn.indexOfFirst { it.outPoint == input.outPoint }
            require(inputIndex >= 0) { "transaction doesn't spend the input to sign" }
            return sign(tx, inputIndex, input.redeemScript.toByteArray(), input.txOut.amount, key, sigHash)
        }

        open fun checkSig(sig: ByteVector64, pubKey: PublicKey, sigHash: Int = SigHash.SIGHASH_ALL): Boolean {
            val data = Transaction.hashForSigning(tx, 0, input.redeemScript.toByteArray(), sigHash, input.txOut.amount, SigVersion.SIGVERSION_WITNESS_V0)
            return Crypto.verifySignature(data, sig, pubKey)
        }

        @Serializable
        data class SpliceTx(override val input: InputInfo, @Contextual override val tx: Transaction) : TransactionWithInputInfo()

        @Serializable
        data class CommitTx(override val input: InputInfo, @Contextual override val tx: Transaction) : TransactionWithInputInfo()

        @Serializable
        sealed class HtlcTx : TransactionWithInputInfo() {
            abstract val htlcId: Long

            @Serializable
            data class HtlcSuccessTx(
                override val input: InputInfo,
                @Contextual override val tx: Transaction,
                @Contextual val paymentHash: ByteVector32,
                override val htlcId: Long
            ) : HtlcTx() {
                override fun sign(key: PrivateKey, sigHash: Int): ByteVector64 {
                    return when (input.scriptTreeAndInternalKey) {
                        null -> super.sign(key, sigHash)
                        else -> {
                            val branch = input.scriptTreeAndInternalKey.scriptTree as ScriptTree.Branch
                            Transaction.signInputTaprootScriptPath(key, tx, 0, listOf(input.txOut), SigHash.SIGHASH_SINGLE or SigHash.SIGHASH_ANYONECANPAY, branch.right.hash())
                        }
                    }
                }

                override fun checkSig(sig: ByteVector64, pubKey: PublicKey, sigHash: Int): Boolean {
                    return when (input.scriptTreeAndInternalKey) {
                        null -> super.checkSig(sig, pubKey, sigHash)
                        else -> {
                            val sighash = SigHash.SIGHASH_SINGLE or SigHash.SIGHASH_ANYONECANPAY
                            val branch = input.scriptTreeAndInternalKey.scriptTree as ScriptTree.Branch
                            val data = Transaction.hashForSigningTaprootScriptPath(tx, inputIndex = 0, listOf(input.txOut), sighash, branch.right.hash())
                            Crypto.verifySignatureSchnorr(data, sig, pubKey.xOnly())
                        }
                    }
                }
            }

            @Serializable
            data class HtlcTimeoutTx(override val input: InputInfo, @Contextual override val tx: Transaction, override val htlcId: Long) : HtlcTx() {
                override fun sign(key: PrivateKey, sigHash: Int): ByteVector64 {
                    return when (val tree = input.scriptTreeAndInternalKey) {
                        null -> super.sign(key, sigHash)
                        else -> {
                            val branch = tree.scriptTree as ScriptTree.Branch
                            Transaction.signInputTaprootScriptPath(key, tx, 0, listOf(input.txOut), SigHash.SIGHASH_SINGLE or SigHash.SIGHASH_ANYONECANPAY, branch.left.hash())
                        }
                    }
                }
            }
        }

        @Serializable
        sealed class ClaimHtlcTx : TransactionWithInputInfo() {
            abstract val htlcId: Long

            @Serializable
            data class ClaimHtlcSuccessTx(override val input: InputInfo, @Contextual override val tx: Transaction, override val htlcId: Long) : ClaimHtlcTx() {
                override fun sign(key: PrivateKey, sigHash: Int): ByteVector64 {
                    return when (input.scriptTreeAndInternalKey) {
                        null -> super.sign(key, sigHash)
                        else -> {
                            val branch = input.scriptTreeAndInternalKey.scriptTree as ScriptTree.Branch
                            Transaction.signInputTaprootScriptPath(key, tx, 0, listOf(input.txOut), SigHash.SIGHASH_DEFAULT, branch.right.hash())
                        }
                    }
                }
            }

            @Serializable
            data class ClaimHtlcTimeoutTx(override val input: InputInfo, @Contextual override val tx: Transaction, override val htlcId: Long) : ClaimHtlcTx() {
                override fun sign(key: PrivateKey, sigHash: Int): ByteVector64 {
                    return when (input.scriptTreeAndInternalKey) {
                        null -> super.sign(key, sigHash)
                        else -> {
                            val htlcTree = input.scriptTreeAndInternalKey.scriptTree as ScriptTree.Branch
                            Transaction.signInputTaprootScriptPath(key, tx, 0, listOf(input.txOut), SigHash.SIGHASH_DEFAULT, htlcTree.left.hash())
                        }
                    }
                }
            }
        }

        @Serializable
        sealed class ClaimAnchorOutputTx : TransactionWithInputInfo() {
            @Serializable
            data class ClaimLocalAnchorOutputTx(override val input: InputInfo, @Contextual override val tx: Transaction) : ClaimAnchorOutputTx()

            @Serializable
            data class ClaimRemoteAnchorOutputTx(override val input: InputInfo, @Contextual override val tx: Transaction) : ClaimAnchorOutputTx()
        }

        @Serializable
        data class ClaimLocalDelayedOutputTx(override val input: InputInfo, @Contextual override val tx: Transaction) : TransactionWithInputInfo() {
            override fun sign(key: PrivateKey, sigHash: Int): ByteVector64 {
                return when (input.scriptTreeAndInternalKey) {
                    null -> super.sign(key, sigHash)
                    else -> {
                        when (val tree = input.scriptTreeAndInternalKey.scriptTree) {
                            is ScriptTree.Leaf -> {
                                // this tx claims an HTLC delayed output
                                Transaction.signInputTaprootScriptPath(key, tx, 0, listOf(input.txOut), SigHash.SIGHASH_DEFAULT, tree.hash())
                            }

                            is ScriptTree.Branch -> {
                                // this tx claims a delayed to-local output
                                Transaction.signInputTaprootScriptPath(key, tx, 0, listOf(input.txOut), SigHash.SIGHASH_DEFAULT, tree.left.hash())
                            }
                        }
                    }
                }
            }
        }

        @Serializable
        sealed class ClaimRemoteCommitMainOutputTx : TransactionWithInputInfo() {
            // TODO: once we deprecate v2/v3 serialization, we can remove the class nesting.
            @Serializable
            data class ClaimRemoteDelayedOutputTx(override val input: InputInfo, @Contextual override val tx: Transaction) : ClaimRemoteCommitMainOutputTx() {
                override fun sign(key: PrivateKey, sigHash: Int): ByteVector64 {
                    return when (input.scriptTreeAndInternalKey) {
                        null -> super.sign(key, sigHash)
                        else -> {
                            val toRemoteScriptTree = input.scriptTreeAndInternalKey.scriptTree as ScriptTree.Leaf
                            Transaction.signInputTaprootScriptPath(key, tx, 0, listOf(input.txOut), SigHash.SIGHASH_DEFAULT, toRemoteScriptTree.hash())
                        }
                    }
                }
            }
        }

        @Serializable
        data class MainPenaltyTx(override val input: InputInfo, @Contextual override val tx: Transaction) : TransactionWithInputInfo() {
            override fun sign(key: PrivateKey, sigHash: Int): ByteVector64 {
                return when (input.scriptTreeAndInternalKey) {
                    null -> super.sign(key, sigHash)
                    else -> {
                        val toLocalScriptTree = input.scriptTreeAndInternalKey.scriptTree as ScriptTree.Branch
                        Transaction.signInputTaprootScriptPath(key, tx, 0, listOf(input.txOut), SigHash.SIGHASH_DEFAULT, toLocalScriptTree.right.hash())
                    }
                }
            }
        }

        @Serializable
        data class HtlcPenaltyTx(override val input: InputInfo, @Contextual override val tx: Transaction) : TransactionWithInputInfo() {
            override fun sign(key: PrivateKey, sigHash: Int): ByteVector64 {
                return when (input.scriptTreeAndInternalKey) {
                    null -> super.sign(key, sigHash)
                    else -> Transaction.signInputTaprootKeyPath(key, tx, 0, listOf(input.txOut), SigHash.SIGHASH_DEFAULT, input.scriptTreeAndInternalKey.scriptTree)
                }
            }
        }

        @Serializable
        data class ClaimHtlcDelayedOutputPenaltyTx(override val input: InputInfo, @Contextual override val tx: Transaction) : TransactionWithInputInfo() {
            override fun sign(key: PrivateKey, sigHash: Int): ByteVector64 {
                return when (input.scriptTreeAndInternalKey) {
                    null -> super.sign(key, sigHash)
                    else -> Transaction.signInputTaprootKeyPath(key, tx, 0, listOf(input.txOut), SigHash.SIGHASH_DEFAULT, input.scriptTreeAndInternalKey.scriptTree)
                }
            }
        }

        @Serializable
        data class ClosingTx(override val input: InputInfo, @Contextual override val tx: Transaction, val toLocalIndex: Int?) : TransactionWithInputInfo() {
            val toLocalOutput: TxOut? get() = toLocalIndex?.let { tx.txOut[it] }
        }
    }

    sealed class TxGenerationSkipped {
        object OutputNotFound : TxGenerationSkipped() {
            override fun toString() = "output not found (probably trimmed)"
        }

        object AmountBelowDustLimit : TxGenerationSkipped() {
            override fun toString() = "amount is below dust limit"
        }
    }

    /**
     * When *local* *current* [[CommitTx]] is published:
     *   - [[ClaimDelayedOutputTx]] spends to-local output of [[CommitTx]] after a delay
     *   - [[HtlcSuccessTx]] spends htlc-received outputs of [[CommitTx]] for which we have the preimage
     *     - [[ClaimDelayedOutputTx]] spends [[HtlcSuccessTx]] after a delay
     *   - [[HtlcTimeoutTx]] spends htlc-sent outputs of [[CommitTx]] after a timeout
     *     - [[ClaimDelayedOutputTx]] spends [[HtlcTimeoutTx]] after a delay
     *
     * When *remote* *current* [[CommitTx]] is published:
     *   - [[ClaimP2WPKHOutputTx]] spends to-local output of [[CommitTx]]
     *   - [[ClaimHtlcSuccessTx]] spends htlc-received outputs of [[CommitTx]] for which we have the preimage
     *   - [[ClaimHtlcTimeoutTx]] spends htlc-sent outputs of [[CommitTx]] after a timeout
     *
     * When *remote* *revoked* [[CommitTx]] is published:
     *   - [[ClaimP2WPKHOutputTx]] spends to-local output of [[CommitTx]]
     *   - [[MainPenaltyTx]] spends remote main output using the per-commitment secret
     *   - [[HtlcSuccessTx]] spends htlc-sent outputs of [[CommitTx]] for which they have the preimage (published by remote)
     *     - [[ClaimDelayedOutputPenaltyTx]] spends [[HtlcSuccessTx]] using the revocation secret (published by local)
     *   - [[HtlcTimeoutTx]] spends htlc-received outputs of [[CommitTx]] after a timeout (published by remote)
     *     - [[ClaimDelayedOutputPenaltyTx]] spends [[HtlcTimeoutTx]] using the revocation secret (published by local)
     *   - [[HtlcPenaltyTx]] spends competes with [[HtlcSuccessTx]] and [[HtlcTimeoutTx]] for the same outputs (published by local)
     */
    // legacy swap-in. witness is 2 signatures (73 bytes) + redeem script (77 bytes)
    const val swapInputWeightLegacy = 392

    // musig2 swap-in. witness is a single Schnorr signature (64 bytes)
    const val swapInputWeight = 233

    // The following values are specific to lightning and used to estimate fees.
    const val claimP2WPKHOutputWeight = 438
    const val claimAnchorOutputWeight = 321
    const val claimHtlcDelayedWeight = 483
    const val claimHtlcSuccessWeight = 574
    const val claimHtlcTimeoutWeight = 548
    const val mainPenaltyWeight = 484
    const val htlcPenaltyWeight = 581 // based on spending an HTLC-Success output (would be 571 with HTLC-Timeout)

    private fun weight2feeMsat(feerate: FeeratePerKw, weight: Int): MilliSatoshi = (feerate.toLong() * weight).msat

    fun weight2fee(feerate: FeeratePerKw, weight: Int): Satoshi = weight2feeMsat(feerate, weight).truncateToSatoshi()

    /**
     * @param fee tx fee
     * @param weight tx weight
     * @return the fee rate (in Satoshi/Kw) for this tx
     */
    fun fee2rate(fee: Satoshi, weight: Int): FeeratePerKw = FeeratePerKw((fee * 1000L) / weight.toLong())

    /** As defined in https://github.com/lightning/bolts/blob/master/03-transactions.md#dust-limits */
    fun dustLimit(scriptPubKey: ByteVector): Satoshi {
        return runTrying {
            val script = Script.parse(scriptPubKey)
            when {
                Script.isPay2pkh(script) -> 546.sat
                Script.isPay2sh(script) -> 540.sat
                Script.isPay2wpkh(script) -> 294.sat
                Script.isPay2wsh(script) -> 330.sat
                Script.isNativeWitnessScript(script) -> 354.sat
                else -> 546.sat
            }
        }.getOrElse { 546.sat }
    }

    /** Offered HTLCs below this amount will be trimmed. */
    fun offeredHtlcTrimThreshold(dustLimit: Satoshi, spec: CommitmentSpec): Satoshi = dustLimit + weight2fee(spec.feerate, Commitments.HTLC_TIMEOUT_WEIGHT)

    fun trimOfferedHtlcs(dustLimit: Satoshi, spec: CommitmentSpec): List<OutgoingHtlc> {
        val threshold = offeredHtlcTrimThreshold(dustLimit, spec)
        return spec.htlcs
            .filterIsInstance<OutgoingHtlc>()
            .filter { it.add.amountMsat >= threshold }
    }

    /** Received HTLCs below this amount will be trimmed. */
    fun receivedHtlcTrimThreshold(dustLimit: Satoshi, spec: CommitmentSpec): Satoshi = dustLimit + weight2fee(spec.feerate, Commitments.HTLC_SUCCESS_WEIGHT)

    fun trimReceivedHtlcs(dustLimit: Satoshi, spec: CommitmentSpec): List<IncomingHtlc> {
        val threshold = receivedHtlcTrimThreshold(dustLimit, spec)
        return spec.htlcs
            .filterIsInstance<IncomingHtlc>()
            .filter { it.add.amountMsat >= threshold }
    }

    /** Fee for an un-trimmed HTLC. */
    fun htlcOutputFee(feerate: FeeratePerKw): MilliSatoshi = weight2feeMsat(feerate, Commitments.HTLC_OUTPUT_WEIGHT)

    /**
     * While fees are generally computed in Satoshis (since this is the smallest on-chain unit), it may be useful in some
     * cases to calculate it in MilliSatoshi to avoid rounding issues.
     * If you are adding multiple fees together for example, you should always add them in MilliSatoshi and then round
     * down to Satoshi.
     */
    fun commitTxFeeMsat(dustLimit: Satoshi, spec: CommitmentSpec, isTaprootChannel: Boolean): MilliSatoshi {
        val trimmedOfferedHtlcs = trimOfferedHtlcs(dustLimit, spec)
        val trimmedReceivedHtlcs = trimReceivedHtlcs(dustLimit, spec)
        val weight = if (isTaprootChannel) {
            Commitments.COMMIT_WEIGHT_TAPROOT + Commitments.HTLC_OUTPUT_WEIGHT * (trimmedOfferedHtlcs.size + trimmedReceivedHtlcs.size)
        } else {
            Commitments.COMMIT_WEIGHT + Commitments.HTLC_OUTPUT_WEIGHT * (trimmedOfferedHtlcs.size + trimmedReceivedHtlcs.size)
        }
        return weight2feeMsat(spec.feerate, weight) + (Commitments.ANCHOR_AMOUNT * 2).toMilliSatoshi()
    }

    fun commitTxFee(dustLimit: Satoshi, spec: CommitmentSpec, isTaprootChannel: Boolean): Satoshi = commitTxFeeMsat(dustLimit, spec, isTaprootChannel).truncateToSatoshi()

    /**
     * @param commitTxNumber commit tx number
     * @param isInitiator true if we are the channel initiator
     * @param localPaymentBasePoint local payment base point
     * @param remotePaymentBasePoint remote payment base point
     * @return the obscured tx number as defined in BOLT #3 (a 48 bits integer)
     */
    fun obscuredCommitTxNumber(commitTxNumber: Long, isInitiator: Boolean, localPaymentBasePoint: PublicKey, remotePaymentBasePoint: PublicKey): Long {
        // from BOLT 3: SHA256(payment-basepoint from open_channel || payment-basepoint from accept_channel)
        val h = if (isInitiator) {
            Crypto.sha256(localPaymentBasePoint.value + remotePaymentBasePoint.value)
        } else {
            Crypto.sha256(remotePaymentBasePoint.value + localPaymentBasePoint.value)
        }
        val blind = Pack.int64LE(h.takeLast(6).reversed().toByteArray() + ByteArray(4) { 0 })
        return commitTxNumber xor blind
    }

    /**
     * @param commitTx commit tx
     * @param isInitiator true if we are the channel initiator
     * @param localPaymentBasePoint local payment base point
     * @param remotePaymentBasePoint remote payment base point
     * @return the actual commit tx number that was blinded and stored in locktime and sequence fields
     */
    fun getCommitTxNumber(commitTx: Transaction, isInitiator: Boolean, localPaymentBasePoint: PublicKey, remotePaymentBasePoint: PublicKey): Long {
        val blind = obscuredCommitTxNumber(0, isInitiator, localPaymentBasePoint, remotePaymentBasePoint)
        val obscured = decodeTxNumber(commitTx.txIn.first().sequence, commitTx.lockTime)
        return obscured xor blind
    }

    /**
     * This is a trick to split and encode a 48-bit txnumber into the sequence and locktime fields of a tx
     *
     * @param txnumber commitment number
     * @return (sequence, locktime)
     */
    fun encodeTxNumber(txnumber: Long): Pair<Long, Long> {
        require(txnumber <= 0xffffffffffffL) { "txnumber must be lesser than 48 bits long" }
        return Pair(0x80000000L or (txnumber shr 24), (txnumber and 0xffffffL) or 0x20000000)
    }

    fun decodeTxNumber(sequence: Long, locktime: Long): Long = ((sequence and 0xffffffL) shl 24) + (locktime and 0xffffffL)

    /**
     * Represent a link between a commitment spec item (to-local, to-remote, htlc) and the actual output in the commit tx
     *
     * @param output           transaction output
     * @param redeemScript     redeem script that matches this output (most of them are p2wsh)
     * @param commitmentOutput commitment spec item this output is built from
     */
    data class CommitmentOutputLink<T : CommitmentOutput>(val output: TxOut, val redeemScript: List<ScriptElt>, val scriptTreeAndInternalKey: ScriptTreeAndInternalKey?, val commitmentOutput: T) : Comparable<CommitmentOutputLink<T>> {

        constructor(output: TxOut, redeemScript: List<ScriptElt>, commitmentOutput: T) : this(output, redeemScript, null, commitmentOutput)

        constructor(output: TxOut, scriptTreeAndInternalKey: ScriptTreeAndInternalKey, commitmentOutput: T) : this(output, listOf(), scriptTreeAndInternalKey, commitmentOutput)

        /**
         * We sort HTLC outputs according to BIP69 + CLTV as tie-breaker for offered HTLC, we do this only for the outgoing
         * HTLC because we must agree with the remote on the order of HTLC-Timeout transactions even for identical HTLC outputs.
         * See https://github.com/lightningnetwork/lightning-rfc/issues/448#issuecomment-432074187.
         */
        override fun compareTo(other: CommitmentOutputLink<T>): Int {
            val htlcA = (this.commitmentOutput as? OutHtlc)?.outgoingHtlc?.add
            val htlcB = (other.commitmentOutput as? OutHtlc)?.outgoingHtlc?.add
            return when {
                htlcA != null && htlcB != null && htlcA.paymentHash == htlcB.paymentHash && htlcA.amountMsat == htlcB.amountMsat -> htlcA.cltvExpiry.compareTo(htlcB.cltvExpiry)
                else -> LexicographicalOrdering.compare(this.output, other.output)
            }
        }
    }

    fun makeCommitTxOutputs(
        localFundingPubkey: PublicKey,
        remoteFundingPubkey: PublicKey,
        localPaysCommitTxFees: Boolean,
        localDustLimit: Satoshi,
        localRevocationPubkey: PublicKey,
        toLocalDelay: CltvExpiryDelta,
        localDelayedPaymentPubkey: PublicKey,
        remotePaymentPubkey: PublicKey,
        localHtlcPubkey: PublicKey,
        remoteHtlcPubkey: PublicKey,
        spec: CommitmentSpec,
        isTaprootChannel: Boolean
    ): TransactionsCommitmentOutputs {
        val commitFee = commitTxFee(localDustLimit, spec, isTaprootChannel)

        val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = if (localPaysCommitTxFees) {
            Pair(spec.toLocal.truncateToSatoshi() - commitFee, spec.toRemote.truncateToSatoshi())
        } else {
            Pair(spec.toLocal.truncateToSatoshi(), spec.toRemote.truncateToSatoshi() - commitFee)
        } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway

        val outputs = ArrayList<CommitmentOutputLink<CommitmentOutput>>()

        if (toLocalAmount >= localDustLimit) {
            when (isTaprootChannel) {
                true -> {
                    val toLocalScriptTree = Scripts.Taproot.toLocalScriptTree(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
                    outputs.add(
                        CommitmentOutputLink(
                            TxOut(toLocalAmount, Script.pay2tr(XonlyPublicKey(NUMS_POINT), toLocalScriptTree)),
                            ScriptTreeAndInternalKey(toLocalScriptTree, NUMS_POINT.xOnly()),
                            CommitmentOutput.ToLocal
                        )
                    )
                }

                else -> outputs.add(
                    CommitmentOutputLink(
                        TxOut(toLocalAmount, Script.pay2wsh(Scripts.toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey))),
                        Scripts.toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey),
                        CommitmentOutput.ToLocal
                    )
                )
            }
        }

        if (toRemoteAmount >= localDustLimit) {
            when (isTaprootChannel) {
                true -> {
                    val toRemoteScriptTree = Taproot.toRemoteScriptTree(remotePaymentPubkey)
                    outputs.add(
                        CommitmentOutputLink(
                            TxOut(toRemoteAmount, Script.pay2tr(XonlyPublicKey(NUMS_POINT), toRemoteScriptTree)),
                            ScriptTreeAndInternalKey(toRemoteScriptTree, NUMS_POINT.xOnly()),
                            CommitmentOutput.ToRemote
                        )
                    )
                }

                else -> outputs.add(
                    CommitmentOutputLink(
                        TxOut(toRemoteAmount, Script.pay2wsh(Scripts.toRemoteDelayed(remotePaymentPubkey))),
                        Scripts.toRemoteDelayed(remotePaymentPubkey),
                        CommitmentOutput.ToRemote
                    )
                )

            }
        }

        val untrimmedHtlcs = trimOfferedHtlcs(localDustLimit, spec).isNotEmpty() || trimReceivedHtlcs(localDustLimit, spec).isNotEmpty()
        if (untrimmedHtlcs || toLocalAmount >= localDustLimit) {
            when (isTaprootChannel) {
                true -> {
                    outputs.add(
                        CommitmentOutputLink(
                            TxOut(Commitments.ANCHOR_AMOUNT, Script.pay2tr(localDelayedPaymentPubkey.xOnly(), Scripts.Taproot.anchorScriptTree)),
                            Scripts.Taproot.anchorScript,
                            CommitmentOutput.ToLocalAnchor(localFundingPubkey)
                        )
                    )
                }

                else -> outputs.add(
                    CommitmentOutputLink(
                        TxOut(Commitments.ANCHOR_AMOUNT, Script.pay2wsh(Scripts.toAnchor(localFundingPubkey))),
                        Scripts.toAnchor(localFundingPubkey),
                        CommitmentOutput.ToLocalAnchor(localFundingPubkey)
                    )
                )
            }
        }

        if (untrimmedHtlcs || toRemoteAmount >= localDustLimit) {
            when (isTaprootChannel) {
                true -> outputs.add(
                    CommitmentOutputLink(
                        TxOut(Commitments.ANCHOR_AMOUNT, Script.pay2tr(remotePaymentPubkey.xOnly(), Scripts.Taproot.anchorScriptTree)),
                        Scripts.Taproot.anchorScript,
                        CommitmentOutput.ToLocalAnchor(remoteFundingPubkey)
                    )
                )

                else -> outputs.add(
                    CommitmentOutputLink(
                        TxOut(Commitments.ANCHOR_AMOUNT, Script.pay2wsh(Scripts.toAnchor(remoteFundingPubkey))),
                        Scripts.toAnchor(remoteFundingPubkey),
                        CommitmentOutput.ToLocalAnchor(remoteFundingPubkey)
                    )
                )
            }
        }

        trimOfferedHtlcs(localDustLimit, spec).forEach { htlc ->
            when (isTaprootChannel) {
                true -> {
                    val offeredHtlcTree = Scripts.Taproot.offeredHtlcTree(localHtlcPubkey, remoteHtlcPubkey, htlc.add.paymentHash)
                    outputs.add(
                        CommitmentOutputLink(
                            TxOut(htlc.add.amountMsat.truncateToSatoshi(), Script.pay2tr(localRevocationPubkey.xOnly(), offeredHtlcTree)), ScriptTreeAndInternalKey(offeredHtlcTree, localRevocationPubkey.xOnly()), OutHtlc(htlc)
                        )
                    )
                }

                else -> {
                    val redeemScript = Scripts.htlcOffered(localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, Crypto.ripemd160(htlc.add.paymentHash.toByteArray()))
                    outputs.add(CommitmentOutputLink(TxOut(htlc.add.amountMsat.truncateToSatoshi(), Script.pay2wsh(redeemScript)), redeemScript, OutHtlc(htlc)))
                }
            }
        }

        trimReceivedHtlcs(localDustLimit, spec).forEach { htlc ->
            when (isTaprootChannel) {
                true -> {
                    val receivedHtlcTree = Scripts.Taproot.receivedHtlcTree(localHtlcPubkey, remoteHtlcPubkey, htlc.add.paymentHash, htlc.add.cltvExpiry)
                    outputs.add(
                        CommitmentOutputLink(
                            TxOut(htlc.add.amountMsat.truncateToSatoshi(), Script.pay2tr(localRevocationPubkey.xOnly(), receivedHtlcTree)), ScriptTreeAndInternalKey(receivedHtlcTree, localRevocationPubkey.xOnly()), InHtlc(htlc)
                        )
                    )
                }

                else -> {
                    val redeemScript = Scripts.htlcReceived(localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, Crypto.ripemd160(htlc.add.paymentHash.toByteArray()), htlc.add.cltvExpiry)
                    outputs.add(CommitmentOutputLink(TxOut(htlc.add.amountMsat.truncateToSatoshi(), Script.pay2wsh(redeemScript)), redeemScript, InHtlc(htlc)))
                }
            }
        }

        return outputs.apply { sort() }
    }

    fun makeCommitTx(
        commitTxInput: InputInfo,
        commitTxNumber: Long,
        localPaymentBasePoint: PublicKey,
        remotePaymentBasePoint: PublicKey,
        localIsChannelOpener: Boolean,
        outputs: TransactionsCommitmentOutputs
    ): TransactionWithInputInfo.CommitTx {
        val txNumber = obscuredCommitTxNumber(commitTxNumber, localIsChannelOpener, localPaymentBasePoint, remotePaymentBasePoint)
        val (sequence, locktime) = encodeTxNumber(txNumber)

        val tx = Transaction(
            version = 2,
            txIn = listOf(TxIn(commitTxInput.outPoint, ByteVector.empty, sequence = sequence)),
            txOut = outputs.map { it.output },
            lockTime = locktime
        )

        return TransactionWithInputInfo.CommitTx(commitTxInput, tx)
    }

    sealed class TxResult<T> {
        abstract fun <R> map(f: (T) -> R): TxResult<R>

        data class Skipped<T>(val why: TxGenerationSkipped) : TxResult<T>() {
            override fun <R> map(f: (T) -> R): TxResult<R> = Skipped(why)
        }
        data class Success<T>(val result: T) : TxResult<T>() {
            override fun <R> map(f: (T) -> R): TxResult<R> = Success(f(result))
        }
    }

    private fun makeHtlcTimeoutTx(
        commitTx: Transaction,
        output: CommitmentOutputLink<OutHtlc>,
        outputIndex: Int,
        localDustLimit: Satoshi,
        localRevocationPubkey: PublicKey,
        toLocalDelay: CltvExpiryDelta,
        localDelayedPaymentPubkey: PublicKey,
        feerate: FeeratePerKw,
        isTaprootChannel: Boolean
    ): TxResult<TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx> {
        val fee = weight2fee(feerate, Commitments.HTLC_TIMEOUT_WEIGHT)
        val redeemScript = output.redeemScript
        val htlc = output.commitmentOutput.outgoingHtlc.add
        val amount = htlc.amountMsat.truncateToSatoshi() - fee
        return if (amount < localDustLimit) {
            TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
        } else {
            when (isTaprootChannel) {
                true -> {
                    val input = InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], output.scriptTreeAndInternalKey!!)
                    val tree = ScriptTree.Leaf(Taproot.toDelayScript(localDelayedPaymentPubkey, toLocalDelay))
                    val tx = Transaction(
                        version = 2,
                        txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 1L)),
                        txOut = listOf(TxOut(amount, Script.pay2tr(localRevocationPubkey.xOnly(), tree))),
                        lockTime = htlc.cltvExpiry.toLong()
                    )
                    TxResult.Success(TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx(input, tx, htlc.id))
                }

                else -> {
                    val input = InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], ByteVector(Script.write(redeemScript)))
                    val tx = Transaction(
                        version = 2,
                        txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 1L)),
                        txOut = listOf(TxOut(amount, Script.pay2wsh(Scripts.toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)))),
                        lockTime = htlc.cltvExpiry.toLong()
                    )
                    TxResult.Success(TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx(input, tx, htlc.id))
                }
            }
        }
    }

    private fun makeHtlcSuccessTx(
        commitTx: Transaction,
        output: CommitmentOutputLink<InHtlc>,
        outputIndex: Int,
        localDustLimit: Satoshi,
        localRevocationPubkey: PublicKey,
        toLocalDelay: CltvExpiryDelta,
        localDelayedPaymentPubkey: PublicKey,
        feerate: FeeratePerKw,
        isTaprootChannel: Boolean
    ): TxResult<TransactionWithInputInfo.HtlcTx.HtlcSuccessTx> {
        val fee = weight2fee(feerate, Commitments.HTLC_SUCCESS_WEIGHT)
        val redeemScript = output.redeemScript
        val htlc = output.commitmentOutput.incomingHtlc.add
        val amount = htlc.amountMsat.truncateToSatoshi() - fee
        return if (amount < localDustLimit) {
            TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
        } else {
            when (isTaprootChannel) {
                true -> {
                    val input = InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], output.scriptTreeAndInternalKey!!)
                    val tree = ScriptTree.Leaf(Taproot.toDelayScript(localDelayedPaymentPubkey, toLocalDelay))
                    val tx = Transaction(
                        version = 2,
                        txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 1L)),
                        txOut = listOf(TxOut(amount, Script.pay2tr(localRevocationPubkey.xOnly(), tree))),
                        lockTime = 0
                    )
                    TxResult.Success(TransactionWithInputInfo.HtlcTx.HtlcSuccessTx(input, tx, htlc.paymentHash, htlc.id))
                }

                else -> {
                    val input = InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], ByteVector(Script.write(redeemScript)))
                    val tx = Transaction(
                        version = 2,
                        txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 1L)),
                        txOut = listOf(TxOut(amount, Script.pay2wsh(Scripts.toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)))),
                        lockTime = 0
                    )
                    TxResult.Success(TransactionWithInputInfo.HtlcTx.HtlcSuccessTx(input, tx, htlc.paymentHash, htlc.id))
                }
            }
        }
    }

    fun makeHtlcTxs(
        commitTx: Transaction,
        localDustLimit: Satoshi,
        localRevocationPubkey: PublicKey,
        toLocalDelay: CltvExpiryDelta,
        localDelayedPaymentPubkey: PublicKey,
        feerate: FeeratePerKw,
        outputs: TransactionsCommitmentOutputs,
        isTaprootChannel: Boolean
    ): List<TransactionWithInputInfo.HtlcTx> {
        val htlcTimeoutTxs = outputs
            .mapIndexedNotNull map@{ outputIndex, link ->
                val outHtlc = link.commitmentOutput as? OutHtlc ?: return@map null
                val co = CommitmentOutputLink(link.output, link.redeemScript, link.scriptTreeAndInternalKey, outHtlc)
                makeHtlcTimeoutTx(commitTx, co, outputIndex, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, feerate, isTaprootChannel)
            }
            .mapNotNull { (it as? TxResult.Success)?.result }

        val htlcSuccessTxs = outputs
            .mapIndexedNotNull map@{ outputIndex, link ->
                val inHtlc = link.commitmentOutput as? InHtlc ?: return@map null
                val co = CommitmentOutputLink(link.output, link.redeemScript, link.scriptTreeAndInternalKey, inHtlc)
                makeHtlcSuccessTx(commitTx, co, outputIndex, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, feerate, isTaprootChannel)
            }
            .mapNotNull { (it as? TxResult.Success)?.result }

        return (htlcTimeoutTxs + htlcSuccessTxs).sortedBy { it.input.outPoint.index }
    }

    fun makeClaimHtlcSuccessTx(
        commitTx: Transaction,
        outputs: TransactionsCommitmentOutputs,
        localDustLimit: Satoshi,
        localHtlcPubkey: PublicKey,
        remoteHtlcPubkey: PublicKey,
        remoteRevocationPubkey: PublicKey,
        localFinalScriptPubKey: ByteArray,
        htlc: UpdateAddHtlc,
        feerate: FeeratePerKw,
    ): TxResult<TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcSuccessTx> {
        val redeemScript = Scripts.htlcOffered(remoteHtlcPubkey, localHtlcPubkey, remoteRevocationPubkey, Crypto.ripemd160(htlc.paymentHash))
        return outputs.withIndex()
            .firstOrNull { (it.value.commitmentOutput as? OutHtlc)?.outgoingHtlc?.add?.id == htlc.id }
            ?.let { (outputIndex, _) ->
                val input = when (val tree = outputs[outputIndex].scriptTreeAndInternalKey) {
                    null -> InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], ByteVector(Script.write(redeemScript)))
                    else -> InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], tree)
                }
                val tx = Transaction(
                    version = 2,
                    txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 1L)),
                    txOut = listOf(TxOut(0.sat, localFinalScriptPubKey)),
                    lockTime = htlc.cltvExpiry.toLong()
                )
                val weight = addSigs(TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcSuccessTx(input, tx, htlc.id), PlaceHolderSig, ByteVector32.Zeroes).tx.weight()
                val fee = weight2fee(feerate, weight)
                val amount = input.txOut.amount - fee
                if (amount < localDustLimit) {
                    TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
                } else {
                    val tx1 = tx.copy(txOut = listOf(tx.txOut.first().copy(amount = amount)))
                    TxResult.Success(TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcSuccessTx(input, tx1, htlc.id))
                }
            }
            ?: TxResult.Skipped(TxGenerationSkipped.OutputNotFound)
    }

    fun makeClaimHtlcTimeoutTx(
        commitTx: Transaction,
        outputs: TransactionsCommitmentOutputs,
        localDustLimit: Satoshi,
        localHtlcPubkey: PublicKey,
        remoteHtlcPubkey: PublicKey,
        remoteRevocationPubkey: PublicKey,
        localFinalScriptPubKey: ByteArray,
        htlc: UpdateAddHtlc,
        feerate: FeeratePerKw,
    ): TxResult<TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcTimeoutTx> {
        val redeemScript = Scripts.htlcReceived(remoteHtlcPubkey, localHtlcPubkey, remoteRevocationPubkey, Crypto.ripemd160(htlc.paymentHash), htlc.cltvExpiry)
        return outputs.withIndex()
            .firstOrNull { (it.value.commitmentOutput as? InHtlc)?.incomingHtlc?.add?.id == htlc.id }
            ?.let { (outputIndex, _) ->
                val input = when (val tree = outputs[outputIndex].scriptTreeAndInternalKey) {
                    null -> InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], ByteVector(Script.write(redeemScript)))
                    else -> InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], tree)
                }
                // unsigned tx
                val tx = Transaction(
                    version = 2,
                    txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 1L)),
                    txOut = listOf(TxOut(0.sat, localFinalScriptPubKey)),
                    lockTime = htlc.cltvExpiry.toLong()
                )
                val weight = addSigs(TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcTimeoutTx(input, tx, htlc.id), PlaceHolderSig).tx.weight()
                val fee = weight2fee(feerate, weight)
                val amount = input.txOut.amount - fee
                if (amount < localDustLimit) {
                    TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
                } else {
                    val tx1 = tx.copy(txOut = listOf(tx.txOut.first().copy(amount = amount)))
                    TxResult.Success(TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcTimeoutTx(input, tx1, htlc.id))
                }
            }
            ?: TxResult.Skipped(TxGenerationSkipped.OutputNotFound)
    }

    fun makeClaimRemoteDelayedOutputTx(
        commitTx: Transaction, localDustLimit: Satoshi,
        localPaymentPubkey: PublicKey,
        localFinalScriptPubKey: ByteVector,
        feerate: FeeratePerKw,
        isTaprootChannel: Boolean
    ): TxResult<TransactionWithInputInfo.ClaimRemoteCommitMainOutputTx.ClaimRemoteDelayedOutputTx> {


        val (redeemScript, pubkeyScript, scriptTree_opt) = when(isTaprootChannel) {
            true -> {
                val toRemoteTree = Taproot.toRemoteScriptTree(localPaymentPubkey)
                Triple(
                    Taproot.toRemoteScript(localPaymentPubkey),
                    Script.write(Script.pay2tr(XonlyPublicKey(NUMS_POINT), toRemoteTree)),
                    ScriptTreeAndInternalKey(toRemoteTree, NUMS_POINT.xOnly())
                )
            }

            else -> {
                val redeemScript = Scripts.toRemoteDelayed(localPaymentPubkey)
                Triple(redeemScript, Script.write(Script.pay2wsh(redeemScript)), null)
            }
        }

        return when (val pubkeyScriptIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)) {
            is TxResult.Skipped -> TxResult.Skipped(pubkeyScriptIndex.why)
            is TxResult.Success -> {
                val outputIndex = pubkeyScriptIndex.result
                val input = when (isTaprootChannel) {
                    true -> InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], scriptTree_opt!!)
                    else -> InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], ByteVector(Script.write(redeemScript)))
                }
                // unsigned transaction
                val tx = Transaction(
                    version = 2,
                    txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 1)),
                    txOut = listOf(TxOut(0.sat, localFinalScriptPubKey)),
                    lockTime = 0
                )
                // compute weight with a dummy 73 bytes signature (the largest you can get)
                val weight = addSigs(TransactionWithInputInfo.ClaimRemoteCommitMainOutputTx.ClaimRemoteDelayedOutputTx(input, tx), PlaceHolderSig).tx.weight()
                val fee = weight2fee(feerate, weight)
                val amount = input.txOut.amount - fee
                if (amount < localDustLimit) {
                    TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
                } else {
                    val tx1 = tx.copy(txOut = listOf(tx.txOut.first().copy(amount = amount)))
                    TxResult.Success(TransactionWithInputInfo.ClaimRemoteCommitMainOutputTx.ClaimRemoteDelayedOutputTx(input, tx1))
                }
            }
        }
    }

fun makeHtlcDelayedTx(htlcTx: Transaction,
                      localDustLimit: Satoshi,
                      localRevocationPubkey: PublicKey,
                      toLocalDelay: CltvExpiryDelta,
                      localDelayedPaymentPubkey: PublicKey,
                      localFinalScriptPubKey: ByteArray,
                      feeratePerKw: FeeratePerKw,
                      isTaprootChannel: Boolean
): TxResult<TransactionWithInputInfo.ClaimLocalDelayedOutputTx> {
    return when(isTaprootChannel) {
      true -> {
          val htlcTxTree = ScriptTree.Leaf(Taproot.toDelayScript(localDelayedPaymentPubkey, toLocalDelay))
          val ScriptTreeAndInternalKey = ScriptTreeAndInternalKey(htlcTxTree, localRevocationPubkey.xOnly())
          when (val pubkeyScriptIndex = findPubKeyScriptIndex(htlcTx, ScriptTreeAndInternalKey.publicKeyScript.toByteArray())) {
              is TxResult.Skipped -> TxResult.Skipped(pubkeyScriptIndex.why)
              is TxResult.Success -> {
                  val outputIndex = pubkeyScriptIndex.result
                  val input = InputInfo(OutPoint(htlcTx, outputIndex.toLong()), htlcTx.txOut[outputIndex], ScriptTreeAndInternalKey)
                  // unsigned transaction
                  val tx = Transaction(
                      version = 2,
                      txIn = listOf(TxIn(input.outPoint, ByteVector.empty, toLocalDelay.toLong())),
                      txOut = listOf(TxOut(Satoshi(0), localFinalScriptPubKey)),
                      lockTime = 0
                  )
                  val weight = run {
                      val witness = Script.witnessScriptPathPay2tr(localRevocationPubkey.xOnly(), htlcTxTree, ScriptWitness(listOf(ByteVector64.Zeroes)), htlcTxTree)
                      tx.updateWitness(0, witness).weight()
                  }
                  val fee = weight2fee(feeratePerKw, weight)
                  val amount = input.txOut.amount - fee
                  if (amount < localDustLimit) {
                      TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
                  } else {
                      val tx1 = tx.copy(txOut = listOf(tx.txOut.first().copy(amount = amount)))
                      TxResult.Success(TransactionWithInputInfo.ClaimLocalDelayedOutputTx(input, tx1))
                  }
              }
          }
      }
      else -> {
          makeClaimLocalDelayedOutputTx(htlcTx, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, localFinalScriptPubKey, feeratePerKw, isTaprootChannel)
      }
    }
  }

    fun makeClaimLocalDelayedOutputTx(
        delayedOutputTx: Transaction,
        localDustLimit: Satoshi,
        localRevocationPubkey: PublicKey,
        toLocalDelay: CltvExpiryDelta,
        localDelayedPaymentPubkey: PublicKey,
        localFinalScriptPubKey: ByteArray,
        feerate: FeeratePerKw,
        isTaprootChannel: Boolean
    ): TxResult<TransactionWithInputInfo.ClaimLocalDelayedOutputTx> {

        val (redeemScript, pubkeyScript, scriptTree_opt) = when (isTaprootChannel) {
            true -> {
                val toLocalScriptTree = Taproot.toLocalScriptTree(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
                Triple(
                    ByteVector.empty,
                    Script.write(Script.pay2tr(XonlyPublicKey(NUMS_POINT), toLocalScriptTree)),
                    ScriptTreeAndInternalKey(toLocalScriptTree, NUMS_POINT.xOnly())
                )
            }

            else -> {
                val redeemScript = Script.write(Scripts.toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)).byteVector()
                Triple(redeemScript, Script.write(Script.pay2wsh(redeemScript)), null)
            }
        }

        return when (val pubkeyScriptIndex = findPubKeyScriptIndex(delayedOutputTx, pubkeyScript)) {
            is TxResult.Skipped -> TxResult.Skipped(pubkeyScriptIndex.why)
            is TxResult.Success -> {
                val outputIndex = pubkeyScriptIndex.result
                val input = InputInfo(OutPoint(delayedOutputTx, outputIndex.toLong()), delayedOutputTx.txOut[outputIndex], redeemScript, scriptTree_opt)
                // unsigned transaction
                val tx = Transaction(
                    version = 2,
                    txIn = listOf(TxIn(input.outPoint, ByteVector.empty, toLocalDelay.toLong())),
                    txOut = listOf(TxOut(0.sat, localFinalScriptPubKey)),
                    lockTime = 0
                )
                // compute weight with a dummy 73 bytes signature (the largest you can get)
                val weight = when(isTaprootChannel) {
                    true -> {
                        val toLocalScriptTree = Taproot.toLocalScriptTree(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
                        val witness = Script.witnessScriptPathPay2tr(XonlyPublicKey(NUMS_POINT), toLocalScriptTree.left as ScriptTree.Leaf, ScriptWitness(listOf(ByteVector64.Zeroes)), toLocalScriptTree)
                        tx.updateWitness(0, witness).weight()
                    }
                    else -> addSigs(TransactionWithInputInfo.ClaimLocalDelayedOutputTx(input, tx), PlaceHolderSig).tx.weight()
                }
                val fee = weight2fee(feerate, weight)
                val amount = input.txOut.amount - fee
                if (amount < localDustLimit) {
                    TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
                } else {
                    val tx1 = tx.copy(txOut = listOf(tx.txOut.first().copy(amount = amount)))
                    TxResult.Success(TransactionWithInputInfo.ClaimLocalDelayedOutputTx(input, tx1))
                }
            }
        }
    }

    fun makeClaimDelayedOutputPenaltyTxs(
        delayedOutputTx: Transaction,
        localDustLimit: Satoshi,
        localRevocationPubkey: PublicKey,
        toLocalDelay: CltvExpiryDelta,
        localDelayedPaymentPubkey: PublicKey,
        localFinalScriptPubKey: ByteArray,
        feerate: FeeratePerKw,
        isTaprootChannel: Boolean
    ): List<TxResult<TransactionWithInputInfo.ClaimHtlcDelayedOutputPenaltyTx>> {
        val (redeemScript, pubkeyScript, scripTree) = when (isTaprootChannel) {
            true -> {
                val tree = ScriptTree.Leaf(Taproot.toDelayScript(localDelayedPaymentPubkey, toLocalDelay))
                Triple(
                    Taproot.toDelayScript(localDelayedPaymentPubkey, toLocalDelay),
                    Script.write(Script.pay2tr(localRevocationPubkey.xOnly(), tree)),
                    ScriptTreeAndInternalKey(tree, localRevocationPubkey.xOnly())
                )
            }
            else -> {
                val script = Scripts.toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
                Triple(script, Script.write(Script.pay2wsh(script)), null)
            }
        }
        return when (val pubkeyScriptIndexes = findPubKeyScriptIndexes(delayedOutputTx, pubkeyScript)) {
            is TxResult.Skipped -> listOf(TxResult.Skipped(pubkeyScriptIndexes.why))
            is TxResult.Success -> pubkeyScriptIndexes.result.map { outputIndex ->
                val input = when (isTaprootChannel) {
                    true -> InputInfo(OutPoint(delayedOutputTx, outputIndex.toLong()), delayedOutputTx.txOut[outputIndex], scripTree!!)
                    else -> InputInfo(OutPoint(delayedOutputTx, outputIndex.toLong()), delayedOutputTx.txOut[outputIndex], ByteVector(Script.write(redeemScript)))
                }
                // unsigned transaction
                val tx = Transaction(
                    version = 2,
                    txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 0xffffffffL)),
                    txOut = listOf(TxOut(0.sat, localFinalScriptPubKey)),
                    lockTime = 0
                )
                // compute weight with a dummy 73 bytes signature (the largest you can get)
                val weight = addSigs(TransactionWithInputInfo.ClaimHtlcDelayedOutputPenaltyTx(input, tx), PlaceHolderSig).tx.weight()
                val fee = weight2fee(feerate, weight)
                val amount = input.txOut.amount - fee
                if (amount < localDustLimit) {
                    TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
                } else {
                    val tx1 = tx.copy(txOut = listOf(tx.txOut.first().copy(amount = amount)))
                    TxResult.Success(TransactionWithInputInfo.ClaimHtlcDelayedOutputPenaltyTx(input, tx1))
                }
            }
        }
    }

    fun makeMainPenaltyTx(
        commitTx: Transaction,
        localDustLimit: Satoshi,
        remoteRevocationPubkey: PublicKey,
        localFinalScriptPubKey: ByteArray,
        toRemoteDelay: CltvExpiryDelta,
        remoteDelayedPaymentPubkey: PublicKey,
        feerate: FeeratePerKw,
        isTaprootChannel: Boolean
    ): TxResult<TransactionWithInputInfo.MainPenaltyTx> {
        val redeemScript = when (isTaprootChannel) {
            true -> Taproot.toDelayScript(remoteDelayedPaymentPubkey, toRemoteDelay)
            else -> Scripts.toLocalDelayed(remoteRevocationPubkey, toRemoteDelay, remoteDelayedPaymentPubkey)
        }
        val pubkeyScript = when (isTaprootChannel) {
            true -> {
                val toLocalScriptTree = Taproot.toLocalScriptTree(remoteRevocationPubkey, toRemoteDelay, remoteDelayedPaymentPubkey)
                Script.write(Script.pay2tr(XonlyPublicKey(NUMS_POINT), toLocalScriptTree))
            }

            else -> Script.write(Script.pay2wsh(redeemScript))
        }
        return when (val pubkeyScriptIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)) {
            is TxResult.Skipped -> TxResult.Skipped(pubkeyScriptIndex.why)
            is TxResult.Success -> {
                val outputIndex = pubkeyScriptIndex.result
                val input = when (isTaprootChannel) {
                    true -> {
                        val tree = ScriptTreeAndInternalKey(Taproot.toLocalScriptTree(remoteRevocationPubkey, toRemoteDelay, remoteDelayedPaymentPubkey), NUMS_POINT.xOnly())
                        InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], ByteVector.empty, tree)
                    }

                    else -> InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], ByteVector(Script.write(redeemScript)))
                }
                // unsigned transaction
                val tx = Transaction(
                    version = 2,
                    txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 0xffffffffL)),
                    txOut = listOf(TxOut(0.sat, localFinalScriptPubKey)),
                    lockTime = 0
                )
                // compute weight with a dummy 73 bytes signature (the largest you can get)
                val weight = addSigs(TransactionWithInputInfo.MainPenaltyTx(input, tx), PlaceHolderSig).tx.weight()
                val fee = weight2fee(feerate, weight)
                val amount = input.txOut.amount - fee
                if (amount < localDustLimit) {
                    TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
                } else {
                    val tx1 = tx.copy(txOut = listOf(tx.txOut.first().copy(amount = amount)))
                    TxResult.Success(TransactionWithInputInfo.MainPenaltyTx(input, tx1))
                }
            }
        }
    }

    /**
     * We already have the redeemScript, no need to build it
     */
    fun makeHtlcPenaltyTx(
        commitTx: Transaction,
        htlcOutputIndex: Int,
        redeemScript: ByteArray,
        localDustLimit: Satoshi,
        localFinalScriptPubKey: ByteArray,
        feerate: FeeratePerKw
    ): TxResult<TransactionWithInputInfo.HtlcPenaltyTx> {
        val input = InputInfo(OutPoint(commitTx, htlcOutputIndex.toLong()), commitTx.txOut[htlcOutputIndex], ByteVector(redeemScript))
        // unsigned transaction
        val tx = Transaction(
            version = 2,
            txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 0xffffffffL)),
            txOut = listOf(TxOut(0.sat, localFinalScriptPubKey)),
            lockTime = 0
        )
        // compute weight with a dummy 73 bytes signature (the largest you can get)
        val weight = addSigs(TransactionWithInputInfo.MainPenaltyTx(input, tx), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feerate, weight)
        val amount = input.txOut.amount - fee
        return if (amount < localDustLimit) {
            TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
        } else {
            val tx1 = tx.copy(txOut = listOf(tx.txOut.first().copy(amount = amount)))
            TxResult.Success(TransactionWithInputInfo.HtlcPenaltyTx(input, tx1))
        }
    }

    fun makeHtlcPenaltyTx(
        commitTx: Transaction,
        htlcOutputIndex: Int,
        scriptTreeAndInternalKey: ScriptTreeAndInternalKey,
        localDustLimit: Satoshi,
        localFinalScriptPubKey: ByteArray,
        feeratePerKw: FeeratePerKw): TxResult<TransactionWithInputInfo.HtlcPenaltyTx> {
        val input = InputInfo(OutPoint(commitTx, htlcOutputIndex.toLong()), commitTx.txOut[htlcOutputIndex], scriptTreeAndInternalKey)
        // unsigned transaction
        val tx = Transaction(
            version = 2,
            txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 0xffffffffL)),
            txOut = listOf(TxOut(Satoshi(0), localFinalScriptPubKey)),
            lockTime = 0)
        // compute weight with a dummy 73 bytes signature (the largest you can get)
        val weight = addSigs(TransactionWithInputInfo.MainPenaltyTx(input, tx), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        return if (amount < localDustLimit) {
            TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
        } else {
            val tx1 = tx.copy(txOut = listOf(tx.txOut.first().copy(amount = amount)))
            TxResult.Success(TransactionWithInputInfo.HtlcPenaltyTx(input, tx1))
        }
    }


    fun makeClosingTx(
        commitTxInput: InputInfo,
        localScriptPubKey: ByteArray,
        remoteScriptPubKey: ByteArray,
        localPaysClosingFees: Boolean,
        dustLimit: Satoshi,
        closingFee: Satoshi,
        spec: CommitmentSpec
    ): TransactionWithInputInfo.ClosingTx {
        require(spec.htlcs.isEmpty()) { "there shouldn't be any pending htlcs" }

        val (toLocalAmount, toRemoteAmount) = if (localPaysClosingFees) {
            Pair(spec.toLocal.truncateToSatoshi() - closingFee, spec.toRemote.truncateToSatoshi())
        } else {
            Pair(spec.toLocal.truncateToSatoshi(), spec.toRemote.truncateToSatoshi() - closingFee)
        } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway

        val toLocalOutputOpt = toLocalAmount.takeIf { it >= dustLimit }?.let { TxOut(it, localScriptPubKey) }
        val toRemoteOutputOpt = toRemoteAmount.takeIf { it >= dustLimit }?.let { TxOut(it, remoteScriptPubKey) }

        val tx = LexicographicalOrdering.sort(
            Transaction(
                version = 2,
                txIn = listOf(TxIn(commitTxInput.outPoint, ByteVector.empty, sequence = 0xffffffffL)),
                txOut = listOfNotNull(toLocalOutputOpt, toRemoteOutputOpt),
                lockTime = 0
            )
        )
        val toLocalOutput = when (val toLocalIndex = findPubKeyScriptIndex(tx, localScriptPubKey)) {
            is TxResult.Skipped -> null
            is TxResult.Success -> toLocalIndex.result
        }
        return TransactionWithInputInfo.ClosingTx(commitTxInput, tx, toLocalOutput)
    }

    private fun findPubKeyScriptIndex(tx: Transaction, pubkeyScript: ByteArray): TxResult<Int> {
        val outputIndex = tx.txOut.indexOfFirst { txOut -> txOut.publicKeyScript.contentEquals(pubkeyScript) }
        return if (outputIndex >= 0) {
            TxResult.Success(outputIndex)
        } else {
            TxResult.Skipped(TxGenerationSkipped.OutputNotFound)
        }
    }

    private fun findPubKeyScriptIndexes(tx: Transaction, pubkeyScript: ByteArray): TxResult<List<Int>> {
        val outputIndexes = tx.txOut.withIndex().filter { it.value.publicKeyScript.contentEquals(pubkeyScript) }.map { it.index }
        return if (outputIndexes.isNotEmpty()) {
            TxResult.Success(outputIndexes)
        } else {
            TxResult.Skipped(TxGenerationSkipped.OutputNotFound)
        }
    }

    /**
     * Default public key used for fee estimation
     */
    val PlaceHolderPubKey = PrivateKey(ByteVector32.One).publicKey()

    /**
     * This default sig takes 72B when encoded in DER (incl. 1B for the trailing sig hash), it is used for fee estimation
     * It is 72 bytes because our signatures are normalized (low-s) and will take up 72 bytes at most in DER format
     */
    val PlaceHolderSig = ByteVector64(ByteArray(64) { 0xaa.toByte() })
        .also { check(Scripts.der(it, SigHash.SIGHASH_ALL).size() == 72) { "Should be 72 bytes but is ${Scripts.der(it, SigHash.SIGHASH_ALL).size()} bytes" } }

    fun sign(tx: Transaction, inputIndex: Int, redeemScript: ByteArray, amount: Satoshi, key: PrivateKey, sigHash: Int = SigHash.SIGHASH_ALL): ByteVector64 {
        val sigDER = tx.signInput(inputIndex, redeemScript, sigHash, amount, SigVersion.SIGVERSION_WITNESS_V0, key)
        return Crypto.der2compact(sigDER)
    }

    fun sign(txInfo: TransactionWithInputInfo, key: PrivateKey, sigHash: Int = SigHash.SIGHASH_ALL): ByteVector64 {
        return txInfo.sign(key, sigHash)
    }

    fun partialSign(
        key: PrivateKey, tx: Transaction, inputIndex: Int, spentOutputs: List<TxOut>,
        localFundingPublicKey: PublicKey, remoteFundingPublicKey: PublicKey,
        localNonce: Pair<SecretNonce, IndividualNonce>, remoteNextLocalNonce: IndividualNonce
    ): Either<Throwable, ByteVector32> {
        val publicKeys = Scripts.sort(listOf(localFundingPublicKey, remoteFundingPublicKey))
        return Musig2.signTaprootInput(key, tx, inputIndex, spentOutputs, publicKeys, localNonce.first, listOf(localNonce.second, remoteNextLocalNonce), null)
    }

    fun partialSign(
        txinfo: TransactionWithInputInfo, key: PrivateKey,
        localFundingPublicKey: PublicKey, remoteFundingPublicKey: PublicKey,
        localNonce: Pair<SecretNonce, IndividualNonce>, remoteNextLocalNonce: IndividualNonce
    ): Either<Throwable, ByteVector32> {
        val inputIndex = txinfo.tx.txIn.indexOfFirst { it.outPoint == txinfo.input.outPoint }
        return partialSign(key, txinfo.tx, inputIndex, listOf(txinfo.input.txOut), localFundingPublicKey, remoteFundingPublicKey, localNonce, remoteNextLocalNonce)
    }

    fun aggregatePartialSignatures(
        txinfo: TransactionWithInputInfo,
        localSig: ByteVector32, remoteSig: ByteVector32,
        localFundingPublicKey: PublicKey, remoteFundingPublicKey: PublicKey,
        localNonce: IndividualNonce, remoteNonce: IndividualNonce
    ): Either<Throwable, ByteVector64> {
        return Musig2.aggregateTaprootSignatures(
            listOf(localSig, remoteSig), txinfo.tx, txinfo.tx.txIn.indexOfFirst { it.outPoint == txinfo.input.outPoint },
            listOf(txinfo.input.txOut),
            Scripts.sort(listOf(localFundingPublicKey, remoteFundingPublicKey)),
            listOf(localNonce, remoteNonce),
            null
        )
    }

    fun addSigs(
        commitTx: TransactionWithInputInfo.CommitTx,
        localFundingPubkey: PublicKey,
        remoteFundingPubkey: PublicKey,
        localSig: ByteVector64,
        remoteSig: ByteVector64
    ): TransactionWithInputInfo.CommitTx {
        val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
        return commitTx.copy(tx = commitTx.tx.updateWitness(0, witness))
    }

    fun addSigs(mainPenaltyTx: TransactionWithInputInfo.MainPenaltyTx, revocationSig: ByteVector64): TransactionWithInputInfo.MainPenaltyTx {
        val witness = when (val tree = mainPenaltyTx.input.scriptTreeAndInternalKey) {
            null -> Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig, mainPenaltyTx.input.redeemScript)
            else -> Script.witnessScriptPathPay2tr(tree.internalKey, (tree.scriptTree as ScriptTree.Branch).right as ScriptTree.Leaf, ScriptWitness(listOf(revocationSig)), tree.scriptTree)
        }
        return mainPenaltyTx.copy(tx = mainPenaltyTx.tx.updateWitness(0, witness))
    }

    fun addSigs(htlcPenaltyTx: TransactionWithInputInfo.HtlcPenaltyTx, revocationSig: ByteVector64, revocationPubkey: PublicKey): TransactionWithInputInfo.HtlcPenaltyTx {
        val witness = when (htlcPenaltyTx.input.scriptTreeAndInternalKey) {
            null -> {
                Scripts.witnessHtlcWithRevocationSig(revocationSig, revocationPubkey, htlcPenaltyTx.input.redeemScript)
            }
            else -> {
                Script.witnessKeyPathPay2tr(revocationSig)
            }
        }
        return htlcPenaltyTx.copy(tx = htlcPenaltyTx.tx.updateWitness(0, witness))
    }

    fun addSigs(htlcSuccessTx: TransactionWithInputInfo.HtlcTx.HtlcSuccessTx, localSig: ByteVector64, remoteSig: ByteVector64, paymentPreimage: ByteVector32): TransactionWithInputInfo.HtlcTx.HtlcSuccessTx {
        val witness = when (htlcSuccessTx.input.scriptTreeAndInternalKey) {
            null -> Scripts.witnessHtlcSuccess(localSig, remoteSig, paymentPreimage, htlcSuccessTx.input.redeemScript)
            else -> {
                val branch = htlcSuccessTx.input.scriptTreeAndInternalKey.scriptTree as ScriptTree.Branch
                val sigHash = (SigHash.SIGHASH_SINGLE or SigHash.SIGHASH_ANYONECANPAY).toByte()
                Script.witnessScriptPathPay2tr(htlcSuccessTx.input.scriptTreeAndInternalKey.internalKey, branch.right as ScriptTree.Leaf, ScriptWitness(listOf(remoteSig.concat(sigHash), localSig.concat(sigHash), paymentPreimage)), branch)
            }
        }
        return htlcSuccessTx.copy(tx = htlcSuccessTx.tx.updateWitness(0, witness))
    }

    fun addSigs(htlcTimeoutTx: TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx, localSig: ByteVector64, remoteSig: ByteVector64): TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx {
        val witness = when (htlcTimeoutTx.input.scriptTreeAndInternalKey) {
            null -> Scripts.witnessHtlcTimeout(localSig, remoteSig, htlcTimeoutTx.input.redeemScript)
            else -> {
                val branch = htlcTimeoutTx.input.scriptTreeAndInternalKey.scriptTree as ScriptTree.Branch
                val sigHash = (SigHash.SIGHASH_SINGLE or SigHash.SIGHASH_ANYONECANPAY).toByte()
                Script.witnessScriptPathPay2tr(htlcTimeoutTx.input.scriptTreeAndInternalKey.internalKey, branch.left as ScriptTree.Leaf, ScriptWitness(listOf(remoteSig.concat(sigHash), localSig.concat(sigHash))), branch)
            }
        }
        return htlcTimeoutTx.copy(tx = htlcTimeoutTx.tx.updateWitness(0, witness))
    }

    fun addSigs(claimHtlcSuccessTx: TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcSuccessTx, localSig: ByteVector64, paymentPreimage: ByteVector32): TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcSuccessTx {
        val witness = when (val tree = claimHtlcSuccessTx.input.scriptTreeAndInternalKey) {
            null -> Scripts.witnessClaimHtlcSuccessFromCommitTx(localSig, paymentPreimage, claimHtlcSuccessTx.input.redeemScript)
            else -> Script.witnessScriptPathPay2tr(tree.internalKey, (tree.scriptTree as ScriptTree.Branch).right as ScriptTree.Leaf, ScriptWitness(listOf(localSig, paymentPreimage)), tree.scriptTree)
        }

        return claimHtlcSuccessTx.copy(tx = claimHtlcSuccessTx.tx.updateWitness(0, witness))
    }

    fun addSigs(claimHtlcTimeoutTx: TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcTimeoutTx, localSig: ByteVector64): TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcTimeoutTx {
        val witness = when (val tree = claimHtlcTimeoutTx.input.scriptTreeAndInternalKey) {
            null -> Scripts.witnessClaimHtlcTimeoutFromCommitTx(localSig, claimHtlcTimeoutTx.input.redeemScript)
            else -> Script.witnessScriptPathPay2tr(tree.internalKey, (tree.scriptTree as ScriptTree.Branch).left as ScriptTree.Leaf, ScriptWitness(listOf(localSig)), tree.scriptTree)

        }
        return claimHtlcTimeoutTx.copy(tx = claimHtlcTimeoutTx.tx.updateWitness(0, witness))
    }

//    fun addSigs(htlcDelayedTx: TransactionWithInputInfo.HtlcDelayedTx, localSig: ByteVector64): TransactionWithInputInfo.HtlcDelayedTx {
//        val witness = when (val tree = htlcDelayedTx.input.scriptTreeAndInternalKey) {
//            null -> witnessToLocalDelayedAfterDelay(localSig, htlcDelayedTx.input.redeemScript)
//            else -> Script.witnessScriptPathPay2tr(tree.internalKey, tree.scriptTree as ScriptTree.Leaf, ScriptWitness(listOf(localSig)), tree.scriptTree)
//        }
//        return htlcDelayedTx.copy(tx = htlcDelayedTx.tx.updateWitness(0, witness))
//    }

    fun addSigs(claimRemoteDelayed: TransactionWithInputInfo.ClaimRemoteCommitMainOutputTx.ClaimRemoteDelayedOutputTx, localSig: ByteVector64): TransactionWithInputInfo.ClaimRemoteCommitMainOutputTx.ClaimRemoteDelayedOutputTx {
        val witness = when (val tree = claimRemoteDelayed.input.scriptTreeAndInternalKey) {
            null -> Scripts.witnessToRemoteDelayedAfterDelay(localSig, claimRemoteDelayed.input.redeemScript)
            else -> {
                val leaf = claimRemoteDelayed.input.scriptTreeAndInternalKey.scriptTree as ScriptTree.Leaf
               Script.witnessScriptPathPay2tr(tree.internalKey, leaf, ScriptWitness(listOf(localSig)), leaf)
            }
        }
        return claimRemoteDelayed.copy(tx = claimRemoteDelayed.tx.updateWitness(0, witness))
    }

    fun addSigs(claimLocalDelayed: TransactionWithInputInfo.ClaimLocalDelayedOutputTx, localSig: ByteVector64): TransactionWithInputInfo.ClaimLocalDelayedOutputTx {
        val witness = when (val tree = claimLocalDelayed.input.scriptTreeAndInternalKey) {
            null -> Scripts.witnessToLocalDelayedAfterDelay(localSig, claimLocalDelayed.input.redeemScript)
            else -> {
                when (tree.scriptTree) {
                    is ScriptTree.Branch -> {
                        // claim a to-local delayed output
                        Script.witnessScriptPathPay2tr(tree.internalKey, tree.scriptTree.left as ScriptTree.Leaf, ScriptWitness(listOf(localSig)), tree.scriptTree)
                    }

                    is ScriptTree.Leaf -> {
                        // claim a delayed HTLC output
                        Script.witnessScriptPathPay2tr(tree.internalKey, tree.scriptTree, ScriptWitness(listOf(localSig)), tree.scriptTree)
                    }

                }
            }
        }
        return claimLocalDelayed.copy(tx = claimLocalDelayed.tx.updateWitness(0, witness))
    }

    fun addSigs(claimHtlcDelayedPenalty: TransactionWithInputInfo.ClaimHtlcDelayedOutputPenaltyTx, revocationSig: ByteVector64): TransactionWithInputInfo.ClaimHtlcDelayedOutputPenaltyTx {
        val witness = when (claimHtlcDelayedPenalty.input.scriptTreeAndInternalKey) {
            null -> Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig, claimHtlcDelayedPenalty.input.redeemScript)
            else -> Script.witnessKeyPathPay2tr(revocationSig)
        }
        return claimHtlcDelayedPenalty.copy(tx = claimHtlcDelayedPenalty.tx.updateWitness(0, witness))
    }

    fun addSigs(closingTx: TransactionWithInputInfo.ClosingTx, localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: ByteVector64, remoteSig: ByteVector64): TransactionWithInputInfo.ClosingTx {
        val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
        return closingTx.copy(tx = closingTx.tx.updateWitness(0, witness))
    }

    fun addAggregatedSignature(commitTx: TransactionWithInputInfo.CommitTx, aggregatedSignature: ByteVector64): TransactionWithInputInfo.CommitTx {
        return commitTx.copy(tx = commitTx.tx.updateWitness(0, Script.witnessKeyPathPay2tr(aggregatedSignature)))
    }

    fun addAggregatedSignature(closingTx: TransactionWithInputInfo.ClosingTx, aggregatedSignature: ByteVector64): TransactionWithInputInfo.ClosingTx {
        return closingTx.copy(tx = closingTx.tx.updateWitness(0, Script.witnessKeyPathPay2tr(aggregatedSignature)))
    }

    fun checkSpendable(txinfo: TransactionWithInputInfo): Try<Unit> = runTrying {
        txinfo.tx.correctlySpends(mapOf(txinfo.tx.txIn.first().outPoint to txinfo.input.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    fun checkSig(txinfo: TransactionWithInputInfo, sig: ByteVector64, pubKey: PublicKey, sigHash: Int = SigHash.SIGHASH_ALL): Boolean {
        val data = txinfo.tx.hashForSigning(0, txinfo.input.redeemScript.toByteArray(), sigHash, txinfo.input.txOut.amount, SigVersion.SIGVERSION_WITNESS_V0)
        return Crypto.verifySignature(data, sig, pubKey)
    }
}
