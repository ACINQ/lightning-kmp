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
import fr.acinq.bitcoin.utils.Either
import fr.acinq.bitcoin.utils.flatMap
import fr.acinq.bitcoin.utils.runTrying
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.ChannelSpendSignature
import fr.acinq.lightning.crypto.CommitmentPublicKeys
import fr.acinq.lightning.crypto.LocalCommitmentKeys
import fr.acinq.lightning.crypto.RemoteCommitmentKeys
import fr.acinq.lightning.transactions.CommitmentOutput.InHtlc
import fr.acinq.lightning.transactions.CommitmentOutput.OutHtlc
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.UpdateAddHtlc

/**
 * Created by PM on 15/12/2016.
 */
object Transactions {

    const val MAX_STANDARD_TX_WEIGHT = 400_000
    // legacy swap-in. witness is 2 signatures (73 bytes) + redeem script (77 bytes)
    const val swapInputWeightLegacy = 392
    // musig2 swap-in. witness is a single Schnorr signature (64 bytes)
    const val swapInputWeight = 233

    sealed class CommitmentFormat {
        /** Weight of a fully signed channel output, when spent by a [ChannelSpendTransaction]. */
        abstract val fundingInputWeight: Int
        /** Weight of a fully signed [CommitTx] transaction without any HTLCs. */
        abstract val commitWeight: Int
        /** Weight of an additional HTLC output added to a [CommitTx]. */
        abstract val htlcOutputWeight: Int
        /** Weight of a fully signed [HtlcTimeoutTx] transaction without additional wallet inputs. */
        abstract val htlcTimeoutWeight: Int
        /** Weight of a fully signed [HtlcSuccessTx] transaction without additional wallet inputs. */
        abstract val htlcSuccessWeight: Int
        /** Weight of a fully signed [ClaimHtlcSuccessTx] transaction. */
        abstract val claimHtlcSuccessWeight: Int
        /** Weight of a fully signed [ClaimHtlcTimeoutTx] transaction. */
        abstract val claimHtlcTimeoutWeight: Int
        /** Weight of a fully signed [ClaimLocalDelayedOutputTx] transaction. */
        abstract val toLocalDelayedWeight: Int
        /** Weight of a fully signed [ClaimRemoteDelayedOutputTx] transaction. */
        abstract val toRemoteWeight: Int
        /** Weight of a fully signed [HtlcDelayedTx] 3rd-stage transaction (spending the output of an [HtlcTx]). */
        abstract val htlcDelayedWeight: Int
        /** Weight of a fully signed [MainPenaltyTx] transaction. */
        abstract val mainPenaltyWeight: Int
        /** Weight of a fully signed [HtlcPenaltyTx] transaction for an offered HTLC. */
        abstract val htlcOfferedPenaltyWeight: Int
        /** Weight of a fully signed [HtlcPenaltyTx] transaction for a received HTLC. */
        abstract val htlcReceivedPenaltyWeight: Int
        /** Weight of a fully signed [ClaimHtlcDelayedOutputPenaltyTx] transaction. */
        abstract val claimHtlcPenaltyWeight: Int
        /** Amount of the anchor outputs of the [CommitTx]. */
        abstract val anchorAmount: Satoshi

        object AnchorOutputs : CommitmentFormat() {
            override val fundingInputWeight: Int = 384
            override val commitWeight: Int = 1124
            override val htlcOutputWeight: Int = 172
            override val htlcTimeoutWeight: Int = 666
            override val htlcSuccessWeight: Int = 706
            override val claimHtlcSuccessWeight: Int = 574
            override val claimHtlcTimeoutWeight: Int = 547
            override val toLocalDelayedWeight: Int = 483
            override val toRemoteWeight: Int = 442
            override val htlcDelayedWeight: Int = 483
            override val mainPenaltyWeight: Int = 483
            override val htlcOfferedPenaltyWeight: Int = 575
            override val htlcReceivedPenaltyWeight: Int = 580
            override val claimHtlcPenaltyWeight: Int = 483
            override val anchorAmount: Satoshi = 330.sat
        }
    }

    data class InputInfo(val outPoint: OutPoint, val txOut: TxOut)

    /** This trait contains redeem information necessary to spend different types of segwit inputs. */
    sealed class RedeemInfo {
        abstract val pubkeyScript: ByteVector

        /** @param redeemScript the actual script must be known to redeem pay2wsh inputs. */
        data class P2wsh(val redeemScript: ByteVector) : RedeemInfo() {
            constructor(redeemScript: List<ScriptElt>) : this(Script.write(redeemScript).byteVector())

            override val pubkeyScript: ByteVector = Script.write(Script.pay2wsh(redeemScript)).byteVector()
        }
    }

    sealed class TransactionWithInputInfo {
        abstract val input: InputInfo
        abstract val tx: Transaction
        val amountIn: Satoshi get() = input.txOut.amount
        val fee: Satoshi get() = input.txOut.amount - tx.txOut.map { it.amount }.sum()
        val inputIndex: Int get() = tx.txIn.indexOfFirst { it.outPoint == input.outPoint }

        fun sign(key: PrivateKey, sigHash: Int, redeemInfo: RedeemInfo, extraUtxos: Map<OutPoint, TxOut>): ByteVector64 {
            // Note that we only need to provide details about all transaction inputs when using taproot, but we want to
            // test that we're always correctly providing all inputs in all code paths to benefit from our existing test coverage.
            val inputsMap = extraUtxos + (input.outPoint to input.txOut)
            tx.txIn.forEach { require(inputsMap.contains(it.outPoint)) { "cannot sign txId=${tx.txid}: missing input details for ${it.outPoint}" } }
            return when (redeemInfo) {
                is RedeemInfo.P2wsh -> {
                    val sigDER = tx.signInput(inputIndex, redeemInfo.redeemScript, sigHash, amountIn, SigVersion.SIGVERSION_WITNESS_V0, key)
                    Crypto.der2compact(sigDER)
                }
            }
        }

        fun checkSig(sig: ByteVector64, publicKey: PublicKey, sigHash: Int, redeemInfo: RedeemInfo): Boolean {
            return if (inputIndex >= 0) {
                when (redeemInfo) {
                    is RedeemInfo.P2wsh -> {
                        val redeemScript = redeemInfo.redeemScript.toByteArray()
                        val data = tx.hashForSigning(inputIndex, redeemScript, sigHash, amountIn, SigVersion.SIGVERSION_WITNESS_V0)
                        Crypto.verifySignature(data, sig, publicKey)
                    }
                }
            } else {
                false
            }
        }

        /** Check that this transaction is correctly signed. */
        fun validate(extraUtxos: Map<OutPoint, TxOut>): Boolean {
            val inputsMap = extraUtxos + (input.outPoint to input.txOut)
            val allInputsProvided = tx.txIn.all { inputsMap.contains(it.outPoint) }
            val witnessesOk = runTrying { Transaction.correctlySpends(tx, inputsMap, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }.isSuccess
            return allInputsProvided && witnessesOk
        }
    }

    /**
     * Transactions spending the channel funding output: [CommitTx], [SpliceTx] and [ClosingTx].
     * Those transactions always require two signatures, one from each channel participant.
     */
    sealed class ChannelSpendTransaction : TransactionWithInputInfo() {
        /** Sign the channel's 2-of-2 funding output when using [CommitmentFormat.AnchorOutputs]. */
        fun sign(localFundingKey: PrivateKey, remoteFundingPubkey: PublicKey, extraUtxos: Map<OutPoint, TxOut>): ChannelSpendSignature.IndividualSignature {
            val redeemScript = Script.write(Scripts.multiSig2of2(localFundingKey.publicKey(), remoteFundingPubkey)).byteVector()
            val sig = sign(localFundingKey, SigHash.SIGHASH_ALL, RedeemInfo.P2wsh(redeemScript), extraUtxos)
            return ChannelSpendSignature.IndividualSignature(sig)
        }

        /** Aggregate local and remote channel spending signatures when using [CommitmentFormat.AnchorOutputs]. */
        fun aggregateSigs(localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: ChannelSpendSignature.IndividualSignature, remoteSig: ChannelSpendSignature.IndividualSignature): Transaction {
            val witness = Scripts.witness2of2(localSig.sig, remoteSig.sig, localFundingPubkey, remoteFundingPubkey)
            return tx.updateWitness(inputIndex, witness)
        }

        /** Verify a signature received from the remote channel participant. */
        fun checkRemoteSig(localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, remoteSig: ChannelSpendSignature.IndividualSignature): Boolean {
            val redeemScript = Script.write(Scripts.multiSig2of2(localFundingPubkey, remoteFundingPubkey)).byteVector()
            return checkSig(remoteSig.sig, remoteFundingPubkey, SigHash.SIGHASH_ALL, RedeemInfo.P2wsh(redeemScript))
        }
    }

    /** This transaction collaboratively spends the channel funding output to change its capacity. */
    data class SpliceTx(override val input: InputInfo, override val tx: Transaction) : ChannelSpendTransaction()

    /** This transaction unilaterally spends the channel funding output (force-close). */
    data class CommitTx(override val input: InputInfo, override val tx: Transaction) : ChannelSpendTransaction() {
        fun sign(localFundingKey: PrivateKey, remoteFundingPubkey: PublicKey): ChannelSpendSignature.IndividualSignature = sign(localFundingKey, remoteFundingPubkey, mapOf())
    }

    /** This transaction collaboratively spends the channel funding output (mutual-close). */
    data class ClosingTx(override val input: InputInfo, override val tx: Transaction, val toLocalOutputIndex: Int?) : ChannelSpendTransaction() {
        val toLocalOutput: TxOut? get() = toLocalOutputIndex?.let { tx.txOut[it] }

        fun sign(localFundingKey: PrivateKey, remoteFundingPubkey: PublicKey): ChannelSpendSignature.IndividualSignature = sign(localFundingKey, remoteFundingPubkey, mapOf())
    }

    /** Transactions spending a [CommitTx] or one of its descendants. */
    sealed class ForceCloseTransaction : TransactionWithInputInfo() {
        abstract val commitmentFormat: CommitmentFormat
        abstract val expectedWeight: Int

        /** Sighash flags to use when signing the transaction. */
        val sigHash: Int get() = when (commitmentFormat) {
            CommitmentFormat.AnchorOutputs -> SigHash.SIGHASH_ALL
        }

        abstract fun sign(): ForceCloseTransaction
    }

    /**
     * Transactions spending a local [CommitTx] or one of its descendants:
     *    - [ClaimLocalDelayedOutputTx] spends the to-local output of [CommitTx] after a delay
     *    - [HtlcSuccessTx] spends received htlc outputs of [CommitTx] for which we have the preimage
     *      - [HtlcDelayedTx] spends [HtlcSuccessTx] after a delay
     *    - [[tlcTimeoutTx] spends sent htlc outputs of [CommitTx] after a timeout
     *      - [HtlcDelayedTx] spends [HtlcTimeoutTx] after a delay
     */
    sealed class LocalCommitForceCloseTransaction : ForceCloseTransaction() {
        abstract val commitKeys: LocalCommitmentKeys
    }

    /**
     * Transactions spending a remote [CommitTx] or one of its descendants.
     *
     * When a current remote [CommitTx] is published:
     *    - [ClaimRemoteDelayedOutputTx] spends the to-local output of [CommitTx]
     *    - [ClaimHtlcSuccessTx] spends received htlc outputs of [CommitTx] for which we have the preimage
     *    - [ClaimHtlcTimeoutTx] spends sent htlc outputs of [CommitTx] after a timeout
     *
     * When a revoked remote [CommitTx] is published:
     *    - [ClaimRemoteDelayedOutputTx] spends the to-local output of [CommitTx]
     *    - [MainPenaltyTx] spends the remote main output using the revocation secret
     *    - [HtlcPenaltyTx] spends all htlc outputs using the revocation secret (and competes with [HtlcSuccessTx] and [HtlcTimeoutTx] published by the remote node)
     *    - [ClaimHtlcDelayedOutputPenaltyTx] spends [HtlcSuccessTx] transactions published by the remote node using the revocation secret
     *    - [ClaimHtlcDelayedOutputPenaltyTx] spends [HtlcTimeoutTx] transactions published by the remote node using the revocation secret
     */
    sealed class RemoteCommitForceCloseTransaction : ForceCloseTransaction() {
        abstract val commitKeys: RemoteCommitmentKeys
    }

    /** Owner of a given HTLC transaction (local/remote). */
    sealed class TxOwner {
        object Local : TxOwner()
        object Remote : TxOwner()
    }

    /**
     * HTLC transactions require local and remote signatures and can be spent using two distinct script paths:
     *  - the success path by revealing the payment preimage
     *  - the timeout path after a predefined block height
     *
     * The success path must be used before the timeout is reached, otherwise there is a race where both channel
     * participants may claim the output.
     *
     * We first create unsigned HTLC transactions based on the [CommitTx]: this lets us produce our local signature,
     * which we need to send to our peer for their commitment.
     *
     * Once confirmed, HTLC transactions need to be spent by an [HtlcDelayedTx] after a relative delay to get the funds
     * back into our on-chain wallet.
     */
    sealed class HtlcTx : TransactionWithInputInfo() {
        abstract val htlcId: Long
        abstract val paymentHash: ByteVector32
        abstract val htlcExpiry: CltvExpiry
        abstract val commitmentFormat: CommitmentFormat

        /** Create redeem information for this HTLC transaction, based on the commitment format used. */
        abstract fun redeemInfo(commitKeys: CommitmentPublicKeys): RedeemInfo

        fun sigHash(txOwner: TxOwner): Int = when (commitmentFormat) {
            CommitmentFormat.AnchorOutputs -> when (txOwner) {
                TxOwner.Local -> SigHash.SIGHASH_ALL
                TxOwner.Remote -> SigHash.SIGHASH_SINGLE or SigHash.SIGHASH_ANYONECANPAY
            }
        }

        /** Sign an HTLC transaction for the remote commitment. */
        fun localSig(commitKeys: RemoteCommitmentKeys): ByteVector64 {
            return sign(commitKeys.ourHtlcKey, sigHash(TxOwner.Remote), redeemInfo(commitKeys.publicKeys), extraUtxos = mapOf())
        }

        fun checkRemoteSig(commitKeys: LocalCommitmentKeys, remoteSig: ByteVector64): Boolean {
            // The transaction was signed by our remote for us: from their point of view, we're a remote owner.
            val remoteSighash = sigHash(TxOwner.Remote)
            return checkSig(remoteSig, commitKeys.theirHtlcPublicKey, remoteSighash, redeemInfo(commitKeys.publicKeys))
        }
    }

    /** This transaction spends a received (incoming) HTLC from a local or remote commitment by revealing the payment preimage. */
    data class HtlcSuccessTx(
        override val input: InputInfo,
        override val tx: Transaction,
        override val paymentHash: ByteVector32,
        override val htlcId: Long,
        override val htlcExpiry: CltvExpiry,
        override val commitmentFormat: CommitmentFormat
    ) : HtlcTx() {
        override fun redeemInfo(commitKeys: CommitmentPublicKeys): RedeemInfo = redeemInfo(commitKeys, paymentHash, htlcExpiry, commitmentFormat)

        fun sign(commitKeys: LocalCommitmentKeys, remoteSig: ByteVector64, preimage: ByteVector32): HtlcSuccessTx {
            val redeemInfo = redeemInfo(commitKeys.publicKeys)
            val localSig = sign(commitKeys.ourHtlcKey, sigHash(TxOwner.Local), redeemInfo, extraUtxos = mapOf())
            val witness = when (redeemInfo) {
                is RedeemInfo.P2wsh -> Scripts.witnessHtlcSuccess(localSig, remoteSig, preimage, redeemInfo.redeemScript)
            }
            return this.copy(tx = tx.updateWitness(inputIndex, witness))
        }

        companion object {
            fun redeemInfo(commitKeys: CommitmentPublicKeys, paymentHash: ByteVector32, htlcExpiry: CltvExpiry, commitmentFormat: CommitmentFormat): RedeemInfo {
                return when (commitmentFormat) {
                    CommitmentFormat.AnchorOutputs -> RedeemInfo.P2wsh(Scripts.htlcReceived(commitKeys, paymentHash, htlcExpiry))
                }
            }

            fun createUnsignedTx(commitTx: Transaction, output: InHtlc, outputIndex: Int, commitmentFormat: CommitmentFormat): HtlcSuccessTx {
                val htlc = output.htlc.add
                val input = InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex])
                val tx = Transaction(
                    version = 2,
                    txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 1)),
                    txOut = listOf(output.htlcDelayedOutput),
                    lockTime = 0
                )
                return HtlcSuccessTx(input, tx, htlc.paymentHash, htlc.id, htlc.cltvExpiry, commitmentFormat)
            }
        }
    }

    /** This transaction spends an offered (outgoing) HTLC from a local or remote commitment after its expiry. */
    data class HtlcTimeoutTx(
        override val input: InputInfo,
        override val tx: Transaction,
        override val paymentHash: ByteVector32,
        override val htlcId: Long,
        override val htlcExpiry: CltvExpiry,
        override val commitmentFormat: CommitmentFormat
    ) : HtlcTx() {
        override fun redeemInfo(commitKeys: CommitmentPublicKeys): RedeemInfo = redeemInfo(commitKeys, paymentHash, commitmentFormat)

        fun sign(commitKeys: LocalCommitmentKeys, remoteSig: ByteVector64): HtlcTimeoutTx {
            val redeemInfo = redeemInfo(commitKeys.publicKeys)
            val localSig = sign(commitKeys.ourHtlcKey, sigHash(TxOwner.Local), redeemInfo, extraUtxos = mapOf())
            val witness = when (redeemInfo) {
                is RedeemInfo.P2wsh -> Scripts.witnessHtlcTimeout(localSig, remoteSig, redeemInfo.redeemScript)
            }
            return this.copy(tx = tx.updateWitness(inputIndex, witness))
        }

        companion object {
            fun redeemInfo(commitKeys: CommitmentPublicKeys, paymentHash: ByteVector32, commitmentFormat: CommitmentFormat): RedeemInfo {
                return when (commitmentFormat) {
                    CommitmentFormat.AnchorOutputs -> RedeemInfo.P2wsh(Scripts.htlcOffered(commitKeys, paymentHash))
                }
            }

            fun createUnsignedTx(commitTx: Transaction, output: OutHtlc, outputIndex: Int, commitmentFormat: CommitmentFormat): HtlcTimeoutTx {
                val htlc = output.htlc.add
                val input = InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex])
                val tx = Transaction(
                    version = 2,
                    txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 1)),
                    txOut = listOf(output.htlcDelayedOutput),
                    lockTime = htlc.cltvExpiry.toLong()
                )
                return HtlcTimeoutTx(input, tx, htlc.paymentHash, htlc.id, htlc.cltvExpiry, commitmentFormat)
            }
        }
    }

    /** This transaction spends the output of a local [HtlcTx] after a to_self_delay relative delay. */
    data class HtlcDelayedTx(
        override val commitKeys: LocalCommitmentKeys,
        override val input: InputInfo,
        override val tx: Transaction,
        val toLocalDelay: CltvExpiryDelta,
        override val commitmentFormat: CommitmentFormat
    ) : LocalCommitForceCloseTransaction() {
        override val expectedWeight: Int = commitmentFormat.htlcDelayedWeight

        override fun sign(): HtlcDelayedTx {
            val witness = when (commitmentFormat) {
                CommitmentFormat.AnchorOutputs -> {
                    val redeemScript = Script.write(Scripts.toLocalDelayed(commitKeys.publicKeys, toLocalDelay)).byteVector()
                    val sig = sign(commitKeys.ourDelayedPaymentKey, sigHash, RedeemInfo.P2wsh(redeemScript), extraUtxos = mapOf())
                    Scripts.witnessToLocalDelayedAfterDelay(sig, redeemScript)
                }
            }
            return this.copy(tx = tx.updateWitness(inputIndex, witness))
        }

        companion object {
            fun redeemInfo(commitKeys: CommitmentPublicKeys, toLocalDelay: CltvExpiryDelta, commitmentFormat: CommitmentFormat): RedeemInfo {
                return when (commitmentFormat) {
                    CommitmentFormat.AnchorOutputs -> RedeemInfo.P2wsh(Scripts.toLocalDelayed(commitKeys, toLocalDelay))
                }
            }

            fun createUnsignedTx(
                commitKeys: LocalCommitmentKeys,
                htlcTx: Transaction,
                localDustLimit: Satoshi,
                toLocalDelay: CltvExpiryDelta,
                localFinalScriptPubKey: ByteVector,
                feerate: FeeratePerKw,
                commitmentFormat: CommitmentFormat
            ): Either<TxGenerationSkipped, HtlcDelayedTx> {
                val pubkeyScript = redeemInfo(commitKeys.publicKeys, toLocalDelay, commitmentFormat).pubkeyScript
                return findPubKeyScriptIndex(htlcTx, pubkeyScript).flatMap { outputIndex ->
                    val input = InputInfo(OutPoint(htlcTx, outputIndex.toLong()), htlcTx.txOut[outputIndex])
                    val amount = input.txOut.amount - weight2fee(feerate, commitmentFormat.htlcDelayedWeight)
                    val tx = Transaction(
                        version = 2,
                        txIn = listOf(TxIn(input.outPoint, ByteVector.empty, toLocalDelay.toLong())),
                        txOut = listOf(TxOut(amount, localFinalScriptPubKey)),
                        lockTime = 0
                    )
                    val unsignedTx = HtlcDelayedTx(commitKeys, input, tx, toLocalDelay, commitmentFormat)
                    skipTxIfBelowDust(unsignedTx, localDustLimit)
                }
            }
        }
    }

    sealed class ClaimHtlcTx : RemoteCommitForceCloseTransaction() {
        abstract val htlcId: Long
        abstract val paymentHash: ByteVector32
        abstract val htlcExpiry: CltvExpiry
    }

    /** This transaction spends an HTLC we received by revealing the payment preimage, from the remote commitment. */
    data class ClaimHtlcSuccessTx(
        override val commitKeys: RemoteCommitmentKeys,
        override val input: InputInfo,
        override val tx: Transaction,
        override val htlcId: Long,
        val preimage: ByteVector32,
        override val htlcExpiry: CltvExpiry,
        override val commitmentFormat: CommitmentFormat
    ) : ClaimHtlcTx() {
        override val paymentHash: ByteVector32 = Crypto.sha256(preimage.toByteArray()).byteVector32()
        override val expectedWeight: Int = commitmentFormat.claimHtlcSuccessWeight

        override fun sign(): ClaimHtlcSuccessTx {
            // Note that in/out HTLCs are inverted in the remote commitment: from their point of view it's an offered (outgoing) HTLC.
            val witness = when (commitmentFormat) {
                CommitmentFormat.AnchorOutputs -> {
                    val redeemScript = Script.write(Scripts.htlcOffered(commitKeys.publicKeys, paymentHash)).byteVector()
                    val sig = sign(commitKeys.ourHtlcKey, sigHash, RedeemInfo.P2wsh(redeemScript), extraUtxos = mapOf())
                    Scripts.witnessClaimHtlcSuccessFromCommitTx(sig, preimage, redeemScript)
                }
            }
            return this.copy(tx = tx.updateWitness(inputIndex, witness))
        }

        companion object {
            /**
             * Find the output of the commitment transaction matching this HTLC.
             * Note that we match on a specific HTLC, because we may have multiple HTLCs with the same payment_hash, expiry
             * and amount and thus the same pubkeyScript, and we must make sure we claim them all.
             */
            fun findInput(commitTx: Transaction, outputs: List<CommitmentOutput>, htlc: UpdateAddHtlc): InputInfo? {
                return outputs.withIndex()
                    .firstOrNull { (it.value as? OutHtlc)?.htlc?.add?.id == htlc.id }
                    ?.let { InputInfo(OutPoint(commitTx, it.index.toLong()), commitTx.txOut[it.index]) }
            }

            fun createUnsignedTx(
                commitKeys: RemoteCommitmentKeys,
                commitTx: Transaction,
                dustLimit: Satoshi,
                outputs: List<CommitmentOutput>,
                localFinalScriptPubKey: ByteVector,
                htlc: UpdateAddHtlc,
                preimage: ByteVector32,
                feerate: FeeratePerKw,
                commitmentFormat: CommitmentFormat
            ): Either<TxGenerationSkipped, ClaimHtlcSuccessTx> {
                return when (val input = findInput(commitTx, outputs, htlc)) {
                    null -> Either.Left(TxGenerationSkipped.OutputNotFound)
                    else -> {
                        val amount = input.txOut.amount - weight2fee(feerate, commitmentFormat.claimHtlcSuccessWeight)
                        val tx = Transaction(
                            version = 2,
                            txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 1)),
                            txOut = listOf(TxOut(amount, localFinalScriptPubKey)),
                            lockTime = 0
                        )
                        val unsignedTx = ClaimHtlcSuccessTx(commitKeys, input, tx, htlc.id, preimage, htlc.cltvExpiry, commitmentFormat)
                        skipTxIfBelowDust(unsignedTx, dustLimit)
                    }
                }
            }
        }
    }

    /** This transaction spends an HTLC we sent after its expiry, from the remote commitment. */
    data class ClaimHtlcTimeoutTx(
        override val commitKeys: RemoteCommitmentKeys,
        override val input: InputInfo,
        override val tx: Transaction,
        override val htlcId: Long,
        override val paymentHash: ByteVector32,
        override val htlcExpiry: CltvExpiry,
        override val commitmentFormat: CommitmentFormat
    ) : ClaimHtlcTx() {
        override val expectedWeight: Int = commitmentFormat.claimHtlcTimeoutWeight

        override fun sign(): ClaimHtlcTimeoutTx {
            // Note that in/out HTLCs are inverted in the remote commitment: from their point of view it's an offered (outgoing) HTLC.
            val witness = when (commitmentFormat) {
                CommitmentFormat.AnchorOutputs -> {
                    val redeemScript = Script.write(Scripts.htlcReceived(commitKeys.publicKeys, paymentHash, htlcExpiry)).byteVector()
                    val sig = sign(commitKeys.ourHtlcKey, sigHash, RedeemInfo.P2wsh(redeemScript), extraUtxos = mapOf())
                    Scripts.witnessClaimHtlcTimeoutFromCommitTx(sig, redeemScript)
                }
            }
            return this.copy(tx = tx.updateWitness(inputIndex, witness))
        }

        companion object {
            /**
             * Find the output of the commitment transaction matching this HTLC.
             * Note that we match on a specific HTLC, because we may have multiple HTLCs with the same payment_hash, expiry
             * and amount and thus the same pubkeyScript, and we must make sure we claim them all.
             */
            fun findInput(commitTx: Transaction, outputs: List<CommitmentOutput>, htlc: UpdateAddHtlc): InputInfo? {
                return outputs.withIndex()
                    .firstOrNull { (it.value as? InHtlc)?.htlc?.add?.id == htlc.id }
                    ?.let { InputInfo(OutPoint(commitTx, it.index.toLong()), commitTx.txOut[it.index]) }
            }

            fun createUnsignedTx(
                commitKeys: RemoteCommitmentKeys,
                commitTx: Transaction,
                dustLimit: Satoshi,
                outputs: List<CommitmentOutput>,
                localFinalScriptPubKey: ByteVector,
                htlc: UpdateAddHtlc,
                feerate: FeeratePerKw,
                commitmentFormat: CommitmentFormat
            ): Either<TxGenerationSkipped, ClaimHtlcTimeoutTx> {
                return when (val input = findInput(commitTx, outputs, htlc)) {
                    null -> Either.Left(TxGenerationSkipped.OutputNotFound)
                    else -> {
                        val amount = input.txOut.amount - weight2fee(feerate, commitmentFormat.claimHtlcTimeoutWeight)
                        val tx = Transaction(
                            version = 2,
                            txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 1)),
                            txOut = listOf(TxOut(amount, localFinalScriptPubKey)),
                            lockTime = htlc.cltvExpiry.toLong()
                        )
                        val unsignedTx = ClaimHtlcTimeoutTx(commitKeys, input, tx, htlc.id, htlc.paymentHash, htlc.cltvExpiry, commitmentFormat)
                        skipTxIfBelowDust(unsignedTx, dustLimit)
                    }
                }
            }
        }
    }

    /** This transaction spends our main balance from our commitment after a to_self_delay relative delay. */
    data class ClaimLocalDelayedOutputTx(
        override val commitKeys: LocalCommitmentKeys,
        override val input: InputInfo,
        override val tx: Transaction,
        val toLocalDelay: CltvExpiryDelta,
        override val commitmentFormat: CommitmentFormat,
    ) : LocalCommitForceCloseTransaction() {
        override val expectedWeight: Int = commitmentFormat.toLocalDelayedWeight

        override fun sign(): ClaimLocalDelayedOutputTx {
            val witness = when (commitmentFormat) {
                CommitmentFormat.AnchorOutputs -> {
                    val redeemScript = Script.write(Scripts.toLocalDelayed(commitKeys.publicKeys, toLocalDelay)).byteVector()
                    val sig = sign(commitKeys.ourDelayedPaymentKey, sigHash, RedeemInfo.P2wsh(redeemScript), extraUtxos = mapOf())
                    Scripts.witnessToLocalDelayedAfterDelay(sig, redeemScript)
                }
            }
            return this.copy(tx = tx.updateWitness(inputIndex, witness))
        }

        companion object {
            fun createUnsignedTx(
                commitKeys: LocalCommitmentKeys,
                commitTx: Transaction,
                localDustLimit: Satoshi,
                toLocalDelay: CltvExpiryDelta,
                localFinalScriptPubKey: ByteVector,
                feerate: FeeratePerKw,
                commitmentFormat: CommitmentFormat
            ): Either<TxGenerationSkipped, ClaimLocalDelayedOutputTx> {
                val redeemInfo = when (commitmentFormat) {
                    CommitmentFormat.AnchorOutputs -> RedeemInfo.P2wsh(Scripts.toLocalDelayed(commitKeys.publicKeys, toLocalDelay))
                }
                return findPubKeyScriptIndex(commitTx, redeemInfo.pubkeyScript).flatMap { outputIndex ->
                    val input = InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex])
                    val amount = input.txOut.amount - weight2fee(feerate, commitmentFormat.toLocalDelayedWeight)
                    val tx = Transaction(
                        version = 2,
                        txIn = listOf(TxIn(input.outPoint, ByteVector.empty, toLocalDelay.toLong())),
                        txOut = listOf(TxOut(amount, localFinalScriptPubKey)),
                        lockTime = 0
                    )
                    val unsignedTx = ClaimLocalDelayedOutputTx(commitKeys, input, tx, toLocalDelay, commitmentFormat)
                    skipTxIfBelowDust(unsignedTx, localDustLimit)
                }
            }
        }
    }

    /** This transaction spends our main balance from the remote commitment with a 1-block relative delay. */
    data class ClaimRemoteDelayedOutputTx(
        override val commitKeys: RemoteCommitmentKeys,
        override val input: InputInfo,
        override val tx: Transaction,
        override val commitmentFormat: CommitmentFormat,
    ) : RemoteCommitForceCloseTransaction() {
        override val expectedWeight: Int = commitmentFormat.toRemoteWeight

        override fun sign(): ClaimRemoteDelayedOutputTx {
            val witness = when (commitmentFormat) {
                CommitmentFormat.AnchorOutputs -> {
                    val redeemScript = Script.write(Scripts.toRemoteDelayed(commitKeys.publicKeys)).byteVector()
                    val sig = sign(commitKeys.ourPaymentKey, sigHash, RedeemInfo.P2wsh(redeemScript), extraUtxos = mapOf())
                    Scripts.witnessToRemoteDelayedAfterDelay(sig, redeemScript)
                }
            }
            return this.copy(tx = tx.updateWitness(inputIndex, witness))
        }

        companion object {
            fun createUnsignedTx(
                commitKeys: RemoteCommitmentKeys,
                commitTx: Transaction,
                localDustLimit: Satoshi,
                localFinalScriptPubKey: ByteVector,
                feerate: FeeratePerKw,
                commitmentFormat: CommitmentFormat
            ): Either<TxGenerationSkipped, ClaimRemoteDelayedOutputTx> {
                val redeemInfo = when (commitmentFormat) {
                    CommitmentFormat.AnchorOutputs -> RedeemInfo.P2wsh(Scripts.toRemoteDelayed(commitKeys.publicKeys))
                }
                return findPubKeyScriptIndex(commitTx, redeemInfo.pubkeyScript).flatMap { outputIndex ->
                    val input = InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex])
                    val amount = input.txOut.amount - weight2fee(feerate, commitmentFormat.toRemoteWeight)
                    val tx = Transaction(
                        version = 2,
                        txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 1)),
                        txOut = listOf(TxOut(amount, localFinalScriptPubKey)),
                        lockTime = 0
                    )
                    val unsignedTx = ClaimRemoteDelayedOutputTx(commitKeys, input, tx, commitmentFormat)
                    skipTxIfBelowDust(unsignedTx, localDustLimit)
                }
            }
        }
    }

    /** This transaction spends the remote main balance from one of their revoked commitments. */
    data class MainPenaltyTx(
        override val commitKeys: RemoteCommitmentKeys,
        val revocationKey: PrivateKey,
        override val input: InputInfo,
        override val tx: Transaction,
        val toRemoteDelay: CltvExpiryDelta,
        override val commitmentFormat: CommitmentFormat,
    ) : RemoteCommitForceCloseTransaction() {
        override val expectedWeight: Int = commitmentFormat.mainPenaltyWeight

        override fun sign(): MainPenaltyTx {
            val witness = when (commitmentFormat) {
                CommitmentFormat.AnchorOutputs -> {
                    val redeemScript = Script.write(Scripts.toLocalDelayed(commitKeys.publicKeys, toRemoteDelay)).byteVector()
                    val sig = sign(revocationKey, sigHash, RedeemInfo.P2wsh(redeemScript), extraUtxos = mapOf())
                    Scripts.witnessToLocalDelayedWithRevocationSig(sig, redeemScript)
                }
            }
            return this.copy(tx = tx.updateWitness(inputIndex, witness))
        }

        companion object {
            fun createUnsignedTx(
                commitKeys: RemoteCommitmentKeys,
                revocationKey: PrivateKey,
                commitTx: Transaction,
                localDustLimit: Satoshi,
                localFinalScriptPubKey: ByteVector,
                toRemoteDelay: CltvExpiryDelta,
                feerate: FeeratePerKw,
                commitmentFormat: CommitmentFormat
            ): Either<TxGenerationSkipped, MainPenaltyTx> {
                val redeemInfo = when (commitmentFormat) {
                    CommitmentFormat.AnchorOutputs -> RedeemInfo.P2wsh(Scripts.toLocalDelayed(commitKeys.publicKeys, toRemoteDelay))
                }
                return findPubKeyScriptIndex(commitTx, redeemInfo.pubkeyScript).flatMap { outputIndex ->
                    val input = InputInfo(OutPoint(commitTx, outputIndex.toLong()), commitTx.txOut[outputIndex])
                    val amount = input.txOut.amount - weight2fee(feerate, commitmentFormat.mainPenaltyWeight)
                    val tx = Transaction(
                        version = 2,
                        txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 0xffffffffL)),
                        txOut = listOf(TxOut(amount, localFinalScriptPubKey)),
                        lockTime = 0
                    )
                    val unsignedTx = MainPenaltyTx(commitKeys, revocationKey, input, tx, toRemoteDelay, commitmentFormat)
                    skipTxIfBelowDust(unsignedTx, localDustLimit)
                }
            }
        }
    }

    private data class HtlcPenaltyRedeemDetails(val redeemInfo: RedeemInfo, val paymentHash: ByteVector32, val htlcExpiry: CltvExpiry, val weight: Int)

    /** This transaction spends an HTLC output from one of the remote revoked commitments. */
    data class HtlcPenaltyTx(
        override val commitKeys: RemoteCommitmentKeys,
        val revocationKey: PrivateKey,
        val redeemInfo: RedeemInfo,
        override val input: InputInfo,
        override val tx: Transaction,
        val paymentHash: ByteVector32,
        val htlcExpiry: CltvExpiry,
        override val commitmentFormat: CommitmentFormat,
    ) : RemoteCommitForceCloseTransaction() {
        // We don't know if this is an incoming or outgoing HTLC, so we just use the bigger one (they are very close anyway).
        override val expectedWeight: Int = maxOf(commitmentFormat.htlcOfferedPenaltyWeight, commitmentFormat.htlcReceivedPenaltyWeight)

        override fun sign(): HtlcPenaltyTx {
            val sig = sign(revocationKey, sigHash, redeemInfo, extraUtxos = mapOf())
            val witness = when (redeemInfo) {
                is RedeemInfo.P2wsh -> Scripts.witnessHtlcWithRevocationSig(commitKeys, sig, redeemInfo.redeemScript)
            }
            return this.copy(tx = tx.updateWitness(inputIndex, witness))
        }

        companion object {
            fun createUnsignedTxs(
                commitKeys: RemoteCommitmentKeys,
                revocationKey: PrivateKey,
                commitTx: Transaction,
                htlcs: List<Pair<ByteVector32, CltvExpiry>>,
                localDustLimit: Satoshi,
                localFinalScriptPubKey: ByteVector,
                feerate: FeeratePerKw,
                commitmentFormat: CommitmentFormat
            ): List<Either<TxGenerationSkipped, HtlcPenaltyTx>> {
                // We create the output scripts for the corresponding HTLCs.
                val redeemInfos = htlcs.flatMap { (paymentHash, htlcExpiry) ->
                    // We don't know if this was an incoming or outgoing HTLC, so we try both cases.
                    val (offered, received) = when (commitmentFormat) {
                        CommitmentFormat.AnchorOutputs -> {
                            val offered = RedeemInfo.P2wsh(Scripts.htlcOffered(commitKeys.publicKeys, paymentHash))
                            val received = RedeemInfo.P2wsh(Scripts.htlcReceived(commitKeys.publicKeys, paymentHash, htlcExpiry))
                            Pair(offered, received)
                        }
                    }
                    listOf(
                        offered.pubkeyScript to HtlcPenaltyRedeemDetails(offered, paymentHash, htlcExpiry, commitmentFormat.htlcOfferedPenaltyWeight),
                        received.pubkeyScript to HtlcPenaltyRedeemDetails(received, paymentHash, htlcExpiry, commitmentFormat.htlcReceivedPenaltyWeight),
                    )
                }.toMap()
                // We check every output of the commitment transaction, and create an HTLC-penalty transaction if it is an HTLC output.
                return commitTx.txOut.withIndex()
                    .mapNotNull { (outputIndex, txOut) -> redeemInfos[txOut.publicKeyScript]?.let { Pair(outputIndex, it) } }
                    .map { (idx, details) -> createUnsignedTx(commitKeys, revocationKey, commitTx, idx, details, localDustLimit, localFinalScriptPubKey, feerate, commitmentFormat) }
            }

            private fun createUnsignedTx(
                commitKeys: RemoteCommitmentKeys,
                revocationKey: PrivateKey,
                commitTx: Transaction,
                htlcOutputIndex: Int,
                redeemDetails: HtlcPenaltyRedeemDetails,
                localDustLimit: Satoshi,
                localFinalScriptPubKey: ByteVector,
                feerate: FeeratePerKw,
                commitmentFormat: CommitmentFormat
            ): Either<TxGenerationSkipped, HtlcPenaltyTx> {
                val input = InputInfo(OutPoint(commitTx, htlcOutputIndex.toLong()), commitTx.txOut[htlcOutputIndex])
                val amount = input.txOut.amount - weight2fee(feerate, redeemDetails.weight)
                val tx = Transaction(
                    version = 2,
                    txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 0xffffffffL)),
                    txOut = listOf(TxOut(amount, localFinalScriptPubKey)),
                    lockTime = 0
                )
                val unsignedTx = HtlcPenaltyTx(commitKeys, revocationKey, redeemDetails.redeemInfo, input, tx, redeemDetails.paymentHash, redeemDetails.htlcExpiry, commitmentFormat)
                return skipTxIfBelowDust(unsignedTx, localDustLimit)
            }
        }
    }

    /** This transaction spends a remote [HtlcTx] from one of their revoked commitments. */
    data class ClaimHtlcDelayedOutputPenaltyTx(
        override val commitKeys: RemoteCommitmentKeys,
        val revocationKey: PrivateKey,
        override val input: InputInfo,
        override val tx: Transaction,
        val toRemoteDelay: CltvExpiryDelta,
        override val commitmentFormat: CommitmentFormat,
    ) : RemoteCommitForceCloseTransaction() {
        override val expectedWeight: Int = commitmentFormat.claimHtlcPenaltyWeight

        override fun sign(): ClaimHtlcDelayedOutputPenaltyTx {
            val witness = when (commitmentFormat) {
                CommitmentFormat.AnchorOutputs -> {
                    val redeemScript = Script.write(Scripts.toLocalDelayed(commitKeys.publicKeys, toRemoteDelay)).byteVector()
                    val sig = sign(revocationKey, sigHash, RedeemInfo.P2wsh(redeemScript), extraUtxos = mapOf())
                    Scripts.witnessToLocalDelayedWithRevocationSig(sig, redeemScript)
                }
            }
            return this.copy(tx = tx.updateWitness(inputIndex, witness))
        }

        companion object {
            fun createUnsignedTxs(
                commitKeys: RemoteCommitmentKeys,
                revocationKey: PrivateKey,
                htlcTx: Transaction,
                localDustLimit: Satoshi,
                toRemoteDelay: CltvExpiryDelta,
                localFinalScriptPubKey: ByteVector,
                feerate: FeeratePerKw,
                commitmentFormat: CommitmentFormat
            ): List<Either<TxGenerationSkipped, ClaimHtlcDelayedOutputPenaltyTx>> {
                val redeemInfo = when (commitmentFormat) {
                    CommitmentFormat.AnchorOutputs -> RedeemInfo.P2wsh(Scripts.toLocalDelayed(commitKeys.publicKeys, toRemoteDelay))
                }
                // Note that we check *all* outputs of the tx, because it could spend a batch of HTLC outputs from the commit tx.
                return htlcTx.txOut.withIndex().mapNotNull { (outputIndex, txOut) ->
                    when {
                        txOut.publicKeyScript == redeemInfo.pubkeyScript -> {
                            val input = InputInfo(OutPoint(htlcTx, outputIndex.toLong()), htlcTx.txOut[outputIndex])
                            val amount = input.txOut.amount - weight2fee(feerate, commitmentFormat.claimHtlcPenaltyWeight)
                            val tx = Transaction(
                                version = 2,
                                txIn = listOf(TxIn(input.outPoint, ByteVector.empty, 0xffffffffL)),
                                txOut = listOf(TxOut(amount, localFinalScriptPubKey)),
                                lockTime = 0
                            )
                            val unsignedTx = ClaimHtlcDelayedOutputPenaltyTx(commitKeys, revocationKey, input, tx, toRemoteDelay, commitmentFormat)
                            skipTxIfBelowDust(unsignedTx, localDustLimit)
                        }
                        else -> null
                    }
                }
            }
        }
    }

    sealed class TxGenerationSkipped {
        // @formatter:off
        object OutputNotFound : TxGenerationSkipped() { override fun toString() = "output not found (probably trimmed)" }
        object AmountBelowDustLimit : TxGenerationSkipped() { override fun toString() = "amount is below dust limit" }
        // @formatter:on
    }

    private fun weight2feeMsat(feerate: FeeratePerKw, weight: Int): MilliSatoshi = (feerate.toLong() * weight).msat

    fun weight2fee(feerate: FeeratePerKw, weight: Int): Satoshi = weight2feeMsat(feerate, weight).truncateToSatoshi()

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
                script[0] == OP_RETURN -> 0.sat // OP_RETURN is never dust
                else -> 546.sat
            }
        }.getOrElse { 546.sat }
    }

    /** When an output is using OP_RETURN, we usually want to make sure its amount is 0, otherwise bitcoin nodes won't accept it. */
    fun isOpReturn(scriptPubKey: ByteVector): Boolean {
        return runTrying {
            val script = Script.parse(scriptPubKey)
            script[0] == OP_RETURN
        }.getOrElse { false }
    }

    /** Offered HTLCs below this amount will be trimmed. */
    fun offeredHtlcTrimThreshold(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): Satoshi = dustLimit + weight2fee(spec.feerate, commitmentFormat.htlcTimeoutWeight)

    fun trimOfferedHtlcs(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): List<OutgoingHtlc> {
        val threshold = offeredHtlcTrimThreshold(dustLimit, spec, commitmentFormat)
        return spec.htlcs
            .filterIsInstance<OutgoingHtlc>()
            .filter { it.add.amountMsat >= threshold }
    }

    /** Received HTLCs below this amount will be trimmed. */
    fun receivedHtlcTrimThreshold(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): Satoshi = dustLimit + weight2fee(spec.feerate, commitmentFormat.htlcSuccessWeight)

    fun trimReceivedHtlcs(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): List<IncomingHtlc> {
        val threshold = receivedHtlcTrimThreshold(dustLimit, spec, commitmentFormat)
        return spec.htlcs
            .filterIsInstance<IncomingHtlc>()
            .filter { it.add.amountMsat >= threshold }
    }

    /** Fee for an un-trimmed HTLC. */
    fun htlcOutputFee(feerate: FeeratePerKw, commitmentFormat: CommitmentFormat): MilliSatoshi = weight2feeMsat(feerate, commitmentFormat.htlcOutputWeight)

    /**
     * While fees are generally computed in satoshis (since this is the smallest on-chain unit), it may be useful in some
     * cases to calculate it in milliSatoshi to avoid rounding issues.
     * If you are adding multiple fees together for example, you should always add them in milliSatoshi and then round
     * down to satoshi.
     */
    fun commitTxFeeMsat(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): MilliSatoshi {
        val trimmedOfferedHtlcs = trimOfferedHtlcs(dustLimit, spec, commitmentFormat)
        val trimmedReceivedHtlcs = trimReceivedHtlcs(dustLimit, spec, commitmentFormat)
        val weight = commitmentFormat.commitWeight + commitmentFormat.htlcOutputWeight * (trimmedOfferedHtlcs.size + trimmedReceivedHtlcs.size)
        return weight2feeMsat(spec.feerate, weight) + (commitmentFormat.anchorAmount * 2).toMilliSatoshi()
    }

    fun commitTxFee(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): Satoshi = commitTxFeeMsat(dustLimit, spec, commitmentFormat).truncateToSatoshi()

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

    fun makeFundingScript(localFundingKey: PublicKey, remoteFundingKey: PublicKey, commitmentFormat: CommitmentFormat): RedeemInfo {
        return when (commitmentFormat) {
            CommitmentFormat.AnchorOutputs -> RedeemInfo.P2wsh(Scripts.multiSig2of2(localFundingKey, remoteFundingKey))
        }
    }

    fun makeFundingInputInfo(fundingTxId: TxId, fundingOutputIndex: Long, fundingAmount: Satoshi, localFundingKey: PublicKey, remoteFundingKey: PublicKey, commitmentFormat: CommitmentFormat): InputInfo {
        val redeemInfo = makeFundingScript(localFundingKey, remoteFundingKey, commitmentFormat)
        val fundingTxOut = TxOut(fundingAmount, redeemInfo.pubkeyScript)
        return InputInfo(OutPoint(fundingTxId, fundingOutputIndex), fundingTxOut)
    }

    fun makeCommitTxOutputs(
        localFundingPubkey: PublicKey,
        remoteFundingPubkey: PublicKey,
        commitKeys: CommitmentPublicKeys,
        payCommitTxFees: Boolean,
        dustLimit: Satoshi,
        toSelfDelay: CltvExpiryDelta,
        commitmentFormat: CommitmentFormat,
        spec: CommitmentSpec,
    ): List<CommitmentOutput> {
        val outputs = ArrayList<CommitmentOutput>()
        val commitFee = commitTxFee(dustLimit, spec, commitmentFormat)
        val (toLocalAmount, toRemoteAmount) = if (payCommitTxFees) {
            Pair(spec.toLocal.truncateToSatoshi() - commitFee, spec.toRemote.truncateToSatoshi())
        } else {
            Pair(spec.toLocal.truncateToSatoshi(), spec.toRemote.truncateToSatoshi() - commitFee)
        } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway

        if (toLocalAmount >= dustLimit) {
            val redeemInfo = when (commitmentFormat) {
                CommitmentFormat.AnchorOutputs -> RedeemInfo.P2wsh(Scripts.toLocalDelayed(commitKeys, toSelfDelay))
            }
            outputs.add(CommitmentOutput.ToLocal(TxOut(toLocalAmount, redeemInfo.pubkeyScript)))
        }
        if (toRemoteAmount >= dustLimit) {
            val redeemInfo = when (commitmentFormat) {
                CommitmentFormat.AnchorOutputs -> RedeemInfo.P2wsh(Scripts.toRemoteDelayed(commitKeys))
            }
            outputs.add(CommitmentOutput.ToRemote(TxOut(toRemoteAmount, redeemInfo.pubkeyScript)))
        }

        val untrimmedHtlcs = trimOfferedHtlcs(dustLimit, spec, commitmentFormat).isNotEmpty() || trimReceivedHtlcs(dustLimit, spec, commitmentFormat).isNotEmpty()
        if (untrimmedHtlcs || toLocalAmount >= dustLimit) {
            val redeemInfo = when (commitmentFormat) {
                CommitmentFormat.AnchorOutputs -> RedeemInfo.P2wsh(Scripts.toAnchor(localFundingPubkey))
            }
            outputs.add(CommitmentOutput.ToLocalAnchor(TxOut(commitmentFormat.anchorAmount, redeemInfo.pubkeyScript)))
        }
        if (untrimmedHtlcs || toRemoteAmount >= dustLimit) {
            val redeemInfo = when (commitmentFormat) {
                CommitmentFormat.AnchorOutputs -> RedeemInfo.P2wsh(Scripts.toAnchor(remoteFundingPubkey))
            }
            outputs.add(CommitmentOutput.ToRemoteAnchor(TxOut(commitmentFormat.anchorAmount, redeemInfo.pubkeyScript)))
        }

        trimOfferedHtlcs(dustLimit, spec, commitmentFormat).forEach { htlc ->
            val fee = weight2fee(spec.feerate, commitmentFormat.htlcTimeoutWeight)
            val amountAfterFees = htlc.add.amountMsat.truncateToSatoshi() - fee
            val redeemInfo = HtlcTimeoutTx.redeemInfo(commitKeys, htlc.add.paymentHash, commitmentFormat)
            val htlcDelayedRedeemInfo = HtlcDelayedTx.redeemInfo(commitKeys, toSelfDelay, commitmentFormat)
            outputs.add(OutHtlc(htlc, TxOut(htlc.add.amountMsat.truncateToSatoshi(), redeemInfo.pubkeyScript), TxOut(amountAfterFees, htlcDelayedRedeemInfo.pubkeyScript)))
        }
        trimReceivedHtlcs(dustLimit, spec, commitmentFormat).forEach { htlc ->
            val fee = weight2fee(spec.feerate, commitmentFormat.htlcSuccessWeight)
            val amountAfterFees = htlc.add.amountMsat.truncateToSatoshi() - fee
            val redeemInfo = HtlcSuccessTx.redeemInfo(commitKeys, htlc.add.paymentHash, htlc.add.cltvExpiry, commitmentFormat)
            val htlcDelayedRedeemInfo = HtlcDelayedTx.redeemInfo(commitKeys, toSelfDelay, commitmentFormat)
            outputs.add(InHtlc(htlc, TxOut(htlc.add.amountMsat.truncateToSatoshi(), redeemInfo.pubkeyScript), TxOut(amountAfterFees, htlcDelayedRedeemInfo.pubkeyScript)))
        }

        return outputs.apply { sort() }
    }

    fun makeCommitTx(
        commitTxInput: InputInfo,
        commitTxNumber: Long,
        localPaymentBasePoint: PublicKey,
        remotePaymentBasePoint: PublicKey,
        localIsChannelOpener: Boolean,
        outputs: List<CommitmentOutput>
    ): CommitTx {
        val txNumber = obscuredCommitTxNumber(commitTxNumber, localIsChannelOpener, localPaymentBasePoint, remotePaymentBasePoint)
        val (sequence, locktime) = encodeTxNumber(txNumber)
        val tx = Transaction(
            version = 2,
            txIn = listOf(TxIn(commitTxInput.outPoint, ByteVector.empty, sequence = sequence)),
            txOut = outputs.map { it.txOut },
            lockTime = locktime
        )
        return CommitTx(commitTxInput, tx)
    }

    fun makeHtlcTxs(
        commitTx: Transaction,
        outputs: List<CommitmentOutput>,
        commitmentFormat: CommitmentFormat
    ): List<HtlcTx> {
        return outputs.mapIndexedNotNull { outputIndex, output ->
            when (output) {
                is OutHtlc -> HtlcTimeoutTx.createUnsignedTx(commitTx, output, outputIndex, commitmentFormat)
                is InHtlc -> HtlcSuccessTx.createUnsignedTx(commitTx, output, outputIndex, commitmentFormat)
                else -> null
            }
        }
    }

    sealed class ClosingTxFee {
        data class PaidByUs(val fee: Satoshi) : ClosingTxFee()
        data class PaidByThem(val fee: Satoshi) : ClosingTxFee()
    }

    /** Each closing attempt can result in multiple potential closing transactions, depending on which outputs are included. */
    data class ClosingTxs(val localAndRemote: ClosingTx?, val localOnly: ClosingTx?, val remoteOnly: ClosingTx?) {
        val preferred: ClosingTx? = localAndRemote ?: localOnly ?: remoteOnly
        val all: List<ClosingTx> = listOfNotNull(localAndRemote, localOnly, remoteOnly)

        override fun toString(): String = "localAndRemote=${localAndRemote?.tx?.toString()}, localOnly=${localOnly?.tx?.toString()}, remoteOnly=${remoteOnly?.tx?.toString()}"
    }

    fun makeClosingTxs(input: InputInfo, spec: CommitmentSpec, fee: ClosingTxFee, lockTime: Long, localScriptPubKey: ByteVector, remoteScriptPubKey: ByteVector): ClosingTxs {
        require(spec.htlcs.isEmpty()) { "there shouldn't be any pending htlcs" }

        val txNoOutput = Transaction(2, listOf(TxIn(input.outPoint, listOf(), sequence = 0xFFFFFFFDL)), listOf(), lockTime)

        // We compute the remaining balance for each side after paying the closing fees.
        // This lets us decide whether outputs can be included in the closing transaction or not.
        val (toLocalAmount, toRemoteAmount) = when (fee) {
            is ClosingTxFee.PaidByUs -> Pair(spec.toLocal.truncateToSatoshi() - fee.fee, spec.toRemote.truncateToSatoshi())
            is ClosingTxFee.PaidByThem -> Pair(spec.toLocal.truncateToSatoshi(), spec.toRemote.truncateToSatoshi() - fee.fee)
        }
        val toLocalOutput = when {
            toLocalAmount >= dustLimit(localScriptPubKey) -> TxOut(if (isOpReturn(localScriptPubKey)) 0.sat else toLocalAmount, localScriptPubKey)
            else -> null
        }
        val toRemoteOutput = when {
            toRemoteAmount >= dustLimit(remoteScriptPubKey) -> TxOut(if (isOpReturn(remoteScriptPubKey)) 0.sat else toRemoteAmount, remoteScriptPubKey)
            else -> null
        }
        // We may create multiple closing transactions based on which outputs may be included.
        return when {
            toLocalOutput != null && toRemoteOutput != null -> {
                val txLocalAndRemote = LexicographicalOrdering.sort(txNoOutput.copy(txOut = listOf(toLocalOutput, toRemoteOutput)))
                val toLocalIndex = when (val i = findPubKeyScriptIndex(txLocalAndRemote, localScriptPubKey)) {
                    is Either.Left -> null
                    is Either.Right -> i.value
                }
                ClosingTxs(
                    localAndRemote = ClosingTx(input, txLocalAndRemote, toLocalIndex),
                    // We also provide a version of the transaction without the remote output, which they may want to omit if not economical to spend.
                    localOnly = ClosingTx(input, txNoOutput.copy(txOut = listOf(toLocalOutput)), 0),
                    remoteOnly = null
                )
            }
            toLocalOutput != null -> ClosingTxs(
                localAndRemote = null,
                localOnly = ClosingTx(input, txNoOutput.copy(txOut = listOf(toLocalOutput)), 0),
                remoteOnly = null,
            )
            toRemoteOutput != null -> ClosingTxs(
                localAndRemote = null,
                localOnly = null,
                remoteOnly = ClosingTx(input, txNoOutput.copy(txOut = listOf(toRemoteOutput)), null)
            )
            else -> ClosingTxs(localAndRemote = null, localOnly = null, remoteOnly = null)
        }
    }

    /** We skip creating transactions spending commitment outputs when the remaining amount is below dust. */
    private fun <T : TransactionWithInputInfo> skipTxIfBelowDust(txInfo: T, dustLimit: Satoshi): Either<TxGenerationSkipped, T> {
        return when {
            txInfo.tx.txOut.first().amount < dustLimit -> Either.Left(TxGenerationSkipped.AmountBelowDustLimit)
            else -> Either.Right(txInfo)
        }
    }

    private fun findPubKeyScriptIndex(tx: Transaction, pubkeyScript: ByteVector): Either<TxGenerationSkipped, Int> {
        val outputIndex = tx.txOut.indexOfFirst { txOut -> txOut.publicKeyScript == pubkeyScript }
        return if (outputIndex >= 0) {
            Either.Right(outputIndex)
        } else {
            Either.Left(TxGenerationSkipped.OutputNotFound)
        }
    }

    /**
     * This default sig takes 72B when encoded in DER (incl. 1B for the trailing sig hash), it is used for fee estimation
     * It is 72 bytes because our signatures are normalized (low-s) and will take up 72 bytes at most in DER format
     */
    val PlaceHolderSig = ByteVector64(ByteArray(64) { 0xaa.toByte() })
        .also { check(Scripts.der(it, SigHash.SIGHASH_ALL).size() == 72) { "Should be 72 bytes but is ${Scripts.der(it, SigHash.SIGHASH_ALL).size()} bytes" } }

}
