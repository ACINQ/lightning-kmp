package fr.acinq.lightning.payment

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.nodeFee
import fr.acinq.lightning.Lightning.randomBytes
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomBytes64
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.channel.states.Channel
import fr.acinq.lightning.crypto.RouteBlinding
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.crypto.sphinx.Sphinx.hash
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
            val finalPayload = PaymentOnion.FinalPayload.Standard(TlvStream(OnionPaymentPayloadTlv.AmountToForward(finalAmount), OnionPaymentPayloadTlv.OutgoingCltv(finalExpiry), OnionPaymentPayloadTlv.PaymentData(paymentSecret, finalAmount)))
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
            assertIs<PaymentOnion.FinalPayload.Standard>(payloadE)
            assertEquals(finalAmount, payloadE.amount)
            assertEquals(finalAmount, payloadE.totalAmount)
            assertEquals(finalExpiry, payloadE.expiry)
            assertEquals(paymentSecret, payloadE.paymentSecret)
        }

        // Wallets don't need to decrypt onions for intermediate nodes, but it's useful to test that encryption works correctly.
        fun decryptChannelRelay(add: UpdateAddHtlc, privateKey: PrivateKey): Pair<PaymentOnion.ChannelRelayPayload, OnionRoutingPacket> {
            val decrypted = Sphinx.peel(privateKey, add.paymentHash, add.onionRoutingPacket).right!!
            assertFalse(decrypted.isLastPacket)
            val decoded = PaymentOnion.ChannelRelayPayload.read(decrypted.payload).right!!
            return Pair(decoded, decrypted.nextPacket)
        }

        // Wallets don't need to decrypt onions for intermediate nodes, but it's useful to test that encryption works correctly.
        fun decryptNodeRelay(add: UpdateAddHtlc, privateKey: PrivateKey): Triple<PaymentOnion.FinalPayload, PaymentOnion.NodeRelayPayload, OnionRoutingPacket> {
            val decrypted = Sphinx.peel(privateKey, add.paymentHash, add.onionRoutingPacket).right!!
            assertTrue(decrypted.isLastPacket)
            val outerPayload = PaymentOnion.FinalPayload.Standard.read(decrypted.payload).right!!
            val trampolineOnion = outerPayload.records.get<OnionPaymentPayloadTlv.TrampolineOnion>()
            assertNotNull(trampolineOnion)
            val decryptedInner = Sphinx.peel(privateKey, add.paymentHash, trampolineOnion.packet).right!!
            val innerPayload = PaymentOnion.NodeRelayPayload.read(decryptedInner.payload).right!!
            assertNull(innerPayload.records.get<OnionPaymentPayloadTlv.PaymentData>())
            assertNull(innerPayload.records.get<OnionPaymentPayloadTlv.PaymentMetadata>())
            assertNull(innerPayload.records.get<OnionPaymentPayloadTlv.InvoiceFeatures>())
            assertNull(innerPayload.records.get<OnionPaymentPayloadTlv.InvoiceRoutingInfo>())
            return Triple(outerPayload, innerPayload, decryptedInner.nextPacket)
        }

        // Wallets don't need to decrypt onions for intermediate nodes, but it's useful to test that encryption works correctly.
        fun decryptRelayToNonTrampolinePayload(add: UpdateAddHtlc, privateKey: PrivateKey): Triple<PaymentOnion.FinalPayload, PaymentOnion.RelayToNonTrampolinePayload, OnionRoutingPacket> {
            val decrypted = Sphinx.peel(privateKey, add.paymentHash, add.onionRoutingPacket).right!!
            assertTrue(decrypted.isLastPacket)
            val outerPayload = PaymentOnion.FinalPayload.Standard.read(decrypted.payload).right!!
            val trampolineOnion = outerPayload.records.get<OnionPaymentPayloadTlv.TrampolineOnion>()
            assertNotNull(trampolineOnion)
            val decryptedInner = Sphinx.peel(privateKey, add.paymentHash, trampolineOnion.packet).right!!
            val innerPayload = PaymentOnion.RelayToNonTrampolinePayload.read(decryptedInner.payload).right!!
            return Triple(outerPayload, innerPayload, decryptedInner.nextPacket)
        }

        // Wallets don't need to decrypt onions for intermediate nodes, but it's useful to test that encryption works correctly.
        fun decryptRelayToBlinded(add: UpdateAddHtlc, privateKey: PrivateKey): Triple<PaymentOnion.FinalPayload, PaymentOnion.RelayToBlindedPayload, OnionRoutingPacket> {
            val decrypted = Sphinx.peel(privateKey, add.paymentHash, add.onionRoutingPacket).right!!
            assertTrue(decrypted.isLastPacket)
            val outerPayload = PaymentOnion.FinalPayload.Standard.read(decrypted.payload).right!!
            val trampolineOnion = outerPayload.records.get<OnionPaymentPayloadTlv.TrampolineOnion>()
            assertNotNull(trampolineOnion)
            val decryptedInner = Sphinx.peel(privateKey, add.paymentHash, trampolineOnion.packet).right!!
            val innerPayload = PaymentOnion.RelayToBlindedPayload.read(decryptedInner.payload).right!!
            return Triple(outerPayload, innerPayload, decryptedInner.nextPacket)
        }

        // Create an HTLC paying an empty blinded path.
        fun createBlindedHtlc(): Pair<UpdateAddHtlc, PaymentOnion.FinalPayload.Blinded> {
            val paymentMetadata = OfferPaymentMetadata.V1(randomBytes32(), finalAmount, paymentPreimage, randomKey().publicKey(), null, 1, currentTimestampMillis())
            val blindedPayload = RouteBlindingEncryptedData(TlvStream(RouteBlindingEncryptedDataTlv.PathId(paymentMetadata.toPathId(privE))))
            val blindedRoute = RouteBlinding.create(randomKey(), listOf(e), listOf(blindedPayload.write().byteVector())).route
            val finalPayload = PaymentOnion.FinalPayload.Blinded(
                TlvStream(
                    OnionPaymentPayloadTlv.AmountToForward(finalAmount),
                    OnionPaymentPayloadTlv.TotalAmount(finalAmount),
                    OnionPaymentPayloadTlv.OutgoingCltv(finalExpiry),
                    OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads.last()),
                ),
                blindedPayload
            )
            val blindedHop = ChannelHop(d, blindedRoute.blindedNodeIds.last(), channelUpdateDE)
            val (amountE, expiryE, onionE) = OutgoingPaymentPacket.buildPacket(paymentMetadata.paymentHash, listOf(blindedHop), finalPayload, OnionRoutingPacket.PaymentPacketLength)
            val add = UpdateAddHtlc(randomBytes32(), 2, amountE, paymentMetadata.paymentHash, expiryE, onionE.packet, blindedRoute.blindingKey)
            return Pair(add, finalPayload)
        }
    }

    @Test
    fun `build onion`() {
        testBuildOnion()
    }

    @Test
    fun `build a command including the onion`() {
        val (add, _) = OutgoingPaymentPacket.buildCommand(UUID.randomUUID(), paymentHash, hops, PaymentOnion.FinalPayload.Standard.createSinglePartPayload(finalAmount, finalExpiry, paymentSecret, null))
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
        val (add, _) = OutgoingPaymentPacket.buildCommand(UUID.randomUUID(), paymentHash, hops.take(1), PaymentOnion.FinalPayload.Standard.createSinglePartPayload(finalAmount, finalExpiry, paymentSecret, paymentMetadata))
        assertEquals(add.amount, finalAmount)
        assertEquals(add.cltvExpiry, finalExpiry)
        assertEquals(add.paymentHash, paymentHash)
        assertEquals(add.onion.payload.size(), OnionRoutingPacket.PaymentPacketLength)

        // let's peel the onion
        val addB = UpdateAddHtlc(randomBytes32(), 0, finalAmount, paymentHash, finalExpiry, add.onion)
        val finalPayload = IncomingPaymentPacket.decrypt(addB, privB).right!!
        assertIs<PaymentOnion.FinalPayload.Standard>(finalPayload)
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
            PaymentOnion.FinalPayload.Standard.createMultiPartPayload(finalAmount, finalAmount * 3, finalExpiry, paymentSecret, paymentMetadata),
            null
        )
        assertEquals(amountBC, amountAC)
        assertEquals(expiryBC, expiryAC)

        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            trampolineChannelHops,
            PaymentOnion.FinalPayload.Standard.createTrampolinePayload(amountAC, amountAC, expiryAC, randomBytes32(), trampolineOnion.packet),
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

        // c forwards the trampoline payment to d.
        val (amountD, expiryD, onionD) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(c, d, channelUpdateCD)),
            PaymentOnion.FinalPayload.Standard.createTrampolinePayload(amountCD, amountCD, expiryCD, randomBytes32(), packetD),
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

        // d forwards the trampoline payment to e.
        val (amountE, expiryE, onionE) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(d, e, channelUpdateDE)),
            PaymentOnion.FinalPayload.Standard.createTrampolinePayload(amountDE, amountDE, expiryDE, randomBytes32(), packetE),
            OnionRoutingPacket.PaymentPacketLength
        )
        assertEquals(amountDE, amountE)
        assertEquals(expiryDE, expiryE)
        val addE = UpdateAddHtlc(randomBytes32(), 4, amountE, paymentHash, expiryE, onionE.packet)
        val payloadE = IncomingPaymentPacket.decrypt(addE, privE).right!!
        val expectedFinalPayload = PaymentOnion.FinalPayload.Standard(
            TlvStream(
                OnionPaymentPayloadTlv.AmountToForward(finalAmount),
                OnionPaymentPayloadTlv.OutgoingCltv(finalExpiry),
                OnionPaymentPayloadTlv.PaymentData(paymentSecret, finalAmount * 3),
                OnionPaymentPayloadTlv.PaymentMetadata(paymentMetadata)
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

        val routingHints = listOf(Bolt11Invoice.TaggedField.ExtraHop(randomKey().publicKey(), ShortChannelId(42), 10.msat, 100, CltvExpiryDelta(144)))
        val invoiceFeatures = Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory, Feature.PaymentSecret to FeatureSupport.Mandatory, Feature.BasicMultiPartPayment to FeatureSupport.Optional)
        val invoice = Bolt11Invoice(
            "lnbcrt", finalAmount, currentTimestampSeconds(), e, listOf(
                Bolt11Invoice.TaggedField.PaymentHash(paymentHash),
                Bolt11Invoice.TaggedField.PaymentSecret(paymentSecret),
                Bolt11Invoice.TaggedField.PaymentMetadata(paymentMetadata),
                Bolt11Invoice.TaggedField.DescriptionHash(randomBytes32()),
                Bolt11Invoice.TaggedField.Features(invoiceFeatures.toByteArray().toByteVector()),
                Bolt11Invoice.TaggedField.RoutingInfo(routingHints)
            ), ByteVector.empty
        )
        val (amountAC, expiryAC, trampolineOnion) = OutgoingPaymentPacket.buildTrampolineToNonTrampolinePacket(
            invoice,
            trampolineHops,
            PaymentOnion.FinalPayload.Standard.createSinglePartPayload(finalAmount, finalExpiry, randomBytes32(), null)
        )
        assertEquals(amountBC, amountAC)
        assertEquals(expiryBC, expiryAC)

        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            trampolineChannelHops,
            PaymentOnion.FinalPayload.Standard.createTrampolinePayload(amountAC, amountAC, expiryAC, randomBytes32(), trampolineOnion.packet),
            OnionRoutingPacket.PaymentPacketLength
        )
        assertEquals(amountAB, firstAmount)
        assertEquals(expiryAB, firstExpiry)

        val addB = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val (_, packetC) = decryptChannelRelay(addB, privB)

        val addC = UpdateAddHtlc(randomBytes32(), 2, amountBC, paymentHash, expiryBC, packetC)
        val (outerC, innerC, packetD) = decryptNodeRelay(addC, privC)
        assertIs<PaymentOnion.FinalPayload.Standard>(outerC)
        assertEquals(amountBC, outerC.amount)
        assertEquals(amountBC, outerC.totalAmount)
        assertEquals(expiryBC, outerC.expiry)
        assertNotEquals(invoice.paymentSecret, outerC.paymentSecret)
        assertEquals(amountCD, innerC.amountToForward)
        assertEquals(expiryCD, innerC.outgoingCltv)
        assertEquals(d, innerC.outgoingNodeId)

        // c forwards the trampoline payment to d.
        val (amountD, expiryD, onionD) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(c, d, channelUpdateCD)),
            PaymentOnion.FinalPayload.Standard.createTrampolinePayload(amountCD, amountCD, expiryCD, randomBytes32(), packetD),
            OnionRoutingPacket.PaymentPacketLength
        )
        assertEquals(amountCD, amountD)
        assertEquals(expiryCD, expiryD)
        val addD = UpdateAddHtlc(randomBytes32(), 3, amountD, paymentHash, expiryD, onionD.packet)
        val (outerD, innerD, _) = decryptRelayToNonTrampolinePayload(addD, privD)
        assertIs<PaymentOnion.FinalPayload.Standard>(outerD)
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
    fun `build a trampoline payment to blinded paths`() {
        val features = Features(Feature.BasicMultiPartPayment to FeatureSupport.Optional)
        val offer = OfferTypes.Offer.createNonBlindedOffer(finalAmount, "test offer", e, features, Block.LivenetGenesisBlock.hash)
        // E uses a 1-hop blinded path from its LSP.
        val (invoice, blindedRoute) = run {
            val payerKey = randomKey()
            val request = OfferTypes.InvoiceRequest(offer, finalAmount, 1, features, payerKey, null, Block.LivenetGenesisBlock.hash)
            val paymentMetadata = OfferPaymentMetadata.V1(offer.offerId, finalAmount, paymentPreimage, payerKey.publicKey(), null, 1, currentTimestampMillis())
            val blindedPayloads = listOf(
                RouteBlindingEncryptedData(
                    TlvStream(
                        RouteBlindingEncryptedDataTlv.OutgoingChannelId(channelUpdateDE.shortChannelId),
                        RouteBlindingEncryptedDataTlv.PaymentRelay(channelUpdateDE.cltvExpiryDelta, channelUpdateDE.feeProportionalMillionths, channelUpdateDE.feeBaseMsat),
                        RouteBlindingEncryptedDataTlv.PaymentConstraints(finalExpiry, 1.msat),
                    )
                ),
                RouteBlindingEncryptedData(TlvStream(RouteBlindingEncryptedDataTlv.PathId(paymentMetadata.toPathId(privE)))),
            ).map { it.write().byteVector() }
            val blindedRouteDetails = RouteBlinding.create(randomKey(), listOf(d, e), blindedPayloads)
            val paymentInfo = OfferTypes.PaymentInfo(channelUpdateDE.feeBaseMsat, channelUpdateDE.feeProportionalMillionths, channelUpdateDE.cltvExpiryDelta, channelUpdateDE.htlcMinimumMsat, channelUpdateDE.htlcMaximumMsat!!, Features.empty)
            val path = Bolt12Invoice.Companion.PaymentBlindedContactInfo(OfferTypes.ContactInfo.BlindedPath(blindedRouteDetails.route), paymentInfo)
            val invoice = Bolt12Invoice(request, paymentPreimage, blindedRouteDetails.blindedPrivateKey(privE), 600, features, listOf(path))
            assertEquals(invoice.nodeId, blindedRouteDetails.route.blindedNodeIds.last())
            Pair(invoice, blindedRouteDetails.route)
        }

        // C pays that invoice using a trampoline node to relay to the invoice's blinded path.
        val (firstAmount, firstExpiry, onion) = run {
            val trampolineHop = NodeHop(d, invoice.nodeId, channelUpdateDE.cltvExpiryDelta, feeD)
            val (trampolineAmount, trampolineExpiry, trampolineOnion) = OutgoingPaymentPacket.buildTrampolineToNonTrampolinePacket(invoice, trampolineHop, finalAmount, finalExpiry)
            val trampolinePayload = PaymentOnion.FinalPayload.Standard.createTrampolinePayload(trampolineAmount, trampolineAmount, trampolineExpiry, randomBytes32(), trampolineOnion.packet)
            OutgoingPaymentPacket.buildPacket(invoice.paymentHash, listOf(ChannelHop(c, d, channelUpdateCD)), trampolinePayload, OnionRoutingPacket.PaymentPacketLength)
        }
        assertEquals(amountCD, firstAmount)
        assertEquals(expiryCD, firstExpiry)

        // D decrypts the onion that contains a blinded path in the trampoline onion.
        val addD = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val (outerD, innerD, _) = decryptRelayToBlinded(addD, privD)
        assertEquals(amountCD, outerD.amount)
        assertEquals(amountCD, outerD.totalAmount)
        assertEquals(expiryCD, outerD.expiry)
        assertEquals(finalAmount, innerD.amountToForward)
        assertEquals(expiryDE, innerD.outgoingCltv)
        assertEquals(listOf(blindedRoute), innerD.outgoingBlindedPaths.map { it.route.route })
        assertEquals(invoice.features.toByteArray().toByteVector(), innerD.invoiceFeatures)

        // D is the introduction node of the blinded path: it can decrypt the first blinded payload and relay to E.
        val addE = run {
            val (dataD, blindingE) = RouteBlinding.decryptPayload(privD, blindedRoute.blindingKey, blindedRoute.encryptedPayloads.first()).right!!
            val payloadD = RouteBlindingEncryptedData.read(dataD.toByteArray()).right!!
            assertEquals(channelUpdateDE.shortChannelId, payloadD.outgoingChannelId)
            // D would normally create this payload based on the blinded path's payment_info field.
            val payloadE = PaymentOnion.FinalPayload.Blinded(
                TlvStream(
                    OnionPaymentPayloadTlv.AmountToForward(finalAmount),
                    OnionPaymentPayloadTlv.TotalAmount(finalAmount),
                    OnionPaymentPayloadTlv.OutgoingCltv(finalExpiry),
                    OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads.last()),
                ),
                // This dummy value is ignored when creating the htlc (D is not the recipient).
                RouteBlindingEncryptedData(TlvStream(RouteBlindingEncryptedDataTlv.PathId(ByteVector("deadbeef"))))
            )
            val blindedHop = ChannelHop(d, blindedRoute.blindedNodeIds.last(), channelUpdateDE)
            val (amountE, expiryE, onionE) = OutgoingPaymentPacket.buildPacket(addD.paymentHash, listOf(blindedHop), payloadE, OnionRoutingPacket.PaymentPacketLength)
            UpdateAddHtlc(randomBytes32(), 2, amountE, addD.paymentHash, expiryE, onionE.packet, blindingE)
        }

        // E can correctly decrypt the blinded payment.
        val payloadE = IncomingPaymentPacket.decrypt(addE, privE).right!!
        assertIs<PaymentOnion.FinalPayload.Blinded>(payloadE)
        val paymentMetadata = OfferPaymentMetadata.fromPathId(e, payloadE.pathId)
        assertNotNull(paymentMetadata)
        assertEquals(offer.offerId, paymentMetadata.offerId)
        assertEquals(paymentMetadata.paymentHash, invoice.paymentHash)
    }

    @Test
    fun `build a payment to a blinded path`() {
        val (addE, payloadE) = createBlindedHtlc()
        // E can correctly decrypt the blinded payment.
        assertEquals(payloadE, IncomingPaymentPacket.decrypt(addE, privE).right)
    }

    @Test
    fun `fail to decrypt when the onion is invalid`() {
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            hops,
            PaymentOnion.FinalPayload.Standard.createSinglePartPayload(finalAmount, finalExpiry, randomBytes32(), null),
            OnionRoutingPacket.PaymentPacketLength
        )
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
            PaymentOnion.FinalPayload.Standard.createMultiPartPayload(finalAmount, finalAmount * 2, finalExpiry, paymentSecret, null),
            null
        )
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            trampolineChannelHops,
            PaymentOnion.FinalPayload.Standard.createTrampolinePayload(amountAC, amountAC, expiryAC, randomBytes32(), trampolineOnion.packet.copy(payload = trampolineOnion.packet.payload.reversed())),
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
            PaymentOnion.FinalPayload.Standard.createSinglePartPayload(finalAmount, finalExpiry, randomBytes32(), paymentMetadata),
            OnionRoutingPacket.PaymentPacketLength
        )
        val add = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val failure = IncomingPaymentPacket.decrypt(add, privB)
        assertTrue(failure.isLeft)
        assertEquals(InvalidOnionHmac.code, failure.left!!.code)
    }

    @Test
    fun `fail to decrypt when blinded route data is invalid`() {
        val paymentMetadata = OfferPaymentMetadata.V1(randomBytes32(), finalAmount, paymentPreimage, randomKey().publicKey(), null, 1, currentTimestampMillis())
        val blindedPayload = RouteBlindingEncryptedData(TlvStream(RouteBlindingEncryptedDataTlv.PathId(paymentMetadata.toPathId(privE))))
        val blindedRoute = RouteBlinding.create(randomKey(), listOf(e), listOf(blindedPayload.write().byteVector())).route
        val payloadE = PaymentOnion.FinalPayload.Blinded(
            TlvStream(
                OnionPaymentPayloadTlv.AmountToForward(finalAmount),
                OnionPaymentPayloadTlv.TotalAmount(finalAmount),
                OnionPaymentPayloadTlv.OutgoingCltv(finalExpiry),
                // The encrypted data is invalid.
                OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads.last().reversed()),
            ),
            blindedPayload
        )
        val blindedHop = ChannelHop(d, blindedRoute.blindedNodeIds.last(), channelUpdateDE)
        val (amountE, expiryE, onionE) = OutgoingPaymentPacket.buildPacket(paymentMetadata.paymentHash, listOf(blindedHop), payloadE, OnionRoutingPacket.PaymentPacketLength)
        val addE = UpdateAddHtlc(randomBytes32(), 2, amountE, paymentMetadata.paymentHash, expiryE, onionE.packet, blindedRoute.blindingKey)
        val failure = IncomingPaymentPacket.decrypt(addE, privE)
        assertTrue(failure.isLeft)
        assertEquals(failure.left, InvalidOnionBlinding(hash(addE.onionRoutingPacket)))
    }

    @Test
    fun `fail to decrypt at the final node when amount has been modified by next-to-last node`() {
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            hops.take(1),
            PaymentOnion.FinalPayload.Standard.createSinglePartPayload(finalAmount, finalExpiry, randomBytes32(), paymentMetadata),
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
            PaymentOnion.FinalPayload.Standard.createSinglePartPayload(finalAmount, finalExpiry, randomBytes32(), paymentMetadata),
            OnionRoutingPacket.PaymentPacketLength
        )
        val add = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry - CltvExpiryDelta(12), onion.packet)
        val failure = IncomingPaymentPacket.decrypt(add, privB)
        assertEquals(Either.Left(FinalIncorrectCltvExpiry(firstExpiry - CltvExpiryDelta(12))), failure)
    }

    @Test
    fun `fail to decrypt blinded payment at the final node when amount is too low`() {
        val (addE, _) = createBlindedHtlc()
        // E receives a smaller amount than expected and rejects the payment.
        val failure = IncomingPaymentPacket.decrypt(addE.copy(amountMsat = addE.amountMsat - 1.msat), privE).left
        assertEquals(InvalidOnionBlinding(hash(addE.onionRoutingPacket)), failure)
    }

    @Test
    fun `fail to decrypt blinded payment at the final node when expiry is too low`() {
        val (addE, _) = createBlindedHtlc()
        // E receives a smaller expiry than expected and rejects the payment.
        val failure = IncomingPaymentPacket.decrypt(addE.copy(cltvExpiry = addE.cltvExpiry - CltvExpiryDelta(1)), privE).left
        assertEquals(InvalidOnionBlinding(hash(addE.onionRoutingPacket)), failure)
    }

    @Test
    fun `fail to decrypt at the final trampoline node when amount has been modified by next-to-last trampoline`() {
        val (amountAC, expiryAC, trampolineOnion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            trampolineHops,
            PaymentOnion.FinalPayload.Standard.createMultiPartPayload(finalAmount, finalAmount, finalExpiry, paymentSecret, null),
            null
        )
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            trampolineChannelHops,
            PaymentOnion.FinalPayload.Standard.createTrampolinePayload(amountAC, amountAC, expiryAC, randomBytes32(), trampolineOnion.packet),
            OnionRoutingPacket.PaymentPacketLength
        )
        val (_, packetC) = decryptChannelRelay(UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet), privB)
        val (_, _, packetD) = decryptNodeRelay(UpdateAddHtlc(randomBytes32(), 2, amountBC, paymentHash, expiryBC, packetC), privC)
        // c forwards the trampoline payment to d.
        val (amountD, expiryD, onionD) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(c, d, channelUpdateCD)),
            PaymentOnion.FinalPayload.Standard.createTrampolinePayload(amountCD, amountCD, expiryCD, randomBytes32(), packetD),
            OnionRoutingPacket.PaymentPacketLength
        )
        val (_, _, packetE) = decryptNodeRelay(UpdateAddHtlc(randomBytes32(), 3, amountD, paymentHash, expiryD, onionD.packet), privD)
        // d forwards an invalid amount to e (the outer total amount doesn't match the inner amount).
        val invalidTotalAmount = amountDE + 100.msat
        val (amountE, expiryE, onionE) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(d, e, channelUpdateDE)),
            PaymentOnion.FinalPayload.Standard.createTrampolinePayload(amountDE, invalidTotalAmount, expiryDE, randomBytes32(), packetE),
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
            PaymentOnion.FinalPayload.Standard.createMultiPartPayload(finalAmount, finalAmount, finalExpiry, paymentSecret, null),
            null
        )
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            trampolineChannelHops,
            PaymentOnion.FinalPayload.Standard.createTrampolinePayload(amountAC, amountAC, expiryAC, randomBytes32(), trampolineOnion.packet),
            OnionRoutingPacket.PaymentPacketLength
        )
        val (_, packetC) = decryptChannelRelay(UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet), privB)
        val (_, _, packetD) = decryptNodeRelay(UpdateAddHtlc(randomBytes32(), 2, amountBC, paymentHash, expiryBC, packetC), privC)
        // c forwards the trampoline payment to d.
        val (amountD, expiryD, onionD) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(c, d, channelUpdateCD)),
            PaymentOnion.FinalPayload.Standard.createTrampolinePayload(amountCD, amountCD, expiryCD, randomBytes32(), packetD),
            OnionRoutingPacket.PaymentPacketLength
        )
        val (_, _, packetE) = decryptNodeRelay(UpdateAddHtlc(randomBytes32(), 3, amountD, paymentHash, expiryD, onionD.packet), privD)
        // d forwards an invalid expiry to e (the outer expiry doesn't match the inner expiry).
        val invalidExpiry = expiryDE - CltvExpiryDelta(12)
        val (amountE, expiryE, onionE) = OutgoingPaymentPacket.buildPacket(
            paymentHash,
            listOf(ChannelHop(d, e, channelUpdateDE)),
            PaymentOnion.FinalPayload.Standard.createTrampolinePayload(amountDE, amountDE, invalidExpiry, randomBytes32(), packetE),
            OnionRoutingPacket.PaymentPacketLength
        )
        val failure = IncomingPaymentPacket.decrypt(UpdateAddHtlc(randomBytes32(), 4, amountE, paymentHash, expiryE, onionE.packet), privE)
        assertEquals(Either.Left(FinalIncorrectCltvExpiry(invalidExpiry)), failure)
    }

    @Test
    fun `relay to blinded with many large blinded routes`() {
        val invoice = Bolt12Invoice.fromString("lni1qqs0sehhttf0swv6sxsxuefk9q23yj9h0cl4wyn324jlt2gll4fzk0syzquta4dy4m9jgp5s970w2lw8928ppnqz662mq8r6dyy7w9kgv0ann0zlk7aacwpykl7uu5adckf7t0sgpees93j6nwr4xle4ck4d2wup6j044fx6f5zkdacvjen3herm493en2lmqgprfesng92cpl68qvlxv96prguvtl7yv49nwq9a92zl5n2pv73u4wgqx08jupl9mcphw7ww5mv50j7xkcjxvjamrysy3wsfmgj4cu9aq4y6hszdk5rys4qwa4muxczk4nuj54gyw3jqygaqqk2edh9c90ergwvaxu93jynty9z0gzrpcwuhkzt05epatv45qqg93d3x5hx8jgwgkwpnz85y3narw93pqvmp27mzgwjaswd76lgxm88qw2ehqszd2mlgntvnwq0vzrk4ehq325pqdl3gcz4k7xeh9sdx5fr2uclhf7f3aqm9u9dq38rg6cvsqqqqqqq9yqjwyp2qxqsqqpvzzqnfhw87mvwxx0qrljmlxppxr7h4hmdgdzpsxnj0rw2jckjtc93g44v3gmrfva58gmnfdenjq6tnyp3k7mmvypq5dg8aqsqsxhj07sv0ez642nzanm4xvwtvyfaag2dry5wge0r3zqpt5g2mls3xqwsadj5tn7gm50l43xmsu2dszx8s672wrkqn48qp7ftw6kvhltlrkqsrwc9agnmlg8dzefxe4vlz9euhhwjzeflzrrkkurluwhzfjqf62trsq2dm4984vptwe3zpea0e3n27w75pyt2ym8g734awf99ep759a4xdf29epp5z3e303e08q2e6qdycnlzdkv4c80u2pr7ql2ndpa9zfafwyqkf9r9cqtaza35txq9rppm56ajy97nema0j8vtuld097u0hjhh5cmwhlp8du95va7wvq2emxkvafy2wg8ghl7vpkw2w0w0pp2tr6gqqmnvxy9u9jg9rkwn2flxn9d3vd9x0g94wdhgap22l780d0t9zywpj7yu949347hsag0s6pgpkuaf2pg8xqzjk336e8azw8672qtvvljye6gtevmnrygav24jvsxmypf25rqs8ersr9wfsqhdxjl6lmy3lqac4q96rjn02vtk4n6zcaqzr5qhlqlywrga7lmwt99gyj23ndzrl2y8380nxydqntsh7gpajau2wrvpshzpryq37n72ujf26djermgcf47wfsfdts3p97u0lnt6szck8zlqzqfz6ex8p9xtugj6jtuyf3flkqk9ahu8e6dxgvp49xl24x6hdmljcjqptk8erxq4uwnkv4g32p4d8un0f9az5ft59mwvgtf3xxm3xepx68x6vk46pf5xh4lyr35m32q5ukd5xj4kj7vka67uggjd4saahn3dswdwsqe8epg2pvele4cz0zvq288t94n69cj87ewal7yqvvpq6tj6mpjw2d36uhlf2khtjlpdfv672gvkszdpqht37shtf0ytyfnl7xkeudm2xscvpack3whk29hsfr36cv95ddzc2klk2yc93s09y2uh2xz5k5jap4550z2vd7gy9faxd2gcr4xjmke8yhn0wdsqxpe78qyqy0elprd0swe9yxc82as23vm5fyumc7nv470xfsjy3zjg4zfvpa6kv3ndl0w3ukc6627dua9g7pmzfupesqvpjfw3rjt39hlmk40gtralyk5a4lq42207acdqekpgmdjqpmw0zy3cz8d3cpvw4a28lqnf3etu8adl43zra0zk5m094h86g4yfm8cxnz7zqyqa5q4dz6ceqp0jeuakvyx4653h0kjllhhpfduquh4ch8rtu9lf20gqznhxe60n3m45k80vcgnvut8cfgpvy37ae93rpah9sa4ddyzyt9gu0uj5zny00vrkkxycrxm8wk3gzyrn3q8caxrqwa48fwqzlagph95y5tw555px0s97y4lgspgedkg4q9gxgv4qyc7x8433j0a5fnntdjhlsmx4wje53dj8y4c3757yt87kenvej8ugwkg05flugshd906tvtqcldrcgxfqqw8ecz926ft3ehdahpjhueag8znklv6qvz7w3htszz5k3we0dd8r2grxpz5edgtuh28u0c5ugrf8autgu3crkjt58ev9gcvnetn5kjwk4wehr8yq7lulsug7dhyg0reyqlhhsxs8ueghtm02g7jd2p9ytwstsacry3y8mv202y4qqqqqqqqqqqq9qq6qqqqqqqqqqqqqsqqqqqf2q5htpqqqqqqqraqqqqqqpqp5qqqqqqqqqqqqpqqqqqqqqh6uzcqqqqqqqqqqqqqqeqqrgqqqqqqqqqqqqzqqqqqqjnems5qqqpfqyv6g47m4gyqw3qa7penn7yv39ufk8j0mpj0ldfyu9qd7j2vcgt27rqe9l5ydpm2szfcs2uqczqqqtqggrxc2hkcjr5hvrn0kh6pkeecrjkdcyqn2kl6y6mymsrmqsa4wdcy2lqsxw9n37kdz8zq0ytckjvcy7jcwqrqj5rgd56q4e3g76k0lja96hzkdda7z5sxqwemrdjje2rqm7jcv6ll39dut0mqjvrl4skedkfz5mu").get()
        val (_, _, trampolineOnion) = OutgoingPaymentPacket.buildTrampolineToNonTrampolinePacket(invoice, NodeHop(randomKey().publicKey(), randomKey().publicKey(), CltvExpiryDelta(444), 0.msat), finalAmount, finalExpiry)
        assertTrue(trampolineOnion.packet.payload.size() < 1142)
    }
}

