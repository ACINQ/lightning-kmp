package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.EncodedNodeId
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.NodeUri
import fr.acinq.lightning.ShortChannelId
import fr.acinq.lightning.crypto.RouteBlinding
import fr.acinq.lightning.crypto.sphinx.DecryptedPacket
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.io.PayOffer
import fr.acinq.lightning.io.PeerEvent
import fr.acinq.lightning.logging.MDCLogger
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.tests.utils.testLoggerFactory
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.MessageOnion
import fr.acinq.lightning.wire.OfferTypes
import fr.acinq.lightning.wire.OnionMessage
import fr.acinq.lightning.wire.RouteBlindingEncryptedData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class OfferManagerTestsCommon : LightningTestSuite() {
    val trampolineKey = randomKey()
    val walletParams = TestConstants.Alice.walletParams.copy(trampolineNode = NodeUri(trampolineKey.publicKey(), "trampoline.com", 9735))
    val logger: MDCLogger = MDCLogger(testLoggerFactory.newLogger(this::class))

    fun trampolineRelay(msg: OnionMessage): Pair<OnionMessage, Either<ShortChannelId, EncodedNodeId>> {
        val blindedPrivateKey = RouteBlinding.derivePrivateKey(trampolineKey, msg.blindingKey)
        val decrypted = Sphinx.peel(
            blindedPrivateKey,
            ByteVector.empty,
            msg.onionRoutingPacket
        )
        assertIs<Either.Right<DecryptedPacket>>(decrypted)
        assertFalse(decrypted.value.isLastPacket)
        val message = MessageOnion.read(decrypted.value.payload.toByteArray())
        val (decryptedPayload, nextBlinding) = RouteBlinding.decryptPayload(
            trampolineKey,
            msg.blindingKey,
            message.encryptedData
        ).right!!
        val relayInfo = RouteBlindingEncryptedData.read(decryptedPayload.toByteArray()).right!!

        return Pair(OnionMessage(relayInfo.nextBlindingOverride ?: nextBlinding, decrypted.value.nextPacket), relayInfo.nextNodeId?.let { Either.Right(it) } ?: Either.Left(relayInfo.outgoingChannelId!!))
    }

    @Test
    fun `offer workflow`() = runSuspendTest {
        val eventsFlow = MutableSharedFlow<PeerEvent>(extraBufferCapacity = 64)
        val aliceOfferManager = OfferManager(TestConstants.Alice.nodeParams, walletParams, eventsFlow, logger)
        val bobOfferManager = OfferManager(TestConstants.Bob.nodeParams, walletParams, eventsFlow, logger)

        val pathId = randomBytes32()
        val offer = OfferTypes.Offer.createBlindedOffer(
            1000.msat,
            "Blockaccino",
            TestConstants.Alice.nodeParams,
            walletParams.trampolineNode,
            pathId,
            setOf(OfferTypes.OfferQuantityMax(0))
        )
        aliceOfferManager.registerOffer(offer, pathId)

        val payOffer = PayOffer(UUID.randomUUID(), randomKey(), 5500.msat, offer, 20.seconds)
        val (_, invoiceRequests) = bobOfferManager.requestInvoice(payOffer)
        assertTrue(invoiceRequests.size == 1)
        val relay1 = trampolineRelay(invoiceRequests.first())
        assertEquals(Either.Right(EncodedNodeId(trampolineKey.publicKey())), relay1.second)
        val relay2 = trampolineRelay(relay1.first)
        assertEquals(Either.Left(ShortChannelId.peerId(TestConstants.Alice.nodeParams.nodeId)), relay2.second)
        val invoiceResponse = aliceOfferManager.receiveMessage(relay2.first, listOf(), 0)
        assertIs<OnionMessageAction.SendMessage>(invoiceResponse)
        val relay3 = trampolineRelay(invoiceResponse.message)
        assertEquals(Either.Right(EncodedNodeId(trampolineKey.publicKey())), relay3.second)
        val relay4 = trampolineRelay(relay3.first)
        assertEquals(Either.Right(EncodedNodeId(TestConstants.Bob.nodeParams.nodeId)), relay4.second)
        val payInvoice = bobOfferManager.receiveMessage(relay4.first, listOf(), 0)
        assertIs<OnionMessageAction.PayInvoice>(payInvoice)
        assertEquals(payOffer, payInvoice.payOffer)
    }
}