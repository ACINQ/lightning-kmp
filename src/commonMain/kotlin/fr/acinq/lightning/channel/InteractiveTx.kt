package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Script.tail
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.crypto.musig2.Musig2
import fr.acinq.bitcoin.crypto.musig2.SecretNonce
import fr.acinq.bitcoin.utils.getOrDefault
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.bitcoin.utils.Either
import fr.acinq.bitcoin.utils.Try
import fr.acinq.bitcoin.utils.runTrying
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.crypto.Bolt3Derivation.deriveForCommitment
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.logging.*
import fr.acinq.lightning.transactions.CommitmentSpec
import fr.acinq.lightning.transactions.DirectedHtlc
import fr.acinq.lightning.transactions.Scripts
import fr.acinq.lightning.transactions.SwapInProtocol
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.transactions.*
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*
import kotlinx.coroutines.CompletableDeferred

/**
 * Created by t-bast on 22/08/2022.
 */

/** An input that is already shared between participants (e.g. the current funding output when doing a splice). */
sealed class SharedFundingInput {
    abstract val info: Transactions.InputInfo
    abstract val weight: Int
    abstract fun sign(channelKeys: KeyManager.ChannelKeys, tx: Transaction): ByteVector64

    data class Multisig2of2(override val info: Transactions.InputInfo, val fundingTxIndex: Long, val remoteFundingPubkey: PublicKey) : SharedFundingInput() {

        constructor(commitment: Commitment) : this(
            info = commitment.commitInput,
            fundingTxIndex = commitment.fundingTxIndex,
            remoteFundingPubkey = commitment.remoteFundingPubkey
        )

        // This value was computed assuming 73 bytes signatures (worst-case scenario).
        override val weight: Int = Multisig2of2.weight

        override fun sign(channelKeys: KeyManager.ChannelKeys, tx: Transaction): ByteVector64 {
            val fundingKey = channelKeys.fundingKey(fundingTxIndex)
            return fundingKey.sign(Transactions.TransactionWithInputInfo.SpliceTx(info, tx))
        }

        companion object {
            const val weight: Int = 388
        }
    }
}

/** The current balances of a [[SharedFundingInput]]. */
data class SharedFundingInputBalances(val toLocal: MilliSatoshi, val toRemote: MilliSatoshi, val toHtlcs: MilliSatoshi) {
    val fundingAmount: Satoshi = (toLocal + toRemote + toHtlcs).truncateToSatoshi()
}

/**
 * @param channelId id of the channel.
 * @param isInitiator true if we initiated the protocol, in which case we will pay fees for the shared parts of the transaction.
 * @param localContribution amount contributed by us to the shared output (can be negative when removing funds from an existing channel).
 * @param remoteContribution amount contributed by our peer to the shared output (can be negative when removing funds from an existing channel).
 * @param sharedInput previous input shared between the two participants (e.g. previous funding output when splicing).
 * @param remoteFundingPubkey public key provided by our peer, that will be combined with ours to create the script of the shared output.
 * @param localOutputs outputs to be added to the shared transaction (e.g. splice-out).
 * @param lockTime transaction lock time.
 * @param dustLimit outputs below this value are considered invalid.
 * @param targetFeerate transaction feerate.
 */
data class InteractiveTxParams(
    val channelId: ByteVector32,
    val isInitiator: Boolean,
    val localContribution: Satoshi,
    val remoteContribution: Satoshi,
    val sharedInput: SharedFundingInput?,
    val remoteFundingPubkey: PublicKey,
    val localOutputs: List<TxOut>,
    val lockTime: Long,
    val dustLimit: Satoshi,
    val targetFeerate: FeeratePerKw
) {
    constructor(channelId: ByteVector32, isInitiator: Boolean, localContribution: Satoshi, remoteContribution: Satoshi, remoteFundingPubKey: PublicKey, lockTime: Long, dustLimit: Satoshi, targetFeerate: FeeratePerKw) :
            this(channelId, isInitiator, localContribution, remoteContribution, null, remoteFundingPubKey, listOf(), lockTime, dustLimit, targetFeerate)

    /** Amount of the new funding output, which is the sum of the shared input, if any, and both sides' contributions. */
    val fundingAmount: Satoshi = (sharedInput?.info?.txOut?.amount ?: 0.sat) + localContribution + remoteContribution

    // BOLT 2: MUST set `feerate` greater than or equal to 25/24 times the `feerate` of the previously constructed transaction, rounded down.
    val minNextFeerate: FeeratePerKw = targetFeerate * 25 / 24

    // BOLT 2: the initiator's serial IDs MUST use even values and the non-initiator odd values.
    val serialIdParity = if (isInitiator) 0 else 1

    fun fundingPubkeyScript(channelKeys: KeyManager.ChannelKeys): ByteVector {
        val fundingTxIndex = (sharedInput as? SharedFundingInput.Multisig2of2)?.let { it.fundingTxIndex + 1 } ?: 0
        return Helpers.Funding.makeFundingPubKeyScript(channelKeys.fundingPubKey(fundingTxIndex), remoteFundingPubkey)
    }
}

sealed class InteractiveTxInput {
    abstract val serialId: Long
    abstract val outPoint: OutPoint
    abstract val sequence: UInt
    abstract val txOut: TxOut

    sealed interface Outgoing
    sealed interface Incoming

    sealed class Local : InteractiveTxInput(), Outgoing {
        abstract val previousTx: Transaction
        abstract val previousTxOutput: Long
        override val outPoint: OutPoint get() = OutPoint(previousTx, previousTxOutput)
        override val txOut: TxOut get() = previousTx.txOut[previousTxOutput.toInt()]
    }

    /** A local-only input that funds the interactive transaction. */
    data class LocalOnly(override val serialId: Long, override val previousTx: Transaction, override val previousTxOutput: Long, override val sequence: UInt) : Local()

    /** A local input that funds the interactive transaction, coming from a 2-of-2 legacy (pay2wsh) swap-in transaction. */
    data class LocalLegacySwapIn(
        override val serialId: Long,
        override val previousTx: Transaction,
        override val previousTxOutput: Long,
        override val sequence: UInt,
        val userKey: PublicKey,
        val serverKey: PublicKey,
        val refundDelay: Int
    ) : Local()

    /** A local input that funds the interactive transaction, coming from a 2-of-2 swap-in transaction. */
    data class LocalSwapIn(
        override val serialId: Long,
        override val previousTx: Transaction,
        override val previousTxOutput: Long,
        override val sequence: UInt,
        val addressIndex: Int,
        val userKey: PublicKey,
        val serverKey: PublicKey,
        val userRefundKey: PublicKey,
        val refundDelay: Int
    ) : Local()

    /**
     * A remote input that funds the interactive transaction.
     * We only keep the data we need from our peer's TxAddInput to avoid storing potentially large messages in our DB.
     */
    sealed class Remote : InteractiveTxInput(), Incoming

    /** A remote-only input that funds the interactive transaction. */
    data class RemoteOnly(override val serialId: Long, override val outPoint: OutPoint, override val txOut: TxOut, override val sequence: UInt) : Remote()

    /** A remote input from a legacy (pay2wsh) swap-in: our peer needs our signature to build a witness for that input. */
    data class RemoteLegacySwapIn(
        override val serialId: Long,
        override val outPoint: OutPoint,
        override val txOut: TxOut,
        override val sequence: UInt,
        val userKey: PublicKey,
        val serverKey: PublicKey,
        val refundDelay: Int
    ) : Remote()

    /** A remote input from a swap-in: our peer needs our signature to build a witness for that input. */
    data class RemoteSwapIn(
        override val serialId: Long,
        override val outPoint: OutPoint,
        override val txOut: TxOut,
        override val sequence: UInt,
        val userKey: PublicKey,
        val serverKey: PublicKey,
        val userRefundKey: PublicKey,
        val refundDelay: Int
    ) : Remote()

    /** The shared input can be added by us or by our peer, depending on who initiated the protocol. */
    data class Shared(
        override val serialId: Long,
        override val outPoint: OutPoint,
        val publicKeyScript: ByteVector,
        override val sequence: UInt,
        val localAmount: MilliSatoshi,
        val remoteAmount: MilliSatoshi,
        val htlcAmount: MilliSatoshi
    ) : InteractiveTxInput(), Incoming, Outgoing {
        override val txOut: TxOut get() = TxOut((localAmount + remoteAmount + htlcAmount).truncateToSatoshi(), publicKeyScript)
    }
}

sealed class InteractiveTxOutput {
    abstract val serialId: Long
    abstract val amount: Satoshi
    abstract val pubkeyScript: ByteVector

    sealed interface Outgoing
    sealed interface Incoming

    /** A local-only output of the interactive transaction. */
    sealed class Local : InteractiveTxOutput(), Outgoing {
        data class Change(override val serialId: Long, override val amount: Satoshi, override val pubkeyScript: ByteVector) : Local()
        data class NonChange(override val serialId: Long, override val amount: Satoshi, override val pubkeyScript: ByteVector) : Local()
    }

    /**
     * A remote-only output of the interactive transaction.
     * We only keep the data we need from our peer's TxAddOutput to avoid storing potentially large messages in our DB.
     */
    data class Remote(override val serialId: Long, override val amount: Satoshi, override val pubkeyScript: ByteVector) : InteractiveTxOutput(), Incoming

    /** The shared output can be added by us or by our peer, depending on who initiated the protocol. */
    data class Shared(override val serialId: Long, override val pubkeyScript: ByteVector, val localAmount: MilliSatoshi, val remoteAmount: MilliSatoshi, val htlcAmount: MilliSatoshi) : InteractiveTxOutput(), Incoming, Outgoing {
        // Note that the truncation is a no-op: the sum of balances in a channel must be a satoshi amount.
        override val amount: Satoshi = (localAmount + remoteAmount + htlcAmount).truncateToSatoshi()
    }
}

sealed class FundingContributionFailure {
    // @formatter:off
    data class InputOutOfBounds(val txId: TxId, val outputIndex: Int) : FundingContributionFailure() { override fun toString(): String = "invalid input $txId:$outputIndex (out of bounds)" }
    data class InputBelowDust(val txId: TxId, val outputIndex: Int, val amount: Satoshi, val dustLimit: Satoshi) : FundingContributionFailure() { override fun toString(): String = "invalid input $txId:$outputIndex (below dust: amount=$amount, dust=$dustLimit)" }
    data class InputTxTooLarge(val tx: Transaction) : FundingContributionFailure() { override fun toString(): String = "invalid input tx ${tx.txid} (too large)" }
    data class NotEnoughFunding(val fundingAmount: Satoshi, val nonFundingAmount: Satoshi, val providedAmount: Satoshi) : FundingContributionFailure() { override fun toString(): String = "not enough funds provided (expected at least $fundingAmount + $nonFundingAmount, got $providedAmount)" }
    data class NotEnoughFees(val currentFees: Satoshi, val expectedFees: Satoshi) : FundingContributionFailure() { override fun toString(): String = "not enough funds to pay fees (expected at least $expectedFees, got $currentFees)" }
    data class InvalidFundingBalances(val fundingAmount: Satoshi, val localBalance: MilliSatoshi, val remoteBalance: MilliSatoshi) : FundingContributionFailure() { override fun toString(): String = "invalid balances funding_amount=$fundingAmount local=$localBalance remote=$remoteBalance" }
    // @formatter:on
}

/** Inputs and outputs we contribute to the funding transaction. */
data class FundingContributions(val inputs: List<InteractiveTxInput.Outgoing>, val outputs: List<InteractiveTxOutput.Outgoing>) {
    companion object {
        /** Compute our local splice contribution using all the funds available in our wallet. */
        fun computeSpliceContribution(isInitiator: Boolean, commitment: Commitment, walletInputs: List<WalletState.Utxo>, localOutputs: List<TxOut>, targetFeerate: FeeratePerKw): Satoshi {
            val weight = computeWeightPaid(isInitiator, commitment, walletInputs, localOutputs)
            val fees = Transactions.weight2fee(targetFeerate, weight)
            return walletInputs.map { it.amount }.sum() - localOutputs.map { it.amount }.sum() - fees
        }

        /**
         * @param walletInputs 2-of-2 swap-in wallet inputs.
         */
        fun create(channelKeys: KeyManager.ChannelKeys, swapInKeys: KeyManager.SwapInOnChainKeys, params: InteractiveTxParams, walletInputs: List<WalletState.Utxo>): Either<FundingContributionFailure, FundingContributions> =
            create(channelKeys, swapInKeys, params, null, walletInputs, listOf())

        /**
         * @param sharedUtxo previous input shared between the two participants (e.g. previous funding output when splicing) and our corresponding balance.
         * @param walletInputs 2-of-2 swap-in wallet inputs.
         * @param localOutputs outputs to be added to the shared transaction (e.g. splice-out).
         * @param changePubKey if provided, a corresponding p2wpkh change output will be created.
         */
        fun create(
            channelKeys: KeyManager.ChannelKeys,
            swapInKeys: KeyManager.SwapInOnChainKeys,
            params: InteractiveTxParams,
            sharedUtxo: Pair<SharedFundingInput, SharedFundingInputBalances>?,
            walletInputs: List<WalletState.Utxo>,
            localOutputs: List<TxOut>,
            changePubKey: PublicKey? = null
        ): Either<FundingContributionFailure, FundingContributions> {
            walletInputs.forEach { utxo ->
                if (utxo.previousTx.txOut.size <= utxo.outputIndex) return Either.Left(FundingContributionFailure.InputOutOfBounds(utxo.txId, utxo.outputIndex))
                val dustLimit = Transactions.dustLimit(utxo.previousTx.txOut[utxo.outputIndex].publicKeyScript)
                if (utxo.amount < dustLimit) return Either.Left(FundingContributionFailure.InputBelowDust(utxo.txId, utxo.outputIndex, utxo.amount, dustLimit))
                if (Transaction.write(utxo.previousTx.stripInputWitnesses()).size > 65_000) return Either.Left(FundingContributionFailure.InputTxTooLarge(utxo.previousTx))
            }
            val previousFundingAmount = sharedUtxo?.second?.fundingAmount ?: 0.sat
            val totalAmountIn = previousFundingAmount + walletInputs.map { it.amount }.sum()
            val totalAmountOut = previousFundingAmount + params.localContribution + localOutputs.map { it.amount }.sum()
            if (totalAmountIn < totalAmountOut || params.fundingAmount < params.dustLimit) {
                return Either.Left(FundingContributionFailure.NotEnoughFunding(params.localContribution, localOutputs.map { it.amount }.sum(), totalAmountIn))
            }

            // We compute the fees that we should pay in the shared transaction.
            val fundingPubkeyScript = params.fundingPubkeyScript(channelKeys)
            val weightWithoutChange = computeWeightPaid(params.isInitiator, sharedUtxo?.first, fundingPubkeyScript, walletInputs, localOutputs)
            val weightWithChange = computeWeightPaid(params.isInitiator, sharedUtxo?.first, fundingPubkeyScript, walletInputs, localOutputs + listOf(TxOut(0.sat, Script.pay2wpkh(Transactions.PlaceHolderPubKey))))
            val feesWithoutChange = totalAmountIn - totalAmountOut
            // If we're not the initiator, we don't return an error when we're unable to meet the desired feerate.
            if (params.isInitiator && feesWithoutChange < Transactions.weight2fee(params.targetFeerate, weightWithoutChange)) {
                return Either.Left(FundingContributionFailure.NotEnoughFees(feesWithoutChange, Transactions.weight2fee(params.targetFeerate, weightWithoutChange)))
            }

            val nextLocalBalance = (sharedUtxo?.second?.toLocal ?: 0.msat) + params.localContribution.toMilliSatoshi()
            val nextRemoteBalance = (sharedUtxo?.second?.toRemote ?: 0.msat) + params.remoteContribution.toMilliSatoshi()
            if (nextLocalBalance < 0.msat || nextRemoteBalance < 0.msat) {
                return Either.Left(FundingContributionFailure.InvalidFundingBalances(params.fundingAmount, nextLocalBalance, nextRemoteBalance))
            }

            val sharedOutput = listOf(InteractiveTxOutput.Shared(0, fundingPubkeyScript, nextLocalBalance, nextRemoteBalance, sharedUtxo?.second?.toHtlcs ?: 0.msat))
            val nonChangeOutputs = localOutputs.map { o -> InteractiveTxOutput.Local.NonChange(0, o.amount, o.publicKeyScript) }
            val changeOutput = when (changePubKey) {
                null -> listOf()
                else -> {
                    val changeAmount = totalAmountIn - totalAmountOut - Transactions.weight2fee(params.targetFeerate, weightWithChange)
                    if (params.dustLimit <= changeAmount) {
                        listOf(InteractiveTxOutput.Local.Change(0, changeAmount, Script.write(Script.pay2wpkh(changePubKey)).byteVector()))
                    } else {
                        listOf()
                    }
                }
            }
            val sharedInput = sharedUtxo?.let { (i, balances) -> listOf(InteractiveTxInput.Shared(0, i.info.outPoint, i.info.txOut.publicKeyScript, 0xfffffffdU, balances.toLocal, balances.toRemote, balances.toHtlcs)) } ?: listOf()
            val localInputs = walletInputs.map { i ->
                when (val meta = i.addressMeta) {
                    is WalletState.AddressMeta.Derived -> {
                        val swapInProtocol = swapInKeys.getSwapInProtocol(meta.index)
                        InteractiveTxInput.LocalSwapIn(
                            0,
                            i.previousTx.stripInputWitnesses(),
                            i.outputIndex.toLong(),
                            0xfffffffdU,
                            addressIndex = meta.index,
                            swapInProtocol.userPublicKey, swapInProtocol.serverPublicKey, swapInProtocol.userRefundKey, swapInProtocol.refundDelay
                        )
                    }
                    else -> InteractiveTxInput.LocalLegacySwapIn(
                        0,
                        i.previousTx.stripInputWitnesses(),
                        i.outputIndex.toLong(),
                        0xfffffffdU,
                        swapInKeys.userPublicKey, swapInKeys.remoteServerPublicKey, swapInKeys.refundDelay
                    )
                }
            }
            return if (params.isInitiator) {
                Either.Right(sortFundingContributions(params, sharedInput + localInputs, sharedOutput + nonChangeOutputs + changeOutput))
            } else {
                Either.Right(sortFundingContributions(params, localInputs, nonChangeOutputs + changeOutput))
            }
        }

        /** Strip input witnesses to save space (there is a max size on txs due to lightning message limits). */
        fun Transaction.stripInputWitnesses(): Transaction = copy(txIn = txIn.map { it.updateWitness(ScriptWitness.empty) })

        /** Compute the weight we need to pay on-chain fees for. */
        private fun computeWeightPaid(isInitiator: Boolean, sharedInput: SharedFundingInput?, sharedOutputScript: ByteVector, walletInputs: List<WalletState.Utxo>, localOutputs: List<TxOut>): Int {
            val walletInputsWeight = weight(walletInputs)
            val localOutputsWeight = localOutputs.sumOf { it.weight() }
            return if (isInitiator) {
                // The initiator must add the shared input, the shared output and pay for the fees of the common transaction fields.
                val sharedInputWeight = sharedInput?.weight ?: 0
                val dummyTx = Transaction(2, emptyList(), listOf(TxOut(0.sat, sharedOutputScript)), 0)
                sharedInputWeight + walletInputsWeight + localOutputsWeight + dummyTx.weight()
            } else {
                // The non-initiator only pays for the weights of their own inputs and outputs.
                walletInputsWeight + localOutputsWeight
            }
        }

        fun computeWeightPaid(isInitiator: Boolean, commitment: Commitment, walletInputs: List<WalletState.Utxo>, localOutputs: List<TxOut>): Int =
            computeWeightPaid(
                isInitiator,
                SharedFundingInput.Multisig2of2(commitment.commitInput, commitment.fundingTxIndex, Transactions.PlaceHolderPubKey),
                commitment.commitInput.txOut.publicKeyScript,
                walletInputs,
                localOutputs
            )

        fun weight(walletInputs: List<WalletState.Utxo>): Int = walletInputs.sumOf {
            when {
                Script.isPay2wsh(it.previousTx.txOut[it.outputIndex].publicKeyScript.toByteArray()) -> Transactions.swapInputWeightLegacy
                else -> Transactions.swapInputWeight
            }
        }

        /** We always randomize the order of inputs and outputs. */
        private fun sortFundingContributions(params: InteractiveTxParams, inputs: List<InteractiveTxInput.Outgoing>, outputs: List<InteractiveTxOutput.Outgoing>): FundingContributions {
            val sortedInputs = inputs.shuffled().mapIndexed { i, input ->
                val serialId = 2 * i.toLong() + params.serialIdParity
                when (input) {
                    is InteractiveTxInput.LocalOnly -> input.copy(serialId = serialId)
                    is InteractiveTxInput.LocalLegacySwapIn -> input.copy(serialId = serialId)
                    is InteractiveTxInput.LocalSwapIn -> input.copy(serialId = serialId)
                    is InteractiveTxInput.Shared -> input.copy(serialId = serialId)
                }
            }
            val sortedOutputs = outputs.shuffled().mapIndexed { i, output ->
                val serialId = 2 * (i + inputs.size).toLong() + params.serialIdParity
                when (output) {
                    is InteractiveTxOutput.Local.Change -> output.copy(serialId = serialId)
                    is InteractiveTxOutput.Local.NonChange -> output.copy(serialId = serialId)
                    is InteractiveTxOutput.Shared -> output.copy(serialId = serialId)
                }
            }
            return FundingContributions(sortedInputs, sortedOutputs)
        }
    }
}

/** Unsigned transaction created collaboratively. */
data class SharedTransaction(
    val sharedInput: InteractiveTxInput.Shared?, val sharedOutput: InteractiveTxOutput.Shared,
    val localInputs: List<InteractiveTxInput.Local>, val remoteInputs: List<InteractiveTxInput.Remote>,
    val localOutputs: List<InteractiveTxOutput.Local>, val remoteOutputs: List<InteractiveTxOutput.Remote>,
    val lockTime: Long
) {
    // Note that the satoshi truncations are no-ops: the sum of balances in a channel must be a satoshi amount.
    val localAmountIn: MilliSatoshi = (sharedInput?.localAmount ?: 0.msat) + localInputs.map { i -> i.txOut.amount }.sum().toMilliSatoshi()
    val remoteAmountIn: MilliSatoshi = (sharedInput?.remoteAmount ?: 0.msat) + remoteInputs.map { i -> i.txOut.amount }.sum().toMilliSatoshi()
    val totalAmountIn: Satoshi = (localAmountIn + remoteAmountIn).truncateToSatoshi()
    val localAmountOut: MilliSatoshi = sharedOutput.localAmount + localOutputs.map { o -> o.amount }.sum().toMilliSatoshi()
    val remoteAmountOut: MilliSatoshi = sharedOutput.remoteAmount + remoteOutputs.map { o -> o.amount }.sum().toMilliSatoshi()
    val localFees: MilliSatoshi = localAmountIn - localAmountOut
    val remoteFees: MilliSatoshi = remoteAmountIn - remoteAmountOut
    val fees: Satoshi = (localFees + remoteFees).truncateToSatoshi()

    // tx outputs spent by this transaction
    val spentOutputs: Map<OutPoint, TxOut> = run {
        val sharedOutput = sharedInput?.let { i -> mapOf(i.outPoint to i.txOut) } ?: mapOf()
        val localOutputs = localInputs.associate { i -> i.outPoint to i.txOut }
        val remoteOutputs = remoteInputs.associate { i -> i.outPoint to i.txOut }
        sharedOutput + localOutputs + remoteOutputs
    }

    fun localOnlyInputs(): List<InteractiveTxInput.LocalOnly> = localInputs.filterIsInstance<InteractiveTxInput.LocalOnly>()

    fun remoteOnlyInputs(): List<InteractiveTxInput.RemoteOnly> = remoteInputs.filterIsInstance<InteractiveTxInput.RemoteOnly>()

    fun buildUnsignedTx(): Transaction {
        val sharedTxIn = sharedInput?.let { i -> listOf(Pair(i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence.toLong()))) } ?: listOf()
        val localTxIn = localInputs.map { i -> Pair(i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence.toLong())) }
        val remoteTxIn = remoteInputs.map { i -> Pair(i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence.toLong())) }
        val inputs = (sharedTxIn + localTxIn + remoteTxIn).sortedBy { (serialId, _) -> serialId }.map { (_, txIn) -> txIn }
        val sharedTxOut = listOf(Pair(sharedOutput.serialId, TxOut(sharedOutput.amount, sharedOutput.pubkeyScript)))
        val localTxOut = localOutputs.map { o -> Pair(o.serialId, TxOut(o.amount, o.pubkeyScript)) }
        val remoteTxOut = remoteOutputs.map { o -> Pair(o.serialId, TxOut(o.amount, o.pubkeyScript)) }
        val outputs = (sharedTxOut + localTxOut + remoteTxOut).sortedBy { (serialId, _) -> serialId }.map { (_, txOut) -> txOut }
        return Transaction(2, inputs, outputs, lockTime)
    }

    fun sign(session: InteractiveTxSession, keyManager: KeyManager, fundingParams: InteractiveTxParams, localParams: LocalParams, remoteNodeId: PublicKey): PartiallySignedSharedTransaction {
        val unsignedTx = buildUnsignedTx()
        val sharedSig = fundingParams.sharedInput?.sign(keyManager.channelKeys(localParams.fundingKeyPath), unsignedTx)
        // NB: the order in this list must match the order of the transaction's inputs.
        val previousOutputs = unsignedTx.txIn.map { spentOutputs[it.outPoint]!! }

        // Public nonces for all the musig2 swap-in inputs (local and remote).
        // We have verified that one nonce was provided for each input when receiving `tx_complete`.
        val remoteNonces: Map<Long, IndividualNonce> = when (session.txCompleteReceived) {
            null -> mapOf()
            else -> (localInputs.filterIsInstance<InteractiveTxInput.LocalSwapIn>() + remoteInputs.filterIsInstance<InteractiveTxInput.RemoteSwapIn>())
                .sortedBy { it.serialId }
                .zip(session.txCompleteReceived.publicNonces)
                .associate { it.first.serialId to it.second }
        }

        // If we are swapping funds in, we provide our partial signatures to the corresponding inputs.
        val legacySwapUserSigs = unsignedTx.txIn.mapIndexed { i, txIn ->
            localInputs
                .filterIsInstance<InteractiveTxInput.LocalLegacySwapIn>()
                .find { txIn.outPoint == it.outPoint }
                ?.let { input -> keyManager.swapInOnChainWallet.signSwapInputUserLegacy(unsignedTx, i, input.previousTx.txOut) }
        }.filterNotNull()
        val swapUserPartialSigs = unsignedTx.txIn.mapIndexed { i, txIn ->
            localInputs
                .filterIsInstance<InteractiveTxInput.LocalSwapIn>()
                .find { txIn.outPoint == it.outPoint }
                ?.let { input ->
                    // We generate our secret nonce when sending the corresponding input, we know it exists in the map.
                    val userNonce = session.secretNonces[input.serialId]!!
                    val serverNonce = remoteNonces[input.serialId]!!
                    keyManager.swapInOnChainWallet.signSwapInputUser(unsignedTx, i, previousOutputs, userNonce.first, userNonce.second, serverNonce, input.addressIndex)
                        .map { TxSignaturesTlv.PartialSignature(it, userNonce.second, serverNonce) }
                        .getOrDefault(null)
                }
        }.filterNotNull()

        // If the remote is swapping funds in, they'll need our partial signatures to finalize their witness.
        val legacySwapServerSigs = unsignedTx.txIn.mapIndexed { i, txIn ->
            remoteInputs
                .filterIsInstance<InteractiveTxInput.RemoteLegacySwapIn>()
                .find { txIn.outPoint == it.outPoint }
                ?.let { input ->
                    val serverKey = keyManager.swapInOnChainWallet.localServerPrivateKey(remoteNodeId)
                    val swapInProtocol = SwapInProtocolLegacy(input.userKey, serverKey.publicKey(), input.refundDelay)
                    swapInProtocol.signSwapInputServer(unsignedTx, i, input.txOut, serverKey)
                }
        }.filterNotNull()
        val swapServerPartialSigs = unsignedTx.txIn.mapIndexed { i, txIn ->
            remoteInputs
                .filterIsInstance<InteractiveTxInput.RemoteSwapIn>()
                .find { txIn.outPoint == it.outPoint }
                ?.let { input ->
                    val serverKey = keyManager.swapInOnChainWallet.localServerPrivateKey(remoteNodeId)
                    val swapInProtocol = SwapInProtocol(input.userKey, serverKey.publicKey(), input.userRefundKey, input.refundDelay)
                    // We generate our secret nonce when receiving the corresponding input, we know it exists in the map.
                    val serverNonce = session.secretNonces[input.serialId]!!
                    val userNonce = remoteNonces[input.serialId]!!
                    swapInProtocol.signSwapInputServer(unsignedTx, i, previousOutputs, serverKey, serverNonce.first, userNonce, serverNonce.second)
                        .map { TxSignaturesTlv.PartialSignature(it, userNonce, serverNonce.second) }
                        .getOrDefault(null)
                }
        }.filterNotNull()

        return PartiallySignedSharedTransaction(this, TxSignatures(fundingParams.channelId, unsignedTx, listOf(), sharedSig, legacySwapUserSigs, legacySwapServerSigs, swapUserPartialSigs, swapServerPartialSigs))
    }
}

/** Signed transaction created collaboratively. */
sealed class SignedSharedTransaction {
    abstract val txId: TxId
    abstract val tx: SharedTransaction
    abstract val localSigs: TxSignatures
    abstract val signedTx: Transaction?
}

data class PartiallySignedSharedTransaction(override val tx: SharedTransaction, override val localSigs: TxSignatures) : SignedSharedTransaction() {
    override val txId: TxId = localSigs.txId
    override val signedTx = null

    fun addRemoteSigs(channelKeys: KeyManager.ChannelKeys, fundingParams: InteractiveTxParams, remoteSigs: TxSignatures): FullySignedSharedTransaction? {
        if (localSigs.swapInUserSigs.size != tx.localInputs.filterIsInstance<InteractiveTxInput.LocalLegacySwapIn>().size) return null
        if (localSigs.swapInUserPartialSigs.size != tx.localInputs.filterIsInstance<InteractiveTxInput.LocalSwapIn>().size) return null
        if (remoteSigs.swapInUserSigs.size != tx.remoteInputs.filterIsInstance<InteractiveTxInput.RemoteLegacySwapIn>().size) return null
        if (remoteSigs.swapInUserPartialSigs.size != tx.remoteInputs.filterIsInstance<InteractiveTxInput.RemoteSwapIn>().size) return null
        if (remoteSigs.swapInServerSigs.size != tx.localInputs.filterIsInstance<InteractiveTxInput.LocalLegacySwapIn>().size) return null
        if (remoteSigs.swapInServerPartialSigs.size != tx.localInputs.filterIsInstance<InteractiveTxInput.LocalSwapIn>().size) return null
        if (remoteSigs.witnesses.size != tx.remoteOnlyInputs().size) return null
        if (remoteSigs.txId != localSigs.txId) return null
        val sharedSigs = fundingParams.sharedInput?.let {
            when (it) {
                is SharedFundingInput.Multisig2of2 -> Scripts.witness2of2(
                    localSigs.previousFundingTxSig ?: return null,
                    remoteSigs.previousFundingTxSig ?: return null,
                    channelKeys.fundingPubKey(it.fundingTxIndex),
                    it.remoteFundingPubkey,
                )
            }
        }
        val fullySignedTx = FullySignedSharedTransaction(tx, localSigs, remoteSigs, sharedSigs)
        return when (runTrying { Transaction.correctlySpends(fullySignedTx.signedTx, tx.spentOutputs, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS) }) {
            is Try.Success -> fullySignedTx
            is Try.Failure -> null
        }
    }
}

data class FullySignedSharedTransaction(override val tx: SharedTransaction, override val localSigs: TxSignatures, val remoteSigs: TxSignatures, val sharedSigs: ScriptWitness?) : SignedSharedTransaction() {
    override val signedTx = run {
        val unsignedTx = tx.buildUnsignedTx()
        // NB: the order in this list must match the order of the transaction's inputs.
        val previousOutputs = unsignedTx.txIn.map { tx.spentOutputs[it.outPoint]!! }
        val sharedTxIn = tx.sharedInput?.let { i -> listOf(Pair(i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence.toLong(), sharedSigs ?: ScriptWitness.empty))) } ?: listOf()
        val localOnlyTxIn = tx.localOnlyInputs().sortedBy { i -> i.serialId }.zip(localSigs.witnesses).map { (i, w) -> Pair(i.serialId, TxIn(OutPoint(i.previousTx, i.previousTxOutput), ByteVector.empty, i.sequence.toLong(), w)) }
        val localLegacySwapTxIn = tx.localInputs.filterIsInstance<InteractiveTxInput.LocalLegacySwapIn>().sortedBy { i -> i.serialId }.zip(localSigs.swapInUserSigs.zip(remoteSigs.swapInServerSigs)).map { (i, sigs) ->
            val (userSig, serverSig) = sigs
            val swapInProtocol = SwapInProtocolLegacy(i.userKey, i.serverKey, i.refundDelay)
            val witness = swapInProtocol.witness(userSig, serverSig)
            Pair(i.serialId, TxIn(OutPoint(i.previousTx, i.previousTxOutput), ByteVector.empty, i.sequence.toLong(), witness))
        }
        val localSwapTxIn = tx.localInputs.filterIsInstance<InteractiveTxInput.LocalSwapIn>().sortedBy { i -> i.serialId }.zip(localSigs.swapInUserPartialSigs.zip(remoteSigs.swapInServerPartialSigs)).map { (i, sigs) ->
            val (userSig, serverSig) = sigs
            val swapInProtocol = SwapInProtocol(i.userKey, i.serverKey, i.userRefundKey, i.refundDelay)
            // The remote partial signature may be invalid: when receiving their tx_signatures, we verify that the resulting transaction is valid.
            val witness = swapInProtocol.witness(unsignedTx, unsignedTx.txIn.indexOfFirst { it.outPoint == i.outPoint }, previousOutputs, userSig.localNonce, userSig.remoteNonce, userSig.sig, serverSig.sig).getOrDefault(ScriptWitness.empty)
            Pair(i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence.toLong(), witness))
        }
        val remoteOnlyTxIn = tx.remoteOnlyInputs().sortedBy { i -> i.serialId }.zip(remoteSigs.witnesses).map { (i, w) -> Pair(i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence.toLong(), w)) }
        val remoteLegacySwapTxIn = tx.remoteInputs.filterIsInstance<InteractiveTxInput.RemoteLegacySwapIn>().sortedBy { i -> i.serialId }.zip(remoteSigs.swapInUserSigs.zip(localSigs.swapInServerSigs)).map { (i, sigs) ->
            val (userSig, serverSig) = sigs
            val swapInProtocol = SwapInProtocolLegacy(i.userKey, i.serverKey, i.refundDelay)
            val witness = swapInProtocol.witness(userSig, serverSig)
            Pair(i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence.toLong(), witness))
        }
        val remoteSwapTxIn = tx.remoteInputs.filterIsInstance<InteractiveTxInput.RemoteSwapIn>().sortedBy { i -> i.serialId }.zip(remoteSigs.swapInUserPartialSigs.zip(localSigs.swapInServerPartialSigs)).map { (i, sigs) ->
            val (userSig, serverSig) = sigs
            val swapInProtocol = SwapInProtocol(i.userKey, i.serverKey, i.userRefundKey, i.refundDelay)
            // The remote partial signature may be invalid: when receiving their tx_signatures, we verify that the resulting transaction is valid.
            val witness = swapInProtocol.witness(unsignedTx, unsignedTx.txIn.indexOfFirst { it.outPoint == i.outPoint }, previousOutputs, userSig.localNonce, userSig.remoteNonce, userSig.sig, serverSig.sig).getOrDefault(ScriptWitness.empty)
            Pair(i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence.toLong(), witness))
        }
        val inputs = (sharedTxIn + localOnlyTxIn + localLegacySwapTxIn + localSwapTxIn + remoteOnlyTxIn + remoteLegacySwapTxIn + remoteSwapTxIn).sortedBy { (serialId, _) -> serialId }.map { (_, i) -> i }
        val sharedTxOut = listOf(Pair(tx.sharedOutput.serialId, TxOut(tx.sharedOutput.amount, tx.sharedOutput.pubkeyScript)))
        val localTxOut = tx.localOutputs.map { o -> Pair(o.serialId, TxOut(o.amount, o.pubkeyScript)) }
        val remoteTxOut = tx.remoteOutputs.map { o -> Pair(o.serialId, TxOut(o.amount, o.pubkeyScript)) }
        val outputs = (sharedTxOut + localTxOut + remoteTxOut).sortedBy { (serialId, _) -> serialId }.map { (_, o) -> o }
        Transaction(2, inputs, outputs, tx.lockTime)
    }
    override val txId: TxId = signedTx.txid
    val feerate: FeeratePerKw = Transactions.fee2rate(tx.fees, signedTx.weight())
}

sealed class InteractiveTxSessionAction {
    // @formatter:off
    data class SendMessage(val msg: InteractiveTxConstructionMessage) : InteractiveTxSessionAction()
    data class SignSharedTx(val sharedTx: SharedTransaction, val txComplete: TxComplete?) : InteractiveTxSessionAction()
    sealed class RemoteFailure : InteractiveTxSessionAction()
    data class InvalidSerialId(val channelId: ByteVector32, val serialId: Long) : RemoteFailure() { override fun toString(): String = "invalid serial_id=$serialId" }
    data class UnknownSerialId(val channelId: ByteVector32, val serialId: Long) : RemoteFailure() { override fun toString(): String = "unknown serial_id=$serialId" }
    data class TooManyInteractiveTxRounds(val channelId: ByteVector32) : RemoteFailure() { override fun toString(): String = "too many messages exchanged during interactive tx construction" }
    data class DuplicateSerialId(val channelId: ByteVector32, val serialId: Long) : RemoteFailure() { override fun toString(): String = "duplicate serial_id=$serialId" }
    data class DuplicateInput(val channelId: ByteVector32, val serialId: Long, val previousTxId: TxId, val previousTxOutput: Long) : RemoteFailure() { override fun toString(): String = "duplicate input $previousTxId:$previousTxOutput (serial_id=$serialId)" }
    data class InputOutOfBounds(val channelId: ByteVector32, val serialId: Long, val previousTxId: TxId, val previousTxOutput: Long) : RemoteFailure() { override fun toString(): String = "invalid input $previousTxId:$previousTxOutput (serial_id=$serialId)" }
    data class NonReplaceableInput(val channelId: ByteVector32, val serialId: Long, val previousTxId: TxId, val previousTxOutput: Long, val sequence: Long) : RemoteFailure() { override fun toString(): String = "$previousTxId:$previousTxOutput is not replaceable (serial_id=$serialId, nSequence=$sequence)" }
    data class NonSegwitInput(val channelId: ByteVector32, val serialId: Long, val previousTxId: TxId, val previousTxOutput: Long) : RemoteFailure() { override fun toString(): String = "$previousTxId:$previousTxOutput is not a native segwit input (serial_id=$serialId)" }
    data class PreviousTxMissing(val channelId: ByteVector32, val serialId: Long) : RemoteFailure() { override fun toString(): String = "previous tx missing from tx_add_input (serial_id=$serialId)" }
    data class InvalidSharedInput(val channelId: ByteVector32, val serialId: Long) : RemoteFailure() { override fun toString(): String = "invalid shared tx_add_input (serial_id=$serialId)" }
    data class OutputBelowDust(val channelId: ByteVector32, val serialId: Long, val amount: Satoshi, val dustLimit: Satoshi) : RemoteFailure() { override fun toString(): String = "invalid output amount=$amount below dust=$dustLimit (serial_id=$serialId)" }
    data class InvalidTxInputOutputCount(val channelId: ByteVector32, val inputCount: Int, val outputCount: Int) : RemoteFailure() { override fun toString(): String = "invalid number of inputs or outputs (inputCount=$inputCount, outputCount=$outputCount)" }
    data class InvalidTxBelowReserve(val channelId: ByteVector32, val remoteAmount: Satoshi, val reserve: Satoshi) : RemoteFailure() { override fun toString(): String = "peer takes too much funds out and falls below reverse ($remoteAmount < $reserve)" }
    data class InvalidTxSharedInput(val channelId: ByteVector32) : RemoteFailure() { override fun toString(): String = "shared input is missing or duplicated" }
    data class InvalidTxSharedOutput(val channelId: ByteVector32) : RemoteFailure() { override fun toString(): String = "shared output is missing or duplicated" }
    data class InvalidTxSharedAmount(val channelId: ByteVector32, val serialId: Long, val amount: Satoshi, val expected: Satoshi) : RemoteFailure() { override fun toString(): String = "invalid shared output amount=$amount expected=$expected (serial_id=$serialId)" }
    data class InvalidTxChangeAmount(val channelId: ByteVector32, val txId: TxId) : RemoteFailure() { override fun toString(): String = "change amount is too high (txId=$txId)" }
    data class InvalidTxWeight(val channelId: ByteVector32, val txId: TxId) : RemoteFailure() { override fun toString(): String = "transaction weight is too big for standardness rules (txId=$txId)" }
    data class InvalidTxFeerate(val channelId: ByteVector32, val txId: TxId, val targetFeerate: FeeratePerKw, val actualFeerate: FeeratePerKw) : RemoteFailure() { override fun toString(): String = "transaction feerate too low (txId=$txId, targetFeerate=$targetFeerate, actualFeerate=$actualFeerate" }
    data class InvalidTxDoesNotDoubleSpendPreviousTx(val channelId: ByteVector32, val txId: TxId, val previousTxId: TxId) : RemoteFailure() { override fun toString(): String = "transaction replacement with txId=$txId doesn't double-spend previous attempt (txId=$previousTxId)" }
    data class MissingNonce(val channelId: ByteVector32, val expected: Int, val received: Int): RemoteFailure() { override fun toString(): String = "some musig2 nonces are missing: we expected $expected nonces but received only $received" }
    // @formatter:on
}

data class InteractiveTxSession(
    val remoteNodeId: PublicKey,
    val channelKeys: KeyManager.ChannelKeys,
    val swapInKeys: KeyManager.SwapInOnChainKeys,
    val fundingParams: InteractiveTxParams,
    val previousFunding: SharedFundingInputBalances,
    val toSend: List<Either<InteractiveTxInput.Outgoing, InteractiveTxOutput.Outgoing>>,
    val previousTxs: List<SignedSharedTransaction> = listOf(),
    val localHtlcs: Set<DirectedHtlc>,
    val localInputs: List<InteractiveTxInput.Outgoing> = listOf(),
    val remoteInputs: List<InteractiveTxInput.Incoming> = listOf(),
    val localOutputs: List<InteractiveTxOutput.Outgoing> = listOf(),
    val remoteOutputs: List<InteractiveTxOutput.Incoming> = listOf(),
    val txCompleteSent: TxComplete? = null,
    val txCompleteReceived: TxComplete? = null,
    val inputsReceivedCount: Int = 0,
    val outputsReceivedCount: Int = 0,
    val secretNonces: Map<Long, Pair<SecretNonce, IndividualNonce>> = mapOf()
) {

    //                      Example flow:
    //     +-------+                             +-------+
    //     |       |-------- tx_add_input ------>|       |
    //     |       |<------- tx_add_input -------|       |
    //     |       |-------- tx_add_output ----->|       |
    //     |       |<------- tx_add_output ------|       |
    //     |       |-------- tx_add_input ------>|       |
    //     |   A   |<------- tx_complete --------|   B   |
    //     |       |-------- tx_remove_output -->|       |
    //     |       |<------- tx_add_output ------|       |
    //     |       |-------- tx_complete ------->|       |
    //     |       |<------- tx_complete --------|       |
    //     +-------+                             +-------+

    constructor(
        remoteNodeId: PublicKey,
        channelKeys: KeyManager.ChannelKeys,
        swapInKeys: KeyManager.SwapInOnChainKeys,
        fundingParams: InteractiveTxParams,
        previousLocalBalance: MilliSatoshi,
        previousRemoteBalance: MilliSatoshi,
        localHtlcs: Set<DirectedHtlc>,
        fundingContributions: FundingContributions,
        previousTxs: List<SignedSharedTransaction> = listOf()
    ) : this(
        remoteNodeId,
        channelKeys,
        swapInKeys,
        fundingParams,
        SharedFundingInputBalances(previousLocalBalance, previousRemoteBalance, localHtlcs.map { it.add.amountMsat }.sum()),
        fundingContributions.inputs.map { i -> Either.Left<InteractiveTxInput.Outgoing>(i) } + fundingContributions.outputs.map { o -> Either.Right<InteractiveTxOutput.Outgoing>(o) },
        previousTxs,
        localHtlcs
    )

    val isComplete: Boolean = txCompleteSent != null && txCompleteReceived != null

    fun send(): Pair<InteractiveTxSession, InteractiveTxSessionAction> {
        val msg = toSend.firstOrNull()
        return when (msg) {
            null -> {
                val localSwapIns = localInputs.filterIsInstance<InteractiveTxInput.LocalSwapIn>()
                val remoteSwapIns = remoteInputs.filterIsInstance<InteractiveTxInput.RemoteSwapIn>()
                val publicNonces = (localSwapIns + remoteSwapIns)
                    .map { it.serialId }
                    .sorted()
                    // We generate secret nonces whenever we send and receive tx_add_input, so we know they exist in the map.
                    .map { serialId -> secretNonces[serialId]!!.second }
                val txComplete = TxComplete(fundingParams.channelId, publicNonces)
                val next = copy(txCompleteSent = txComplete)
                if (next.isComplete) {
                    Pair(next, next.validateTx(txComplete))
                } else {
                    Pair(next, InteractiveTxSessionAction.SendMessage(txComplete))
                }
            }
            is Either.Left -> {
                val inputOutgoing = msg.value
                val txAddInput = when (inputOutgoing) {
                    is InteractiveTxInput.LocalOnly -> TxAddInput(fundingParams.channelId, inputOutgoing.serialId, inputOutgoing.previousTx, inputOutgoing.previousTxOutput, inputOutgoing.sequence)
                    is InteractiveTxInput.LocalLegacySwapIn -> {
                        val swapInParams = TxAddInputTlv.SwapInParamsLegacy(swapInKeys.userPublicKey, swapInKeys.remoteServerPublicKey, swapInKeys.refundDelay)
                        TxAddInput(fundingParams.channelId, inputOutgoing.serialId, inputOutgoing.previousTx, inputOutgoing.previousTxOutput, inputOutgoing.sequence, TlvStream(swapInParams))
                    }
                    is InteractiveTxInput.LocalSwapIn -> {
                        val swapInProtocol = swapInKeys.getSwapInProtocol(inputOutgoing.addressIndex)
                        val swapInParams = TxAddInputTlv.SwapInParams(swapInProtocol.userPublicKey, swapInProtocol.serverPublicKey, swapInProtocol.userRefundKey, swapInProtocol.refundDelay)
                        TxAddInput(fundingParams.channelId, inputOutgoing.serialId, inputOutgoing.previousTx, inputOutgoing.previousTxOutput, inputOutgoing.sequence, TlvStream(swapInParams))
                    }
                    is InteractiveTxInput.Shared -> TxAddInput(fundingParams.channelId, inputOutgoing.serialId, inputOutgoing.outPoint, inputOutgoing.sequence)
                }
                val nextSecretNonces = when (inputOutgoing) {
                    // Generate a secret nonce for this input if we don't already have one.
                    is InteractiveTxInput.LocalSwapIn -> when (secretNonces[inputOutgoing.serialId]) {
                        null -> {
                            val secretNonce = Musig2.generateNonce(randomBytes32(), swapInKeys.userPrivateKey.instantiate(), listOf(swapInKeys.userPublicKey, swapInKeys.remoteServerPublicKey))
                            secretNonces + (inputOutgoing.serialId to secretNonce)
                        }
                        else -> secretNonces
                    }
                    else -> secretNonces
                }
                val next = copy(toSend = toSend.tail(), localInputs = localInputs + msg.value, txCompleteSent = null, secretNonces = nextSecretNonces)
                Pair(next, InteractiveTxSessionAction.SendMessage(txAddInput))
            }
            is Either.Right -> {
                val outputOutgoing = msg.value
                val next = copy(toSend = toSend.tail(), localOutputs = localOutputs + outputOutgoing, txCompleteSent = null)
                val txAddOutput = when (outputOutgoing) {
                    is InteractiveTxOutput.Local -> TxAddOutput(fundingParams.channelId, outputOutgoing.serialId, outputOutgoing.amount, outputOutgoing.pubkeyScript)
                    is InteractiveTxOutput.Shared -> TxAddOutput(fundingParams.channelId, outputOutgoing.serialId, outputOutgoing.amount, outputOutgoing.pubkeyScript)
                }
                Pair(next, InteractiveTxSessionAction.SendMessage(txAddOutput))
            }
        }
    }

    private fun receiveInput(message: TxAddInput): Either<InteractiveTxSessionAction.RemoteFailure, InteractiveTxSession> {
        if (inputsReceivedCount + 1 >= MAX_INPUTS_OUTPUTS_RECEIVED) {
            return Either.Left(InteractiveTxSessionAction.TooManyInteractiveTxRounds(message.channelId))
        }
        if (remoteInputs.find { i -> (i as InteractiveTxInput).serialId == message.serialId } != null) {
            return Either.Left(InteractiveTxSessionAction.DuplicateSerialId(message.channelId, message.serialId))
        }
        // We check whether this is the shared input or a remote input.
        val input = when (message.previousTx) {
            null -> {
                val expectedSharedOutpoint = fundingParams.sharedInput?.info?.outPoint ?: return Either.Left(InteractiveTxSessionAction.PreviousTxMissing(message.channelId, message.serialId))
                val receivedSharedOutpoint = message.sharedInput ?: return Either.Left(InteractiveTxSessionAction.PreviousTxMissing(message.channelId, message.serialId))
                if (expectedSharedOutpoint != receivedSharedOutpoint) return Either.Left(InteractiveTxSessionAction.PreviousTxMissing(message.channelId, message.serialId))
                InteractiveTxInput.Shared(message.serialId, receivedSharedOutpoint, fundingParams.sharedInput.info.txOut.publicKeyScript, message.sequence, previousFunding.toLocal, previousFunding.toRemote, previousFunding.toHtlcs)
            }

            else -> {
                if (message.previousTx.txOut.size <= message.previousTxOutput) {
                    return Either.Left(InteractiveTxSessionAction.InputOutOfBounds(message.channelId, message.serialId, message.previousTx.txid, message.previousTxOutput))
                }
                fundingParams.sharedInput?.let {
                    if (it.info.outPoint == OutPoint(message.previousTx, message.previousTxOutput)) {
                        return Either.Left(InteractiveTxSessionAction.InvalidSharedInput(message.channelId, message.serialId))
                    }
                }
                if (!Script.isNativeWitnessScript(message.previousTx.txOut[message.previousTxOutput.toInt()].publicKeyScript)) {
                    return Either.Left(InteractiveTxSessionAction.NonSegwitInput(message.channelId, message.serialId, message.previousTx.txid, message.previousTxOutput))
                }
                val outpoint = OutPoint(message.previousTx, message.previousTxOutput)
                val txOut = message.previousTx.txOut[message.previousTxOutput.toInt()]
                when {
                    message.swapInParams != null -> InteractiveTxInput.RemoteSwapIn(
                        message.serialId,
                        outpoint,
                        txOut,
                        message.sequence,
                        message.swapInParams.userKey,
                        message.swapInParams.serverKey,
                        message.swapInParams.userRefundKey,
                        message.swapInParams.refundDelay
                    )
                    message.swapInParamsLegacy != null -> InteractiveTxInput.RemoteLegacySwapIn(
                        message.serialId,
                        outpoint,
                        txOut,
                        message.sequence,
                        message.swapInParamsLegacy.userKey,
                        message.swapInParamsLegacy.serverKey,
                        message.swapInParamsLegacy.refundDelay
                    )
                    else -> InteractiveTxInput.RemoteOnly(message.serialId, outpoint, txOut, message.sequence)
                }
            }
        }
        if ((localInputs.map { (it as InteractiveTxInput).outPoint } + remoteInputs.map { (it as InteractiveTxInput).outPoint }).contains(input.outPoint)) {
            return Either.Left(InteractiveTxSessionAction.DuplicateInput(message.channelId, message.serialId, input.outPoint.txid, input.outPoint.index))
        }
        if (message.sequence > 0xfffffffdU) {
            return Either.Left(InteractiveTxSessionAction.NonReplaceableInput(message.channelId, message.serialId, input.outPoint.txid, input.outPoint.index, message.sequence.toLong()))
        }
        val secretNonces1 = when (input) {
            // Generate a secret nonce for this input if we don't already have one.
            is InteractiveTxInput.RemoteSwapIn -> when (secretNonces[input.serialId]) {
                null -> {
                    val secretNonce = Musig2.generateNonce(randomBytes32(), swapInKeys.localServerPrivateKey(remoteNodeId).instantiate(), listOf(input.userKey, input.serverKey))
                    secretNonces + (input.serialId to secretNonce)
                }
                else -> secretNonces
            }
            else -> secretNonces
        }
        val session1 = this.copy(remoteInputs = remoteInputs + input, inputsReceivedCount = inputsReceivedCount + 1, txCompleteReceived = null, secretNonces = secretNonces1)
        return Either.Right(session1)
    }

    private fun receiveOutput(message: TxAddOutput): Either<InteractiveTxSessionAction.RemoteFailure, InteractiveTxOutput.Incoming> {
        return if (outputsReceivedCount + 1 >= MAX_INPUTS_OUTPUTS_RECEIVED) {
            Either.Left(InteractiveTxSessionAction.TooManyInteractiveTxRounds(message.channelId))
        } else if (remoteOutputs.find { o -> (o as InteractiveTxOutput).serialId == message.serialId } != null) {
            Either.Left(InteractiveTxSessionAction.DuplicateSerialId(message.channelId, message.serialId))
        } else if (message.amount < fundingParams.dustLimit) {
            Either.Left(InteractiveTxSessionAction.OutputBelowDust(message.channelId, message.serialId, message.amount, fundingParams.dustLimit))
        } else if (message.pubkeyScript == fundingParams.fundingPubkeyScript(channelKeys) && message.amount != fundingParams.fundingAmount) {
            Either.Left(InteractiveTxSessionAction.InvalidTxSharedAmount(message.channelId, message.serialId, message.amount, fundingParams.fundingAmount))
        } else if (message.pubkeyScript == fundingParams.fundingPubkeyScript(channelKeys)) {
            val localAmount = previousFunding.toLocal + fundingParams.localContribution.toMilliSatoshi()
            val remoteAmount = previousFunding.toRemote + fundingParams.remoteContribution.toMilliSatoshi()
            Either.Right(InteractiveTxOutput.Shared(message.serialId, message.pubkeyScript, localAmount, remoteAmount, previousFunding.toHtlcs))
        } else {
            Either.Right(InteractiveTxOutput.Remote(message.serialId, message.amount, message.pubkeyScript))
        }
    }

    fun receive(message: InteractiveTxConstructionMessage): Pair<InteractiveTxSession, InteractiveTxSessionAction> {
        if (message is HasSerialId && (message.serialId.mod(2) == 1) != fundingParams.isInitiator) {
            return Pair(this, InteractiveTxSessionAction.InvalidSerialId(fundingParams.channelId, message.serialId))
        }
        return when (message) {
            is TxAddInput -> {
                receiveInput(message).fold(
                    { f -> Pair(this, f) },
                    { next -> next.send() }
                )
            }
            is TxAddOutput -> {
                receiveOutput(message).fold(
                    { f -> Pair(this, f) },
                    { output -> copy(remoteOutputs = remoteOutputs + output, outputsReceivedCount = outputsReceivedCount + 1, txCompleteReceived = null).send() }
                )
            }
            is TxRemoveInput -> {
                val remoteInputs1 = remoteInputs.filterNot { i -> (i as InteractiveTxInput).serialId == message.serialId }
                if (remoteInputs.size != remoteInputs1.size) {
                    val next = copy(remoteInputs = remoteInputs1, txCompleteReceived = null)
                    next.send()
                } else {
                    Pair(this, InteractiveTxSessionAction.UnknownSerialId(message.channelId, message.serialId))
                }
            }
            is TxRemoveOutput -> {
                val remoteOutputs1 = remoteOutputs.filterNot { o -> (o as InteractiveTxOutput).serialId == message.serialId }
                if (remoteOutputs.size != remoteOutputs1.size) {
                    val next = copy(remoteOutputs = remoteOutputs1, txCompleteReceived = null)
                    next.send()
                } else {
                    Pair(this, InteractiveTxSessionAction.UnknownSerialId(message.channelId, message.serialId))
                }
            }
            is TxComplete -> {
                val next = copy(txCompleteReceived = message)
                if (next.isComplete) {
                    Pair(next, next.validateTx(null))
                } else {
                    next.send()
                }
            }
        }
    }

    private fun validateTx(txComplete: TxComplete?): InteractiveTxSessionAction {
        // tx_complete MUST have been sent and received for us to reach this state, require is used here to tell the compiler that txCompleteSent and txCompleteReceived are not null
        require(txCompleteSent != null)
        require(txCompleteReceived != null)

        if (localInputs.size + remoteInputs.size > 252 || localOutputs.size + remoteOutputs.size > 252) {
            return InteractiveTxSessionAction.InvalidTxInputOutputCount(fundingParams.channelId, localInputs.size + remoteInputs.size, localOutputs.size + remoteOutputs.size)
        }

        val sharedInputs = localInputs.filterIsInstance<InteractiveTxInput.Shared>() + remoteInputs.filterIsInstance<InteractiveTxInput.Shared>()
        val localOnlyInputs = localInputs.filterIsInstance<InteractiveTxInput.Local>()
        val remoteOnlyInputs = remoteInputs.filterIsInstance<InteractiveTxInput.Remote>()
        val sharedOutputs = localOutputs.filterIsInstance<InteractiveTxOutput.Shared>() + remoteOutputs.filterIsInstance<InteractiveTxOutput.Shared>()
        val localOnlyOutputs = localOutputs.filterIsInstance<InteractiveTxOutput.Local>()
        val remoteOnlyOutputs = remoteOutputs.filterIsInstance<InteractiveTxOutput.Remote>()

        if (sharedOutputs.size != 1) {
            return InteractiveTxSessionAction.InvalidTxSharedOutput(fundingParams.channelId)
        }
        val sharedOutput = sharedOutputs.first()

        val sharedInput = fundingParams.sharedInput?.let {
            if (fundingParams.remoteContribution >= 0.sat) {
                // If remote has a positive contribution, we do not check their post-splice reserve level, because they are improving
                // their situation, even if they stay below the requirement. Note that if local splices-in some funds in the same
                // operation, remote post-splice reserve may actually be worse than before, but that's not their fault.
            } else {
                // To compute the remote reserve, we discard the local contribution. It's okay if they go below reserve because
                // we added capacity to the channel with a splice-in.
                val remoteReserve = ((fundingParams.fundingAmount - fundingParams.localContribution) / 100).max(fundingParams.dustLimit)
                if (sharedOutput.remoteAmount < remoteReserve && remoteOnlyOutputs.isNotEmpty()) {
                    return InteractiveTxSessionAction.InvalidTxBelowReserve(fundingParams.channelId, sharedOutput.remoteAmount.truncateToSatoshi(), remoteReserve)
                }
            }
            if (sharedInputs.size != 1) {
                return InteractiveTxSessionAction.InvalidTxSharedInput(fundingParams.channelId)
            }
            sharedInputs.first()
        }

        // Our peer must send us one nonce for each swap input (local and remote), ordered by serial_id.
        val swapInputsCount = localInputs.count { it is InteractiveTxInput.LocalSwapIn } + remoteInputs.count { it is InteractiveTxInput.RemoteSwapIn }
        if (txCompleteReceived.publicNonces.size != swapInputsCount) {
            return InteractiveTxSessionAction.MissingNonce(fundingParams.channelId, swapInputsCount, txCompleteReceived.publicNonces.size)
        }

        val sharedTx = SharedTransaction(sharedInput, sharedOutput, localOnlyInputs, remoteOnlyInputs, localOnlyOutputs, remoteOnlyOutputs, fundingParams.lockTime)
        val tx = sharedTx.buildUnsignedTx()
        if (sharedTx.localAmountIn < sharedTx.localAmountOut || sharedTx.remoteAmountIn < sharedTx.remoteAmountOut) {
            return InteractiveTxSessionAction.InvalidTxChangeAmount(fundingParams.channelId, tx.txid)
        }

        // The transaction isn't signed yet, and segwit witnesses can be arbitrarily low (e.g. when using an OP_1 script),
        // so we use empty witnesses to provide a lower bound on the transaction weight.
        if (tx.weight() > Transactions.MAX_STANDARD_TX_WEIGHT) {
            return InteractiveTxSessionAction.InvalidTxWeight(fundingParams.channelId, tx.txid)
        }

        if (previousTxs.isNotEmpty()) {
            // This is an RBF attempt: even if our peer does not contribute to the feerate increase, we'd like to broadcast
            // the new transaction if it has a better feerate than the previous one. This is better than being stuck with
            // a transaction that doesn't confirm.
            // We don't know yet the witness weight since the transaction isn't signed, so we compare unsigned transactions.
            val previousUnsignedTx = previousTxs.first().tx.buildUnsignedTx()
            val previousFeerate = Transactions.fee2rate(previousTxs.first().tx.fees, previousUnsignedTx.weight())
            val nextFeerate = Transactions.fee2rate(sharedTx.fees, tx.weight())
            if (nextFeerate <= previousFeerate) {
                return InteractiveTxSessionAction.InvalidTxFeerate(fundingParams.channelId, tx.txid, fundingParams.targetFeerate, nextFeerate)
            }
        } else {
            val minimumFee = Transactions.weight2fee(fundingParams.targetFeerate, tx.weight())
            if (sharedTx.fees < minimumFee) {
                return InteractiveTxSessionAction.InvalidTxFeerate(fundingParams.channelId, tx.txid, fundingParams.targetFeerate, Transactions.fee2rate(sharedTx.fees, tx.weight()))
            }
        }

        // The transaction must double-spend every previous attempt, otherwise there is a risk that two funding transactions
        // confirm for the same channel.
        val currentInputs = tx.txIn.map { i -> i.outPoint }.toSet()
        previousTxs.forEach { previousSharedTx ->
            val previousTx = previousSharedTx.tx.buildUnsignedTx()
            val previousInputs = previousTx.txIn.map { i -> i.outPoint }
            if (previousInputs.find { i -> currentInputs.contains(i) } == null) {
                return InteractiveTxSessionAction.InvalidTxDoesNotDoubleSpendPreviousTx(fundingParams.channelId, tx.txid, previousTx.txid)
            }
        }

        return InteractiveTxSessionAction.SignSharedTx(sharedTx, txComplete)
    }

    companion object {
        // We restrict the number of inputs / outputs that our peer can send us to ensure the protocol eventually ends.
        const val MAX_INPUTS_OUTPUTS_RECEIVED = 4096
    }
}

sealed class InteractiveTxSigningSessionAction {
    /** Wait for their tx_signatures before sending ours. */
    data object WaitForTxSigs : InteractiveTxSigningSessionAction()

    /** Send our tx_signatures: we cannot forget the channel until it has been spent or double-spent. */
    data class SendTxSigs(val fundingTx: LocalFundingStatus.UnconfirmedFundingTx, val commitment: Commitment, val localSigs: TxSignatures) : InteractiveTxSigningSessionAction()
    data class AbortFundingAttempt(val reason: ChannelException) : InteractiveTxSigningSessionAction() {
        override fun toString(): String = reason.message
    }
}

/**
 * Once a shared transaction has been created, peers exchange signatures for the commitment and the shared transaction.
 * We store the channel state once we reach that step. Once we've sent tx_signatures, we cannot forget the channel
 * until it has been spent or double-spent.
 */
data class InteractiveTxSigningSession(
    val fundingParams: InteractiveTxParams,
    val fundingTxIndex: Long,
    val fundingTx: PartiallySignedSharedTransaction,
    val liquidityLease: LiquidityAds.Lease?,
    val localCommit: Either<UnsignedLocalCommit, LocalCommit>,
    val remoteCommit: RemoteCommit,
) {

    //                      Example flow:
    //     +-------+                             +-------+
    //     |       |-------- commit_sig -------->|       |
    //     |   A   |<------- commit_sig ---------|   B   |
    //     |       |-------- tx_signatures ----->|       |
    //     |       |<------- tx_signatures ------|       |
    //     +-------+                             +-------+

    val commitInput: Transactions.InputInfo = when (localCommit) {
        is Either.Left -> localCommit.value.commitTx.input
        is Either.Right -> localCommit.value.publishableTxs.commitTx.input
    }

    fun receiveCommitSig(channelKeys: KeyManager.ChannelKeys, channelParams: ChannelParams, remoteCommitSig: CommitSig, currentBlockHeight: Long, logger: MDCLogger): Pair<InteractiveTxSigningSession, InteractiveTxSigningSessionAction> {
        return when (localCommit) {
            is Either.Left -> {
                val localCommitIndex = localCommit.value.index
                val localPerCommitmentPoint = channelKeys.commitmentPoint(localCommitIndex)
                when (val signedLocalCommit = LocalCommit.fromCommitSig(channelKeys, channelParams, fundingTxIndex, fundingParams.remoteFundingPubkey, commitInput, remoteCommitSig, localCommitIndex, localCommit.value.spec, localPerCommitmentPoint, logger)) {
                    is Either.Left -> {
                        val fundingKey = channelKeys.fundingKey(fundingTxIndex)
                        val localSigOfLocalTx = fundingKey.sign(localCommit.value.commitTx)
                        val signedLocalCommitTx = Transactions.addSigs(localCommit.value.commitTx, fundingKey.publicKey(), fundingParams.remoteFundingPubkey, localSigOfLocalTx, remoteCommitSig.signature)
                        logger.info { "interactiveTxSession=$this" }
                        logger.info { "channelParams=$channelParams" }
                        logger.info { "fundingKey=${fundingKey.publicKey()}" }
                        logger.info { "localSigOfLocalTx=$localSigOfLocalTx" }
                        logger.info { "signedLocalCommitTx=$signedLocalCommitTx" }
                        Pair(this, InteractiveTxSigningSessionAction.AbortFundingAttempt(signedLocalCommit.value))
                    }
                    is Either.Right -> {
                        if (shouldSignFirst(fundingParams.isInitiator, channelParams, fundingTx.tx)) {
                            val fundingStatus = LocalFundingStatus.UnconfirmedFundingTx(fundingTx, fundingParams, currentBlockHeight)
                            val commitment = Commitment(fundingTxIndex, fundingParams.remoteFundingPubkey, fundingStatus, RemoteFundingStatus.NotLocked, signedLocalCommit.value, remoteCommit, nextRemoteCommit = null)
                            val action = InteractiveTxSigningSessionAction.SendTxSigs(fundingStatus, commitment, fundingTx.localSigs)
                            Pair(this.copy(localCommit = Either.Right(signedLocalCommit.value)), action)
                        } else {
                            Pair(this.copy(localCommit = Either.Right(signedLocalCommit.value)), InteractiveTxSigningSessionAction.WaitForTxSigs)
                        }
                    }
                }
            }
            is Either.Right -> Pair(this, InteractiveTxSigningSessionAction.WaitForTxSigs)
        }
    }

    fun receiveTxSigs(channelKeys: KeyManager.ChannelKeys, remoteTxSigs: TxSignatures, currentBlockHeight: Long): Either<InteractiveTxSigningSessionAction.AbortFundingAttempt, InteractiveTxSigningSessionAction.SendTxSigs> {
        return when (localCommit) {
            is Either.Left -> Either.Left(InteractiveTxSigningSessionAction.AbortFundingAttempt(UnexpectedFundingSignatures(fundingParams.channelId)))
            is Either.Right -> when (val fullySignedTx = fundingTx.addRemoteSigs(channelKeys, fundingParams, remoteTxSigs)) {
                null -> Either.Left(InteractiveTxSigningSessionAction.AbortFundingAttempt(InvalidFundingSignature(fundingParams.channelId, fundingTx.txId)))
                else -> {
                    val fundingStatus = LocalFundingStatus.UnconfirmedFundingTx(fullySignedTx, fundingParams, currentBlockHeight)
                    val commitment = Commitment(fundingTxIndex, fundingParams.remoteFundingPubkey, fundingStatus, RemoteFundingStatus.NotLocked, localCommit.value, remoteCommit, nextRemoteCommit = null)
                    Either.Right(InteractiveTxSigningSessionAction.SendTxSigs(fundingStatus, commitment, fundingTx.localSigs))
                }
            }
        }
    }

    companion object {
        /** A local commitment for which we haven't received our peer's signatures. */
        data class UnsignedLocalCommit(val index: Long, val spec: CommitmentSpec, val commitTx: Transactions.TransactionWithInputInfo.CommitTx, val htlcTxs: List<Transactions.TransactionWithInputInfo.HtlcTx>)

        fun create(
            session: InteractiveTxSession,
            keyManager: KeyManager,
            channelParams: ChannelParams,
            fundingParams: InteractiveTxParams,
            fundingTxIndex: Long,
            sharedTx: SharedTransaction,
            localPushAmount: MilliSatoshi,
            remotePushAmount: MilliSatoshi,
            liquidityLease: LiquidityAds.Lease?,
            localCommitmentIndex: Long,
            remoteCommitmentIndex: Long,
            commitTxFeerate: FeeratePerKw,
            remotePerCommitmentPoint: PublicKey,
            localHtlcs: Set<DirectedHtlc>
        ): Either<ChannelException, Pair<InteractiveTxSigningSession, CommitSig>> {
            val channelKeys = channelParams.localParams.channelKeys(keyManager)
            val unsignedTx = sharedTx.buildUnsignedTx()
            val sharedOutputIndex = unsignedTx.txOut.indexOfFirst { it.publicKeyScript == fundingParams.fundingPubkeyScript(channelKeys) }
            val liquidityFees = liquidityLease?.fees?.total?.toMilliSatoshi() ?: 0.msat
            return Helpers.Funding.makeCommitTxs(
                channelKeys,
                channelParams.channelId,
                channelParams.localParams, channelParams.remoteParams,
                fundingAmount = sharedTx.sharedOutput.amount,
                toLocal = sharedTx.sharedOutput.localAmount - localPushAmount + remotePushAmount - liquidityFees,
                toRemote = sharedTx.sharedOutput.remoteAmount - remotePushAmount + localPushAmount + liquidityFees,
                localHtlcs = localHtlcs,
                localCommitmentIndex = localCommitmentIndex,
                remoteCommitmentIndex = remoteCommitmentIndex,
                commitTxFeerate,
                fundingTxIndex = fundingTxIndex, fundingTxId = unsignedTx.txid, fundingTxOutputIndex = sharedOutputIndex,
                remoteFundingPubkey = fundingParams.remoteFundingPubkey,
                remotePerCommitmentPoint = remotePerCommitmentPoint
            ).map { firstCommitTx ->
                val localSigOfRemoteCommitTx = channelKeys.fundingKey(fundingTxIndex).sign(firstCommitTx.remoteCommitTx)
                val localSigsOfRemoteHtlcTxs = firstCommitTx.remoteHtlcTxs.map { channelKeys.htlcKey.deriveForCommitment(remotePerCommitmentPoint).sign(it, SigHash.SIGHASH_SINGLE or SigHash.SIGHASH_ANYONECANPAY) }

                val alternativeSigs = if (firstCommitTx.remoteHtlcTxs.isEmpty()) {
                    val commitSigTlvs = Commitments.alternativeFeerates.map { feerate ->
                        val alternativeSpec = firstCommitTx.remoteSpec.copy(feerate = feerate)
                        val (alternativeRemoteCommitTx, _) = Commitments.makeRemoteTxs(
                            channelKeys,
                            remoteCommitmentIndex,
                            channelParams.localParams,
                            channelParams.remoteParams,
                            fundingTxIndex,
                            fundingParams.remoteFundingPubkey,
                            firstCommitTx.remoteCommitTx.input,
                            remotePerCommitmentPoint,
                            alternativeSpec
                        )
                        val sig = channelKeys.fundingKey(fundingTxIndex).sign(alternativeRemoteCommitTx)
                        CommitSigTlv.AlternativeFeerateSig(feerate, sig)
                    }
                    TlvStream(CommitSigTlv.AlternativeFeerateSigs(commitSigTlvs) as CommitSigTlv)
                } else {
                    TlvStream.empty()
                }
                val commitSig = CommitSig(channelParams.channelId, localSigOfRemoteCommitTx, localSigsOfRemoteHtlcTxs, alternativeSigs)
                // We haven't received the remote commit_sig: we don't have local htlc txs yet.
                val unsignedLocalCommit = UnsignedLocalCommit(localCommitmentIndex, firstCommitTx.localSpec, firstCommitTx.localCommitTx, listOf())
                val remoteCommit = RemoteCommit(remoteCommitmentIndex, firstCommitTx.remoteSpec, firstCommitTx.remoteCommitTx.tx.txid, remotePerCommitmentPoint)
                val signedFundingTx = sharedTx.sign(session, keyManager, fundingParams, channelParams.localParams, channelParams.remoteParams.nodeId)
                Pair(InteractiveTxSigningSession(fundingParams, fundingTxIndex, signedFundingTx, liquidityLease, Either.Left(unsignedLocalCommit), remoteCommit), commitSig)
            }
        }

        fun shouldSignFirst(isInitiator: Boolean, channelParams: ChannelParams, tx: SharedTransaction): Boolean {
            val sharedAmountIn = tx.sharedInput?.let { it.localAmount + it.remoteAmount } ?: 0.msat
            val localAmountIn = tx.localInputs.map { it.txOut.amount }.sum().toMilliSatoshi() + if (isInitiator) sharedAmountIn else 0.msat
            val remoteAmountIn = tx.remoteInputs.map { it.txOut.amount }.sum().toMilliSatoshi() + if (isInitiator) 0.msat else sharedAmountIn
            return if (localAmountIn == remoteAmountIn) {
                // When both peers contribute the same amount, the peer with the lowest pubkey must transmit its `tx_signatures` first.
                LexicographicalOrdering.isLessThan(channelParams.localParams.nodeId, channelParams.remoteParams.nodeId)
            } else {
                // Otherwise, the peer with the lowest total of input amount must transmit its `tx_signatures` first.
                localAmountIn < remoteAmountIn
            }
        }
    }

}

sealed class RbfStatus {
    data object None : RbfStatus()
    data class RbfRequested(val command: ChannelCommand.Funding.BumpFundingFee) : RbfStatus()
    data class InProgress(val rbfSession: InteractiveTxSession) : RbfStatus()
    data class WaitingForSigs(val session: InteractiveTxSigningSession) : RbfStatus()
    data object RbfAborted : RbfStatus()
}

/** We're waiting for the channel to be quiescent. */
sealed class QuiescenceNegotiation : SpliceStatus() {
    abstract class Initiator : QuiescenceNegotiation() {
        abstract val command: ChannelCommand.Commitment.Splice.Request
    }
    abstract class NonInitiator : QuiescenceNegotiation()
}

/** The channel is quiescent and a splice attempt was initiated. */
sealed class QuiescentSpliceStatus : SpliceStatus()

sealed class SpliceStatus {
    data object None : SpliceStatus()
    /** We stop sending new updates and wait for our updates to be added to the local and remote commitments. */
    data class QuiescenceRequested(override val command: ChannelCommand.Commitment.Splice.Request) : QuiescenceNegotiation.Initiator()
    /** Our updates have been added to the local and remote commitments, we wait for our peer to do the same. */
    data class InitiatorQuiescent(override val command: ChannelCommand.Commitment.Splice.Request) : QuiescenceNegotiation.Initiator()
    /** Our peer has asked us to stop sending new updates and wait for our updates to be added to the local and remote commitments. */
    data class ReceivedStfu(val stfu: Stfu) : QuiescenceNegotiation.NonInitiator()
    /** Our updates have been added to the local and remote commitments, we wait for our peer to use the now quiescent channel. */
    object NonInitiatorQuiescent : QuiescentSpliceStatus()
    /** We told our peer we want to splice funds in the channel. */
    data class Requested(val command: ChannelCommand.Commitment.Splice.Request, val spliceInit: SpliceInit) : QuiescentSpliceStatus()
    /** We both agreed to splice and are building the splice transaction. */
    data class InProgress(
        val replyTo: CompletableDeferred<ChannelCommand.Commitment.Splice.Response>?,
        val spliceSession: InteractiveTxSession,
        val localPushAmount: MilliSatoshi,
        val remotePushAmount: MilliSatoshi,
        val liquidityLease: LiquidityAds.Lease?,
        val origins: List<Origin.PayToOpenOrigin>
    ) : QuiescentSpliceStatus()
    /** The splice transaction has been negotiated, we're exchanging signatures. */
    data class WaitingForSigs(val session: InteractiveTxSigningSession, val origins: List<Origin.PayToOpenOrigin>) : QuiescentSpliceStatus()
    /** The splice attempt was aborted by us, we're waiting for our peer to ack. */
    data object Aborted : QuiescentSpliceStatus()
}
