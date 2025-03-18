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
import fr.acinq.bitcoin.utils.getOrElse
import fr.acinq.bitcoin.utils.runTrying
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.Commitments
import fr.acinq.lightning.channel.PartialSignatureWithNonce
import fr.acinq.lightning.transactions.CommitmentOutput.InHtlc
import fr.acinq.lightning.transactions.CommitmentOutput.OutHtlc
import fr.acinq.lightning.transactions.Scripts.Taproot
import fr.acinq.lightning.transactions.Scripts.Taproot.NUMS_POINT
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.UpdateAddHtlc
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Type alias for a collection of commitment output links */
typealias TransactionsCommitmentOutputs = List<Transactions.CommitmentOutputLink<CommitmentOutput>>

/**
 * Created by PM on 15/12/2016.
 */
object Transactions {

    const val MAX_STANDARD_TX_WEIGHT = 400_000

    sealed class InputInfo {
        abstract val outPoint: OutPoint
        abstract val txOut: TxOut

        data class SegwitInput(override val outPoint: OutPoint, override val txOut: TxOut, val redeemScript: ByteVector) : InputInfo() {
            constructor(outPoint: OutPoint, txOut: TxOut, redeemScript: List<ScriptElt>) : this(outPoint, txOut, ByteVector(Script.write(redeemScript)))
        }

        sealed class RedeemPath {
            data class KeyPath(val scriptTree: ScriptTree?) : RedeemPath()
            data class ScriptPath(val scriptTree: ScriptTree, val leafHash: ByteVector32) : RedeemPath() {
                init {
                    require(findScript(scriptTree, leafHash) != null) { "script tree must contain the provided leaf" }
                }

                companion object {
                    fun findScript(scriptTree: ScriptTree, leafHash: ByteVector32): ScriptTree.Leaf? = when (scriptTree) {
                        is ScriptTree.Leaf -> if (scriptTree.hash() == leafHash) scriptTree else null
                        is ScriptTree.Branch -> findScript(scriptTree.left, leafHash) ?: findScript(scriptTree.right, leafHash)
                    }
                }
            }
        }

        data class TaprootInput(override val outPoint: OutPoint, override val txOut: TxOut, val internalKey: XonlyPublicKey, val redeemPath: RedeemPath) : InputInfo()
    }

    @Serializable
    sealed class TransactionWithInputInfo {
        @Contextual
        abstract val input: InputInfo
        abstract val tx: Transaction
        val amountIn: Satoshi get() = input.txOut.amount
        val fee: Satoshi get() = input.txOut.amount - tx.txOut.map { it.amount }.sum()

        fun sign(key: PrivateKey): ByteVector64 {
            val sigHash = when (input) {
                is InputInfo.SegwitInput -> SigHash.SIGHASH_ALL
                is InputInfo.TaprootInput -> SigHash.SIGHASH_DEFAULT
            }
            return sign(key, sigHash)
        }

        open fun sign(key: PrivateKey, sigHash: Int): ByteVector64 {
            val inputIndex = tx.txIn.indexOfFirst { it.outPoint == input.outPoint }
            require(inputIndex >= 0) { "transaction doesn't spend the input to sign" }
            return when (input) {
                is InputInfo.SegwitInput -> sign(tx, inputIndex, (input as InputInfo.SegwitInput).redeemScript.toByteArray(), input.txOut.amount, key, sigHash)
                is InputInfo.TaprootInput -> {
                    when (val redeemPath = (input as InputInfo.TaprootInput).redeemPath) {
                        is InputInfo.RedeemPath.KeyPath -> {
                            Transaction.signInputTaprootKeyPath(key, tx, inputIndex, listOf(input.txOut), sigHash, redeemPath.scriptTree)
                        }

                        is InputInfo.RedeemPath.ScriptPath -> {
                            Transaction.signInputTaprootScriptPath(key, tx, inputIndex, listOf(input.txOut), sigHash, redeemPath.leafHash)
                        }
                    }
                }
            }
        }

        open fun checkSig(sig: ByteVector64, pubKey: PublicKey, sigHash: Int = SigHash.SIGHASH_ALL): Boolean {
            return when (input) {
                is InputInfo.SegwitInput -> {
                    val data = Transaction.hashForSigning(tx, 0, (input as InputInfo.SegwitInput).redeemScript.toByteArray(), sigHash, input.txOut.amount, SigVersion.SIGVERSION_WITNESS_V0)
                    Crypto.verifySignature(data, sig, pubKey)
                }

                is InputInfo.TaprootInput -> {
                    val data = when (val redeemPath = (input as InputInfo.TaprootInput).redeemPath) {
                        is InputInfo.RedeemPath.KeyPath -> {
                            Transaction.hashForSigningTaprootKeyPath(tx, 0, listOf(input.txOut), sigHash)
                        }

                        is InputInfo.RedeemPath.ScriptPath -> {
                            Transaction.hashForSigningTaprootScriptPath(tx, 0, listOf(input.txOut), sigHash, redeemPath.leafHash)
                        }
                    }
                    Crypto.verifySignatureSchnorr(data, sig, pubKey.xOnly())
                }
            }
        }

        @Serializable
        data class SpliceTx(@Contextual override val input: InputInfo, @Contextual override val tx: Transaction) : TransactionWithInputInfo()

        @Serializable
        data class CommitTx(@Contextual override val input: InputInfo, @Contextual override val tx: Transaction) : TransactionWithInputInfo() {
            fun checkPartialSignature(psig: PartialSignatureWithNonce, localPubKey: PublicKey, localNonce: IndividualNonce, remotePubKey: PublicKey): Boolean {
                val inputIndex = this.tx.txIn.indexOfFirst { it.outPoint == input.outPoint }
                val session = Musig2.taprootSession(
                    this.tx,
                    inputIndex,
                    listOf(this.input.txOut),
                    Scripts.sort(listOf(localPubKey, remotePubKey)),
                    listOf(localNonce, psig.nonce),
                    null
                )
                val result = session.map { it.verify(psig.partialSig, psig.nonce, remotePubKey) }.getOrElse { false }
                return result
            }
        }

        @Serializable
        sealed class HtlcTx : TransactionWithInputInfo() {
            abstract val htlcId: Long

            @Serializable
            data class HtlcSuccessTx(
                @Contextual override val input: InputInfo,
                @Contextual override val tx: Transaction,
                @Contextual val paymentHash: ByteVector32,
                override val htlcId: Long
            ) : HtlcTx() {
            }

            @Serializable
            data class HtlcTimeoutTx(@Contextual override val input: InputInfo, @Contextual override val tx: Transaction, override val htlcId: Long) : HtlcTx() {
            }
        }

        @Serializable
        sealed class ClaimHtlcTx : TransactionWithInputInfo() {
            abstract val htlcId: Long

            @Serializable
            data class ClaimHtlcSuccessTx(@Contextual override val input: InputInfo, @Contextual override val tx: Transaction, override val htlcId: Long) : ClaimHtlcTx() {
            }

            @Serializable
            data class ClaimHtlcTimeoutTx(@Contextual override val input: InputInfo, @Contextual override val tx: Transaction, override val htlcId: Long) : ClaimHtlcTx() {
            }
        }

        @Serializable
        sealed class ClaimAnchorOutputTx : TransactionWithInputInfo() {
            @Serializable
            data class ClaimLocalAnchorOutputTx(@Contextual override val input: InputInfo, @Contextual override val tx: Transaction) : ClaimAnchorOutputTx()

            @Serializable
            data class ClaimRemoteAnchorOutputTx(@Contextual override val input: InputInfo, @Contextual override val tx: Transaction) : ClaimAnchorOutputTx()
        }

        @Serializable
        data class ClaimLocalDelayedOutputTx(@Contextual override val input: InputInfo, @Contextual override val tx: Transaction) : TransactionWithInputInfo() {
        }

        @Serializable
        sealed class ClaimRemoteCommitMainOutputTx : TransactionWithInputInfo() {
            // TODO: once we deprecate v2/v3 serialization, we can remove the class nesting.
            @Serializable
            data class ClaimRemoteDelayedOutputTx(@Contextual override val input: InputInfo, @Contextual override val tx: Transaction) : ClaimRemoteCommitMainOutputTx() {
            }
        }

        @Serializable
        data class MainPenaltyTx(@Contextual override val input: InputInfo, @Contextual override val tx: Transaction) : TransactionWithInputInfo() {
        }

        @Serializable
        data class HtlcPenaltyTx(@Contextual override val input: InputInfo, @Contextual override val tx: Transaction) : TransactionWithInputInfo() {
        }

        @Serializable
        data class ClaimHtlcDelayedOutputPenaltyTx(@Contextual override val input: InputInfo, @Contextual override val tx: Transaction) : TransactionWithInputInfo() {
        }

        @Serializable
        data class ClosingTx(@Contextual override val input: InputInfo, @Contextual override val tx: Transaction, val toLocalIndex: Int?) : TransactionWithInputInfo() {
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
     * When *local* *current* [TransactionWithInputInfo.CommitTx] is published:
     *   - [TransactionWithInputInfo.ClaimLocalDelayedOutputTx] spends to-local output of [TransactionWithInputInfo.CommitTx] after a delay
     *   - [TransactionWithInputInfo.HtlcTx.HtlcSuccessTx] spends htlc-received outputs of [TransactionWithInputInfo.CommitTx] for which we have the preimage
     *     - [TransactionWithInputInfo.ClaimLocalDelayedOutputTx] spends [TransactionWithInputInfo.HtlcTx.HtlcSuccessTx] after a delay
     *   - [TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx] spends htlc-sent outputs of [TransactionWithInputInfo.CommitTx] after a timeout
     *     - [TransactionWithInputInfo.ClaimLocalDelayedOutputTx] spends [TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx] after a delay
     *
     * When *remote* *current* [TransactionWithInputInfo.CommitTx] is published:
     *   - [TransactionWithInputInfo.ClaimRemoteCommitMainOutputTx.ClaimRemoteDelayedOutputTx] spends to-local output of [TransactionWithInputInfo.CommitTx]
     *   - [TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcSuccessTx] spends htlc-received outputs of [TransactionWithInputInfo.CommitTx] for which we have the preimage
     *   - [TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcTimeoutTx] spends htlc-sent outputs of [TransactionWithInputInfo.CommitTx] after a timeout
     *
     * When *remote* *revoked* [TransactionWithInputInfo.CommitTx] is published:
     *   - [TransactionWithInputInfo.ClaimRemoteCommitMainOutputTx.ClaimRemoteDelayedOutputTx] spends to-local output of [TransactionWithInputInfo.CommitTx]
     *   - [TransactionWithInputInfo.MainPenaltyTx] spends remote main output using the per-commitment secret
     *   - [TransactionWithInputInfo.HtlcTx.HtlcSuccessTx] spends htlc-sent outputs of [TransactionWithInputInfo.CommitTx] for which they have the preimage (published by remote)
     *     - [TransactionWithInputInfo.ClaimHtlcDelayedOutputPenaltyTx] spends [TransactionWithInputInfo.HtlcTx.HtlcSuccessTx] using the revocation secret (published by local)
     *   - [TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx] spends htlc-received outputs of [TransactionWithInputInfo.CommitTx] after a timeout (published by remote)
     *     - [TransactionWithInputInfo.ClaimHtlcDelayedOutputPenaltyTx] spends [TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx] using the revocation secret (published by local)
     *   - [TransactionWithInputInfo.HtlcPenaltyTx] spends competes with [TransactionWithInputInfo.HtlcTx.HtlcSuccessTx] and [TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx] for the same outputs (published by local)
     */
    // legacy swap-in. witness is 2 signatures (73 bytes) + redeem script (77 bytes)
    const val swapInputWeightLegacy = 392

    // musig2 swap-in. witness is a single Schnorr signature (64 bytes)
    const val swapInputWeight = 233

    // The following values are specific to lightning and used to estimate fees.
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
     */
    sealed class CommitmentOutputLink<T : CommitmentOutput> : Comparable<CommitmentOutputLink<T>> {
        abstract val output: TxOut
        abstract val commitmentOutput: T

        @Suppress("UNCHECKED_CAST")
        inline fun <reified R : CommitmentOutput> filter(): CommitmentOutputLink<R>? = when (commitmentOutput) {
            is R -> this as CommitmentOutputLink<R>
            else -> null
        }

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

        data class SegwitOutput<T : CommitmentOutput>(override val output: TxOut, val redeemScript: List<ScriptElt>, override val commitmentOutput: T) : CommitmentOutputLink<T>()

        data class TaprootOutput<T : CommitmentOutput>(override val output: TxOut, val internalKey: XonlyPublicKey, val scriptTree: ScriptTree?, override val commitmentOutput: T) : CommitmentOutputLink<T>()
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
                    val toLocalScriptTree = Taproot.toLocalScriptTree(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
                    outputs.add(
                        CommitmentOutputLink.TaprootOutput(
                            TxOut(toLocalAmount, Script.pay2tr(XonlyPublicKey(NUMS_POINT), toLocalScriptTree)),
                            NUMS_POINT.xOnly(), toLocalScriptTree,
                            CommitmentOutput.ToLocal
                        )
                    )
                }

                else -> outputs.add(
                    CommitmentOutputLink.SegwitOutput(
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
                        CommitmentOutputLink.TaprootOutput(
                            TxOut(toRemoteAmount, Script.pay2tr(XonlyPublicKey(NUMS_POINT), toRemoteScriptTree)),
                            NUMS_POINT.xOnly(), toRemoteScriptTree,
                            CommitmentOutput.ToRemote
                        )
                    )
                }

                else -> outputs.add(
                    CommitmentOutputLink.SegwitOutput(
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
                        CommitmentOutputLink.TaprootOutput(
                            TxOut(Commitments.ANCHOR_AMOUNT, Script.pay2tr(localDelayedPaymentPubkey.xOnly(), Taproot.anchorScriptTree)),
                            localDelayedPaymentPubkey.xOnly(), Taproot.anchorScriptTree,
                            CommitmentOutput.ToLocalAnchor(localFundingPubkey)
                        )
                    )
                }

                else -> outputs.add(
                    CommitmentOutputLink.SegwitOutput(
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
                    CommitmentOutputLink.TaprootOutput(
                        TxOut(Commitments.ANCHOR_AMOUNT, Script.pay2tr(remotePaymentPubkey.xOnly(), Taproot.anchorScriptTree)),
                        remotePaymentPubkey.xOnly(), Taproot.anchorScriptTree,
                        CommitmentOutput.ToLocalAnchor(remoteFundingPubkey)
                    )
                )

                else -> outputs.add(
                    CommitmentOutputLink.SegwitOutput(
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
                    val offeredHtlcTree = Taproot.offeredHtlcTree(localHtlcPubkey, remoteHtlcPubkey, htlc.add.paymentHash)
                    outputs.add(
                        CommitmentOutputLink.TaprootOutput(
                            TxOut(htlc.add.amountMsat.truncateToSatoshi(), Script.pay2tr(localRevocationPubkey.xOnly(), offeredHtlcTree)), localRevocationPubkey.xOnly(), offeredHtlcTree, OutHtlc(htlc)
                        )
                    )
                }

                else -> {
                    val redeemScript = Scripts.htlcOffered(localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, Crypto.ripemd160(htlc.add.paymentHash.toByteArray()))
                    outputs.add(CommitmentOutputLink.SegwitOutput(TxOut(htlc.add.amountMsat.truncateToSatoshi(), Script.pay2wsh(redeemScript)), redeemScript, OutHtlc(htlc)))
                }
            }
        }

        trimReceivedHtlcs(localDustLimit, spec).forEach { htlc ->
            when (isTaprootChannel) {
                true -> {
                    val receivedHtlcTree = Taproot.receivedHtlcTree(localHtlcPubkey, remoteHtlcPubkey, htlc.add.paymentHash, htlc.add.cltvExpiry)
                    outputs.add(
                        CommitmentOutputLink.TaprootOutput(
                            TxOut(htlc.add.amountMsat.truncateToSatoshi(), Script.pay2tr(localRevocationPubkey.xOnly(), receivedHtlcTree)), localRevocationPubkey.xOnly(), receivedHtlcTree, InHtlc(htlc)
                        )
                    )
                }

                else -> {
                    val redeemScript = Scripts.htlcReceived(localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, Crypto.ripemd160(htlc.add.paymentHash.toByteArray()), htlc.add.cltvExpiry)
                    outputs.add(CommitmentOutputLink.SegwitOutput(TxOut(htlc.add.amountMsat.truncateToSatoshi(), Script.pay2wsh(redeemScript)), redeemScript, InHtlc(htlc)))
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

        abstract fun <R> flatMap(f: (T) -> TxResult<R>): TxResult<R>

        abstract fun orElse(or: TxResult<T>): TxResult<T>

        data class Skipped<T>(val why: TxGenerationSkipped) : TxResult<T>() {
            override fun <R> map(f: (T) -> R): TxResult<R> = Skipped(why)
            override fun <R> flatMap(f: (T) -> TxResult<R>) = Skipped<R>(why)
            override fun orElse(or: TxResult<T>): TxResult<T> = or
        }

        data class Success<T>(val result: T) : TxResult<T>() {
            override fun <R> map(f: (T) -> R): TxResult<R> = Success(f(result))
            override fun <R> flatMap(f: (T) -> TxResult<R>): TxResult<R> = f(result)
            override fun orElse(or: TxResult<T>): TxResult<T> = this
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
    ): TxResult<TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx> {
        val fee = weight2fee(feerate, Commitments.HTLC_TIMEOUT_WEIGHT)
        val htlc = output.commitmentOutput.outgoingHtlc.add
        val amount = htlc.amountMsat.truncateToSatoshi() - fee
        return if (amount < localDustLimit) {
            TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
        } else {
            when (output) {
                is CommitmentOutputLink.TaprootOutput -> {
                    val scriptTree: ScriptTree.Branch = (output.scriptTree as ScriptTree.Branch?)!!
                    val input = InputInfo.TaprootInput(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], output.internalKey, InputInfo.RedeemPath.ScriptPath(scriptTree, scriptTree.left.hash()))
                    val tree = ScriptTree.Leaf(Taproot.toLocalDelayed(localDelayedPaymentPubkey, toLocalDelay))
                    val tx = Transaction(
                        version = 2,
                        txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 1L)),
                        txOut = listOf(TxOut(amount, Script.pay2tr(localRevocationPubkey.xOnly(), tree))),
                        lockTime = htlc.cltvExpiry.toLong()
                    )
                    TxResult.Success(TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx(input, tx, htlc.id))
                }

                is CommitmentOutputLink.SegwitOutput -> {
                    val input = InputInfo.SegwitInput(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], output.redeemScript)
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
    ): TxResult<TransactionWithInputInfo.HtlcTx.HtlcSuccessTx> {
        val fee = weight2fee(feerate, Commitments.HTLC_SUCCESS_WEIGHT)
        val htlc = output.commitmentOutput.incomingHtlc.add
        val amount = htlc.amountMsat.truncateToSatoshi() - fee
        return if (amount < localDustLimit) {
            TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
        } else {
            when (output) {
                is CommitmentOutputLink.TaprootOutput -> {
                    val scriptTree: ScriptTree.Branch = (output.scriptTree as ScriptTree.Branch?)!!
                    val input = InputInfo.TaprootInput(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], output.internalKey, InputInfo.RedeemPath.ScriptPath(scriptTree, scriptTree.right.hash()))
                    val tree = ScriptTree.Leaf(Taproot.toLocalDelayed(localDelayedPaymentPubkey, toLocalDelay))
                    val tx = Transaction(
                        version = 2,
                        txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 1L)),
                        txOut = listOf(TxOut(amount, Script.pay2tr(localRevocationPubkey.xOnly(), tree))),
                        lockTime = 0
                    )
                    TxResult.Success(TransactionWithInputInfo.HtlcTx.HtlcSuccessTx(input, tx, htlc.paymentHash, htlc.id))
                }

                is CommitmentOutputLink.SegwitOutput -> {
                    val input = InputInfo.SegwitInput(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], output.redeemScript)
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
        outputs: TransactionsCommitmentOutputs
    ): List<TransactionWithInputInfo.HtlcTx> {
        val htlcTimeoutTxs = outputs
            .mapIndexed { outputIndex, link ->
                link.filter<OutHtlc>()?.let { makeHtlcTimeoutTx(commitTx, it, outputIndex, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, feerate) }
            }
            .filterIsInstance<TxResult.Success<TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx>>()
            .map { it.result }

        val htlcSuccessTxs = outputs
            .mapIndexed { outputIndex, link ->
                link.filter<InHtlc>()?.let { makeHtlcSuccessTx(commitTx, it, outputIndex, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, feerate) }
            }
            .filterIsInstance<TxResult.Success<TransactionWithInputInfo.HtlcTx.HtlcSuccessTx>>()
            .map { it.result }

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
                val input = when (val output = outputs[outputIndex]) {
                    is CommitmentOutputLink.SegwitOutput -> InputInfo.SegwitInput(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], redeemScript)
                    is CommitmentOutputLink.TaprootOutput -> {
                        val scriptTree: ScriptTree.Branch = output.scriptTree as ScriptTree.Branch
                        InputInfo.TaprootInput(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], output.internalKey, InputInfo.RedeemPath.ScriptPath(scriptTree, scriptTree.right.hash()))
                    }
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
                val input = when (val output = outputs[outputIndex]) {
                    is CommitmentOutputLink.SegwitOutput -> InputInfo.SegwitInput(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], redeemScript)
                    is CommitmentOutputLink.TaprootOutput -> {
                        val scriptTree: ScriptTree.Branch = output.scriptTree as ScriptTree.Branch
                        InputInfo.TaprootInput(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], output.internalKey, InputInfo.RedeemPath.ScriptPath(scriptTree, scriptTree.left.hash()))
                    }
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
    ): TxResult<TransactionWithInputInfo.ClaimRemoteCommitMainOutputTx.ClaimRemoteDelayedOutputTx> {

        fun makeUnsignedTx(input: InputInfo): TxResult<TransactionWithInputInfo.ClaimRemoteCommitMainOutputTx.ClaimRemoteDelayedOutputTx> {
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
            return if (amount < localDustLimit) {
                TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
            } else {
                val tx1 = tx.copy(txOut = listOf(tx.txOut.first().copy(amount = amount)))
                TxResult.Success(TransactionWithInputInfo.ClaimRemoteCommitMainOutputTx.ClaimRemoteDelayedOutputTx(input, tx1))
            }
        }

        fun makeClaimRemoteDelayedOutputTxTaproot(): TxResult<TransactionWithInputInfo.ClaimRemoteCommitMainOutputTx.ClaimRemoteDelayedOutputTx> {
            val pubkeyScript = Script.pay2tr(XonlyPublicKey(NUMS_POINT), Taproot.toRemoteScriptTree(localPaymentPubkey))
            return findPubKeyScriptIndex(commitTx, pubkeyScript).flatMap { outputIndex ->
                val scriptTree = Taproot.toRemoteScriptTree(localPaymentPubkey)
                val input = InputInfo.TaprootInput(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], NUMS_POINT.xOnly(), InputInfo.RedeemPath.ScriptPath(scriptTree, scriptTree.hash()))
                makeUnsignedTx(input)
            }
        }

        fun makeClaimRemoteDelayedOutputTxSegwit(): TxResult<TransactionWithInputInfo.ClaimRemoteCommitMainOutputTx.ClaimRemoteDelayedOutputTx> {
            val pubkeyScript = Script.pay2wsh(Scripts.toRemoteDelayed(localPaymentPubkey))
            return findPubKeyScriptIndex(commitTx, pubkeyScript).flatMap { outputIndex ->
                val input = InputInfo.SegwitInput(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], Scripts.toRemoteDelayed(localPaymentPubkey))
                makeUnsignedTx(input)
            }
        }

        return makeClaimRemoteDelayedOutputTxTaproot().orElse(makeClaimRemoteDelayedOutputTxSegwit())
    }

    fun makeHtlcDelayedTx(
        htlcTx: Transaction,
        localDustLimit: Satoshi,
        localRevocationPubkey: PublicKey,
        toLocalDelay: CltvExpiryDelta,
        localDelayedPaymentPubkey: PublicKey,
        localFinalScriptPubKey: ByteArray,
        feeratePerKw: FeeratePerKw,
    ): TxResult<TransactionWithInputInfo.ClaimLocalDelayedOutputTx> {

        fun makeHtlcDelayedTxTaproot(): TxResult<TransactionWithInputInfo.ClaimLocalDelayedOutputTx> {
            val htlcTxTree = ScriptTree.Leaf(Taproot.toLocalDelayed(localDelayedPaymentPubkey, toLocalDelay))
            return when (val pubkeyScriptIndex = findPubKeyScriptIndex(htlcTx, Script.pay2tr(localRevocationPubkey.xOnly(), htlcTxTree))) {
                is TxResult.Skipped -> TxResult.Skipped(pubkeyScriptIndex.why)
                is TxResult.Success -> {
                    val outputIndex = pubkeyScriptIndex.result
                    val input = InputInfo.TaprootInput(OutPoint(htlcTx, outputIndex.toLong()), htlcTx.txOut[outputIndex], localRevocationPubkey.xOnly(), InputInfo.RedeemPath.ScriptPath(htlcTxTree, htlcTxTree.hash()))
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
        return makeHtlcDelayedTxTaproot().orElse(makeClaimLocalDelayedOutputTx(htlcTx, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, localFinalScriptPubKey, feeratePerKw))
    }

    fun makeClaimLocalDelayedOutputTx(
        delayedOutputTx: Transaction,
        localDustLimit: Satoshi,
        localRevocationPubkey: PublicKey,
        toLocalDelay: CltvExpiryDelta,
        localDelayedPaymentPubkey: PublicKey,
        localFinalScriptPubKey: ByteArray,
        feerate: FeeratePerKw,
    ): TxResult<TransactionWithInputInfo.ClaimLocalDelayedOutputTx> {

        fun makeUnsignedTx(input: InputInfo): TxResult<TransactionWithInputInfo.ClaimLocalDelayedOutputTx> {
            val tx = Transaction(
                version = 2,
                txIn = listOf(TxIn(input.outPoint, ByteVector.empty, toLocalDelay.toLong())),
                txOut = listOf(TxOut(0.sat, localFinalScriptPubKey)),
                lockTime = 0
            )
            val weight = when (input) {
                is InputInfo.TaprootInput -> {
                    val toLocalScriptTree = Taproot.toLocalScriptTree(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
                    val witness = Script.witnessScriptPathPay2tr(XonlyPublicKey(NUMS_POINT), toLocalScriptTree.left as ScriptTree.Leaf, ScriptWitness(listOf(ByteVector64.Zeroes)), toLocalScriptTree)
                    tx.updateWitness(0, witness).weight()
                }

                else -> addSigs(TransactionWithInputInfo.ClaimLocalDelayedOutputTx(input, tx), PlaceHolderSig).tx.weight()
            }
            val fee = weight2fee(feerate, weight)
            val amount = input.txOut.amount - fee
            return if (amount < localDustLimit) {
                TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
            } else {
                val tx1 = tx.copy(txOut = listOf(tx.txOut.first().copy(amount = amount)))
                TxResult.Success(TransactionWithInputInfo.ClaimLocalDelayedOutputTx(input, tx1))
            }
        }

        fun makeClaimLocalDelayedOutputTxTaproot(): TxResult<TransactionWithInputInfo.ClaimLocalDelayedOutputTx> {
            val toLocalScriptTree = Taproot.toLocalScriptTree(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
            val pubkeyScript = Script.pay2tr(XonlyPublicKey(NUMS_POINT), toLocalScriptTree)
            return findPubKeyScriptIndex(delayedOutputTx, pubkeyScript).flatMap { outputIndex ->
                val input = InputInfo.TaprootInput(
                    OutPoint(delayedOutputTx, outputIndex.toLong()),
                    delayedOutputTx.txOut[outputIndex],
                    NUMS_POINT.xOnly(),
                    InputInfo.RedeemPath.ScriptPath(toLocalScriptTree, toLocalScriptTree.left.hash())
                )
                makeUnsignedTx(input)
            }
        }

        fun makeClaimLocalDelayedOutputTxSegwit(): TxResult<TransactionWithInputInfo.ClaimLocalDelayedOutputTx> {
            val redeemScript = Script.write(Scripts.toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)).byteVector()
            val pubkeyScript = Script.pay2wsh(redeemScript)
            return findPubKeyScriptIndex(delayedOutputTx, pubkeyScript).flatMap { outputIndex ->
                val input = InputInfo.SegwitInput(OutPoint(delayedOutputTx, outputIndex.toLong()), delayedOutputTx.txOut[outputIndex], Scripts.toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey))
                makeUnsignedTx(input)
            }
        }

        return makeClaimLocalDelayedOutputTxTaproot().orElse(makeClaimLocalDelayedOutputTxSegwit())
    }

    fun makeClaimDelayedOutputPenaltyTxs(
        delayedOutputTx: Transaction,
        localDustLimit: Satoshi,
        localRevocationPubkey: PublicKey,
        toLocalDelay: CltvExpiryDelta,
        localDelayedPaymentPubkey: PublicKey,
        localFinalScriptPubKey: ByteArray,
        feerate: FeeratePerKw,
    ): List<TxResult<TransactionWithInputInfo.ClaimHtlcDelayedOutputPenaltyTx>> {

        fun makeUnsignedTx(input: InputInfo): TxResult<TransactionWithInputInfo.ClaimHtlcDelayedOutputPenaltyTx> {
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
            return if (amount < localDustLimit) {
                TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
            } else {
                val tx1 = tx.copy(txOut = listOf(tx.txOut.first().copy(amount = amount)))
                TxResult.Success(TransactionWithInputInfo.ClaimHtlcDelayedOutputPenaltyTx(input, tx1))
            }
        }

        fun makeClaimHtlcDelayedOutputPenaltyTxsTaproot(): TxResult<List<TxResult<TransactionWithInputInfo.ClaimHtlcDelayedOutputPenaltyTx>>> {
            val tree = Taproot.toLocalScriptTree(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
            val pubkeyScript = Script.pay2tr(localRevocationPubkey.xOnly(), tree.left)
            return findPubKeyScriptIndexes(delayedOutputTx, pubkeyScript).map { outputIndexes ->
                outputIndexes.map { outputIndex ->
                    val input = InputInfo.TaprootInput(OutPoint(delayedOutputTx, outputIndex.toLong()), delayedOutputTx.txOut[outputIndex], localRevocationPubkey.xOnly(), InputInfo.RedeemPath.KeyPath(tree.left))
                    makeUnsignedTx(input)
                }
            }
        }

        fun makeClaimHtlcDelayedOutputPenaltyTxsSegwit(): TxResult<List<TxResult<TransactionWithInputInfo.ClaimHtlcDelayedOutputPenaltyTx>>> {
            val pubkeyScript = Script.pay2wsh(Scripts.toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey))
            return findPubKeyScriptIndexes(delayedOutputTx, pubkeyScript).map { outputIndexes ->
                outputIndexes.map { outputIndex ->
                    val redeemScript = Scripts.toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
                    val input = InputInfo.SegwitInput(OutPoint(delayedOutputTx, outputIndex.toLong()), delayedOutputTx.txOut[outputIndex], redeemScript)
                    makeUnsignedTx(input)
                }
            }
        }

        return when (val result = makeClaimHtlcDelayedOutputPenaltyTxsTaproot().orElse(makeClaimHtlcDelayedOutputPenaltyTxsSegwit())) {
            is TxResult.Skipped -> listOf(TxResult.Skipped(result.why))
            is TxResult.Success -> result.result
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
    ): TxResult<TransactionWithInputInfo.MainPenaltyTx> {

        fun nmakeUnsignedTx(input: InputInfo): TxResult<TransactionWithInputInfo.MainPenaltyTx> {
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
                TxResult.Success(TransactionWithInputInfo.MainPenaltyTx(input, tx1))
            }
        }

        fun makeMainPenaltyTxTaproot(): TxResult<TransactionWithInputInfo.MainPenaltyTx> {
            val tree = Taproot.toLocalScriptTree(remoteRevocationPubkey, toRemoteDelay, remoteDelayedPaymentPubkey)
            val pubkeyScript = Script.pay2tr(XonlyPublicKey(NUMS_POINT), tree)
            return findPubKeyScriptIndex(commitTx, pubkeyScript).flatMap { outputIndex ->
                val input = InputInfo.TaprootInput(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], NUMS_POINT.xOnly(), InputInfo.RedeemPath.ScriptPath(tree, tree.right.hash()))
                nmakeUnsignedTx(input)
            }
        }

        fun makeMainPenaltyTxSegwit(): TxResult<TransactionWithInputInfo.MainPenaltyTx> {
            val redeemScript = Scripts.toLocalDelayed(remoteRevocationPubkey, toRemoteDelay, remoteDelayedPaymentPubkey)
            val pubkeyScript = Script.pay2wsh(redeemScript)
            return findPubKeyScriptIndex(commitTx, pubkeyScript).flatMap { outputIndex ->
                val input = InputInfo.SegwitInput(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], redeemScript)
                nmakeUnsignedTx(input)
            }
        }

        return makeMainPenaltyTxTaproot().orElse(makeMainPenaltyTxSegwit())
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
        val input = InputInfo.SegwitInput(OutPoint(commitTx, htlcOutputIndex.toLong()), commitTx.txOut[htlcOutputIndex], ByteVector(redeemScript))
        // unsigned transaction
        val tx = Transaction(
            version = 2,
            txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 0xffffffffL)),
            txOut = listOf(TxOut(0.sat, localFinalScriptPubKey)),
            lockTime = 0
        )
        // compute weight with a dummy 73 bytes signature (the largest you can get)
        val weight = addSigs(TransactionWithInputInfo.HtlcPenaltyTx(input, tx), PlaceHolderSig, PlaceHolderPubKey).tx.weight()
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
        internalKey: XonlyPublicKey,
        scriptTree: ScriptTree?,
        localDustLimit: Satoshi,
        localFinalScriptPubKey: ByteArray,
        feeratePerKw: FeeratePerKw
    ): TxResult<TransactionWithInputInfo.HtlcPenaltyTx> {
        val input = InputInfo.TaprootInput(OutPoint(commitTx, htlcOutputIndex.toLong()), commitTx.txOut[htlcOutputIndex], internalKey, InputInfo.RedeemPath.KeyPath(scriptTree))
        // unsigned transaction
        val tx = Transaction(
            version = 2,
            txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 0xffffffffL)),
            txOut = listOf(TxOut(Satoshi(0), localFinalScriptPubKey)),
            lockTime = 0
        )
        // compute weight with a dummy 73 bytes signature (the largest you can get)
        val weight = addSigs(TransactionWithInputInfo.HtlcPenaltyTx(input, tx), PlaceHolderSig, PlaceHolderPubKey).tx.weight()
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

    private fun findPubKeyScriptIndex(tx: Transaction, pubkeyScript: List<ScriptElt>): TxResult<Int> = findPubKeyScriptIndex(tx, Script.write(pubkeyScript))

    private fun findPubKeyScriptIndexes(tx: Transaction, pubkeyScript: ByteArray): TxResult<List<Int>> {
        val outputIndexes = tx.txOut.withIndex().filter { it.value.publicKeyScript.contentEquals(pubkeyScript) }.map { it.index }
        return if (outputIndexes.isNotEmpty()) {
            TxResult.Success(outputIndexes)
        } else {
            TxResult.Skipped(TxGenerationSkipped.OutputNotFound)
        }
    }

    private fun findPubKeyScriptIndexes(tx: Transaction, pubkeyScript: List<ScriptElt>): TxResult<List<Int>> = findPubKeyScriptIndexes(tx, Script.write(pubkeyScript))

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

    fun sign(txInfo: TransactionWithInputInfo, key: PrivateKey, sigHash: Int): ByteVector64 {
        return txInfo.sign(key, sigHash)
    }

    fun sign(txInfo: TransactionWithInputInfo, key: PrivateKey): ByteVector64 {
        return txInfo.sign(key)
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
        val witness = when (mainPenaltyTx.input) {
            is InputInfo.SegwitInput -> {
                Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig, mainPenaltyTx.input.redeemScript)
            }

            is InputInfo.TaprootInput -> {
                when (val redeemPath = mainPenaltyTx.input.redeemPath) {
                    is InputInfo.RedeemPath.ScriptPath -> {
                        Script.witnessScriptPathPay2tr(
                            mainPenaltyTx.input.internalKey,
                            (redeemPath.scriptTree as ScriptTree.Branch).right as ScriptTree.Leaf,
                            ScriptWitness(listOf(revocationSig)),
                            redeemPath.scriptTree
                        )
                    }

                    is InputInfo.RedeemPath.KeyPath -> error("unexpected key path redeem path when building main penalty tx")
                }
            }
        }
        return mainPenaltyTx.copy(tx = mainPenaltyTx.tx.updateWitness(0, witness))
    }

    fun addSigs(htlcPenaltyTx: TransactionWithInputInfo.HtlcPenaltyTx, revocationSig: ByteVector64, revocationPubkey: PublicKey): TransactionWithInputInfo.HtlcPenaltyTx {
        val witness = when (htlcPenaltyTx.input) {
            is InputInfo.SegwitInput -> {
                Scripts.witnessHtlcWithRevocationSig(revocationSig, revocationPubkey, htlcPenaltyTx.input.redeemScript)
            }

            else -> {
                Script.witnessKeyPathPay2tr(revocationSig)
            }
        }
        return htlcPenaltyTx.copy(tx = htlcPenaltyTx.tx.updateWitness(0, witness))
    }

    fun addSigs(htlcSuccessTx: TransactionWithInputInfo.HtlcTx.HtlcSuccessTx, localSig: ByteVector64, remoteSig: ByteVector64, paymentPreimage: ByteVector32): TransactionWithInputInfo.HtlcTx.HtlcSuccessTx {
        val witness = when (htlcSuccessTx.input) {
            is InputInfo.SegwitInput -> Scripts.witnessHtlcSuccess(localSig, remoteSig, paymentPreimage, htlcSuccessTx.input.redeemScript)
            is InputInfo.TaprootInput -> {
                when (val redeemPath = htlcSuccessTx.input.redeemPath) {
                    is InputInfo.RedeemPath.ScriptPath -> {
                        val branch = redeemPath.scriptTree as ScriptTree.Branch
                        val sigHash = (SigHash.SIGHASH_SINGLE or SigHash.SIGHASH_ANYONECANPAY).toByte()
                        Script.witnessScriptPathPay2tr(htlcSuccessTx.input.internalKey, branch.right as ScriptTree.Leaf, ScriptWitness(listOf(remoteSig.concat(sigHash), localSig, paymentPreimage)), branch)
                    }

                    is InputInfo.RedeemPath.KeyPath -> error("unexpected key path when building HTLC success tx")
                }
            }
        }
        return htlcSuccessTx.copy(tx = htlcSuccessTx.tx.updateWitness(0, witness))
    }

    fun addSigs(htlcTimeoutTx: TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx, localSig: ByteVector64, remoteSig: ByteVector64): TransactionWithInputInfo.HtlcTx.HtlcTimeoutTx {
        val witness = when (htlcTimeoutTx.input) {
            is InputInfo.SegwitInput -> Scripts.witnessHtlcTimeout(localSig, remoteSig, htlcTimeoutTx.input.redeemScript)
            is InputInfo.TaprootInput -> {
                when (val redeemPath = htlcTimeoutTx.input.redeemPath) {
                    is InputInfo.RedeemPath.ScriptPath -> {
                        val branch = redeemPath.scriptTree as ScriptTree.Branch
                        val sigHash = (SigHash.SIGHASH_SINGLE or SigHash.SIGHASH_ANYONECANPAY).toByte()
                        Script.witnessScriptPathPay2tr(htlcTimeoutTx.input.internalKey, branch.left as ScriptTree.Leaf, ScriptWitness(listOf(remoteSig.concat(sigHash), localSig)), branch)
                    }

                    is InputInfo.RedeemPath.KeyPath -> error("unexpected key path when building HTLC timeout tx")
                }
            }
        }
        return htlcTimeoutTx.copy(tx = htlcTimeoutTx.tx.updateWitness(0, witness))
    }

    fun addSigs(claimHtlcSuccessTx: TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcSuccessTx, localSig: ByteVector64, paymentPreimage: ByteVector32): TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcSuccessTx {
        val witness = when (claimHtlcSuccessTx.input) {
            is InputInfo.SegwitInput -> Scripts.witnessClaimHtlcSuccessFromCommitTx(localSig, paymentPreimage, claimHtlcSuccessTx.input.redeemScript)
            is InputInfo.TaprootInput -> {
                when (val redeemPath = claimHtlcSuccessTx.input.redeemPath) {
                    is InputInfo.RedeemPath.ScriptPath -> {
                        val tree = redeemPath.scriptTree
                        Script.witnessScriptPathPay2tr(claimHtlcSuccessTx.input.internalKey, (tree as ScriptTree.Branch).right as ScriptTree.Leaf, ScriptWitness(listOf(localSig, paymentPreimage)), tree)
                    }

                    is InputInfo.RedeemPath.KeyPath -> error("unexpected key path when building claim HTLC success tx")
                }
            }
        }

        return claimHtlcSuccessTx.copy(tx = claimHtlcSuccessTx.tx.updateWitness(0, witness))
    }

    fun addSigs(claimHtlcTimeoutTx: TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcTimeoutTx, localSig: ByteVector64): TransactionWithInputInfo.ClaimHtlcTx.ClaimHtlcTimeoutTx {
        val witness = when (claimHtlcTimeoutTx.input) {
            is InputInfo.SegwitInput -> Scripts.witnessClaimHtlcTimeoutFromCommitTx(localSig, claimHtlcTimeoutTx.input.redeemScript)
            is InputInfo.TaprootInput -> {
                when (val redeemPath = claimHtlcTimeoutTx.input.redeemPath) {
                    is InputInfo.RedeemPath.ScriptPath -> {
                        val tree = redeemPath.scriptTree
                        Script.witnessScriptPathPay2tr(claimHtlcTimeoutTx.input.internalKey, (tree as ScriptTree.Branch).left as ScriptTree.Leaf, ScriptWitness(listOf(localSig)), tree)
                    }

                    is InputInfo.RedeemPath.KeyPath -> error("unexpected key path when building claim HTLC timeout tx")
                }
            }

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
        val witness = when (claimRemoteDelayed.input) {
            is InputInfo.SegwitInput -> Scripts.witnessToRemoteDelayedAfterDelay(localSig, claimRemoteDelayed.input.redeemScript)
            is InputInfo.TaprootInput -> {
                val leaf = (claimRemoteDelayed.input.redeemPath as InputInfo.RedeemPath.ScriptPath).scriptTree as ScriptTree.Leaf
                Script.witnessScriptPathPay2tr(claimRemoteDelayed.input.internalKey, leaf, ScriptWitness(listOf(localSig)), leaf)
            }
        }
        return claimRemoteDelayed.copy(tx = claimRemoteDelayed.tx.updateWitness(0, witness))
    }

    fun addSigs(claimLocalDelayed: TransactionWithInputInfo.ClaimLocalDelayedOutputTx, localSig: ByteVector64): TransactionWithInputInfo.ClaimLocalDelayedOutputTx {
        val witness = when (claimLocalDelayed.input) {
            is InputInfo.SegwitInput -> Scripts.witnessToLocalDelayedAfterDelay(localSig, claimLocalDelayed.input.redeemScript)
            is InputInfo.TaprootInput -> {
                when (val tree = (claimLocalDelayed.input.redeemPath as InputInfo.RedeemPath.ScriptPath).scriptTree) {
                    is ScriptTree.Branch -> {
                        // claim a to-local delayed output
                        Script.witnessScriptPathPay2tr(claimLocalDelayed.input.internalKey, tree.left as ScriptTree.Leaf, ScriptWitness(listOf(localSig)), tree)
                    }

                    is ScriptTree.Leaf -> {
                        // claim a delayed HTLC output
                        Script.witnessScriptPathPay2tr(claimLocalDelayed.input.internalKey, tree, ScriptWitness(listOf(localSig)), tree)
                    }
                }
            }
        }
        return claimLocalDelayed.copy(tx = claimLocalDelayed.tx.updateWitness(0, witness))
    }

    fun addSigs(claimHtlcDelayedPenalty: TransactionWithInputInfo.ClaimHtlcDelayedOutputPenaltyTx, revocationSig: ByteVector64): TransactionWithInputInfo.ClaimHtlcDelayedOutputPenaltyTx {
        val witness = when (claimHtlcDelayedPenalty.input) {
            is InputInfo.SegwitInput -> Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig, claimHtlcDelayedPenalty.input.redeemScript)
            is InputInfo.TaprootInput -> Script.witnessKeyPathPay2tr(revocationSig)
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
        return when (txinfo.input) {
            is InputInfo.SegwitInput -> {
                val data = txinfo.tx.hashForSigning(0, (txinfo.input as InputInfo.SegwitInput).redeemScript.toByteArray(), sigHash, txinfo.amountIn, SigVersion.SIGVERSION_WITNESS_V0)
                return Crypto.verifySignature(data, sig, pubKey)
            }

            is InputInfo.TaprootInput -> false
        }
    }
}
