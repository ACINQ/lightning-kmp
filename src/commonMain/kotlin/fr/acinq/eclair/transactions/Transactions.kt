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

package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.Eclair
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.channel.Commitments
import fr.acinq.eclair.channel.CommitmentsFormat
import fr.acinq.eclair.io.*
import fr.acinq.eclair.transactions.CommitmentOutput.InHtlc
import fr.acinq.eclair.transactions.CommitmentOutput.OutHtlc
import fr.acinq.eclair.transactions.Scripts.der
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.UpdateAddHtlc
import kotlinx.serialization.Serializable


/** Type alias for a collection of commitment output links */
typealias TransactionsCommitmentOutputs = List<Transactions.CommitmentOutputLink<CommitmentOutput>>

/**
 * Created by PM on 15/12/2016.
 */
@OptIn(ExperimentalStdlibApi::class, ExperimentalUnsignedTypes::class)
object Transactions {

    @Serializable
    data class InputInfo constructor(
        @Serializable(with = OutPointKSerializer::class) val outPoint: OutPoint,
        @Serializable(with = TxOutKSerializer::class) val txOut: TxOut,
        @Serializable(with = ByteVectorKSerializer::class) val redeemScript: ByteVector
    ) {
        constructor(outPoint: OutPoint, txOut: TxOut, redeemScript: List<ScriptElt>) : this(outPoint, txOut, ByteVector(Script.write(redeemScript)))
    }

    @Serializable
    sealed class TransactionWithInputInfo {
        abstract val input: Transactions.InputInfo
        abstract val tx: Transaction
        val fee: Satoshi get() = input.txOut.amount - tx.txOut.map { it.amount }.sum()
        val minRelayFee: Satoshi
            get() {
                val vsize = (tx.weight() + 3) / 4
                return (Eclair.MinimumRelayFeeRate * vsize / 1000).sat
            }

        @Serializable
        data class CommitTx(override val input: Transactions.InputInfo, @Serializable(with = TransactionKSerializer::class) override val tx: Transaction) : TransactionWithInputInfo()

        @Serializable
        data class HtlcSuccessTx(override val input: InputInfo, @Serializable(with = TransactionKSerializer::class) override val tx: Transaction, @Serializable(with = ByteVector32KSerializer::class) val paymentHash: ByteVector32) :
            TransactionWithInputInfo()

        @Serializable
        data class HtlcTimeoutTx(override val input: InputInfo, @Serializable(with = TransactionKSerializer::class) override val tx: Transaction) : TransactionWithInputInfo()
        @Serializable
        data class ClaimHtlcSuccessTx(override val input: InputInfo, @Serializable(with = TransactionKSerializer::class) override val tx: Transaction) : TransactionWithInputInfo()
        @Serializable
        data class ClaimHtlcTimeoutTx(override val input: InputInfo, @Serializable(with = TransactionKSerializer::class) override val tx: Transaction) : TransactionWithInputInfo()
        @Serializable
        data class ClaimP2WPKHOutputTx(override val input: InputInfo, @Serializable(with = TransactionKSerializer::class) override val tx: Transaction) : TransactionWithInputInfo()
        @Serializable
        data class ClaimDelayedOutputTx(override val input: InputInfo, @Serializable(with = TransactionKSerializer::class) override val tx: Transaction) : TransactionWithInputInfo()
        @Serializable
        data class ClaimDelayedOutputPenaltyTx(override val input: InputInfo, @Serializable(with = TransactionKSerializer::class) override val tx: Transaction) : TransactionWithInputInfo()
        @Serializable
        data class MainPenaltyTx(override val input: InputInfo, @Serializable(with = TransactionKSerializer::class) override val tx: Transaction) : TransactionWithInputInfo()
        @Serializable
        data class HtlcPenaltyTx(override val input: InputInfo, @Serializable(with = TransactionKSerializer::class) override val tx: Transaction) : TransactionWithInputInfo()
        @Serializable
        data class ClosingTx(override val input: InputInfo, @Serializable(with = TransactionKSerializer::class) override val tx: Transaction) : TransactionWithInputInfo()
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

    /**
     * these values are defined in the RFC
     */
//    val commitWeight = 724
//    val htlcOutputWeight = 172
//    val htlcTimeoutWeight = 663
//    val htlcSuccessWeight = 703

    /**
     * these values are specific to us and used to estimate fees
     */
    val claimP2WPKHOutputWeight = 438
    val claimHtlcDelayedWeight = 483
    val claimHtlcSuccessWeight = 571
    val claimHtlcTimeoutWeight = 545
    val mainPenaltyWeight = 484
    val htlcPenaltyWeight = 578 // based on spending an HTLC-Success output (would be 571 with HTLC-Timeout)

    fun weight2feeMsat(feeratePerKw: Long, weight: Int) = (feeratePerKw * weight).msat

    fun weight2fee(feeratePerKw: Long, weight: Int): Satoshi = weight2feeMsat(feeratePerKw, weight).truncateToSatoshi()

    /**
     * @param fee    tx fee
     * @param weight tx weight
     * @return the fee rate (in Satoshi/Kw) for this tx
     */
    fun fee2rate(fee: Satoshi, weight: Int): Long = (fee.sat * 1000L) / weight

    /** Offered HTLCs below this amount will be trimmed. */
    fun offeredHtlcTrimThreshold(commitmentsFormat: CommitmentsFormat, dustLimit: Satoshi, spec: CommitmentSpec): Satoshi = dustLimit + weight2fee(spec.feeratePerKw, commitmentsFormat.htlcTimeoutWeight)

    fun trimOfferedHtlcs(commitmentsFormat: CommitmentsFormat, dustLimit: Satoshi, spec: CommitmentSpec): List<OutgoingHtlc> {
        val threshold = offeredHtlcTrimThreshold(commitmentsFormat, dustLimit, spec)
        return spec.htlcs
            .filterIsInstance<OutgoingHtlc>()
            .filter { it.add.amountMsat >= threshold }
    }

    /** Received HTLCs below this amount will be trimmed. */
    fun receivedHtlcTrimThreshold(commitmentsFormat: CommitmentsFormat, dustLimit: Satoshi, spec: CommitmentSpec): Satoshi = dustLimit + weight2fee(spec.feeratePerKw, commitmentsFormat.htlcSuccessWeight)

    fun trimReceivedHtlcs(commitmentsFormat: CommitmentsFormat, dustLimit: Satoshi, spec: CommitmentSpec): List<IncomingHtlc> {
        val threshold = receivedHtlcTrimThreshold(commitmentsFormat, dustLimit, spec)
        return spec.htlcs
            .filterIsInstance<IncomingHtlc>()
            .filter { it.add.amountMsat >= threshold }
    }

    /** Fee for an un-trimmed HTLC. */
    fun htlcOutputFee(commitmentsFormat: CommitmentsFormat, feeratePerKw: Long): MilliSatoshi = weight2feeMsat(feeratePerKw, commitmentsFormat.htlcOutputWeight)

    /**
     * While fees are generally computed in Satoshis (since this is the smallest on-chain unit), it may be useful in some
     * cases to calculate it in MilliSatoshi to avoid rounding issues.
     * If you are adding multiple fees together for example, you should always add them in MilliSatoshi and then round
     * down to Satoshi.
     */
    fun commitTxFeeMsat(commitmentsFormat: CommitmentsFormat, dustLimit: Satoshi, spec: CommitmentSpec): MilliSatoshi {
        val trimmedOfferedHtlcs = trimOfferedHtlcs(commitmentsFormat, dustLimit, spec)
        val trimmedReceivedHtlcs = trimReceivedHtlcs(commitmentsFormat, dustLimit, spec)
        val weight = commitmentsFormat.commitWeight + commitmentsFormat.htlcOutputWeight * (trimmedOfferedHtlcs.size + trimmedReceivedHtlcs.size)
        return weight2feeMsat(spec.feeratePerKw, weight)
    }

    fun commitTxFee(commitmentsFormat: CommitmentsFormat, dustLimit: Satoshi, spec: CommitmentSpec): Satoshi {
        val base = commitTxFeeMsat(commitmentsFormat, dustLimit, spec).truncateToSatoshi()
        return when (commitmentsFormat) {
            is CommitmentsFormat.LegacyFormat -> base
            is CommitmentsFormat.AnchorOutputs -> base + (Commitments.ANCHOR_AMOUNT * 2)
        }
    }

    /**
     *
     * @param commitTxNumber         commit tx number
     * @param isFunder               true if local node is funder
     * @param localPaymentBasePoint  local payment base point
     * @param remotePaymentBasePoint remote payment base point
     * @return the obscured tx number as defined in BOLT #3 (a 48 bits integer)
     */
    fun obscuredCommitTxNumber(commitTxNumber: Long, isFunder: Boolean, localPaymentBasePoint: PublicKey, remotePaymentBasePoint: PublicKey): Long {
        // from BOLT 3: SHA256(payment-basepoint from open_channel || payment-basepoint from accept_channel)
        val h = if (isFunder)
            Crypto.sha256(localPaymentBasePoint.value + remotePaymentBasePoint.value)
        else
            Crypto.sha256(remotePaymentBasePoint.value + localPaymentBasePoint.value)

        val blind = Pack.int64LE(h.takeLast(6).reversed().toByteArray() + ByteArray(4) { 0 })
        return commitTxNumber xor blind
    }

    /**
     *
     * @param commitTx               commit tx
     * @param isFunder               true if local node is funder
     * @param localPaymentBasePoint  local payment base point
     * @param remotePaymentBasePoint remote payment base point
     * @return the actual commit tx number that was blinded and stored in locktime and sequence fields
     */
    fun getCommitTxNumber(commitTx: Transaction, isFunder: Boolean, localPaymentBasePoint: PublicKey, remotePaymentBasePoint: PublicKey): Long {
        val blind = obscuredCommitTxNumber(0, isFunder, localPaymentBasePoint, remotePaymentBasePoint)
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
    data class CommitmentOutputLink<T : CommitmentOutput>(val output: TxOut, val redeemScript: List<ScriptElt>, val commitmentOutput: T) : Comparable<CommitmentOutputLink<T>> {
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
        commitmentsFormat: CommitmentsFormat,
        localFundingPubkey: PublicKey,
        remoteFundingPubkey: PublicKey,
        localIsFunder: Boolean,
        localDustLimit: Satoshi,
        localRevocationPubkey: PublicKey,
        toLocalDelay: CltvExpiryDelta,
        localDelayedPaymentPubkey: PublicKey,
        remotePaymentPubkey: PublicKey,
        localHtlcPubkey: PublicKey,
        remoteHtlcPubkey: PublicKey,
        spec: CommitmentSpec
    ): TransactionsCommitmentOutputs {
        val commitFee = commitTxFee(commitmentsFormat, localDustLimit, spec)

        val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) =
            if (localIsFunder) {
                Pair(spec.toLocal.truncateToSatoshi() - commitFee, spec.toRemote.truncateToSatoshi())
            } else {
                Pair(spec.toLocal.truncateToSatoshi(), spec.toRemote.truncateToSatoshi() - commitFee)
            } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway
        val outputs = ArrayList<CommitmentOutputLink<CommitmentOutput>>()

        if (toLocalAmount >= localDustLimit) outputs.add(
            CommitmentOutputLink(
                TxOut(toLocalAmount, Script.pay2wsh(Scripts.toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey))),
                Scripts.toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey),
                CommitmentOutput.ToLocal
            )
        )

        if (toRemoteAmount >= localDustLimit) {
            when (commitmentsFormat) {
                is CommitmentsFormat.LegacyFormat -> outputs.add(
                    CommitmentOutputLink(
                        TxOut(toRemoteAmount, Script.pay2wpkh(remotePaymentPubkey)),
                        Script.pay2pkh(remotePaymentPubkey),
                        CommitmentOutput.ToRemote
                    )
                )
                is CommitmentsFormat.AnchorOutputs -> outputs.add(
                    CommitmentOutputLink(
                        TxOut(toRemoteAmount, Script.pay2wsh(Scripts.toRemoteDelayed(remotePaymentPubkey))),
                        Scripts.toRemoteDelayed(remotePaymentPubkey),
                        CommitmentOutput.ToRemote
                    )
                )
            }
        }

        if (commitmentsFormat == CommitmentsFormat.AnchorOutputs) {
            val untrimmedHtlcs = trimOfferedHtlcs(commitmentsFormat, localDustLimit, spec).isNotEmpty() || trimReceivedHtlcs(commitmentsFormat, localDustLimit, spec).isNotEmpty()
            if (untrimmedHtlcs || toLocalAmount >= localDustLimit)
                outputs.add(
                    CommitmentOutputLink(
                        TxOut(Commitments.ANCHOR_AMOUNT, Script.pay2wsh(Scripts.toAnchor(localFundingPubkey))),
                        Scripts.toAnchor(localFundingPubkey),
                        CommitmentOutput.ToLocalAnchor(localFundingPubkey)
                    )
                )
            if (untrimmedHtlcs || toRemoteAmount >= localDustLimit)
                outputs.add(
                    CommitmentOutputLink(
                        TxOut(Commitments.ANCHOR_AMOUNT, Script.pay2wsh(Scripts.toAnchor(remoteFundingPubkey))),
                        Scripts.toAnchor(remoteFundingPubkey),
                        CommitmentOutput.ToLocalAnchor(remoteFundingPubkey)
                    )
                )
        }

        trimOfferedHtlcs(commitmentsFormat, localDustLimit, spec).forEach { htlc ->
            val redeemScript = Scripts.htlcOffered(commitmentsFormat, localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, Crypto.ripemd160(htlc.add.paymentHash.toByteArray()))
            outputs.add(CommitmentOutputLink(TxOut(htlc.add.amountMsat.truncateToSatoshi(), Script.pay2wsh(redeemScript)), redeemScript, OutHtlc(htlc)))
        }

        trimReceivedHtlcs(commitmentsFormat, localDustLimit, spec).forEach { htlc ->
            val redeemScript = Scripts.htlcReceived(commitmentsFormat, localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, Crypto.ripemd160(htlc.add.paymentHash.toByteArray()), htlc.add.cltvExpiry)
            outputs.add(CommitmentOutputLink(TxOut(htlc.add.amountMsat.truncateToSatoshi(), Script.pay2wsh(redeemScript)), redeemScript, InHtlc(htlc)))
        }

        return outputs.apply { sort() }
    }

    fun makeCommitTx(
        commitmentsFormat: CommitmentsFormat,
        commitTxInput: InputInfo,
        commitTxNumber: Long,
        localPaymentBasePoint: PublicKey,
        remotePaymentBasePoint: PublicKey,
        localIsFunder: Boolean,
        outputs: TransactionsCommitmentOutputs
    ): TransactionWithInputInfo.CommitTx {
        println(commitmentsFormat)
        val txnumber = obscuredCommitTxNumber(commitTxNumber, localIsFunder, localPaymentBasePoint, remotePaymentBasePoint)
        val (sequence, locktime) = encodeTxNumber(txnumber)

        val tx = Transaction(
            version = 2,
            txIn = listOf(TxIn(commitTxInput.outPoint, ByteVector.empty, sequence = sequence)),
            txOut = outputs.map { it.output },
            lockTime = locktime
        )

        return TransactionWithInputInfo.CommitTx(commitTxInput, tx)
    }

    sealed class TxResult<T> {
        data class Skipped<T>(val why: TxGenerationSkipped) : TxResult<T>()
        data class Success<T>(val result: T) : TxResult<T>()
    }

    fun makeHtlcTimeoutTx(
        commitmentsFormat: CommitmentsFormat,
        commitTx: Transaction,
        output: CommitmentOutputLink<OutHtlc>,
        outputIndex: Int,
        localDustLimit: Satoshi,
        localRevocationPubkey: PublicKey,
        toLocalDelay: CltvExpiryDelta,
        localDelayedPaymentPubkey: PublicKey,
        feeratePerKw: Long
    ): TxResult<TransactionWithInputInfo.HtlcTimeoutTx> {
        val fee = weight2fee(feeratePerKw, commitmentsFormat.htlcTimeoutWeight)
        val redeemScript = output.redeemScript
        val htlc = output.commitmentOutput.outgoingHtlc.add
        val amount = htlc.amountMsat.truncateToSatoshi() - fee
        return if (amount < localDustLimit) {
            TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
        } else {
            val input = InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], ByteVector(Script.write(redeemScript)))
            val sequence = when (commitmentsFormat) {
                is CommitmentsFormat.LegacyFormat -> 0L
                is CommitmentsFormat.AnchorOutputs -> 1L
            }
            TxResult.Success(
                TransactionWithInputInfo.HtlcTimeoutTx(
                    input,
                    Transaction(
                        version = 2,
                        txIn = listOf(TxIn(input.outPoint, ByteVector.empty, sequence)),
                        txOut = listOf(TxOut(amount, Script.pay2wsh(Scripts.toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)))),
                        lockTime = htlc.cltvExpiry.toLong()
                    )
                )
            )
        }
    }

    fun makeHtlcSuccessTx(
        commitmentsFormat: CommitmentsFormat,
        commitTx: Transaction,
        output: CommitmentOutputLink<InHtlc>,
        outputIndex: Int,
        localDustLimit: Satoshi,
        localRevocationPubkey: PublicKey,
        toLocalDelay: CltvExpiryDelta,
        localDelayedPaymentPubkey: PublicKey,
        feeratePerKw: Long
    ): TxResult<TransactionWithInputInfo.HtlcSuccessTx> {
        val fee = weight2fee(feeratePerKw, commitmentsFormat.htlcSuccessWeight)
        val redeemScript = output.redeemScript
        val htlc = output.commitmentOutput.incomingHtlc.add
        val amount = htlc.amountMsat.truncateToSatoshi() - fee
        return if (amount < localDustLimit) {
            TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
        } else {
            val input = InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], ByteVector(Script.write(redeemScript)))
            val sequence = when (commitmentsFormat) {
                is CommitmentsFormat.LegacyFormat -> 0L
                is CommitmentsFormat.AnchorOutputs -> 1L
            }
            TxResult.Success(
                TransactionWithInputInfo.HtlcSuccessTx(
                    input,
                    Transaction(
                        version = 2,
                        txIn = listOf(TxIn(input.outPoint, ByteVector.empty, sequence)),
                        txOut = listOf(TxOut(amount, Script.pay2wsh(Scripts.toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)))),
                        lockTime = 0
                    ),
                    htlc.paymentHash
                )
            )
        }
    }

    fun makeHtlcTxs(
        commitmentsFormat: CommitmentsFormat,
        commitTx: Transaction,
        localDustLimit: Satoshi,
        localRevocationPubkey: PublicKey,
        toLocalDelay: CltvExpiryDelta,
        localDelayedPaymentPubkey: PublicKey,
        feeratePerKw: Long,
        outputs: TransactionsCommitmentOutputs
    ): Pair<List<TransactionWithInputInfo.HtlcTimeoutTx>, List<TransactionWithInputInfo.HtlcSuccessTx>> {
        val htlcTimeoutTxs = outputs
            .mapIndexedNotNull map@{ outputIndex, link ->
                val outHtlc = link.commitmentOutput as? OutHtlc ?: return@map null
                val co = CommitmentOutputLink(link.output, link.redeemScript, outHtlc)
                makeHtlcTimeoutTx(commitmentsFormat, commitTx, co, outputIndex, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, feeratePerKw)
            }
            .mapNotNull { (it as? TxResult.Success)?.result }

        val htlcSuccessTxs = outputs
            .mapIndexedNotNull map@{ outputIndex, link ->
                val inHtlc = link.commitmentOutput as? InHtlc ?: return@map null
                val co = CommitmentOutputLink(link.output, link.redeemScript, inHtlc)
                makeHtlcSuccessTx(commitmentsFormat, commitTx, co, outputIndex, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, feeratePerKw)
            }
            .mapNotNull { (it as? TxResult.Success)?.result }

        return Pair(htlcTimeoutTxs, htlcSuccessTxs)
    }

    fun makeClaimHtlcSuccessTx(
        commitmentsFormat: CommitmentsFormat,
        commitTx: Transaction,
        outputs: TransactionsCommitmentOutputs,
        localDustLimit: Satoshi,
        localHtlcPubkey: PublicKey,
        remoteHtlcPubkey: PublicKey,
        remoteRevocationPubkey: PublicKey,
        localFinalScriptPubKey: ByteArray,
        htlc: UpdateAddHtlc,
        feeratePerKw: Long
    ): TxResult<TransactionWithInputInfo.ClaimHtlcSuccessTx> {
        val redeemScript = Scripts.htlcOffered(commitmentsFormat, remoteHtlcPubkey, localHtlcPubkey, remoteRevocationPubkey, Crypto.ripemd160(htlc.paymentHash))
        return outputs.withIndex()
            .firstOrNull { (it.value.commitmentOutput as? OutHtlc)?.outgoingHtlc?.add?.id == htlc.id }
            ?.let { (outputIndex, _) ->
                val input = InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], ByteVector(Script.write(redeemScript)))
                val tx = Transaction(
                    version = 2,
                    txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 0x00000000L)),
                    txOut = listOf(TxOut(0.sat, localFinalScriptPubKey)),
                    lockTime = htlc.cltvExpiry.toLong()
                )
                val weight = addSigs(TransactionWithInputInfo.ClaimHtlcTimeoutTx(input, tx), PlaceHolderSig).tx.weight()
                val fee = weight2fee(feeratePerKw, weight)
                val amount = input.txOut.amount - fee
                if (amount < localDustLimit) {
                    TxResult.Skipped<TransactionWithInputInfo.ClaimHtlcSuccessTx>(TxGenerationSkipped.AmountBelowDustLimit)
                } else {
                    val tx1 = tx.copy(txOut = listOf(tx.txOut.first().copy(amount = amount)))
                    TxResult.Success(TransactionWithInputInfo.ClaimHtlcSuccessTx(input, tx1))
                }
            }
            ?: TxResult.Skipped(TxGenerationSkipped.OutputNotFound)
    }

    fun makeClaimHtlcTimeoutTx(
        commitmentsFormat: CommitmentsFormat,
        commitTx: Transaction,
        outputs: TransactionsCommitmentOutputs,
        localDustLimit: Satoshi,
        localHtlcPubkey: PublicKey,
        remoteHtlcPubkey: PublicKey,
        remoteRevocationPubkey: PublicKey,
        localFinalScriptPubKey: ByteArray,
        htlc: UpdateAddHtlc,
        feeratePerKw: Long
    ): TxResult<TransactionWithInputInfo.ClaimHtlcTimeoutTx> {
        val redeemScript = Scripts.htlcReceived(commitmentsFormat, remoteHtlcPubkey, localHtlcPubkey, remoteRevocationPubkey, Crypto.ripemd160(htlc.paymentHash), htlc.cltvExpiry)
        return outputs.withIndex()
            .firstOrNull { (it.value.commitmentOutput as? InHtlc)?.incomingHtlc?.add?.id == htlc.id }
            ?.let { (outputIndex, _) ->
                val input = InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], ByteVector(Script.write(redeemScript)))
                // unsigned tx
                val tx = Transaction(
                    version = 2,
                    txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 0x00000000L)),
                    txOut = listOf(TxOut(0.sat, localFinalScriptPubKey)),
                    lockTime = htlc.cltvExpiry.toLong()
                )
                val weight = addSigs(TransactionWithInputInfo.ClaimHtlcTimeoutTx(input, tx), PlaceHolderSig).tx.weight()
                val fee = weight2fee(feeratePerKw, weight)
                val amount = input.txOut.amount - fee
                if (amount < localDustLimit) {
                    TxResult.Skipped<TransactionWithInputInfo.ClaimHtlcTimeoutTx>(TxGenerationSkipped.AmountBelowDustLimit)
                } else {
                    val tx1 = tx.copy(txOut = listOf(tx.txOut.first().copy(amount = amount)))
                    TxResult.Success(TransactionWithInputInfo.ClaimHtlcTimeoutTx(input, tx1))
                }
            }
            ?: TxResult.Skipped(TxGenerationSkipped.OutputNotFound)
    }

    fun makeClaimP2WPKHOutputTx(
        delayedOutputTx: Transaction,
        localDustLimit: Satoshi,
        localPaymentPubkey: PublicKey,
        localFinalScriptPubKey: ByteArray,
        feeratePerKw: Long
    ): TxResult<TransactionWithInputInfo.ClaimP2WPKHOutputTx> {
        val redeemScript = Script.pay2pkh(localPaymentPubkey)
        val pubkeyScript = Script.write(Script.pay2wpkh(localPaymentPubkey))
        return when (val pubkeyScriptIndex = findPubKeyScriptIndex(delayedOutputTx, pubkeyScript, amount_opt = null)) {
            is TxResult.Skipped -> TxResult.Skipped(pubkeyScriptIndex.why)
            is TxResult.Success -> {
                val outputIndex = pubkeyScriptIndex.result
                val input = InputInfo(
                    OutPoint(delayedOutputTx, outputIndex.toLong()),
                    delayedOutputTx.txOut[outputIndex],
                    ByteVector(Script.write(redeemScript))
                )
                // unsigned tx
                val tx = Transaction(
                    version = 2,
                    txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 0x00000000L)),
                    txOut = listOf(TxOut(0.sat, localFinalScriptPubKey)),
                    lockTime = 0
                )
                // compute weight with a dummy 73 bytes signature (the largest you can get) and a dummy 33 bytes pubkey
                val weight = addSigs(TransactionWithInputInfo.ClaimP2WPKHOutputTx(input, tx), PlaceHolderPubKey, PlaceHolderSig).tx.weight()
                val fee = weight2fee(feeratePerKw, weight)
                val amount = input.txOut.amount - fee
                if (amount < localDustLimit) {
                    TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
                } else {
                    val tx1 = tx.copy(txOut = listOf(tx.txOut.first().copy(amount = amount)))
                    TxResult.Success(TransactionWithInputInfo.ClaimP2WPKHOutputTx(input, tx1))
                }
            }
        }
    }

    fun makeClaimDelayedOutputTx(
        delayedOutputTx: Transaction,
        localDustLimit: Satoshi,
        localRevocationPubkey: PublicKey,
        toLocalDelay: CltvExpiryDelta,
        localDelayedPaymentPubkey: PublicKey,
        localFinalScriptPubKey: ByteArray,
        feeratePerKw: Long
    ): TxResult<TransactionWithInputInfo.ClaimDelayedOutputTx> {
        val redeemScript = Scripts.toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
        val pubkeyScript = Script.write(Script.pay2wsh(redeemScript))
        return when (val pubkeyScriptIndex = findPubKeyScriptIndex(delayedOutputTx, pubkeyScript, amount_opt = null)) {
            is TxResult.Skipped -> TxResult.Skipped(pubkeyScriptIndex.why)
            is TxResult.Success -> {
                val outputIndex = pubkeyScriptIndex.result
                val input = InputInfo(OutPoint(delayedOutputTx, outputIndex.toLong()), delayedOutputTx.txOut[outputIndex], ByteVector(Script.write(redeemScript)))
                // unsigned transaction
                val tx = Transaction(
                    version = 2,
                    txIn = listOf(TxIn(input.outPoint, ByteVector.empty, toLocalDelay.toLong())),
                    txOut = listOf(TxOut(0.sat, localFinalScriptPubKey)),
                    lockTime = 0
                )
                // compute weight with a dummy 73 bytes signature (the largest you can get)
                val weight = addSigs(TransactionWithInputInfo.ClaimDelayedOutputTx(input, tx), PlaceHolderSig).tx.weight()
                val fee = weight2fee(feeratePerKw, weight)
                val amount = input.txOut.amount - fee
                if (amount < localDustLimit) {
                    TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
                } else {
                    val tx1 = tx.copy(txOut = listOf(tx.txOut.first().copy(amount = amount)))
                    TxResult.Success(TransactionWithInputInfo.ClaimDelayedOutputTx(input, tx1))
                }
            }
        }
    }

    fun makeClaimDelayedOutputPenaltyTx(
        delayedOutputTx: Transaction,
        localDustLimit: Satoshi,
        localRevocationPubkey: PublicKey,
        toLocalDelay: CltvExpiryDelta,
        localDelayedPaymentPubkey: PublicKey,
        localFinalScriptPubKey: ByteArray,
        feeratePerKw: Long
    ): TxResult<TransactionWithInputInfo.ClaimDelayedOutputPenaltyTx> {
        val redeemScript = Scripts.toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
        val pubkeyScript = Script.write(Script.pay2wsh(redeemScript))
        return when (val pubkeyScriptIndex = findPubKeyScriptIndex(delayedOutputTx, pubkeyScript, amount_opt = null)) {
            is TxResult.Skipped -> TxResult.Skipped(pubkeyScriptIndex.why)
            is TxResult.Success -> {
                val outputIndex = pubkeyScriptIndex.result
                val input = InputInfo(OutPoint(delayedOutputTx, outputIndex.toLong()), delayedOutputTx.txOut[outputIndex], ByteVector(Script.write(redeemScript)))
                // unsigned transaction
                val tx = Transaction(
                    version = 2,
                    txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 0xffffffffL)),
                    txOut = listOf(TxOut(0.sat, localFinalScriptPubKey)),
                    lockTime = 0
                )
                // compute weight with a dummy 73 bytes signature (the largest you can get)
                val weight = addSigs(TransactionWithInputInfo.ClaimDelayedOutputPenaltyTx(input, tx), PlaceHolderSig).tx.weight()
                val fee = weight2fee(feeratePerKw, weight)
                val amount = input.txOut.amount - fee
                if (amount < localDustLimit) {
                    TxResult.Skipped(TxGenerationSkipped.AmountBelowDustLimit)
                } else {
                    val tx1 = tx.copy(txOut = listOf(tx.txOut.first().copy(amount = amount)))
                    TxResult.Success(TransactionWithInputInfo.ClaimDelayedOutputPenaltyTx(input, tx1))
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
        feeratePerKw: Long
    ): TxResult<TransactionWithInputInfo.MainPenaltyTx> {
        val redeemScript = Scripts.toLocalDelayed(remoteRevocationPubkey, toRemoteDelay, remoteDelayedPaymentPubkey)
        val pubkeyScript = Script.write(Script.pay2wsh(redeemScript))
        return when (val pubkeyScriptIndex = findPubKeyScriptIndex(commitTx, pubkeyScript, amount_opt = null)) {
            is TxResult.Skipped -> TxResult.Skipped(pubkeyScriptIndex.why)
            is TxResult.Success -> {
                val outputIndex = pubkeyScriptIndex.result
                val input = InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex], ByteVector(Script.write(redeemScript)))
                // unsigned transaction
                val tx = Transaction(
                    version = 2,
                    txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 0xffffffffL)),
                    txOut = listOf(TxOut(0.sat, localFinalScriptPubKey)),
                    lockTime = 0
                )
                // compute weight with a dummy 73 bytes signature (the largest you can get)
                val weight = addSigs(TransactionWithInputInfo.MainPenaltyTx(input, tx), PlaceHolderSig).tx.weight()
                val fee = weight2fee(feeratePerKw, weight)
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
        feeratePerKw: Long
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
        localIsFunder: Boolean,
        dustLimit: Satoshi,
        closingFee: Satoshi,
        spec: CommitmentSpec
    ): TransactionWithInputInfo.ClosingTx {
        require(spec.htlcs.isEmpty()) { "there shouldn't be any pending htlcs" }

        val (toLocalAmount, toRemoteAmount) = if (localIsFunder) {
            Pair(spec.toLocal.truncateToSatoshi() - closingFee, spec.toRemote.truncateToSatoshi())
        } else {
            Pair(spec.toLocal.truncateToSatoshi(), spec.toRemote.truncateToSatoshi() - closingFee)
        } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway

        val toLocalOutput_opt = toLocalAmount.takeIf { it >= dustLimit }?.let { TxOut(it, localScriptPubKey) }
        val toRemoteOutput_opt = toRemoteAmount.takeIf { it >= dustLimit }?.let { TxOut(it, remoteScriptPubKey) }

        val tx = Transaction(
            version = 2,
            txIn = listOf(TxIn(commitTxInput.outPoint, ByteVector.empty, sequence = 0xffffffffL)),
            txOut = listOfNotNull(toLocalOutput_opt, toRemoteOutput_opt),
            lockTime = 0
        )
        return TransactionWithInputInfo.ClosingTx(commitTxInput, LexicographicalOrdering.sort(tx))
    }

    fun findPubKeyScriptIndex(tx: Transaction, pubkeyScript: ByteArray, amount_opt: Satoshi?): TxResult<Int> {
        val outputIndex = tx.txOut
            .withIndex()
            .indexOfFirst { (_, txOut) -> (amount_opt == null || amount_opt == txOut.amount) && txOut.publicKeyScript.contentEquals(pubkeyScript) }
        return if (outputIndex >= 0) {
            TxResult.Success(outputIndex)
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
        .also { check(Scripts.der(it).size() == 72) { "Should be 72 bytes but is ${Scripts.der(it).size()} bytes" } }

    fun sign(tx: Transaction, inputIndex: Int, redeemScript: ByteArray, amount: Satoshi, key: PrivateKey, sigHash: Int = SigHash.SIGHASH_ALL): ByteVector64 {
        val sigDER = Transaction.signInput(tx, inputIndex, redeemScript, sigHash, amount, SigVersion.SIGVERSION_WITNESS_V0, key)
        return Crypto.der2compact(sigDER)
    }

    fun sign(txinfo: TransactionWithInputInfo, key: PrivateKey, sigHash: Int = SigHash.SIGHASH_ALL): ByteVector64 {
        require(txinfo.tx.txIn.size == 1) { "only one input allowed" }
        return sign(txinfo.tx, 0, txinfo.input.redeemScript.toByteArray(), txinfo.input.txOut.amount, key, sigHash)
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
        val witness = Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig, mainPenaltyTx.input.redeemScript)
        return mainPenaltyTx.copy(tx = mainPenaltyTx.tx.updateWitness(0, witness))
    }

    fun addSigs(htlcPenaltyTx: TransactionWithInputInfo.HtlcPenaltyTx, revocationSig: ByteVector64, revocationPubkey: PublicKey): TransactionWithInputInfo.HtlcPenaltyTx {
        val witness = Scripts.witnessHtlcWithRevocationSig(revocationSig, revocationPubkey, htlcPenaltyTx.input.redeemScript)
        return htlcPenaltyTx.copy(tx = htlcPenaltyTx.tx.updateWitness(0, witness))
    }

    fun addSigs(htlcSuccessTx: TransactionWithInputInfo.HtlcSuccessTx, localSig: ByteVector64, remoteSig: ByteVector64, paymentPreimage: ByteVector32, sigHash: Int): TransactionWithInputInfo.HtlcSuccessTx {
        val witness = Scripts.witnessHtlcSuccess(localSig, remoteSig, paymentPreimage, htlcSuccessTx.input.redeemScript, sigHash)
        return htlcSuccessTx.copy(tx = htlcSuccessTx.tx.updateWitness(0, witness))
    }

    fun addSigs(htlcTimeoutTx: TransactionWithInputInfo.HtlcTimeoutTx, localSig: ByteVector64, remoteSig: ByteVector64, sigHash: Int): TransactionWithInputInfo.HtlcTimeoutTx {
        val witness = Scripts.witnessHtlcTimeout(localSig, remoteSig, htlcTimeoutTx.input.redeemScript, sigHash)
        return htlcTimeoutTx.copy(tx = htlcTimeoutTx.tx.updateWitness(0, witness))
    }

    fun addSigs(claimHtlcSuccessTx: TransactionWithInputInfo.ClaimHtlcSuccessTx, localSig: ByteVector64, paymentPreimage: ByteVector32): TransactionWithInputInfo.ClaimHtlcSuccessTx {
        val witness = Scripts.witnessClaimHtlcSuccessFromCommitTx(localSig, paymentPreimage, claimHtlcSuccessTx.input.redeemScript)
        return claimHtlcSuccessTx.copy(tx = claimHtlcSuccessTx.tx.updateWitness(0, witness))
    }

    fun addSigs(claimHtlcTimeoutTx: TransactionWithInputInfo.ClaimHtlcTimeoutTx, localSig: ByteVector64): TransactionWithInputInfo.ClaimHtlcTimeoutTx {
        val witness = Scripts.witnessClaimHtlcTimeoutFromCommitTx(localSig, claimHtlcTimeoutTx.input.redeemScript)
        return claimHtlcTimeoutTx.copy(tx = claimHtlcTimeoutTx.tx.updateWitness(0, witness))
    }

    fun addSigs(claimP2WPKHOutputTx: TransactionWithInputInfo.ClaimP2WPKHOutputTx, localPaymentPubkey: PublicKey, localSig: ByteVector64): TransactionWithInputInfo.ClaimP2WPKHOutputTx {
        val witness = ScriptWitness(listOf(Scripts.der(localSig), localPaymentPubkey.value))
        return claimP2WPKHOutputTx.copy(tx = claimP2WPKHOutputTx.tx.updateWitness(0, witness))
    }

    fun addSigs(claimHtlcDelayed: TransactionWithInputInfo.ClaimDelayedOutputTx, localSig: ByteVector64): TransactionWithInputInfo.ClaimDelayedOutputTx {
        val witness = Scripts.witnessToLocalDelayedAfterDelay(localSig, claimHtlcDelayed.input.redeemScript)
        return claimHtlcDelayed.copy(tx = claimHtlcDelayed.tx.updateWitness(0, witness))
    }

    fun addSigs(claimHtlcDelayedPenalty: TransactionWithInputInfo.ClaimDelayedOutputPenaltyTx, revocationSig: ByteVector64): TransactionWithInputInfo.ClaimDelayedOutputPenaltyTx {
        val witness = Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig, claimHtlcDelayedPenalty.input.redeemScript)
        return claimHtlcDelayedPenalty.copy(tx = claimHtlcDelayedPenalty.tx.updateWitness(0, witness))
    }

    fun addSigs(closingTx: TransactionWithInputInfo.ClosingTx, localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: ByteVector64, remoteSig: ByteVector64): TransactionWithInputInfo.ClosingTx {
        val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
        return closingTx.copy(tx = closingTx.tx.updateWitness(0, witness))
    }

    fun checkSpendable(txinfo: TransactionWithInputInfo): Try<Unit> = runTrying {
        Transaction.correctlySpends(txinfo.tx, mapOf(txinfo.tx.txIn.first().outPoint to txinfo.input.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    fun checkSig(txinfo: TransactionWithInputInfo, sig: ByteVector64, pubKey: PublicKey): Boolean {
        val data = Transaction.hashForSigning(txinfo.tx, 0, txinfo.input.redeemScript.toByteArray(), SigHash.SIGHASH_ALL, txinfo.input.txOut.amount, SigVersion.SIGVERSION_WITNESS_V0)
        return Crypto.verifySignature(data, sig, pubKey)
    }

}
