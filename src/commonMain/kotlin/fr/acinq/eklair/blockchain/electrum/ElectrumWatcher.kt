package fr.acinq.eklair.blockchain.electrum

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.eklair.blockchain.Watch
import kotlinx.coroutines.launch
import org.kodein.log.LoggerFactory
import org.kodein.log.frontend.simplePrintFrontend
import org.kodein.log.newLogger
import kotlin.math.absoluteValue

interface ElectrumWatcher {

    fun start()
    fun stop()
    fun send(message: WatcherEvent)
    fun watch(watch: Watch)

    companion object {
        internal fun makeDummyShortChannelId(txid: ByteVector32): Pair<Int, Int> {
            // we use a height of 0
            // - to make sure that the tx will be marked as "confirmed"
            // - to easily identify scids linked to 0-conf channels
            //
            // this gives us a probability of collisions of 0.1% for 5 0-conf channels and 1% for 20
            // collisions mean that users may temporarily see incorrect numbers for their 0-conf channels (until they've been confirmed)
            // if this ever becomes a problem we could just extract some bits for our dummy height instead of just returning 0
            val height = 0
            val txIndex = Pack.int32BE(txid.slice(0, 16).toByteArray()).absoluteValue
            return height to txIndex
        }
    }
}