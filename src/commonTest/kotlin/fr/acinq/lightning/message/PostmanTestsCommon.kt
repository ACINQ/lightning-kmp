package fr.acinq.lightning.message

import fr.acinq.bitcoin.ByteVector
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.wire.GenericTlv
import fr.acinq.lightning.wire.OfferTypes
import fr.acinq.lightning.wire.OnionMessagePayloadTlv
import fr.acinq.lightning.wire.TlvStream
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class PostmanTestsCommon : LightningTestSuite() {
    @Test
    fun `send message and reply`() = runSuspendTest {
        val aliceKey = randomKey()
        val bobKey = randomKey()
        var alicePostman: Postman? = null
        val bobPostman = Postman(bobKey, aliceKey.publicKey()) { onionMessage ->
            launch { alicePostman!!.processOnionMessage(onionMessage) }
            null }
        alicePostman = Postman(aliceKey, bobKey.publicKey()) { onionMessage ->
            launch { bobPostman.processOnionMessage(onionMessage) }
            null
        }

        val pathId = randomBytes32()
        val blindedPath = OnionMessages.buildRoute(
            randomKey(),
            listOf(OnionMessages.IntermediateNode(bobKey.publicKey())),
            OnionMessages.Destination.Recipient(bobKey.publicKey(), pathId)
        )
        val tlvs1 = TlvStream<OnionMessagePayloadTlv>(setOf(), setOf(GenericTlv(55, ByteVector("c0de"))))
        val tlvs2 = TlvStream<OnionMessagePayloadTlv>(setOf(), setOf(GenericTlv(77, ByteVector("1dea12"))))
        launch {
            val messageToBob = bobPostman.receiveMessage(pathId)!!.messageOnion
            assertEquals(tlvs1.unknown, messageToBob.records.unknown)
            bobPostman.sendMessage(OfferTypes.ContactInfo.BlindedPath(messageToBob.replyPath!!), tlvs2)
        }
        val messageToAlice = alicePostman.sendMessageExpectingReply(OfferTypes.ContactInfo.BlindedPath(blindedPath), tlvs1, 3, 10.seconds).right!!.messageOnion
        assertEquals(tlvs2.unknown, messageToAlice.records.unknown)
    }
}