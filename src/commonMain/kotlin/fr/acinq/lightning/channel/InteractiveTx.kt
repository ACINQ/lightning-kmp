package fr.acinq.lightning.channel

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Script.tail
import fr.acinq.lightning.Lightning.secureRandom
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.Either
import fr.acinq.lightning.utils.sum
import fr.acinq.lightning.wire.*

/**
 * Created by t-bast on 22/08/2022.
 */

data class InteractiveTxParams(
    val channelId: ByteVector32,
    val isInitiator: Boolean,
    val localAmount: Satoshi,
    val remoteAmount: Satoshi,
    val fundingPubkeyScript: ByteVector,
    val lockTime: Long,
    val dustLimit: Satoshi,
    val targetFeerate: FeeratePerKw
) {
    val fundingAmount: Satoshi = localAmount + remoteAmount
}

/** Inputs and outputs we contribute to the funding transaction. */
data class FundingContributions(val inputs: List<TxAddInput>, val outputs: List<TxAddOutput>)

/** A lighter version of our peer's TxAddInput that avoids storing potentially large messages in our DB. */
data class RemoteTxAddInput(val serialId: Long, val outPoint: OutPoint, val txOut: TxOut, val sequence: Long) {
    constructor(i: TxAddInput) : this(i.serialId, OutPoint(i.previousTx, i.previousTxOutput), i.previousTx.txOut[i.previousTxOutput.toInt()], i.sequence)
}

/** A lighter version of our peer's TxAddOutput that avoids storing potentially large messages in our DB. */
data class RemoteTxAddOutput(val serialId: Long, val amount: Satoshi, val pubkeyScript: ByteVector) {
    constructor(o: TxAddOutput) : this(o.serialId, o.amount, o.pubkeyScript)
}

/** Unsigned transaction created collaboratively. */
data class SharedTransaction(val localInputs: List<TxAddInput>, val remoteInputs: List<RemoteTxAddInput>, val localOutputs: List<TxAddOutput>, val remoteOutputs: List<RemoteTxAddOutput>, val lockTime: Long) {
    val localAmountIn: Satoshi = localInputs.map { i -> i.previousTx.txOut[i.previousTxOutput.toInt()].amount }.sum()
    val remoteAmountIn: Satoshi = remoteInputs.map { i -> i.txOut.amount }.sum()
    val totalAmountIn: Satoshi = localAmountIn + remoteAmountIn
    val fees: Satoshi = totalAmountIn - localOutputs.map { i -> i.amount }.sum() - remoteOutputs.map { i -> i.amount }.sum()

    fun localFees(params: InteractiveTxParams): Satoshi {
        val localAmountOut = params.localAmount + localOutputs.filter { o -> o.pubkeyScript != params.fundingPubkeyScript }.map { o -> o.amount }.sum()
        return localAmountIn - localAmountOut
    }

    fun buildUnsignedTx(): Transaction {
        val localTxIn = localInputs.map { i -> Pair(i.serialId, TxIn(OutPoint(i.previousTx, i.previousTxOutput), ByteVector.empty, i.sequence)) }
        val remoteTxIn = remoteInputs.map { i -> Pair(i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence)) }
        val inputs = (localTxIn + remoteTxIn).sortedBy { (serialId, _) -> serialId }.map { (_, txIn) -> txIn }
        val localTxOut = localOutputs.map { o -> Pair(o.serialId, TxOut(o.amount, o.pubkeyScript)) }
        val remoteTxOut = remoteOutputs.map { o -> Pair(o.serialId, TxOut(o.amount, o.pubkeyScript)) }
        val outputs = (localTxOut + remoteTxOut).sortedBy { (serialId, _) -> serialId }.map { (_, txOut) -> txOut }
        return Transaction(2, inputs, outputs, lockTime)
    }
}

/** Signed transaction created collaboratively. */
sealed class SignedSharedTransaction {
    abstract val tx: SharedTransaction
    abstract val localSigs: TxSignatures
    abstract val signedTx: Transaction?
}

data class PartiallySignedSharedTransaction(override val tx: SharedTransaction, override val localSigs: TxSignatures) : SignedSharedTransaction() {
    override val signedTx = null
}

data class FullySignedSharedTransaction(override val tx: SharedTransaction, override val localSigs: TxSignatures, val remoteSigs: TxSignatures) : SignedSharedTransaction() {
    override val signedTx = run {
        require(localSigs.witnesses.size == tx.localInputs.size) { "the number of local signatures does not match the number of local inputs" }
        require(remoteSigs.witnesses.size == tx.remoteInputs.size) { "the number of remote signatures does not match the number of remote inputs" }
        val signedLocalInputs = tx.localInputs.sortedBy { i -> i.serialId }.zip(localSigs.witnesses).map { (i, w) -> Pair(i.serialId, TxIn(OutPoint(i.previousTx, i.previousTxOutput), ByteVector.empty, i.sequence, w)) }
        val signedRemoteInputs = tx.remoteInputs.sortedBy { i -> i.serialId }.zip(remoteSigs.witnesses).map { (i, w) -> Pair(i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence, w)) }
        val inputs = (signedLocalInputs + signedRemoteInputs).sortedBy { (serialId, _) -> serialId }.map { (_, i) -> i }
        val localTxOut = tx.localOutputs.map { o -> Pair(o.serialId, TxOut(o.amount, o.pubkeyScript)) }
        val remoteTxOut = tx.remoteOutputs.map { o -> Pair(o.serialId, TxOut(o.amount, o.pubkeyScript)) }
        val outputs = (localTxOut + remoteTxOut).sortedBy { (serialId, _) -> serialId }.map { (_, o) -> o }
        Transaction(2, inputs, outputs, tx.lockTime)
    }
    val feerate: FeeratePerKw = Transactions.fee2rate(tx.fees, signedTx.weight())
}

sealed class InteractiveTxSessionAction {
    data class SendMessage(val msg: InteractiveTxConstructionMessage) : InteractiveTxSessionAction()
    data class SignSharedTx(val sharedTx: SharedTransaction, val sharedOutputIndex: Int, val txComplete: TxComplete?) : InteractiveTxSessionAction()
    sealed class RemoteFailure : InteractiveTxSessionAction()
    data class InvalidSerialId(val channelId: ByteVector32, val serialId: Long) : RemoteFailure()
    data class UnknownSerialId(val channelId: ByteVector32, val serialId: Long) : RemoteFailure()
    data class TooManyInteractiveTxRounds(val channelId: ByteVector32) : RemoteFailure()
    data class DuplicateSerialId(val channelId: ByteVector32, val serialId: Long) : RemoteFailure()
    data class DuplicateInput(val channelId: ByteVector32, val serialId: Long, val previousTxId: ByteVector32, val previousTxOutput: Long) : RemoteFailure()
    data class InputOutOfBounds(val channelId: ByteVector32, val serialId: Long, val previousTxId: ByteVector32, val previousTxOutput: Long) : RemoteFailure()
    data class NonSegwitInput(val channelId: ByteVector32, val serialId: Long, val previousTxId: ByteVector32, val previousTxOutput: Long) : RemoteFailure()
    data class NonSegwitOutput(val channelId: ByteVector32, val serialId: Long) : RemoteFailure()
    data class OutputBelowDust(val channelId: ByteVector32, val serialId: Long, val amount: Satoshi, val dustLimit: Satoshi) : RemoteFailure()
    data class InvalidTxInputOutputCount(val channelId: ByteVector32, val txId: ByteVector32, val inputCount: Int, val outputCount: Int) : RemoteFailure()
    data class InvalidTxSharedOutput(val channelId: ByteVector32, val txId: ByteVector32) : RemoteFailure()
    data class InvalidTxSharedAmount(val channelId: ByteVector32, val txId: ByteVector32, val amount: Satoshi, val expected: Satoshi) : RemoteFailure()
    data class InvalidTxChangeAmount(val channelId: ByteVector32, val txId: ByteVector32) : RemoteFailure()
    data class InvalidTxWeight(val channelId: ByteVector32, val txId: ByteVector32) : RemoteFailure()
    data class InvalidTxFeerate(val channelId: ByteVector32, val txId: ByteVector32, val targetFeerate: FeeratePerKw, val actualFeerate: FeeratePerKw) : RemoteFailure()
    data class InvalidTxDoesNotDoubleSpendPreviousTx(val channelId: ByteVector32, val txId: ByteVector32, val previousTxId: ByteVector32) : RemoteFailure()
}

data class InteractiveTxSession(
    val fundingParams: InteractiveTxParams,
    val toSend: List<Either<TxAddInput, TxAddOutput>>,
    val previousTxs: List<SignedSharedTransaction> = listOf(),
    val localInputs: List<TxAddInput> = listOf(),
    val remoteInputs: List<TxAddInput> = listOf(),
    val localOutputs: List<TxAddOutput> = listOf(),
    val remoteOutputs: List<TxAddOutput> = listOf(),
    val txCompleteSent: Boolean = false,
    val txCompleteReceived: Boolean = false,
    val inputsReceivedCount: Int = 0,
    val outputsReceivedCount: Int = 0,
) {
    constructor(fundingParams: InteractiveTxParams, fundingContributions: FundingContributions, previousTxs: List<SignedSharedTransaction> = listOf()) : this(
        fundingParams,
        fundingContributions.inputs.map { i -> Either.Left<TxAddInput, TxAddOutput>(i) } + fundingContributions.outputs.map { o -> Either.Right<TxAddInput, TxAddOutput>(o) },
        previousTxs
    )

    val isComplete: Boolean = txCompleteSent && txCompleteReceived

    fun send(): Pair<InteractiveTxSession, InteractiveTxSessionAction> {
        return when (val msg = toSend.firstOrNull()) {
            null -> {
                val txComplete = TxComplete(fundingParams.channelId)
                val next = copy(txCompleteSent = true)
                if (next.isComplete) {
                    Pair(next, next.validateTx(txComplete))
                } else {
                    Pair(next, InteractiveTxSessionAction.SendMessage(txComplete))
                }
            }
            is Either.Left -> {
                val next = copy(toSend = toSend.tail(), localInputs = localInputs + msg.value, txCompleteSent = false)
                Pair(next, InteractiveTxSessionAction.SendMessage(msg.value))
            }
            is Either.Right -> {
                val next = copy(toSend = toSend.tail(), localOutputs = localOutputs + msg.value, txCompleteSent = false)
                Pair(next, InteractiveTxSessionAction.SendMessage(msg.value))
            }
        }
    }

    fun receive(message: InteractiveTxConstructionMessage): Pair<InteractiveTxSession, InteractiveTxSessionAction> {
        if (message is HasSerialId && (message.serialId.mod(2) == 1) != fundingParams.isInitiator) {
            return Pair(this, InteractiveTxSessionAction.InvalidSerialId(fundingParams.channelId, message.serialId))
        }
        return when (message) {
            is TxAddInput -> {
                if (inputsReceivedCount + 1 >= MAX_INPUTS_OUTPUTS_RECEIVED) {
                    Pair(this, InteractiveTxSessionAction.TooManyInteractiveTxRounds(message.channelId))
                } else if (remoteInputs.find { i -> i.serialId == message.serialId } != null) {
                    Pair(this, InteractiveTxSessionAction.DuplicateSerialId(message.channelId, message.serialId))
                } else if (message.previousTx.txOut.size <= message.previousTxOutput) {
                    Pair(this, InteractiveTxSessionAction.InputOutOfBounds(message.channelId, message.serialId, message.previousTx.txid, message.previousTxOutput))
                } else if ((localInputs.map { i -> OutPoint(i.previousTx, i.previousTxOutput) } + remoteInputs.map { i -> OutPoint(i.previousTx, i.previousTxOutput) }).contains(OutPoint(message.previousTx, message.previousTxOutput))) {
                    Pair(this, InteractiveTxSessionAction.DuplicateInput(message.channelId, message.serialId, message.previousTx.txid, message.previousTxOutput))
                } else if (!Script.isNativeWitnessScript(message.previousTx.txOut[message.previousTxOutput.toInt()].publicKeyScript)) {
                    Pair(this, InteractiveTxSessionAction.NonSegwitInput(message.channelId, message.serialId, message.previousTx.txid, message.previousTxOutput))
                } else {
                    val next = copy(remoteInputs = remoteInputs + message, inputsReceivedCount = inputsReceivedCount + 1, txCompleteReceived = false)
                    next.send()
                }
            }
            is TxAddOutput -> {
                if (outputsReceivedCount + 1 >= MAX_INPUTS_OUTPUTS_RECEIVED) {
                    Pair(this, InteractiveTxSessionAction.TooManyInteractiveTxRounds(message.channelId))
                } else if (remoteOutputs.find { o -> o.serialId == message.serialId } != null) {
                    Pair(this, InteractiveTxSessionAction.DuplicateSerialId(message.channelId, message.serialId))
                } else if (message.amount < fundingParams.dustLimit) {
                    Pair(this, InteractiveTxSessionAction.OutputBelowDust(message.channelId, message.serialId, message.amount, fundingParams.dustLimit))
                } else if (!Script.isNativeWitnessScript(message.pubkeyScript)) {
                    Pair(this, InteractiveTxSessionAction.NonSegwitOutput(message.channelId, message.serialId))
                } else {
                    val next = copy(remoteOutputs = remoteOutputs + message, outputsReceivedCount = outputsReceivedCount + 1, txCompleteReceived = false)
                    next.send()
                }
            }
            is TxRemoveInput -> {
                val remoteInputs1 = remoteInputs.filterNot { i -> i.serialId == message.serialId }
                if (remoteInputs.size != remoteInputs1.size) {
                    val next = copy(remoteInputs = remoteInputs1, txCompleteReceived = false)
                    next.send()
                } else {
                    Pair(this, InteractiveTxSessionAction.UnknownSerialId(message.channelId, message.serialId))
                }
            }
            is TxRemoveOutput -> {
                val remoteOutputs1 = remoteOutputs.filterNot { i -> i.serialId == message.serialId }
                if (remoteOutputs.size != remoteOutputs1.size) {
                    val next = copy(remoteOutputs = remoteOutputs1, txCompleteReceived = false)
                    next.send()
                } else {
                    Pair(this, InteractiveTxSessionAction.UnknownSerialId(message.channelId, message.serialId))
                }
            }
            is TxComplete -> {
                val next = copy(txCompleteReceived = true)
                if (next.isComplete) {
                    Pair(next, next.validateTx(null))
                } else {
                    next.send()
                }
            }
        }
    }

    private fun validateTx(txComplete: TxComplete?): InteractiveTxSessionAction {
        val sharedTx = SharedTransaction(localInputs, remoteInputs.map { i -> RemoteTxAddInput(i) }, localOutputs, remoteOutputs.map { o -> RemoteTxAddOutput(o) }, fundingParams.lockTime)
        val tx = sharedTx.buildUnsignedTx()

        if (tx.txIn.size > 252 || tx.txOut.size > 252) {
            return InteractiveTxSessionAction.InvalidTxInputOutputCount(fundingParams.channelId, tx.txid, tx.txIn.size, tx.txOut.size)
        }

        val sharedOutputs = tx.txOut.withIndex().filter { txOut -> txOut.value.publicKeyScript == fundingParams.fundingPubkeyScript }
        if (sharedOutputs.size != 1) {
            return InteractiveTxSessionAction.InvalidTxSharedOutput(fundingParams.channelId, tx.txid)
        }
        val (sharedOutputIndex, sharedOutput) = sharedOutputs.first()
        if (sharedOutput.amount != fundingParams.fundingAmount) {
            return InteractiveTxSessionAction.InvalidTxSharedAmount(fundingParams.channelId, tx.txid, sharedOutput.amount, fundingParams.fundingAmount)
        }

        val localAmountOut = sharedTx.localOutputs.filter { o -> o.pubkeyScript != fundingParams.fundingPubkeyScript }.map { o -> o.amount }.sum() + fundingParams.localAmount
        val remoteAmountOut = sharedTx.remoteOutputs.filter { o -> o.pubkeyScript != fundingParams.fundingPubkeyScript }.map { o -> o.amount }.sum() + fundingParams.remoteAmount
        if (sharedTx.localAmountIn < localAmountOut || sharedTx.remoteAmountIn < remoteAmountOut) {
            return InteractiveTxSessionAction.InvalidTxChangeAmount(fundingParams.channelId, tx.txid)
        }

        // The transaction isn't signed yet, so we estimate its weight knowing that all inputs are using native segwit.
        val minimumWitnessWeight = 107 // see Bolt 3
        val minimumWeight = tx.weight() + tx.txIn.size * minimumWitnessWeight
        if (minimumWeight > Transactions.MAX_STANDARD_TX_WEIGHT) {
            return InteractiveTxSessionAction.InvalidTxWeight(fundingParams.channelId, tx.txid)
        }
        val minimumFee = Transactions.weight2fee(fundingParams.targetFeerate, minimumWeight)
        if (sharedTx.fees < minimumFee) {
            return InteractiveTxSessionAction.InvalidTxFeerate(fundingParams.channelId, tx.txid, fundingParams.targetFeerate, Transactions.fee2rate(sharedTx.fees, minimumWeight))
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

        return InteractiveTxSessionAction.SignSharedTx(sharedTx, sharedOutputIndex, txComplete)
    }

    companion object {
        // We restrict the number of inputs / outputs that our peer can send us to ensure the protocol eventually ends.
        const val MAX_INPUTS_OUTPUTS_RECEIVED = 4096

        /** The initiator must use even values and the non-initiator odd values. */
        fun generateSerialId(isInitiator: Boolean): Long {
            val l = secureRandom.nextLong()
            return if (isInitiator) (l / 2) * 2 else (l / 2) * 2 + 1
        }
    }
}
