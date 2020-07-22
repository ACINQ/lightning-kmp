package fr.acinq.eklair.blockchain

import fr.acinq.bitcoin.*
import fr.acinq.eklair.ShortChannelId
import fr.acinq.eklair.utils.Try
import fr.acinq.eklair.utils.runTrying

sealed class BitcoinEvent
object BITCOIN_FUNDING_PUBLISH_FAILED : BitcoinEvent()
object BITCOIN_FUNDING_DEPTHOK : BitcoinEvent()
object BITCOIN_FUNDING_DEEPLYBURIED : BitcoinEvent()
object BITCOIN_FUNDING_LOST : BitcoinEvent()
object BITCOIN_FUNDING_TIMEOUT : BitcoinEvent()
object BITCOIN_FUNDING_SPENT : BitcoinEvent()
object BITCOIN_OUTPUT_SPENT : BitcoinEvent()
data class BITCOIN_TX_CONFIRMED(val tx: Transaction) : BitcoinEvent()
data class BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(val shortChannelId: ShortChannelId) : BitcoinEvent()
data class BITCOIN_PARENT_TX_CONFIRMED(val childTx: Transaction) : BitcoinEvent()

/**
 * generic "Watch" request
 */
sealed class Watch {
    abstract val event: BitcoinEvent
}

// we need a public key script to use electrum apis
data class WatchConfirmed(val txId: ByteVector32, val publicKeyScript: ByteVector, val minDepth: Long, override val event: BitcoinEvent) : Watch() {
    // if we have the entire transaction, we can get the redeemScript from the witness, and re-compute the publicKeyScript
    // we support both p2pkh and p2wpkh scripts
    constructor(tx: Transaction, minDepth: Long, event: BitcoinEvent) : this(
        tx.txid,
        if (tx.txOut.isEmpty()) ByteVector.empty else tx.txOut[0].publicKeyScript,
        minDepth,
        event
    )

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

data class WatchSpent(val txId: ByteVector32, val outputIndex: Int, val publicKeyScript: ByteVector, override val event: BitcoinEvent) : Watch() {
    constructor(tx: Transaction, outputIndex: Int, event: BitcoinEvent) : this(tx.txid, outputIndex, tx.txOut[outputIndex].publicKeyScript, event)
}

data class WatchLost(val txId: ByteVector32, val minDepth: Long, override val event: BitcoinEvent) : Watch()

/**
 * generic "watch" event
 */
sealed class WatchEvent {
    abstract val event: BitcoinEvent
}

data class WatchEventConfirmed(override val event: BitcoinEvent, val blockHeight: Int, val txIndex: Int, val tx: Transaction) : WatchEvent()
data class WatchEventSpent(override val event: BitcoinEvent, val tx: Transaction) : WatchEvent()
data class WatchEventSpentBasic(override val event: BitcoinEvent) : WatchEvent()
data class WatchEventLost(override val event: BitcoinEvent) : WatchEvent()
