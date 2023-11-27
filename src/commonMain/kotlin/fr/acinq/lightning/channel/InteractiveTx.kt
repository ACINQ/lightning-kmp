package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Script.tail
import fr.acinq.bitcoin.musig2.PublicNonce
import fr.acinq.bitcoin.musig2.SecretNonce
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.crypto.KeyManager
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
            return Transactions.sign(Transactions.TransactionWithInputInfo.SpliceTx(info, tx), fundingKey)
        }

        companion object {
            const val weight: Int = 388
        }
    }
}

/** The current balances of a [[SharedFundingInput]]. */
data class SharedFundingInputBalances(val toLocal: MilliSatoshi, val toRemote: MilliSatoshi) {
    val fundingAmount: Satoshi = (toLocal + toRemote).truncateToSatoshi()
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
        return Script.write(Script.pay2wsh(Scripts.multiSig2of2(channelKeys.fundingPubKey(fundingTxIndex), remoteFundingPubkey))).toByteVector()
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
        override val txOut: TxOut
            get() = previousTx.txOut[previousTxOutput.toInt()]
    }

    /** A local-only input that funds the interactive transaction. */
    data class LocalOnly(override val serialId: Long, override val previousTx: Transaction, override val previousTxOutput: Long, override val sequence: UInt) : Local() {
        override val outPoint: OutPoint = OutPoint(previousTx, previousTxOutput)
    }

    /** A local input that funds the interactive transaction, coming from a 2-of-2 swap-in transaction. */
    data class LocalSwapIn(
        override val serialId: Long,
        override val previousTx: Transaction,
        override val previousTxOutput: Long,
        override val sequence: UInt,
        val swapInParams: TxAddInputTlv.SwapInParams) : Local() {
        override val outPoint: OutPoint = OutPoint(previousTx, previousTxOutput)
    }

    data class LocalMusig2SwapIn(
        override val serialId: Long,
        override val previousTx: Transaction,
        override val previousTxOutput: Long,
        override val sequence: UInt,
        val swapInParams: TxAddInputTlv.SwapInParamsMusig2) : Local() {
        override val outPoint: OutPoint = OutPoint(previousTx, previousTxOutput)
    }
    /**
     * A remote input that funds the interactive transaction.
     * We only keep the data we need from our peer's TxAddInput to avoid storing potentially large messages in our DB.
     */
    sealed class Remote : InteractiveTxInput(), Incoming

    /** A remote-only input that funds the interactive transaction. */
    data class RemoteOnly(override val serialId: Long, override val outPoint: OutPoint, override val txOut: TxOut, override val sequence: UInt) : Remote()

    /** A remote input from a swap-in: our peer needs our signature to build a witness for that input. */
    data class RemoteSwapIn(
        override val serialId: Long,
        override val outPoint: OutPoint,
        override val txOut: TxOut,
        override val sequence: UInt,
        val swapInParams: TxAddInputTlv.SwapInParams) : Remote()

    data class RemoteSwapInMusig2(
        override val serialId: Long,
        override val outPoint: OutPoint,
        override val txOut: TxOut,
        override val sequence: UInt,
        val swapInParams: TxAddInputTlv.SwapInParamsMusig2) : Remote()

    /** The shared input can be added by us or by our peer, depending on who initiated the protocol. */
    data class Shared(override val serialId: Long, override val outPoint: OutPoint, override val txOut: TxOut, override val sequence: UInt, val localAmount: MilliSatoshi, val remoteAmount: MilliSatoshi) : InteractiveTxInput(), Incoming, Outgoing
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
    data class Shared(override val serialId: Long, override val pubkeyScript: ByteVector, val localAmount: MilliSatoshi, val remoteAmount: MilliSatoshi) : InteractiveTxOutput(), Incoming, Outgoing {
        // Note that the truncation is a no-op: the sum of balances in a channel must be a satoshi amount.
        override val amount: Satoshi = (localAmount + remoteAmount).truncateToSatoshi()
    }
}

sealed class FundingContributionFailure {
    // @formatter:off
    data class InputOutOfBounds(val txId: ByteVector32, val outputIndex: Int) : FundingContributionFailure() { override fun toString(): String = "invalid input $txId:$outputIndex (out of bounds)" }
    data class InputBelowDust(val txId: ByteVector32, val outputIndex: Int, val amount: Satoshi, val dustLimit: Satoshi) : FundingContributionFailure() { override fun toString(): String = "invalid input $txId:$outputIndex (below dust: amount=$amount, dust=$dustLimit)" }
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
            walletInputs.forEach { (tx, txOutput) ->
                if (tx.txOut.size <= txOutput) return Either.Left(FundingContributionFailure.InputOutOfBounds(tx.txid, txOutput))
                val dustLimit = Transactions.dustLimit(tx.txOut[txOutput].publicKeyScript)
                if (tx.txOut[txOutput].amount < dustLimit) return Either.Left(FundingContributionFailure.InputBelowDust(tx.txid, txOutput, tx.txOut[txOutput].amount, dustLimit))
                if (Transaction.write(tx.stripInputWitnesses()).size > 65_000) return Either.Left(FundingContributionFailure.InputTxTooLarge(tx))
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

            val sharedOutput = listOf(InteractiveTxOutput.Shared(0, fundingPubkeyScript, nextLocalBalance, nextRemoteBalance))
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
            val sharedInput = sharedUtxo?.let { (i, balances) -> listOf(InteractiveTxInput.Shared(0, i.info.outPoint, i.info.txOut, 0xfffffffdU, balances.toLocal, balances.toRemote)) } ?: listOf()
            val localInputs = walletInputs.map { i ->
                when {
                    Script.isPay2wsh(i.previousTx.txOut[i.outputIndex].publicKeyScript.toByteArray()) ->
                        InteractiveTxInput.LocalSwapIn(
                            0,
                            i.previousTx.stripInputWitnesses(),
                            i.outputIndex.toLong(),
                            0xfffffffdU,
                            TxAddInputTlv.SwapInParams(swapInKeys.userPublicKey, swapInKeys.remoteServerPublicKey, swapInKeys.refundDelay)
                        )
                    else -> InteractiveTxInput.LocalMusig2SwapIn(
                        0,
                        i.previousTx.stripInputWitnesses(),
                        i.outputIndex.toLong(),
                        0xfffffffdU,
                        TxAddInputTlv.SwapInParamsMusig2(swapInKeys.userPublicKey, swapInKeys.remoteServerPublicKey, swapInKeys.userRefundPublicKey, swapInKeys.refundDelay)
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
        private fun Transaction.stripInputWitnesses(): Transaction = copy(txIn = txIn.map { it.updateWitness(ScriptWitness.empty) })

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
                    Script.isPay2wsh(it.previousTx.txOut[it.outputIndex].publicKeyScript.toByteArray()) -> Transactions.swapInputWeight
                    else -> Transactions.swapInputWeightMusig2
                }
            }

        /** We always randomize the order of inputs and outputs. */
        private fun sortFundingContributions(params: InteractiveTxParams, inputs: List<InteractiveTxInput.Outgoing>, outputs: List<InteractiveTxOutput.Outgoing>): FundingContributions {
            val sortedInputs = inputs.shuffled().mapIndexed { i, input ->
                val serialId = 2 * i.toLong() + params.serialIdParity
                when (input) {
                    is InteractiveTxInput.LocalOnly -> input.copy(serialId = serialId)
                    is InteractiveTxInput.LocalSwapIn -> input.copy(serialId = serialId)
                    is InteractiveTxInput.LocalMusig2SwapIn-> input.copy(serialId = serialId)
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

    fun localSwapInputs(): List<InteractiveTxInput.LocalSwapIn> = localInputs.filterIsInstance<InteractiveTxInput.LocalSwapIn>()

    fun remoteOnlyInputs(): List<InteractiveTxInput.RemoteOnly> = remoteInputs.filterIsInstance<InteractiveTxInput.RemoteOnly>()

    fun remoteSwapInputs(): List<InteractiveTxInput.RemoteSwapIn> = remoteInputs.filterIsInstance<InteractiveTxInput.RemoteSwapIn>()

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
        val sharedOutput = fundingParams.sharedInput?.let { i -> mapOf(i.info.outPoint to i.info.txOut) } ?: mapOf()
        val localOutputs = localInputs.associate { i -> i.outPoint to i.txOut }
        val remoteOutputs = remoteInputs.associate { i -> i.outPoint to i.txOut }
        val previousOutputsMap = sharedOutput + localOutputs + remoteOutputs
        val previousOutputs = unsignedTx.txIn.map { previousOutputsMap[it.outPoint]!! }.toList()

        // If we are swapping funds in, we provide our partial signatures to the corresponding inputs.
        val swapUserSigs = unsignedTx.txIn.mapIndexed { i, txIn ->
            localInputs
                .filterIsInstance<InteractiveTxInput.LocalSwapIn>()
                .find { txIn.outPoint == it.outPoint }
                ?.let { input -> keyManager.swapInOnChainWallet.signSwapInputUser(unsignedTx, i, input.previousTx.txOut) }
        }.filterNotNull()

        val swapUserPartialSigs = unsignedTx.txIn.mapIndexed { i, txIn ->
            localInputs
                .filterIsInstance<InteractiveTxInput.LocalMusig2SwapIn>()
                .find { txIn.outPoint == it.outPoint }
                ?.let { input ->
                    val userNonce =  session.secretNonces[input.serialId]
                    require(userNonce != null)
                    require(session.txCompleteReceived != null)
                    val serverNonce = session.txCompleteReceived.publicNonces[input.serialId]
                    require(serverNonce != null)
                    val commonNonce = PublicNonce.aggregate(listOf(userNonce.publicNonce(), serverNonce))
                    TxSignatures.Companion.PartialSignature(keyManager.swapInOnChainWallet.signSwapInputUserMusig2(unsignedTx, i, previousOutputs, userNonce, serverNonce), commonNonce)
                }
        }.filterNotNull()

        // If the remote is swapping funds in, they'll need our partial signatures to finalize their witness.
        val swapServerSigs = unsignedTx.txIn.mapIndexed { i, txIn ->
            remoteInputs
                .filterIsInstance<InteractiveTxInput.RemoteSwapIn>()
                .find { txIn.outPoint == it.outPoint }
                ?.let { input ->
                    val serverKey = keyManager.swapInOnChainWallet.localServerPrivateKey(remoteNodeId)
                    val swapInProtocol = SwapInProtocol(input.swapInParams.userKey, serverKey.publicKey(), input.swapInParams.refundDelay)
                    swapInProtocol.signSwapInputServer(unsignedTx, i, input.txOut, serverKey)
                }
        }.filterNotNull()

        val swapServerPartialSigs = unsignedTx.txIn.mapIndexed { i, txIn ->
            remoteInputs
                .filterIsInstance<InteractiveTxInput.RemoteSwapInMusig2>()
                .find { txIn.outPoint == it.outPoint }
                ?.let { input ->
                    val serverKey = keyManager.swapInOnChainWallet.localServerPrivateKey(remoteNodeId)
                    val userNonce =  session.secretNonces[input.serialId]
                    require(userNonce != null)
                    require(session.txCompleteReceived != null)
                    val serverNonce = session.txCompleteReceived.publicNonces[input.serialId]
                    require(serverNonce != null)
                    val commonNonce = PublicNonce.aggregate(listOf(userNonce.publicNonce(), serverNonce))
                    val swapInProtocol = SwapInProtocolMusig2(input.swapInParams.userKey, serverKey.publicKey(), input.swapInParams.userRefundKey, input.swapInParams.refundDelay)
                    TxSignatures.Companion.PartialSignature(swapInProtocol.signSwapInputServer(unsignedTx, i, previousOutputs, serverNonce, serverKey, userNonce), commonNonce)
                }
        }.filterNotNull()

        return PartiallySignedSharedTransaction(this, TxSignatures(fundingParams.channelId, unsignedTx, listOf(), sharedSig, swapUserSigs, swapServerSigs, swapUserPartialSigs, swapServerPartialSigs))
    }
}

/** Signed transaction created collaboratively. */
sealed class SignedSharedTransaction {
    abstract val txId: ByteVector32
    abstract val tx: SharedTransaction
    abstract val localSigs: TxSignatures
    abstract val signedTx: Transaction?
}

data class PartiallySignedSharedTransaction(override val tx: SharedTransaction, override val localSigs: TxSignatures) : SignedSharedTransaction() {
    override val txId: ByteVector32 = localSigs.txId
    override val signedTx = null

    fun addRemoteSigs(channelKeys: KeyManager.ChannelKeys, fundingParams: InteractiveTxParams, remoteSigs: TxSignatures): FullySignedSharedTransaction? {
        if (localSigs.swapInUserSigs.size != tx.localInputs.filterIsInstance<InteractiveTxInput.LocalSwapIn>().size) return null
        if (localSigs.swapInUserPartialSigs.size != tx.localInputs.filterIsInstance<InteractiveTxInput.LocalMusig2SwapIn>().size) return null
        if (remoteSigs.swapInUserSigs.size != tx.remoteInputs.filterIsInstance<InteractiveTxInput.RemoteSwapIn>().size) return null
        if (remoteSigs.swapInUserPartialSigs.size != tx.remoteInputs.filterIsInstance<InteractiveTxInput.RemoteSwapInMusig2>().size) return null
        if (remoteSigs.swapInServerSigs.size != tx.localInputs.filterIsInstance<InteractiveTxInput.LocalSwapIn>().size) return null
        if (remoteSigs.swapInServerPartialSigs.size != tx.localInputs.filterIsInstance<InteractiveTxInput.LocalMusig2SwapIn>().size) return null
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
        val sharedTxIn = tx.sharedInput?.let { i -> listOf(Pair(i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence.toLong(), sharedSigs ?: ScriptWitness.empty))) } ?: listOf()
        val localOnlyTxIn = tx.localOnlyInputs().sortedBy { i -> i.serialId }.zip(localSigs.witnesses).map { (i, w) -> Pair(i.serialId, TxIn(OutPoint(i.previousTx, i.previousTxOutput), ByteVector.empty, i.sequence.toLong(), w)) }
        val localSwapTxIn = tx.localInputs.filterIsInstance<InteractiveTxInput.LocalSwapIn>().sortedBy { i -> i.serialId }.zip(localSigs.swapInUserSigs.zip(remoteSigs.swapInServerSigs)).map { (i, sigs) ->
            val (userSig, serverSig) = sigs
            val swapInProtocol = SwapInProtocol(i.swapInParams)
            val witness = swapInProtocol.witness(userSig, serverSig)
            Pair(i.serialId, TxIn(OutPoint(i.previousTx, i.previousTxOutput), ByteVector.empty, i.sequence.toLong(), witness))
        }
        val localSwapTxInMusig2 = tx.localInputs.filterIsInstance<InteractiveTxInput.LocalMusig2SwapIn>().sortedBy { i -> i.serialId }.zip(localSigs.swapInUserPartialSigs.zip(remoteSigs.swapInServerPartialSigs)).map { (i, sigs) ->
            val (userSig, serverSig) = sigs
            val swapInProtocol = SwapInProtocolMusig2(i.swapInParams)
            require(userSig.aggregatedPublicNonce == serverSig.aggregatedPublicNonce){ "aggregated public nonces mismatch for local input ${i.serialId}"}
            val commonNonce = userSig.aggregatedPublicNonce
            val unsignedTx = tx.buildUnsignedTx()
            val ctx = swapInProtocol.signingCtx(unsignedTx, unsignedTx.txIn.indexOfFirst { it.outPoint == i.outPoint }, unsignedTx.txIn.map { tx.spentOutputs[it.outPoint]!! }, commonNonce)
            val commonSig = ctx.partialSigAgg(listOf(userSig.sig, serverSig.sig))
            val witness = swapInProtocol.witness(commonSig)
            Pair(i.serialId, TxIn(OutPoint(i.previousTx, i.previousTxOutput), ByteVector.empty, i.sequence.toLong(), witness))
        }

        val remoteOnlyTxIn = tx.remoteOnlyInputs().sortedBy { i -> i.serialId }.zip(remoteSigs.witnesses).map { (i, w) -> Pair(i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence.toLong(), w)) }
        val remoteSwapTxIn = tx.remoteInputs.filterIsInstance<InteractiveTxInput.RemoteSwapIn>().sortedBy { i -> i.serialId }.zip(remoteSigs.swapInUserSigs.zip(localSigs.swapInServerSigs)).map { (i, sigs) ->
            val (userSig, serverSig) = sigs
            val swapInProtocol = SwapInProtocol(i.swapInParams.userKey, i.swapInParams.serverKey, i.swapInParams.refundDelay)
            val witness = swapInProtocol.witness(userSig, serverSig)
            Pair(i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence.toLong(), witness))
        }
        val remoteSwapTxInMusig2 = tx.remoteInputs.filterIsInstance<InteractiveTxInput.RemoteSwapInMusig2>().sortedBy { i -> i.serialId }.zip(remoteSigs.swapInUserPartialSigs.zip(localSigs.swapInServerPartialSigs)).map { (i, sigs) ->
            val (userSig, serverSig) = sigs
            val swapInProtocol = SwapInProtocolMusig2(i.swapInParams)
            require(userSig.aggregatedPublicNonce == serverSig.aggregatedPublicNonce){ "aggregated public nonces mismatch for remote input ${i.serialId}"}
            val commonNonce = userSig.aggregatedPublicNonce
            val unsignedTx = tx.buildUnsignedTx()
            val ctx = swapInProtocol.signingCtx(unsignedTx, unsignedTx.txIn.indexOfFirst { it.outPoint == i.outPoint }, unsignedTx.txIn.map { tx.spentOutputs[it.outPoint]!! }, commonNonce)
            val commonSig = ctx.partialSigAgg(listOf(userSig.sig, serverSig.sig))
            val witness = swapInProtocol.witness(commonSig)
            Pair(i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence.toLong(), witness))
        }
        val inputs = (sharedTxIn + localOnlyTxIn + localSwapTxIn + localSwapTxInMusig2 + remoteOnlyTxIn + remoteSwapTxIn + remoteSwapTxInMusig2).sortedBy { (serialId, _) -> serialId }.map { (_, i) -> i }
        val sharedTxOut = listOf(Pair(tx.sharedOutput.serialId, TxOut(tx.sharedOutput.amount, tx.sharedOutput.pubkeyScript)))
        val localTxOut = tx.localOutputs.map { o -> Pair(o.serialId, TxOut(o.amount, o.pubkeyScript)) }
        val remoteTxOut = tx.remoteOutputs.map { o -> Pair(o.serialId, TxOut(o.amount, o.pubkeyScript)) }
        val outputs = (sharedTxOut + localTxOut + remoteTxOut).sortedBy { (serialId, _) -> serialId }.map { (_, o) -> o }
        Transaction(2, inputs, outputs, tx.lockTime)
    }
    override val txId: ByteVector32 = signedTx.txid
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
    data class DuplicateInput(val channelId: ByteVector32, val serialId: Long, val previousTxId: ByteVector32, val previousTxOutput: Long) : RemoteFailure() { override fun toString(): String = "duplicate input $previousTxId:$previousTxOutput (serial_id=$serialId)" }
    data class InputOutOfBounds(val channelId: ByteVector32, val serialId: Long, val previousTxId: ByteVector32, val previousTxOutput: Long) : RemoteFailure() { override fun toString(): String = "invalid input $previousTxId:$previousTxOutput (serial_id=$serialId)" }
    data class NonReplaceableInput(val channelId: ByteVector32, val serialId: Long, val previousTxId: ByteVector32, val previousTxOutput: Long, val sequence: Long) : RemoteFailure() { override fun toString(): String = "$previousTxId:$previousTxOutput is not replaceable (serial_id=$serialId, nSequence=$sequence)" }
    data class NonSegwitInput(val channelId: ByteVector32, val serialId: Long, val previousTxId: ByteVector32, val previousTxOutput: Long) : RemoteFailure() { override fun toString(): String = "$previousTxId:$previousTxOutput is not a native segwit input (serial_id=$serialId)" }
    data class PreviousTxMissing(val channelId: ByteVector32, val serialId: Long) : RemoteFailure() { override fun toString(): String = "previous tx missing from tx_add_input (serial_id=$serialId)" }
    data class InvalidSharedInput(val channelId: ByteVector32, val serialId: Long) : RemoteFailure() { override fun toString(): String = "invalid shared tx_add_input (serial_id=$serialId)" }
    data class OutputBelowDust(val channelId: ByteVector32, val serialId: Long, val amount: Satoshi, val dustLimit: Satoshi) : RemoteFailure() { override fun toString(): String = "invalid output amount=$amount below dust=$dustLimit (serial_id=$serialId)" }
    data class InvalidTxInputOutputCount(val channelId: ByteVector32, val inputCount: Int, val outputCount: Int) : RemoteFailure() { override fun toString(): String = "invalid number of inputs or outputs (inputCount=$inputCount, outputCount=$outputCount)" }
    data class InvalidTxBelowReserve(val channelId: ByteVector32, val remoteAmount: Satoshi, val reserve: Satoshi) : RemoteFailure() { override fun toString(): String = "peer takes too much funds out and falls below reverse ($remoteAmount < $reserve)" }
    data class InvalidTxSharedInput(val channelId: ByteVector32) : RemoteFailure() { override fun toString(): String = "shared input is missing or duplicated" }
    data class InvalidTxSharedOutput(val channelId: ByteVector32) : RemoteFailure() { override fun toString(): String = "shared output is missing or duplicated" }
    data class InvalidTxSharedAmount(val channelId: ByteVector32, val serialId: Long, val amount: Satoshi, val expected: Satoshi) : RemoteFailure() { override fun toString(): String = "invalid shared output amount=$amount expected=$expected (serial_id=$serialId)" }
    data class InvalidTxChangeAmount(val channelId: ByteVector32, val txId: ByteVector32) : RemoteFailure() { override fun toString(): String = "change amount is too high (txId=$txId)" }
    data class InvalidTxWeight(val channelId: ByteVector32, val txId: ByteVector32) : RemoteFailure() { override fun toString(): String = "transaction weight is too big for standardness rules (txId=$txId)" }
    data class InvalidTxFeerate(val channelId: ByteVector32, val txId: ByteVector32, val targetFeerate: FeeratePerKw, val actualFeerate: FeeratePerKw) : RemoteFailure() { override fun toString(): String = "transaction feerate too low (txId=$txId, targetFeerate=$targetFeerate, actualFeerate=$actualFeerate" }
    data class InvalidTxDoesNotDoubleSpendPreviousTx(val channelId: ByteVector32, val txId: ByteVector32, val previousTxId: ByteVector32) : RemoteFailure() { override fun toString(): String = "transaction replacement with txId=$txId doesn't double-spend previous attempt (txId=$previousTxId)" }
    data class MissingNonce(val channelId: ByteVector32, val serialId: Long): RemoteFailure() { override fun toString(): String = "missing musig2 nonce for input serial_id=$serialId)" }
    // @formatter:on
}

data class InteractiveTxSession(
    val channelKeys: KeyManager.ChannelKeys,
    val swapInKeys: KeyManager.SwapInOnChainKeys,
    val fundingParams: InteractiveTxParams,
    val previousFunding: SharedFundingInputBalances,
    val toSend: List<Either<InteractiveTxInput.Outgoing, InteractiveTxOutput.Outgoing>>,
    val previousTxs: List<SignedSharedTransaction> = listOf(),
    val localInputs: List<InteractiveTxInput.Outgoing> = listOf(),
    val remoteInputs: List<InteractiveTxInput.Incoming> = listOf(),
    val localOutputs: List<InteractiveTxOutput.Outgoing> = listOf(),
    val remoteOutputs: List<InteractiveTxOutput.Incoming> = listOf(),
    val txCompleteSent: TxComplete? = null,
    val txCompleteReceived: TxComplete? = null,
    val inputsReceivedCount: Int = 0,
    val outputsReceivedCount: Int = 0,
    val secretNonces: Map<Long, SecretNonce> = mapOf()
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
        channelKeys: KeyManager.ChannelKeys,
        swapInKeys: KeyManager.SwapInOnChainKeys,
        fundingParams: InteractiveTxParams,
        previousLocalBalance: MilliSatoshi,
        previousRemoteBalance: MilliSatoshi,
        fundingContributions: FundingContributions,
        previousTxs: List<SignedSharedTransaction> = listOf()
    ) : this(
        channelKeys,
        swapInKeys,
        fundingParams,
        SharedFundingInputBalances(previousLocalBalance, previousRemoteBalance),
        fundingContributions.inputs.map { i -> Either.Left<InteractiveTxInput.Outgoing, InteractiveTxOutput.Outgoing>(i) } + fundingContributions.outputs.map { o -> Either.Right<InteractiveTxInput.Outgoing, InteractiveTxOutput.Outgoing>(o) },
        previousTxs
    )

    val isComplete: Boolean = txCompleteSent != null && txCompleteReceived != null

    fun send(): Pair<InteractiveTxSession, InteractiveTxSessionAction> {
        return when (val msg = toSend.firstOrNull()) {
            null -> {
                // generate a new secret nonce for each musig2 new swapin every time we send TxComplete
                val currentNonces = secretNonces
                fun userNonce(serialId: Long) = currentNonces.getOrElse(serialId) { SecretNonce.generate(swapInKeys.userPrivateKey, swapInKeys.userPublicKey, null, null, null, randomBytes32()) }
                fun serverNonce(serialId: Long, serverKey: PublicKey) = currentNonces.getOrElse(serialId) { SecretNonce.generate(null, serverKey, null, null, null, randomBytes32()) }
                val localMusig2SwapIns = localInputs.filterIsInstance<InteractiveTxInput.LocalMusig2SwapIn>()
                val localNonces = localMusig2SwapIns.map { it.serialId to userNonce(it.serialId) }.toMap()
                val remoteMusig2SwapIns = remoteInputs.filterIsInstance<InteractiveTxInput.RemoteSwapInMusig2>()
                val remoteNonces = remoteMusig2SwapIns.map { it.serialId to serverNonce(it.serialId, it.swapInParams.serverKey) }.toMap()
                val txComplete = TxComplete(fundingParams.channelId, (localNonces + remoteNonces).mapValues { it.value.publicNonce() })
                val next = copy(txCompleteSent = txComplete, secretNonces = localNonces + remoteNonces)
                if (next.isComplete) {
                    Pair(next, next.validateTx(txComplete))
                } else {
                    Pair(next, InteractiveTxSessionAction.SendMessage(txComplete))
                }
            }

            is Either.Left -> {
                val next = copy(toSend = toSend.tail(), localInputs = localInputs + msg.value, txCompleteSent = null)
                val txAddInput = when (msg.value) {
                    is InteractiveTxInput.LocalOnly -> TxAddInput(fundingParams.channelId, msg.value.serialId, msg.value.previousTx, msg.value.previousTxOutput, msg.value.sequence)
                    is InteractiveTxInput.LocalSwapIn -> {
                        val swapInParams = TxAddInputTlv.SwapInParams(swapInKeys.userPublicKey, swapInKeys.remoteServerPublicKey, swapInKeys.refundDelay)
                        TxAddInput(fundingParams.channelId, msg.value.serialId, msg.value.previousTx, msg.value.previousTxOutput, msg.value.sequence, TlvStream(swapInParams))
                    }
                    is InteractiveTxInput.LocalMusig2SwapIn -> {
                        val swapInParams = TxAddInputTlv.SwapInParamsMusig2(swapInKeys.userPublicKey, swapInKeys.remoteServerPublicKey, swapInKeys.userRefundPublicKey, swapInKeys.refundDelay)
                        TxAddInput(fundingParams.channelId, msg.value.serialId, msg.value.previousTx, msg.value.previousTxOutput, msg.value.sequence, TlvStream(swapInParams))
                    }
                    is InteractiveTxInput.Shared -> TxAddInput(fundingParams.channelId, msg.value.serialId, msg.value.outPoint, msg.value.sequence)
                }
                Pair(next, InteractiveTxSessionAction.SendMessage(txAddInput))
            }

            is Either.Right -> {
                val next = copy(toSend = toSend.tail(), localOutputs = localOutputs + msg.value, txCompleteSent = null)
                val txAddOutput = when (msg.value) {
                    is InteractiveTxOutput.Local -> TxAddOutput(fundingParams.channelId, msg.value.serialId, msg.value.amount, msg.value.pubkeyScript)
                    is InteractiveTxOutput.Shared -> TxAddOutput(fundingParams.channelId, msg.value.serialId, msg.value.amount, msg.value.pubkeyScript)
                }
                Pair(next, InteractiveTxSessionAction.SendMessage(txAddOutput))
            }
        }
    }

    private fun receiveInput(message: TxAddInput): Either<InteractiveTxSessionAction.RemoteFailure, InteractiveTxInput.Incoming> {
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
                InteractiveTxInput.Shared(message.serialId, receivedSharedOutpoint, fundingParams.sharedInput.info.txOut, message.sequence, previousFunding.toLocal, previousFunding.toRemote)
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
                    message.swapInParamsMusig2 != null -> InteractiveTxInput.RemoteSwapInMusig2(message.serialId, outpoint, txOut, message.sequence, message.swapInParamsMusig2)
                    message.swapInParams != null -> InteractiveTxInput.RemoteSwapIn(message.serialId, outpoint, txOut, message.sequence, message.swapInParams)
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
        return Either.Right(input)
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
            Either.Right(InteractiveTxOutput.Shared(message.serialId, message.pubkeyScript, localAmount, remoteAmount))
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
                    { input -> copy(remoteInputs = remoteInputs + input, inputsReceivedCount = inputsReceivedCount + 1, txCompleteReceived = null).send() }
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
        val localOnlyInputsWithNonces = localOnlyInputs.map {
            when {
                it is InteractiveTxInput.LocalSwapIn && swapInKeys.swapInProtocolMusig2.isMine(it.txOut) -> {
                    val userNonce = secretNonces[it.serialId]
                    val serverNonce = txCompleteReceived.publicNonces[it.serialId]
                    if (userNonce == null || serverNonce == null) return InteractiveTxSessionAction.MissingNonce(fundingParams.channelId, it.serialId)
                    it
                }

                else -> it
            }
        }
        val remoteOnlyInputsWithNonces = remoteOnlyInputs.map {
            when {
                it is InteractiveTxInput.RemoteSwapInMusig2 -> {
                    val userNonce = secretNonces[it.serialId]
                    val serverNonce = txCompleteReceived.publicNonces[it.serialId]
                    if (userNonce == null || serverNonce == null) return InteractiveTxSessionAction.MissingNonce(fundingParams.channelId, it.serialId)
                    it
                }

                else -> it
            }
        }

        val sharedTx = SharedTransaction(sharedInput, sharedOutput, localOnlyInputsWithNonces, remoteOnlyInputsWithNonces, localOnlyOutputs, remoteOnlyOutputs, fundingParams.lockTime)
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
    object WaitForTxSigs : InteractiveTxSigningSessionAction()

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
    val localCommit: Either<UnsignedLocalCommit, LocalCommit>,
    val remoteCommit: RemoteCommit
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
                val fundingKey = channelKeys.fundingKey(fundingTxIndex)
                val localSigOfLocalTx = Transactions.sign(localCommit.value.commitTx, fundingKey)
                val signedLocalCommitTx = Transactions.addSigs(localCommit.value.commitTx, fundingKey.publicKey(), fundingParams.remoteFundingPubkey, localSigOfLocalTx, remoteCommitSig.signature)
                when (Transactions.checkSpendable(signedLocalCommitTx)) {
                    is Try.Failure -> {
                        logger.info { "interactiveTxSession=$this" }
                        logger.info { "channelParams=$channelParams" }
                        logger.info { "fundingKey=${fundingKey.publicKey()}" }
                        logger.info { "localSigOfLocalTx=$localSigOfLocalTx" }
                        logger.info { "signedLocalCommitTx=$signedLocalCommitTx" }
                        Pair(this, InteractiveTxSigningSessionAction.AbortFundingAttempt(InvalidCommitmentSignature(fundingParams.channelId, signedLocalCommitTx.tx.txid)))
                    }

                    is Try.Success -> {
                        val signedLocalCommit = LocalCommit(localCommit.value.index, localCommit.value.spec, PublishableTxs(signedLocalCommitTx, listOf()))
                        if (shouldSignFirst(channelParams, fundingTx.tx)) {
                            val fundingStatus = LocalFundingStatus.UnconfirmedFundingTx(fundingTx, fundingParams, currentBlockHeight)
                            val commitment = Commitment(fundingTxIndex, fundingParams.remoteFundingPubkey, fundingStatus, RemoteFundingStatus.NotLocked, signedLocalCommit, remoteCommit, nextRemoteCommit = null)
                            val action = InteractiveTxSigningSessionAction.SendTxSigs(fundingStatus, commitment, fundingTx.localSigs)
                            Pair(this.copy(localCommit = Either.Right(signedLocalCommit)), action)
                        } else {
                            Pair(this.copy(localCommit = Either.Right(signedLocalCommit)), InteractiveTxSigningSessionAction.WaitForTxSigs)
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
            localCommitmentIndex: Long,
            remoteCommitmentIndex: Long,
            commitTxFeerate: FeeratePerKw,
            remotePerCommitmentPoint: PublicKey
        ): Either<ChannelException, Pair<InteractiveTxSigningSession, CommitSig>> {
            val channelKeys = channelParams.localParams.channelKeys(keyManager)
            val unsignedTx = sharedTx.buildUnsignedTx()
            val sharedOutputIndex = unsignedTx.txOut.indexOfFirst { it.publicKeyScript == fundingParams.fundingPubkeyScript(channelKeys) }
            return Helpers.Funding.makeCommitTxsWithoutHtlcs(
                channelKeys,
                channelParams.channelId,
                channelParams.localParams, channelParams.remoteParams,
                fundingAmount = sharedTx.sharedOutput.amount,
                toLocal = sharedTx.sharedOutput.localAmount - localPushAmount + remotePushAmount,
                toRemote = sharedTx.sharedOutput.remoteAmount - remotePushAmount + localPushAmount,
                localCommitmentIndex = localCommitmentIndex,
                remoteCommitmentIndex = remoteCommitmentIndex,
                commitTxFeerate,
                fundingTxIndex = fundingTxIndex, fundingTxHash = unsignedTx.hash, fundingTxOutputIndex = sharedOutputIndex,
                remoteFundingPubkey = fundingParams.remoteFundingPubkey,
                remotePerCommitmentPoint = remotePerCommitmentPoint
            ).map { firstCommitTx ->
                val localSigOfRemoteTx = Transactions.sign(firstCommitTx.remoteCommitTx, channelKeys.fundingKey(fundingTxIndex))
                val alternativeSigs = Commitments.alternativeFeerates.map { feerate ->
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
                    val alternativeSig = Transactions.sign(alternativeRemoteCommitTx, channelKeys.fundingKey(fundingTxIndex))
                    CommitSigTlv.AlternativeFeerateSig(feerate, alternativeSig)
                }
                val commitSig = CommitSig(channelParams.channelId, localSigOfRemoteTx, listOf(), TlvStream(CommitSigTlv.AlternativeFeerateSigs(alternativeSigs)))
                val unsignedLocalCommit = UnsignedLocalCommit(localCommitmentIndex, firstCommitTx.localSpec, firstCommitTx.localCommitTx, listOf())
                val remoteCommit = RemoteCommit(remoteCommitmentIndex, firstCommitTx.remoteSpec, firstCommitTx.remoteCommitTx.tx.txid, remotePerCommitmentPoint)
                val signedFundingTx = sharedTx.sign(session, keyManager, fundingParams, channelParams.localParams, channelParams.remoteParams.nodeId)
                Pair(InteractiveTxSigningSession(fundingParams, fundingTxIndex, signedFundingTx, Either.Left(unsignedLocalCommit), remoteCommit), commitSig)
            }
        }

        fun shouldSignFirst(channelParams: ChannelParams, tx: SharedTransaction): Boolean {
            return if (tx.localAmountIn == tx.remoteAmountIn) {
                // When both peers contribute the same amount, the peer with the lowest pubkey must transmit its `tx_signatures` first.
                LexicographicalOrdering.isLessThan(channelParams.localParams.nodeId, channelParams.remoteParams.nodeId)
            } else {
                // Otherwise, the peer with the lowest total of input amount must transmit its `tx_signatures` first.
                tx.localAmountIn < tx.remoteAmountIn
            }
        }
    }

}

sealed class RbfStatus {
    object None : RbfStatus()
    data class RbfRequested(val command: ChannelCommand.Funding.BumpFundingFee) : RbfStatus()
    data class InProgress(val rbfSession: InteractiveTxSession) : RbfStatus()
    data class WaitingForSigs(val session: InteractiveTxSigningSession) : RbfStatus()
    object RbfAborted : RbfStatus()
}

sealed class SpliceStatus {
    object None : SpliceStatus()
    data class Requested(val command: ChannelCommand.Commitment.Splice.Request, val spliceInit: SpliceInit) : SpliceStatus()
    data class InProgress(
        val replyTo: CompletableDeferred<ChannelCommand.Commitment.Splice.Response>?,
        val spliceSession: InteractiveTxSession,
        val localPushAmount: MilliSatoshi,
        val remotePushAmount: MilliSatoshi,
        val origins: List<Origin.PayToOpenOrigin>
    ) : SpliceStatus()

    data class WaitingForSigs(val session: InteractiveTxSigningSession, val origins: List<Origin.PayToOpenOrigin>) : SpliceStatus()
    object Aborted : SpliceStatus()
}
