package fr.acinq.lightning.payment

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.nodeFee
import fr.acinq.lightning.Lightning.randomBytes
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomBytes64
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.channel.Channel
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.router.ChannelHop
import fr.acinq.lightning.router.NodeHop
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*
import kotlin.test.*

class PaymentPacketTestsCommon : LightningTestSuite() {

    companion object {

        private val privA = randomKey()
        private val a = privA.publicKey()
        private val privB = randomKey()
        private val b = privB.publicKey()
        private val privC = randomKey()
        private val c = privC.publicKey()
        private val privD = randomKey()
        private val d = privD.publicKey()
        private val privE = randomKey()
        private val e = privE.publicKey()
        private val defaultChannelUpdate = ChannelUpdate(randomBytes64(), Block.RegtestGenesisBlock.hash, ShortChannelId(0), 0, 1, 0, CltvExpiryDelta(0), 42000.msat, 0.msat, 0, 500000000.msat)
        private val channelUpdateAB = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(1), cltvExpiryDelta = CltvExpiryDelta(4), feeBaseMsat = 642000.msat, feeProportionalMillionths = 7)
        private val channelUpdateBC = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(2), cltvExpiryDelta = CltvExpiryDelta(5), feeBaseMsat = 153000.msat, feeProportionalMillionths = 4)
        private val channelUpdateCD = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(3), cltvExpiryDelta = CltvExpiryDelta(10), feeBaseMsat = 60000.msat, feeProportionalMillionths = 1)
        private val channelUpdateDE = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(4), cltvExpiryDelta = CltvExpiryDelta(7), feeBaseMsat = 766000.msat, feeProportionalMillionths = 10)

        // simple route a -> b -> c -> d -> e
        private val hops = listOf(
            ChannelHop(a, b, channelUpdateAB),
            ChannelHop(b, c, channelUpdateBC),
            ChannelHop(c, d, channelUpdateCD),
            ChannelHop(d, e, channelUpdateDE)
        )

        private val finalAmount = 42000000.msat
        private const val currentBlockCount = 400000L
        private val finalExpiry = CltvExpiry(currentBlockCount) + Channel.MIN_CLTV_EXPIRY_DELTA
        private val paymentPreimage = randomBytes32()
        private val paymentHash = Crypto.sha256(paymentPreimage).toByteVector32()
        private val paymentSecret = randomBytes32()
        private val paymentMetadata = randomBytes(64).toByteVector()

        private val expiryDE = finalExpiry
        private val amountDE = finalAmount
        private val feeD = nodeFee(channelUpdateDE.feeBaseMsat, channelUpdateDE.feeProportionalMillionths, amountDE)

        private val expiryCD = expiryDE + channelUpdateDE.cltvExpiryDelta
        private val amountCD = amountDE + feeD
        private val feeC = nodeFee(channelUpdateCD.feeBaseMsat, channelUpdateCD.feeProportionalMillionths, amountCD)

        private val expiryBC = expiryCD + channelUpdateCD.cltvExpiryDelta
        private val amountBC = amountCD + feeC
        private val feeB = nodeFee(channelUpdateBC.feeBaseMsat, channelUpdateBC.feeProportionalMillionths, amountBC)

        private val expiryAB = expiryBC + channelUpdateBC.cltvExpiryDelta
        private val amountAB = amountBC + feeB

        // simple trampoline route to e:
        //             .--.   .--.
        //            /    \ /    \
        // a -> b -> c      d      e

        private val trampolineHops = listOf(
            NodeHop(a, c, channelUpdateAB.cltvExpiryDelta + channelUpdateBC.cltvExpiryDelta, feeB),
            NodeHop(c, d, channelUpdateCD.cltvExpiryDelta, feeC),
            NodeHop(d, e, channelUpdateDE.cltvExpiryDelta, feeD)
        )

        private val trampolineChannelHops = listOf(
            ChannelHop(a, b, channelUpdateAB),
            ChannelHop(b, c, channelUpdateBC)
        )

        private fun testBuildOnion() {
            val finalPayload =
                PaymentOnion.FinalPayload(TlvStream(listOf(OnionPaymentPayloadTlv.AmountToForward(finalAmount), OnionPaymentPayloadTlv.OutgoingCltv(finalExpiry), OnionPaymentPayloadTlv.PaymentData(paymentSecret, finalAmount))))
            val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacket(paymentHash, hops, finalPayload, OnionRoutingPacket.PaymentPacketLength)
            assertEquals(amountAB, firstAmount)
            assertEquals(expiryAB, firstExpiry)
            assertEquals(OnionRoutingPacket.PaymentPacketLength, onion.packet.payload.size())
            // let's peel the onion
            testPeelOnion(onion.packet)
        }

        private fun testPeelOnion(packet_b: OnionRoutingPacket) {
            val addB = UpdateAddHtlc(randomBytes32(), 0, amountAB, paymentHash, expiryAB, packet_b)
            val (payloadB, packetC) = decryptChannelRelay(addB, privB)
            assertEquals(OnionRoutingPacket.PaymentPacketLength, packetC.payload.size())
            assertEquals(amountBC, payloadB.amountToForward)
            assertEquals(expiryBC, payloadB.outgoingCltv)
            assertEquals(channelUpdateBC.shortChannelId, payloadB.outgoingChannelId)

            val addC = UpdateAddHtlc(randomBytes32(), 1, amountBC, paymentHash, expiryBC, packetC)
            val (payloadC, packetD) = decryptChannelRelay(addC, privC)
            assertEquals(OnionRoutingPacket.PaymentPacketLength, packetD.payload.size())
            assertEquals(amountCD, payloadC.amountToForward)
            assertEquals(expiryCD, payloadC.outgoingCltv)
            assertEquals(channelUpdateCD.shortChannelId, payloadC.outgoingChannelId)

            val addD = UpdateAddHtlc(randomBytes32(), 2, amountCD, paymentHash, expiryCD, packetD)
            val (payloadD, packetE) = decryptChannelRelay(addD, privD)
            assertEquals(OnionRoutingPacket.PaymentPacketLength, packetE.payload.size())
            assertEquals(amountDE, payloadD.amountToForward)
            assertEquals(expiryDE, payloadD.outgoingCltv)
            assertEquals(channelUpdateDE.shortChannelId, payloadD.outgoingChannelId)

            val addE = UpdateAddHtlc(randomBytes32(), 2, amountDE, paymentHash, expiryDE, packetE)
            val payloadE = IncomingPaymentPacket.decrypt(addE, privE).right!!
            assertEquals(finalAmount, payloadE.amount)
            assertEquals(finalAmount, payloadE.totalAmount)
            assertEquals(finalExpiry, payloadE.expiry)
            assertEquals(paymentSecret, payloadE.paymentSecret)
        }

        // Wallets don't need to decrypt onions for intermediate nodes, but it's useful to test that encryption works correctly.
        fun decryptChannelRelay(add: UpdateAddHtlc, privateKey: PrivateKey): Pair<PaymentOnion.ChannelRelayPayload, OnionRoutingPacket> {
            val decrypted = Sphinx.peel(privateKey, add.paymentHash, add.onionRoutingPacket, OnionRoutingPacket.PaymentPacketLength).right!!
            assertFalse(decrypted.isLastPacket)
            val decoded = PaymentOnion.ChannelRelayPayload.read(ByteArrayInput(decrypted.payload.toByteArray()))
            return Pair(decoded, decrypted.nextPacket)
        }

        // Wallets don't need to decrypt onions for intermediate nodes, but it's useful to test that encryption works correctly.
        fun decryptNodeRelay(add: UpdateAddHtlc, privateKey: PrivateKey): Triple<PaymentOnion.FinalPayload, PaymentOnion.NodeRelayPayload, OnionRoutingPacket> {
            val decrypted = Sphinx.peel(privateKey, add.paymentHash, add.onionRoutingPacket, OnionRoutingPacket.PaymentPacketLength).right!!
            assertTrue(decrypted.isLastPacket)
            val outerPayload = PaymentOnion.FinalPayload.read(ByteArrayInput(decrypted.payload.toByteArray()))
            val trampolineOnion = outerPayload.records.get<OnionPaymentPayloadTlv.TrampolineOnion>()
            assertNotNull(trampolineOnion)
            val decryptedInner = Sphinx.peel(privateKey, add.paymentHash, trampolineOnion.packet, OnionRoutingPacket.TrampolinePacketLength).right!!
            val innerPayload = PaymentOnion.NodeRelayPayload.read(ByteArrayInput(decryptedInner.payload.toByteArray()))
            return Triple(outerPayload, innerPayload, decryptedInner.nextPacket)
        }

    }

    @Test
    fun `build onion`() {
        testBuildOnion()
    }

    @Test
    fun `build a command including the onion`() {
        val (add, _) = OutgoingPaymentPacket.buildCommand(UUID.randomUUID(), paymentHash, hops, PaymentOnion.FinalPayload.createSinglePartPayload(finalAmount, finalExpiry, paymentSecret, null))
        assertTrue(add.amount > finalAmount)
        assertEquals(add.cltvExpiry, finalExpiry + channelUpdateDE.cltvExpiryDelta + channelUpdateCD.cltvExpiryDelta + channelUpdateBC.cltvExpiryDelta)
        assertEquals(add.paymentHash, paymentHash)
        assertEquals(add.onion.payload.size(), OnionRoutingPacket.PaymentPacketLength)

        // let's peel the onion
        testPeelOnion(add.onion)
    }

    @Test
    fun `build a command with no hops`() {
        val paymentSecret = randomBytes32()
        val (add, _) = OutgoingPaymentPacket.buildCommand(UUID.randomUUID(), paymentHash, hops.take(1), PaymentOnion.FinalPayload.createSinglePartPayload(finalAmount, finalExpiry, paymentSecret, paymentMetadata))
        assertEquals(add.amount, finalAmount)
        assertEquals(add.cltvExpiry, finalExpiry)
        assertEquals(add.paymentHash, paymentHash)
        assertEquals(add.onion.payload.size(), OnionRoutingPacket.PaymentPacketLength)

        // let's peel the onion
        val addB = UpdateAddHtlc(randomBytes32(), 0, finalAmount, paymentHash, finalExpiry, add.onion)
        val finalPayload = IncomingPaymentPacket.decrypt(addB, privB).right!!
        assertEquals(finalPayload.amount, finalAmount)
        assertEquals(finalPayload.totalAmount, finalAmount)
        assertEquals(finalPayload.expiry, finalExpiry)
        assertEquals(paymentSecret, finalPayload.paymentSecret)
        assertEquals(paymentMetadata, finalPayload.paymentMetadata)
    }

    @Test
    fun `build a trampoline payment`() {
        // simple trampoline route to e:
        //             .--.   .--.
        //            /    \ /    \
        // a -> b -> c      d      e

        val (amountAC, expiryAC, trampolineOnion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            trampolineHops,
            PaymentOnion.FinalPayload.createMultiPartPayload(finalAmount, finalAmount * 3, finalExpiry, paymentSecret, paymentMetadata),
            OnionRoutingPacket.TrampolinePacketLength
        )
        assertEquals(amountBC, amountAC)
        assertEquals(expiryBC, expiryAC)

        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            trampolineChannelHops,
            PaymentOnion.FinalPayload.createTrampolinePayload(amountAC, amountAC, expiryAC, randomBytes32(), trampolineOnion.packet),
            OnionRoutingPacket.PaymentPacketLength
        )
        assertEquals(amountAB, firstAmount)
        assertEquals(expiryAB, firstExpiry)

        val addB = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val (payloadB, packetC) = decryptChannelRelay(addB, privB)
        assertEquals(PaymentOnion.ChannelRelayPayload.create(channelUpdateBC.shortChannelId, amountBC, expiryBC), payloadB)

        val addC = UpdateAddHtlc(randomBytes32(), 2, amountBC, paymentHash, expiryBC, packetC)
        val (outerC, innerC, packetD) = decryptNodeRelay(addC, privC)
        assertEquals(amountBC, outerC.amount)
        assertEquals(amountBC, outerC.totalAmount)
        assertEquals(expiryBC, outerC.expiry)
        assertEquals(amountCD, innerC.amountToForward)
        assertEquals(expiryCD, innerC.outgoingCltv)
        assertEquals(d, innerC.outgoingNodeId)
        assertNull(innerC.invoiceRoutingInfo)
        assertNull(innerC.invoiceFeatures)
        assertNull(innerC.paymentSecret)

        // c forwards the trampoline payment to d.
        val (amountD, expiryD, onionD) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(c, d, channelUpdateCD)),
            PaymentOnion.FinalPayload.createTrampolinePayload(amountCD, amountCD, expiryCD, randomBytes32(), packetD),
            OnionRoutingPacket.PaymentPacketLength
        )
        assertEquals(amountCD, amountD)
        assertEquals(expiryCD, expiryD)
        val addD = UpdateAddHtlc(randomBytes32(), 3, amountD, paymentHash, expiryD, onionD.packet)
        val (outerD, innerD, packetE) = decryptNodeRelay(addD, privD)
        assertEquals(amountCD, outerD.amount)
        assertEquals(amountCD, outerD.totalAmount)
        assertEquals(expiryCD, outerD.expiry)
        assertEquals(amountDE, innerD.amountToForward)
        assertEquals(expiryDE, innerD.outgoingCltv)
        assertEquals(e, innerD.outgoingNodeId)
        assertNull(innerD.invoiceRoutingInfo)
        assertNull(innerD.invoiceFeatures)
        assertNull(innerD.paymentSecret)

        // d forwards the trampoline payment to e.
        val (amountE, expiryE, onionE) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(d, e, channelUpdateDE)),
            PaymentOnion.FinalPayload.createTrampolinePayload(amountDE, amountDE, expiryDE, randomBytes32(), packetE),
            OnionRoutingPacket.PaymentPacketLength
        )
        assertEquals(amountDE, amountE)
        assertEquals(expiryDE, expiryE)
        val addE = UpdateAddHtlc(randomBytes32(), 4, amountE, paymentHash, expiryE, onionE.packet)
        val payloadE = IncomingPaymentPacket.decrypt(addE, privE).right!!
        val expectedFinalPayload = PaymentOnion.FinalPayload(
            TlvStream(
                listOf(
                    OnionPaymentPayloadTlv.AmountToForward(finalAmount),
                    OnionPaymentPayloadTlv.OutgoingCltv(finalExpiry),
                    OnionPaymentPayloadTlv.PaymentData(paymentSecret, finalAmount * 3),
                    OnionPaymentPayloadTlv.PaymentMetadata(paymentMetadata)
                )
            )
        )
        assertEquals(payloadE, expectedFinalPayload)
    }

    @Test
    fun `build a trampoline payment with non-trampoline recipient`() {
        // simple trampoline route to e where e doesn't support trampoline:
        //             .--.
        //            /    \
        // a -> b -> c      d -> e

        val routingHints = listOf(PaymentRequest.TaggedField.ExtraHop(randomKey().publicKey(), ShortChannelId(42), 10.msat, 100, CltvExpiryDelta(144)))
        val invoiceFeatures = Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory, Feature.PaymentSecret to FeatureSupport.Mandatory, Feature.BasicMultiPartPayment to FeatureSupport.Optional)
        val invoice = PaymentRequest(
            "lnbcrt", finalAmount, currentTimestampSeconds(), e, listOf(
                PaymentRequest.TaggedField.PaymentHash(paymentHash),
                PaymentRequest.TaggedField.PaymentSecret(paymentSecret),
                PaymentRequest.TaggedField.PaymentMetadata(paymentMetadata),
                PaymentRequest.TaggedField.DescriptionHash(randomBytes32()),
                PaymentRequest.TaggedField.Features(invoiceFeatures.toByteArray().toByteVector()),
                PaymentRequest.TaggedField.RoutingInfo(routingHints)
            ), ByteVector.empty
        )
        val (amountAC, expiryAC, trampolineOnion) = OutgoingPaymentPacket.buildTrampolineToLegacyPacket(invoice, trampolineHops, PaymentOnion.FinalPayload.createSinglePartPayload(finalAmount, finalExpiry, randomBytes32(), null))
        assertEquals(amountBC, amountAC)
        assertEquals(expiryBC, expiryAC)

        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            trampolineChannelHops,
            PaymentOnion.FinalPayload.createTrampolinePayload(amountAC, amountAC, expiryAC, randomBytes32(), trampolineOnion.packet),
            OnionRoutingPacket.PaymentPacketLength
        )
        assertEquals(amountAB, firstAmount)
        assertEquals(expiryAB, firstExpiry)

        val addB = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val (_, packetC) = decryptChannelRelay(addB, privB)

        val addC = UpdateAddHtlc(randomBytes32(), 2, amountBC, paymentHash, expiryBC, packetC)
        val (outerC, innerC, packetD) = decryptNodeRelay(addC, privC)
        assertEquals(amountBC, outerC.amount)
        assertEquals(amountBC, outerC.totalAmount)
        assertEquals(expiryBC, outerC.expiry)
        assertNotEquals(invoice.paymentSecret, outerC.paymentSecret)
        assertEquals(amountCD, innerC.amountToForward)
        assertEquals(expiryCD, innerC.outgoingCltv)
        assertEquals(d, innerC.outgoingNodeId)
        assertNull(innerC.invoiceRoutingInfo)
        assertNull(innerC.invoiceFeatures)
        assertNull(innerC.paymentSecret)

        // c forwards the trampoline payment to d.
        val (amountD, expiryD, onionD) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(c, d, channelUpdateCD)),
            PaymentOnion.FinalPayload.createTrampolinePayload(amountCD, amountCD, expiryCD, randomBytes32(), packetD),
            OnionRoutingPacket.PaymentPacketLength
        )
        assertEquals(amountCD, amountD)
        assertEquals(expiryCD, expiryD)
        val addD = UpdateAddHtlc(randomBytes32(), 3, amountD, paymentHash, expiryD, onionD.packet)
        val (outerD, innerD, _) = decryptNodeRelay(addD, privD)
        assertEquals(amountCD, outerD.amount)
        assertEquals(amountCD, outerD.totalAmount)
        assertEquals(expiryCD, outerD.expiry)
        assertNotEquals(invoice.paymentSecret, outerD.paymentSecret)
        assertEquals(finalAmount, innerD.amountToForward)
        assertEquals(expiryDE, innerD.outgoingCltv)
        assertEquals(e, innerD.outgoingNodeId)
        assertEquals(finalAmount, innerD.totalAmount)
        assertEquals(invoice.paymentSecret, innerD.paymentSecret)
        assertEquals(invoice.paymentMetadata, innerD.paymentMetadata)
        assertEquals(ByteVector("024100"), innerD.invoiceFeatures) // var_onion_optin, payment_secret, basic_mpp
        assertEquals(listOf(routingHints), innerD.invoiceRoutingInfo)
    }

    @Test
    fun `fail to build a trampoline payment when too much invoice data is provided`() {
        val extraHop = PaymentRequest.TaggedField.ExtraHop(randomKey().publicKey(), ShortChannelId(1), 10.msat, 100, CltvExpiryDelta(12))
        val routingHintOverflow = listOf(extraHop, extraHop, extraHop, extraHop, extraHop, extraHop, extraHop)
        val featuresOverflow = ByteVector("010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101024100")
        val invoice = PaymentRequest(
            "lnbcrt", finalAmount, currentTimestampSeconds(), e, listOf(
                PaymentRequest.TaggedField.PaymentHash(paymentHash),
                PaymentRequest.TaggedField.PaymentSecret(paymentSecret),
                PaymentRequest.TaggedField.Features(featuresOverflow),
                PaymentRequest.TaggedField.DescriptionHash(randomBytes32()),
                PaymentRequest.TaggedField.RoutingInfo(routingHintOverflow)
            ), ByteVector.empty
        )
        assertFails { OutgoingPaymentPacket.buildTrampolineToLegacyPacket(invoice, trampolineHops, PaymentOnion.FinalPayload.createSinglePartPayload(finalAmount, finalExpiry, randomBytes32(), null)) }
    }

    @Test
    fun `fail to decrypt when the onion is invalid`() {
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacket(paymentHash, hops, PaymentOnion.FinalPayload.createSinglePartPayload(finalAmount, finalExpiry, randomBytes32(), null), OnionRoutingPacket.PaymentPacketLength)
        val add = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet.copy(payload = onion.packet.payload.reversed()))
        val failure = IncomingPaymentPacket.decrypt(add, privB)
        assertTrue(failure.isLeft)
        assertEquals(InvalidOnionHmac.code, failure.left!!.code)
    }

    @Test
    fun `fail to decrypt when the trampoline onion is invalid`() {
        val (amountAC, expiryAC, trampolineOnion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            trampolineHops,
            PaymentOnion.FinalPayload.createMultiPartPayload(finalAmount, finalAmount * 2, finalExpiry, paymentSecret, null),
            OnionRoutingPacket.TrampolinePacketLength
        )
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            trampolineChannelHops,
            PaymentOnion.FinalPayload.createTrampolinePayload(amountAC, amountAC, expiryAC, randomBytes32(), trampolineOnion.packet.copy(payload = trampolineOnion.packet.payload.reversed())),
            OnionRoutingPacket.PaymentPacketLength
        )
        val addB = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val (_, packetC) = decryptChannelRelay(addB, privB)
        val addC = UpdateAddHtlc(randomBytes32(), 2, amountBC, paymentHash, expiryBC, packetC)
        val failure = IncomingPaymentPacket.decrypt(addC, privC)
        assertTrue(failure.isLeft)
        assertEquals(InvalidOnionHmac.code, failure.left!!.code)
    }

    @Test
    fun `fail to decrypt when payment hash doesn't match associated data`() {
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacket(
            paymentHash.reversed(),
            hops,
            PaymentOnion.FinalPayload.createSinglePartPayload(finalAmount, finalExpiry, randomBytes32(), paymentMetadata),
            OnionRoutingPacket.PaymentPacketLength
        )
        val add = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val failure = IncomingPaymentPacket.decrypt(add, privB)
        assertTrue(failure.isLeft)
        assertEquals(InvalidOnionHmac.code, failure.left!!.code)
    }

    @Test
    fun `fail to decrypt at the final node when amount has been modified by next-to-last node`() {
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            hops.take(1),
            PaymentOnion.FinalPayload.createSinglePartPayload(finalAmount, finalExpiry, randomBytes32(), paymentMetadata),
            OnionRoutingPacket.PaymentPacketLength
        )
        val add = UpdateAddHtlc(randomBytes32(), 1, firstAmount - 100.msat, paymentHash, firstExpiry, onion.packet)
        val failure = IncomingPaymentPacket.decrypt(add, privB)
        assertEquals(Either.Left(FinalIncorrectHtlcAmount(firstAmount - 100.msat)), failure)
    }

    @Test
    fun `fail to decrypt at the final node when expiry has been modified by next-to-last node`() {
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            hops.take(1),
            PaymentOnion.FinalPayload.createSinglePartPayload(finalAmount, finalExpiry, randomBytes32(), paymentMetadata),
            OnionRoutingPacket.PaymentPacketLength
        )
        val add = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry - CltvExpiryDelta(12), onion.packet)
        val failure = IncomingPaymentPacket.decrypt(add, privB)
        assertEquals(Either.Left(FinalIncorrectCltvExpiry(firstExpiry - CltvExpiryDelta(12))), failure)
    }

    @Test
    fun `fail to decrypt at the final trampoline node when amount has been modified by next-to-last trampoline`() {
        val (amountAC, expiryAC, trampolineOnion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            trampolineHops,
            PaymentOnion.FinalPayload.createMultiPartPayload(finalAmount, finalAmount, finalExpiry, paymentSecret, null),
            OnionRoutingPacket.TrampolinePacketLength
        )
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            trampolineChannelHops,
            PaymentOnion.FinalPayload.createTrampolinePayload(amountAC, amountAC, expiryAC, randomBytes32(), trampolineOnion.packet),
            OnionRoutingPacket.PaymentPacketLength
        )
        val (_, packetC) = decryptChannelRelay(UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet), privB)
        val (_, _, packetD) = decryptNodeRelay(UpdateAddHtlc(randomBytes32(), 2, amountBC, paymentHash, expiryBC, packetC), privC)
        // c forwards the trampoline payment to d.
        val (amountD, expiryD, onionD) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(c, d, channelUpdateCD)),
            PaymentOnion.FinalPayload.createTrampolinePayload(amountCD, amountCD, expiryCD, randomBytes32(), packetD),
            OnionRoutingPacket.PaymentPacketLength
        )
        val (_, _, packetE) = decryptNodeRelay(UpdateAddHtlc(randomBytes32(), 3, amountD, paymentHash, expiryD, onionD.packet), privD)
        // d forwards an invalid amount to e (the outer total amount doesn't match the inner amount).
        val invalidTotalAmount = amountDE + 100.msat
        val (amountE, expiryE, onionE) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(d, e, channelUpdateDE)),
            PaymentOnion.FinalPayload.createTrampolinePayload(amountDE, invalidTotalAmount, expiryDE, randomBytes32(), packetE),
            OnionRoutingPacket.PaymentPacketLength
        )
        val failure = IncomingPaymentPacket.decrypt(UpdateAddHtlc(randomBytes32(), 4, amountE, paymentHash, expiryE, onionE.packet), privE)
        assertEquals(Either.Left(FinalIncorrectHtlcAmount(invalidTotalAmount)), failure)
    }

    @Test
    fun `fail to decrypt at the final trampoline node when expiry has been modified by next-to-last trampoline`() {
        val (amountAC, expiryAC, trampolineOnion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            trampolineHops,
            PaymentOnion.FinalPayload.createMultiPartPayload(finalAmount, finalAmount, finalExpiry, paymentSecret, null),
            OnionRoutingPacket.TrampolinePacketLength
        )
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            trampolineChannelHops,
            PaymentOnion.FinalPayload.createTrampolinePayload(amountAC, amountAC, expiryAC, randomBytes32(), trampolineOnion.packet),
            OnionRoutingPacket.PaymentPacketLength
        )
        val (_, packetC) = decryptChannelRelay(UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet), privB)
        val (_, _, packetD) = decryptNodeRelay(UpdateAddHtlc(randomBytes32(), 2, amountBC, paymentHash, expiryBC, packetC), privC)
        // c forwards the trampoline payment to d.
        val (amountD, expiryD, onionD) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(c, d, channelUpdateCD)),
            PaymentOnion.FinalPayload.createTrampolinePayload(amountCD, amountCD, expiryCD, randomBytes32(), packetD),
            OnionRoutingPacket.PaymentPacketLength
        )
        val (_, _, packetE) = decryptNodeRelay(UpdateAddHtlc(randomBytes32(), 3, amountD, paymentHash, expiryD, onionD.packet), privD)
        // d forwards an invalid expiry to e (the outer expiry doesn't match the inner expiry).
        val invalidExpiry = expiryDE - CltvExpiryDelta(12)
        val (amountE, expiryE, onionE) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(d, e, channelUpdateDE)),
            PaymentOnion.FinalPayload.createTrampolinePayload(amountDE, amountDE, invalidExpiry, randomBytes32(), packetE),
            OnionRoutingPacket.PaymentPacketLength
        )
        val failure = IncomingPaymentPacket.decrypt(UpdateAddHtlc(randomBytes32(), 4, amountE, paymentHash, expiryE, onionE.packet), privE)
        assertEquals(Either.Left(FinalIncorrectCltvExpiry(invalidExpiry)), failure)
    }
}

