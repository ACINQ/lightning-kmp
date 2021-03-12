package fr.acinq.eclair.payment

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.eclair.*
import fr.acinq.eclair.Eclair.nodeFee
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.Eclair.randomBytes64
import fr.acinq.eclair.Eclair.randomKey
import fr.acinq.eclair.channel.Channel
import fr.acinq.eclair.crypto.sphinx.Sphinx
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.router.NodeHop
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import kotlin.test.*

class PaymentPacketTestsCommon : EclairTestSuite() {

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

        private fun testBuildOnion(legacy: Boolean) {
            val finalPayload = if (legacy) {
                FinalLegacyPayload(finalAmount, finalExpiry)
            } else {
                FinalTlvPayload(TlvStream(listOf(OnionTlv.AmountToForward(finalAmount), OnionTlv.OutgoingCltv(finalExpiry))))
            }
            val (firstAmount, firstExpiry, onion) = OutgoingPacket.buildPacket(paymentHash, hops, finalPayload, OnionRoutingPacket.PaymentPacketLength)
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
            val payloadE = IncomingPacket.decrypt(addE, privE).right!!
            assertEquals(finalAmount, payloadE.amount)
            assertEquals(finalAmount, payloadE.totalAmount)
            assertEquals(finalExpiry, payloadE.expiry)
            assertNull(payloadE.paymentSecret)
        }

        // Wallets don't need to decrypt onions for intermediate nodes, but it's useful to test that encryption works correctly.
        fun decryptChannelRelay(add: UpdateAddHtlc, privateKey: PrivateKey): Pair<RelayLegacyPayload, OnionRoutingPacket> {
            val decrypted = Sphinx.peel(privateKey, add.paymentHash, add.onionRoutingPacket, OnionRoutingPacket.PaymentPacketLength).right!!
            assertFalse(decrypted.isLastPacket)
            val decoded = RelayLegacyPayload.read(ByteArrayInput(decrypted.payload.toByteArray()))
            return Pair(decoded, decrypted.nextPacket)
        }

        // Wallets don't need to decrypt onions for intermediate nodes, but it's useful to test that encryption works correctly.
        fun decryptNodeRelay(add: UpdateAddHtlc, privateKey: PrivateKey): Triple<FinalPayload, NodeRelayPayload, OnionRoutingPacket> {
            val decrypted = Sphinx.peel(privateKey, add.paymentHash, add.onionRoutingPacket, OnionRoutingPacket.PaymentPacketLength).right!!
            assertTrue(decrypted.isLastPacket)
            val outerPayload = FinalPayload.read(ByteArrayInput(decrypted.payload.toByteArray()))
            assertTrue(outerPayload is FinalTlvPayload)
            val trampolineOnion = outerPayload.records.get<OnionTlv.TrampolineOnion>()
            assertNotNull(trampolineOnion)
            val decryptedInner = Sphinx.peel(privateKey, add.paymentHash, trampolineOnion.packet, OnionRoutingPacket.TrampolinePacketLength).right!!
            val innerPayload = NodeRelayPayload.read(ByteArrayInput(decryptedInner.payload.toByteArray()))
            return Triple(outerPayload, innerPayload, decryptedInner.nextPacket)
        }

    }

    @Test
    fun `build onion with final legacy payload`() {
        testBuildOnion(legacy = true)
    }

    @Test
    fun `build onion with final tlv payload`() {
        testBuildOnion(legacy = false)
    }

    @Test
    fun `build a command including the onion`() {
        val (add, _) = OutgoingPacket.buildCommand(UUID.randomUUID(), paymentHash, hops, FinalLegacyPayload(finalAmount, finalExpiry))
        assertTrue { add.amount > finalAmount }
        assertEquals(add.cltvExpiry, finalExpiry + channelUpdateDE.cltvExpiryDelta + channelUpdateCD.cltvExpiryDelta + channelUpdateBC.cltvExpiryDelta)
        assertEquals(add.paymentHash, paymentHash)
        assertEquals(add.onion.payload.size(), OnionRoutingPacket.PaymentPacketLength)

        // let's peel the onion
        testPeelOnion(add.onion)
    }

    @Test
    fun `build a command with no hops`() {
        val (add, _) = OutgoingPacket.buildCommand(UUID.randomUUID(), paymentHash, hops.take(1), FinalLegacyPayload(finalAmount, finalExpiry))
        assertEquals(add.amount, finalAmount)
        assertEquals(add.cltvExpiry, finalExpiry)
        assertEquals(add.paymentHash, paymentHash)
        assertEquals(add.onion.payload.size(), OnionRoutingPacket.PaymentPacketLength)

        // let's peel the onion
        val addB = UpdateAddHtlc(randomBytes32(), 0, finalAmount, paymentHash, finalExpiry, add.onion)
        val finalPayload = IncomingPacket.decrypt(addB, privB).right!!
        assertEquals(finalPayload.amount, finalAmount)
        assertEquals(finalPayload.totalAmount, finalAmount)
        assertEquals(finalPayload.expiry, finalExpiry)
        assertNull(finalPayload.paymentSecret)
    }

    @Test
    fun `build a trampoline payment`() {
        // simple trampoline route to e:
        //             .--.   .--.
        //            /    \ /    \
        // a -> b -> c      d      e

        val (amountAC, expiryAC, trampolineOnion) = OutgoingPacket.buildPacket(
            paymentHash,
            trampolineHops,
            FinalPayload.createMultiPartPayload(finalAmount, finalAmount * 3, finalExpiry, paymentSecret),
            OnionRoutingPacket.TrampolinePacketLength
        )
        assertEquals(amountBC, amountAC)
        assertEquals(expiryBC, expiryAC)

        val (firstAmount, firstExpiry, onion) = OutgoingPacket.buildPacket(
            paymentHash,
            trampolineChannelHops,
            FinalPayload.createTrampolinePayload(amountAC, amountAC, expiryAC, randomBytes32(), trampolineOnion.packet),
            OnionRoutingPacket.PaymentPacketLength
        )
        assertEquals(amountAB, firstAmount)
        assertEquals(expiryAB, firstExpiry)

        val addB = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val (payloadB, packetC) = decryptChannelRelay(addB, privB)
        assertEquals(RelayLegacyPayload(channelUpdateBC.shortChannelId, amountBC, expiryBC), payloadB)

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
        val (amountD, expiryD, onionD) = OutgoingPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(c, d, channelUpdateCD)),
            FinalPayload.createTrampolinePayload(amountCD, amountCD, expiryCD, randomBytes32(), packetD),
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
        val (amountE, expiryE, onionE) = OutgoingPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(d, e, channelUpdateDE)),
            FinalPayload.createTrampolinePayload(amountDE, amountDE, expiryDE, randomBytes32(), packetE),
            OnionRoutingPacket.PaymentPacketLength
        )
        assertEquals(amountDE, amountE)
        assertEquals(expiryDE, expiryE)
        val addE = UpdateAddHtlc(randomBytes32(), 4, amountE, paymentHash, expiryE, onionE.packet)
        val payloadE = IncomingPacket.decrypt(addE, privE).right!!
        assertEquals(payloadE, FinalTlvPayload(TlvStream(listOf(OnionTlv.AmountToForward(finalAmount), OnionTlv.OutgoingCltv(finalExpiry), OnionTlv.PaymentData(paymentSecret, finalAmount * 3)))))
    }

    @Test
    fun `build a trampoline payment with non-trampoline recipient`() {
        // simple trampoline route to e where e doesn't support trampoline:
        //             .--.
        //            /    \
        // a -> b -> c      d -> e

        val routingHints = listOf(PaymentRequest.TaggedField.ExtraHop(randomKey().publicKey(), ShortChannelId(42), 10.msat, 100, CltvExpiryDelta(144)))
        val invoiceFeatures = Features(Feature.VariableLengthOnion to FeatureSupport.Optional, Feature.PaymentSecret to FeatureSupport.Optional, Feature.BasicMultiPartPayment to FeatureSupport.Optional)
        val invoice = PaymentRequest(
            "lnbcrt", finalAmount, currentTimestampSeconds(), e, listOf(
                PaymentRequest.TaggedField.PaymentHash(paymentHash),
                PaymentRequest.TaggedField.PaymentSecret(paymentSecret),
                PaymentRequest.TaggedField.DescriptionHash(randomBytes32()),
                PaymentRequest.TaggedField.Features(invoiceFeatures.toByteArray().toByteVector()),
                PaymentRequest.TaggedField.RoutingInfo(routingHints)
            ), ByteVector.empty
        )
        val (amountAC, expiryAC, trampolineOnion) = OutgoingPacket.buildTrampolineToLegacyPacket(invoice, trampolineHops, FinalLegacyPayload(finalAmount, finalExpiry))
        assertEquals(amountBC, amountAC)
        assertEquals(expiryBC, expiryAC)

        val (firstAmount, firstExpiry, onion) = OutgoingPacket.buildPacket(
            paymentHash,
            trampolineChannelHops,
            FinalPayload.createTrampolinePayload(amountAC, amountAC, expiryAC, randomBytes32(), trampolineOnion.packet),
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
        val (amountD, expiryD, onionD) = OutgoingPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(c, d, channelUpdateCD)),
            FinalPayload.createTrampolinePayload(amountCD, amountCD, expiryCD, randomBytes32(), packetD),
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
        assertEquals(ByteVector("028200"), innerD.invoiceFeatures) // var_onion_optin, payment_secret, basic_mpp
        assertEquals(listOf(routingHints), innerD.invoiceRoutingInfo)
    }

    @Test
    fun `fail to build a trampoline payment when too much invoice data is provided`() {
        val extraHop = PaymentRequest.TaggedField.ExtraHop(randomKey().publicKey(), ShortChannelId(1), 10.msat, 100, CltvExpiryDelta(12))
        val routingHintOverflow = listOf(extraHop, extraHop, extraHop, extraHop, extraHop, extraHop, extraHop)
        val invoice = PaymentRequest(
            "lnbcrt", finalAmount, currentTimestampSeconds(), e, listOf(
                PaymentRequest.TaggedField.PaymentHash(paymentHash),
                PaymentRequest.TaggedField.PaymentSecret(paymentSecret),
                PaymentRequest.TaggedField.DescriptionHash(randomBytes32()),
                PaymentRequest.TaggedField.RoutingInfo(routingHintOverflow)
            ), ByteVector.empty
        )
        assertFails { OutgoingPacket.buildTrampolineToLegacyPacket(invoice, trampolineHops, FinalLegacyPayload(finalAmount, finalExpiry)) }
    }

    @Test
    fun `fail to decrypt when the onion is invalid`() {
        val (firstAmount, firstExpiry, onion) = OutgoingPacket.buildPacket(paymentHash, hops, FinalLegacyPayload(finalAmount, finalExpiry), OnionRoutingPacket.PaymentPacketLength)
        val add = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet.copy(payload = onion.packet.payload.reversed()))
        val failure = IncomingPacket.decrypt(add, privB)
        assertTrue(failure.isLeft)
        assertEquals(InvalidOnionHmac.code, failure.left!!.code)
    }

    @Test
    fun `fail to decrypt when the trampoline onion is invalid`() {
        val (amountAC, expiryAC, trampolineOnion) = OutgoingPacket.buildPacket(
            paymentHash,
            trampolineHops,
            FinalPayload.createMultiPartPayload(finalAmount, finalAmount * 2, finalExpiry, paymentSecret),
            OnionRoutingPacket.TrampolinePacketLength
        )
        val (firstAmount, firstExpiry, onion) = OutgoingPacket.buildPacket(
            paymentHash,
            trampolineChannelHops,
            FinalPayload.createTrampolinePayload(amountAC, amountAC, expiryAC, randomBytes32(), trampolineOnion.packet.copy(payload = trampolineOnion.packet.payload.reversed())),
            OnionRoutingPacket.PaymentPacketLength
        )
        val addB = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val (_, packetC) = decryptChannelRelay(addB, privB)
        val addC = UpdateAddHtlc(randomBytes32(), 2, amountBC, paymentHash, expiryBC, packetC)
        val failure = IncomingPacket.decrypt(addC, privC)
        assertTrue(failure.isLeft)
        assertEquals(InvalidOnionHmac.code, failure.left!!.code)
    }

    @Test
    fun `fail to decrypt when payment hash doesn't match associated data`() {
        val (firstAmount, firstExpiry, onion) = OutgoingPacket.buildPacket(paymentHash.reversed(), hops, FinalLegacyPayload(finalAmount, finalExpiry), OnionRoutingPacket.PaymentPacketLength)
        val add = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val failure = IncomingPacket.decrypt(add, privB)
        assertTrue(failure.isLeft)
        assertEquals(InvalidOnionHmac.code, failure.left!!.code)
    }

    @Test
    fun `fail to decrypt at the final node when amount has been modified by next-to-last node`() {
        val (firstAmount, firstExpiry, onion) = OutgoingPacket.buildPacket(paymentHash, hops.take(1), FinalLegacyPayload(finalAmount, finalExpiry), OnionRoutingPacket.PaymentPacketLength)
        val add = UpdateAddHtlc(randomBytes32(), 1, firstAmount - 100.msat, paymentHash, firstExpiry, onion.packet)
        val failure = IncomingPacket.decrypt(add, privB)
        assertEquals(Either.Left(FinalIncorrectHtlcAmount(firstAmount - 100.msat)), failure)
    }

    @Test
    fun `fail to decrypt at the final node when expiry has been modified by next-to-last node`() {
        val (firstAmount, firstExpiry, onion) = OutgoingPacket.buildPacket(paymentHash, hops.take(1), FinalLegacyPayload(finalAmount, finalExpiry), OnionRoutingPacket.PaymentPacketLength)
        val add = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry - CltvExpiryDelta(12), onion.packet)
        val failure = IncomingPacket.decrypt(add, privB)
        assertEquals(Either.Left(FinalIncorrectCltvExpiry(firstExpiry - CltvExpiryDelta(12))), failure)
    }

    @Test
    fun `fail to decrypt at the final trampoline node when amount has been modified by next-to-last trampoline`() {
        val (amountAC, expiryAC, trampolineOnion) = OutgoingPacket.buildPacket(
            paymentHash,
            trampolineHops,
            FinalPayload.createMultiPartPayload(finalAmount, finalAmount, finalExpiry, paymentSecret),
            OnionRoutingPacket.TrampolinePacketLength
        )
        val (firstAmount, firstExpiry, onion) = OutgoingPacket.buildPacket(
            paymentHash,
            trampolineChannelHops,
            FinalPayload.createTrampolinePayload(amountAC, amountAC, expiryAC, randomBytes32(), trampolineOnion.packet),
            OnionRoutingPacket.PaymentPacketLength
        )
        val (_, packetC) = decryptChannelRelay(UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet), privB)
        val (_, _, packetD) = decryptNodeRelay(UpdateAddHtlc(randomBytes32(), 2, amountBC, paymentHash, expiryBC, packetC), privC)
        // c forwards the trampoline payment to d.
        val (amountD, expiryD, onionD) = OutgoingPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(c, d, channelUpdateCD)),
            FinalPayload.createTrampolinePayload(amountCD, amountCD, expiryCD, randomBytes32(), packetD),
            OnionRoutingPacket.PaymentPacketLength
        )
        val (_, _, packetE) = decryptNodeRelay(UpdateAddHtlc(randomBytes32(), 3, amountD, paymentHash, expiryD, onionD.packet), privD)
        // d forwards an invalid amount to e (the outer total amount doesn't match the inner amount).
        val invalidTotalAmount = amountDE + 100.msat
        val (amountE, expiryE, onionE) = OutgoingPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(d, e, channelUpdateDE)),
            FinalPayload.createTrampolinePayload(amountDE, invalidTotalAmount, expiryDE, randomBytes32(), packetE),
            OnionRoutingPacket.PaymentPacketLength
        )
        val failure = IncomingPacket.decrypt(UpdateAddHtlc(randomBytes32(), 4, amountE, paymentHash, expiryE, onionE.packet), privE)
        assertEquals(Either.Left(FinalIncorrectHtlcAmount(invalidTotalAmount)), failure)
    }

    @Test
    fun `fail to decrypt at the final trampoline node when expiry has been modified by next-to-last trampoline`() {
        val (amountAC, expiryAC, trampolineOnion) = OutgoingPacket.buildPacket(
            paymentHash,
            trampolineHops,
            FinalPayload.createMultiPartPayload(finalAmount, finalAmount, finalExpiry, paymentSecret),
            OnionRoutingPacket.TrampolinePacketLength
        )
        val (firstAmount, firstExpiry, onion) = OutgoingPacket.buildPacket(
            paymentHash,
            trampolineChannelHops,
            FinalPayload.createTrampolinePayload(amountAC, amountAC, expiryAC, randomBytes32(), trampolineOnion.packet),
            OnionRoutingPacket.PaymentPacketLength
        )
        val (_, packetC) = decryptChannelRelay(UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet), privB)
        val (_, _, packetD) = decryptNodeRelay(UpdateAddHtlc(randomBytes32(), 2, amountBC, paymentHash, expiryBC, packetC), privC)
        // c forwards the trampoline payment to d.
        val (amountD, expiryD, onionD) = OutgoingPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(c, d, channelUpdateCD)),
            FinalPayload.createTrampolinePayload(amountCD, amountCD, expiryCD, randomBytes32(), packetD),
            OnionRoutingPacket.PaymentPacketLength
        )
        val (_, _, packetE) = decryptNodeRelay(UpdateAddHtlc(randomBytes32(), 3, amountD, paymentHash, expiryD, onionD.packet), privD)
        // d forwards an invalid expiry to e (the outer expiry doesn't match the inner expiry).
        val invalidExpiry = expiryDE - CltvExpiryDelta(12)
        val (amountE, expiryE, onionE) = OutgoingPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(d, e, channelUpdateDE)),
            FinalPayload.createTrampolinePayload(amountDE, amountDE, invalidExpiry, randomBytes32(), packetE),
            OnionRoutingPacket.PaymentPacketLength
        )
        val failure = IncomingPacket.decrypt(UpdateAddHtlc(randomBytes32(), 4, amountE, paymentHash, expiryE, onionE.packet), privE)
        assertEquals(Either.Left(FinalIncorrectCltvExpiry(invalidExpiry)), failure)
    }

    @Test
    fun `fail to decrypt at the final trampoline node when payment secret is missing`() {
        val (amountAC, expiryAC, trampolineOnion) = OutgoingPacket.buildPacket(paymentHash, trampolineHops, FinalPayload.createSinglePartPayload(finalAmount, finalExpiry), OnionRoutingPacket.TrampolinePacketLength) // no payment secret
        val (firstAmount, firstExpiry, onion) = OutgoingPacket.buildPacket(
            paymentHash,
            trampolineChannelHops,
            FinalPayload.createTrampolinePayload(amountAC, amountAC, expiryAC, randomBytes32(), trampolineOnion.packet),
            OnionRoutingPacket.PaymentPacketLength
        )
        val (_, packetC) = decryptChannelRelay(UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet), privB)
        val (_, _, packetD) = decryptNodeRelay(UpdateAddHtlc(randomBytes32(), 2, amountBC, paymentHash, expiryBC, packetC), privC)
        // c forwards the trampoline payment to d.
        val (amountD, expiryD, onionD) = OutgoingPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(c, d, channelUpdateCD)),
            FinalPayload.createTrampolinePayload(amountCD, amountCD, expiryCD, randomBytes32(), packetD),
            OnionRoutingPacket.PaymentPacketLength
        )
        val (_, _, packetE) = decryptNodeRelay(UpdateAddHtlc(randomBytes32(), 3, amountD, paymentHash, expiryD, onionD.packet), privD)
        // d forwards the trampoline payment to e.
        val (amountE, expiryE, onionE) = OutgoingPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(d, e, channelUpdateDE)),
            FinalPayload.createTrampolinePayload(amountDE, amountDE, expiryDE, randomBytes32(), packetE),
            OnionRoutingPacket.PaymentPacketLength
        )
        val failure = IncomingPacket.decrypt(UpdateAddHtlc(randomBytes32(), 4, amountE, paymentHash, expiryE, onionE.packet), privE)
        @Suppress("EXPERIMENTAL_UNSIGNED_LITERALS")
        assertEquals(Either.Left(InvalidOnionPayload(8U, 0)), failure)
    }
}

