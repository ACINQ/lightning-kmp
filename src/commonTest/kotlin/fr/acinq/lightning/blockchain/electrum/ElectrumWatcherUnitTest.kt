package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.byteVector32
import fr.acinq.lightning.tests.utils.LightningTestSuite
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class ElectrumWatcherUnitTest : LightningTestSuite() {
    @Test
    fun `generate unique dummy scids`() {
        // generate 1000 dummy ids
        val dummies = (0 until 1000).map {
            ElectrumWatcher.makeDummyShortChannelId(Random.Default.nextBytes(32).byteVector32())
        }.toSet()

        // make sure that they are unique (we allow for 1 collision here, actual probability of a collision with the current impl. is 1%
        // but that could change and we don't want to make this test impl. dependent)
        // if this test fails it's very likely that the code that generates dummy scids is broken
        assertTrue { dummies.size >= 999 }
    }
}