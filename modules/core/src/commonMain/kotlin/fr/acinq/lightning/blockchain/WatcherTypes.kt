package fr.acinq.lightning.blockchain

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.utils.Try
import fr.acinq.bitcoin.utils.runTrying

sealed class Watch {
    abstract val channelId: ByteVector32
}

sealed class WatchTriggered {
    abstract val channelId: ByteVector32
}

data class WatchConfirmed(
    override val channelId: ByteVector32,
    val txId: TxId,
    // We need a public key script to use electrum apis.
    val publicKeyScript: ByteVector,
    val minDepth: Int,
    val event: OnChainEvent,
) : Watch() {
    // If we have the entire transaction, we can get the redeemScript from the witness, and re-compute the publicKeyScript.
    // We support both p2pkh and p2wpkh scripts.
    constructor(channelId: ByteVector32, tx: Transaction, minDepth: Int, event: OnChainEvent) : this(
        channelId,
        tx.txid,
        if (tx.txOut.isEmpty()) ByteVector.empty else tx.txOut[0].publicKeyScript,
        minDepth,
        event
    )

    init {
        require(minDepth > 0) { "minimum depth must be greater than 0 when watching transaction confirmation" }
    }

    sealed class OnChainEvent
    data object ChannelFundingDepthOk : OnChainEvent()
    data object ClosingTxConfirmed : OnChainEvent()
    data class ParentTxConfirmed(val childTx: Transaction) : OnChainEvent()
    data object AlternativeCommitTxConfirmed : OnChainEvent()

    companion object {
        fun extractPublicKeyScript(witness: ScriptWitness): ByteVector {
            val result = runTrying {
                val pub = PublicKey(witness.last())
                Script.write(Script.pay2wpkh(pub))
            }
            return when (result) {
                is Try.Success -> ByteVector(result.result)
                is Try.Failure -> ByteVector(Script.write(Script.pay2wsh(witness.last())))
            }
        }
    }
}

data class WatchConfirmedTriggered(
    override val channelId: ByteVector32,
    val event: WatchConfirmed.OnChainEvent,
    val blockHeight: Int,
    val txIndex: Int,
    val tx: Transaction
) : WatchTriggered()

data class WatchSpent(
    override val channelId: ByteVector32,
    val txId: TxId,
    val outputIndex: Int,
    val publicKeyScript: ByteVector,
    val event: OnChainEvent
) : Watch() {
    constructor(channelId: ByteVector32, tx: Transaction, outputIndex: Int, event: OnChainEvent) : this(
        channelId,
        tx.txid,
        outputIndex,
        tx.txOut[outputIndex].publicKeyScript,
        event
    )

    // @formatter:off
    sealed class OnChainEvent { abstract val amount: Satoshi }
    data class ChannelSpent(override val amount: Satoshi) : OnChainEvent()
    data class ClosingOutputSpent(override val amount: Satoshi) : OnChainEvent()
    // @formatter:on
}

data class WatchSpentTriggered(
    override val channelId: ByteVector32,
    val event: WatchSpent.OnChainEvent,
    val spendingTx: Transaction
) : WatchTriggered()
