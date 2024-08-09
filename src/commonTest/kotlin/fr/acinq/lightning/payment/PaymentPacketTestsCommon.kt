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
import fr.acinq.lightning.crypto.sphinx.PacketAndSecrets
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.crypto.sphinx.Sphinx.hash
import fr.acinq.lightning.router.ChannelHop
import fr.acinq.lightning.router.NodeHop
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.currentTimestampMillis
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.utils.toByteVector32
import fr.acinq.lightning.wire.*
import fr.acinq.secp256k1.Hex
import kotlin.test.*

class PaymentPacketTestsCommon : LightningTestSuite() {

    companion object {
        private val privB = randomKey()
        private val b = privB.publicKey()
        private val privC = randomKey()
        private val c = privC.publicKey()
        private val privD = randomKey()
        private val d = privD.publicKey()
        private val defaultChannelUpdate = ChannelUpdate(randomBytes64(), Block.RegtestGenesisBlock.hash, ShortChannelId(0), 0, 1, 0, CltvExpiryDelta(0), 42000.msat, 0.msat, 0, 500000000.msat)
        private val channelUpdateBC = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(1105), cltvExpiryDelta = CltvExpiryDelta(36), feeBaseMsat = 7_500.msat, feeProportionalMillionths = 250)
        private val channelUpdateCD = defaultChannelUpdate.copy(shortChannelId = ShortChannelId(1729), cltvExpiryDelta = CltvExpiryDelta(24), feeBaseMsat = 5_000.msat, feeProportionalMillionths = 100)

        private val finalAmount = 50_000_000.msat
        private const val currentBlockCount = 400_000L
        private val finalExpiry = CltvExpiry(currentBlockCount) + Channel.MIN_CLTV_EXPIRY_DELTA
        private val paymentPreimage = randomBytes32()
        private val paymentHash = Crypto.sha256(paymentPreimage).toByteVector32()
        private val paymentSecret = randomBytes32()
        private val paymentMetadata = randomBytes(64).toByteVector()

        private val nonTrampolineFeatures = Features(
            Feature.VariableLengthOnion to FeatureSupport.Mandatory,
            Feature.PaymentSecret to FeatureSupport.Mandatory,
            Feature.BasicMultiPartPayment to FeatureSupport.Optional,
        )
        private val trampolineFeatures = Features(
            Feature.VariableLengthOnion to FeatureSupport.Mandatory,
            Feature.PaymentSecret to FeatureSupport.Mandatory,
            Feature.BasicMultiPartPayment to FeatureSupport.Optional,
            Feature.TrampolinePayment to FeatureSupport.Optional,
        )

        // Amount and expiry sent by C to D.
        private val expiryCD = finalExpiry
        private val amountCD = finalAmount
        private val feeC = nodeFee(channelUpdateCD.feeBaseMsat, channelUpdateCD.feeProportionalMillionths, amountCD)

        // Amount and expiry sent by B to C.
        private val expiryBC = expiryCD + channelUpdateCD.cltvExpiryDelta
        private val amountBC = amountCD + feeC
        private val feeB = nodeFee(channelUpdateBC.feeBaseMsat, channelUpdateBC.feeProportionalMillionths, amountBC)

        // Amount and expiry sent by A to B.
        private val expiryAB = expiryBC + channelUpdateBC.cltvExpiryDelta
        private val amountAB = amountBC + feeB

        // C is directly connected to the recipient D.
        val nodeHop_cd = NodeHop(c, d, channelUpdateCD.cltvExpiryDelta, feeC)
        // B is not directly connected to the recipient D.
        val nodeHop_bd = NodeHop(b, d, channelUpdateBC.cltvExpiryDelta + channelUpdateCD.cltvExpiryDelta, feeB + feeC)

        // Wallets don't need to decrypt onions for intermediate nodes, but it's useful to test that encryption works correctly.
        fun decryptChannelRelay(add: UpdateAddHtlc, privateKey: PrivateKey): Pair<PaymentOnion.ChannelRelayPayload, OnionRoutingPacket> {
            val decrypted = Sphinx.peel(privateKey, add.paymentHash, add.onionRoutingPacket).right!!
            assertFalse(decrypted.isLastPacket)
            val decoded = PaymentOnion.ChannelRelayPayload.read(decrypted.payload).right!!
            return Pair(decoded, decrypted.nextPacket)
        }

        // Wallets don't need to create channel routes, but it's useful to test the end-to-end flow.
        fun encryptChannelRelay(paymentHash: ByteVector32, hops: List<ChannelHop>, finalPayload: PaymentOnion.FinalPayload): Triple<MilliSatoshi, CltvExpiry, PacketAndSecrets> {
            val (firstAmount, firstExpiry, payloads) = hops.drop(1).reversed().fold(Triple(finalPayload.amount, finalPayload.expiry, listOf<PaymentOnion.PerHopPayload>(finalPayload))) { (amount, expiry, payloads), hop ->
                val payload = PaymentOnion.ChannelRelayPayload.create(hop.lastUpdate.shortChannelId, amount, expiry)
                Triple(amount + hop.fee(amount), expiry + hop.cltvExpiryDelta, listOf(payload) + payloads)
            }
            val onion = OutgoingPaymentPacket.buildOnion(hops.map { it.nextNodeId }, payloads, paymentHash, OnionRoutingPacket.PaymentPacketLength)
            return Triple(firstAmount, firstExpiry, onion)
        }

        // Wallets don't need to decrypt onions for intermediate nodes, but it's useful to test that encryption works correctly.
        fun decryptRelayToTrampoline(add: UpdateAddHtlc, privateKey: PrivateKey): Triple<PaymentOnion.FinalPayload, PaymentOnion.NodeRelayPayload, OnionRoutingPacket> {
            val decrypted = Sphinx.peel(privateKey, add.paymentHash, add.onionRoutingPacket).right!!
            assertTrue(decrypted.isLastPacket)
            val outerPayload = PaymentOnion.FinalPayload.Standard.read(decrypted.payload).right!!
            val trampolineOnion = outerPayload.records.get<OnionPaymentPayloadTlv.TrampolineOnion>()
            assertNotNull(trampolineOnion)
            val decryptedInner = Sphinx.peel(privateKey, add.paymentHash, trampolineOnion.packet).right!!
            assertFalse(decryptedInner.isLastPacket)
            val innerPayload = PaymentOnion.NodeRelayPayload.read(decryptedInner.payload).right!!
            assertNull(innerPayload.records.get<OnionPaymentPayloadTlv.PaymentData>())
            assertNull(innerPayload.records.get<OnionPaymentPayloadTlv.PaymentMetadata>())
            assertNull(innerPayload.records.get<OnionPaymentPayloadTlv.RecipientFeatures>())
            assertNull(innerPayload.records.get<OnionPaymentPayloadTlv.InvoiceRoutingInfo>())
            return Triple(outerPayload, innerPayload, decryptedInner.nextPacket)
        }

        // Wallets don't need to decrypt onions for intermediate nodes, but it's useful to test that encryption works correctly.
        fun decryptRelayToLegacy(add: UpdateAddHtlc, privateKey: PrivateKey): Pair<PaymentOnion.FinalPayload.Standard, PaymentOnion.NodeRelayPayload> {
            val decrypted = Sphinx.peel(privateKey, add.paymentHash, add.onionRoutingPacket).right!!
            assertTrue(decrypted.isLastPacket)
            val outerPayload = PaymentOnion.FinalPayload.Standard.read(decrypted.payload).right!!
            val trampolineOnion = outerPayload.records.get<OnionPaymentPayloadTlv.TrampolineOnion>()
            assertNotNull(trampolineOnion)
            val decryptedInner = Sphinx.peel(privateKey, add.paymentHash, trampolineOnion.packet).right!!
            assertTrue(decryptedInner.isLastPacket)
            val innerPayload = PaymentOnion.NodeRelayPayload.read(decryptedInner.payload).right!!
            return Pair(outerPayload, innerPayload)
        }

        // Wallets don't need to decrypt onions for intermediate nodes, but it's useful to test that encryption works correctly.
        fun decryptRelayToBlinded(add: UpdateAddHtlc, privateKey: PrivateKey): Pair<PaymentOnion.FinalPayload.Standard, PaymentOnion.NodeRelayPayload> {
            val decrypted = Sphinx.peel(privateKey, add.paymentHash, add.onionRoutingPacket).right!!
            assertTrue(decrypted.isLastPacket)
            val outerPayload = PaymentOnion.FinalPayload.Standard.read(decrypted.payload).right!!
            val trampolineOnion = outerPayload.records.get<OnionPaymentPayloadTlv.TrampolineOnion>()
            assertNotNull(trampolineOnion)
            val decryptedInner = Sphinx.peel(privateKey, add.paymentHash, trampolineOnion.packet).right!!
            assertTrue(decryptedInner.isLastPacket)
            val innerPayload = PaymentOnion.NodeRelayPayload.read(decryptedInner.payload).right!!
            return Pair(outerPayload, innerPayload)
        }

        fun decryptRelayToBlindedTrampoline(add: UpdateAddHtlc, privateKey: PrivateKey): Triple<PaymentOnion.FinalPayload.Standard, PaymentOnion.NodeRelayPayload, OnionRoutingPacket> {
            val decrypted = Sphinx.peel(privateKey, add.paymentHash, add.onionRoutingPacket).right!!
            assertTrue(decrypted.isLastPacket)
            val outerPayload = PaymentOnion.FinalPayload.Standard.read(decrypted.payload).right!!
            val trampolineOnion = outerPayload.records.get<OnionPaymentPayloadTlv.TrampolineOnion>()
            assertNotNull(trampolineOnion)
            val decryptedInner = Sphinx.peel(privateKey, add.paymentHash, trampolineOnion.packet).right!!
            assertFalse(decryptedInner.isLastPacket)
            val innerPayload = PaymentOnion.NodeRelayPayload.read(decryptedInner.payload).right!!
            return Triple(outerPayload, innerPayload, decryptedInner.nextPacket)
        }

        fun createBlindedHtlcCD(): Pair<UpdateAddHtlc, OfferPaymentMetadata> {
            val paymentMetadata = OfferPaymentMetadata.V1(randomBytes32(), finalAmount, paymentPreimage, randomKey().publicKey(), "hello", 1, currentTimestampMillis())
            val blindedPayload = RouteBlindingEncryptedData(TlvStream(RouteBlindingEncryptedDataTlv.PathId(paymentMetadata.toPathId(privD))))
            val blindedRoute = RouteBlinding.create(randomKey(), listOf(d), listOf(blindedPayload.write().byteVector())).route
            val finalPayload = PaymentOnion.FinalPayload.Blinded(
                TlvStream(
                    OnionPaymentPayloadTlv.AmountToForward(finalAmount),
                    OnionPaymentPayloadTlv.TotalAmount(finalAmount),
                    OnionPaymentPayloadTlv.OutgoingCltv(finalExpiry),
                    OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads.last()),
                ),
                blindedPayload
            )
            val onionD = OutgoingPaymentPacket.buildOnion(listOf(blindedRoute.blindedNodeIds.last()), listOf(finalPayload), paymentHash, OnionRoutingPacket.PaymentPacketLength)
            val addD = UpdateAddHtlc(randomBytes32(), 1, finalAmount, paymentHash, finalExpiry, onionD.packet, blindedRoute.blindingKey, null)
            return Pair(addD, paymentMetadata)
        }

        fun createBlindedPaymentInfo(u: ChannelUpdate): OfferTypes.PaymentInfo {
            return OfferTypes.PaymentInfo(u.feeBaseMsat, u.feeProportionalMillionths, u.cltvExpiryDelta, u.htlcMinimumMsat, u.htlcMaximumMsat!!, Features.empty)
        }
    }

    @Test
    fun `send a trampoline payment -- recipient connected to trampoline node`() {
        //        .--.
        //       /    \
        // b -> c      d
        val invoice = Bolt11Invoice.create(Chain.Regtest, finalAmount, paymentHash, privD, Either.Left("test"), CltvExpiryDelta(6), trampolineFeatures, paymentSecret, paymentMetadata)
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacketToTrampolineRecipient(invoice, finalAmount, finalExpiry, nodeHop_cd)
        assertEquals(amountBC, firstAmount)
        assertEquals(expiryBC, firstExpiry)

        // B sends an HTLC to its trampoline node C.
        val addC = UpdateAddHtlc(randomBytes32(), 1, amountBC, paymentHash, expiryBC, onion.packet)
        val (outerC, innerC, packetD) = decryptRelayToTrampoline(addC, privC)
        assertEquals(amountBC, outerC.amount)
        assertEquals(amountBC, outerC.totalAmount)
        assertEquals(expiryBC, outerC.expiry)
        assertEquals(finalAmount, innerC.amountToForward)
        assertEquals(finalExpiry, innerC.outgoingCltv)
        assertEquals(d, innerC.outgoingNodeId)

        // C forwards the trampoline payment to D over its direct channel.
        val (amountD, expiryD, onionD) = run {
            val payloadD = PaymentOnion.FinalPayload.Standard.createTrampolinePayload(innerC.amountToForward, innerC.amountToForward, innerC.outgoingCltv, randomBytes32(), packetD)
            encryptChannelRelay(paymentHash, listOf(ChannelHop(c, d, channelUpdateCD)), payloadD)
        }
        assertEquals(amountCD, amountD)
        assertEquals(expiryCD, expiryD)
        val addD = UpdateAddHtlc(randomBytes32(), 2, amountD, paymentHash, expiryD, onionD.packet)
        val payloadD = IncomingPaymentPacket.decrypt(addD, privD).right!!
        val expectedFinalPayload = PaymentOnion.FinalPayload.Standard(
            TlvStream(
                OnionPaymentPayloadTlv.AmountToForward(finalAmount),
                OnionPaymentPayloadTlv.OutgoingCltv(finalExpiry),
                OnionPaymentPayloadTlv.PaymentData(paymentSecret, finalAmount),
                OnionPaymentPayloadTlv.PaymentMetadata(paymentMetadata)
            )
        )
        assertEquals(payloadD, expectedFinalPayload)
    }

    @Test
    fun `send a trampoline payment -- recipient not connected to trampoline node`() {
        //        .-> c -.
        //       /        \
        // a -> b          d
        val invoice = Bolt11Invoice.create(Chain.Regtest, finalAmount, paymentHash, privD, Either.Left("test"), CltvExpiryDelta(6), trampolineFeatures, paymentSecret, paymentMetadata)
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacketToTrampolineRecipient(invoice, finalAmount, finalExpiry, nodeHop_bd)
        assertEquals(amountAB, firstAmount)
        assertEquals(expiryAB, firstExpiry)

        // A sends an HTLC to its trampoline node B.
        val addB = UpdateAddHtlc(randomBytes32(), 1, amountAB, paymentHash, expiryAB, onion.packet)
        val (outerB, innerB, packetC) = decryptRelayToTrampoline(addB, privB)
        assertEquals(amountAB, outerB.amount)
        assertEquals(amountAB, outerB.totalAmount)
        assertEquals(expiryAB, outerB.expiry)
        assertEquals(finalAmount, innerB.amountToForward)
        assertEquals(finalExpiry, innerB.outgoingCltv)
        assertEquals(d, innerB.outgoingNodeId)

        // B forwards the trampoline payment to D over an indirect channel route.
        val (amountC, expiryC, onionC) = run {
            val payloadD = PaymentOnion.FinalPayload.Standard.createTrampolinePayload(innerB.amountToForward, innerB.amountToForward, innerB.outgoingCltv, randomBytes32(), packetC)
            encryptChannelRelay(paymentHash, listOf(ChannelHop(b, c, channelUpdateBC), ChannelHop(c, d, channelUpdateCD)), payloadD)
        }
        assertEquals(amountBC, amountC)
        assertEquals(expiryBC, expiryC)

        // C relays the payment to D.
        val addC = UpdateAddHtlc(randomBytes32(), 2, amountC, paymentHash, expiryC, onionC.packet)
        val (payloadC, packetD) = decryptChannelRelay(addC, privC)
        val addD = UpdateAddHtlc(randomBytes32(), 3, payloadC.amountToForward, paymentHash, payloadC.outgoingCltv, packetD)
        val payloadD = IncomingPaymentPacket.decrypt(addD, privD).right!!
        val expectedFinalPayload = PaymentOnion.FinalPayload.Standard(
            TlvStream(
                OnionPaymentPayloadTlv.AmountToForward(finalAmount),
                OnionPaymentPayloadTlv.OutgoingCltv(finalExpiry),
                OnionPaymentPayloadTlv.PaymentData(paymentSecret, finalAmount),
                OnionPaymentPayloadTlv.PaymentMetadata(paymentMetadata)
            )
        )
        assertEquals(payloadD, expectedFinalPayload)
    }

    // See bolt04/trampoline-payment-onion-test.json
    @Test
    fun `send a trampoline payment -- reference test vector`() {
        //                    .-> Dave -.
        //                   /           \
        // Alice -> Bob -> Carol         Eve
        val paymentHash = ByteVector32("4242424242424242424242424242424242424242424242424242424242424242")
        // Alice creates a trampoline onion Carol -> Eve.
        val trampolineOnionForCarol = run {
            val paymentSecret = ByteVector32("2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a")
            val carol = PublicKey.fromHex("027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007")
            val eve = PublicKey.fromHex("02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145")
            val payloads = listOf(
                PaymentOnion.NodeRelayPayload.create(100_000_000.msat, CltvExpiry(800_000), eve),
                PaymentOnion.FinalPayload.Standard.createSinglePartPayload(100_000_000.msat, CltvExpiry(800_000), paymentSecret, paymentMetadata = null),
            )
            val sessionKey = PrivateKey.fromHex("0303030303030303030303030303030303030303030303030303030303030303")
            OutgoingPaymentPacket.buildOnion(sessionKey, listOf(carol, eve), payloads, paymentHash).packet
        }
        assertEquals(
            "0002531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe3371860c0749bfd613056cfc5718beecc25a2f255fc7abbea3cd75ff820e9d30807d19b30f33626452fa54bb2d822e918558ed3e6714deb3f9a2a10895e7553c6f088c9a852043530dbc9abcc486030894364b205f5de60171b451ff462664ebce23b672579bf2a444ebfe0a81875c26d2fa16d426795b9b02ccbc4bdf909c583f0c2ebe9136510645917153ecb05181ca0c1b207824578ee841804a148f4c3df7306dcea52d94222907c9187bc31c0880fc084f0d88716e195c0abe7672d15217623",
            Hex.encode(OnionRoutingPacketSerializer(trampolineOnionForCarol.payload.size()).write(trampolineOnionForCarol)),
        )
        // Alice wraps it into a payment onion Alice -> Bob -> Carol.
        val onionForBob = run {
            val paymentSecret = ByteVector32("2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b")
            val bob = PublicKey.fromHex("0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c")
            val carol = PublicKey.fromHex("027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007")
            val payloads = listOf(
                PaymentOnion.ChannelRelayPayload.create(ShortChannelId("572330x7x1105"), 100_005_000.msat, CltvExpiry(800_250)),
                PaymentOnion.FinalPayload.Standard.createTrampolinePayload(100_005_000.msat, 100_005_000.msat, CltvExpiry(800_250), paymentSecret, trampolineOnionForCarol),
            )
            val sessionKey = PrivateKey.fromHex("0404040404040404040404040404040404040404040404040404040404040404")
            OutgoingPaymentPacket.buildOnion(sessionKey, listOf(bob, carol), payloads, paymentHash, null, OnionRoutingPacket.PaymentPacketLength).packet
        }
        assertEquals(
            "0003462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b9149ce01cce1709194109ab594037113e897ab6120025c770527dd8537997e2528082b984fe078a5667978a573abeaf7977d9b8b6ee4f124d3352f7eea52cc66c0e76b8f6d7a25d4501a04ae190b17baff8e6378b36f165815f714559dfef275278eba897f5f229be70fc8a1980cf859d1c25fe90c77f006419770e19d29ba80be8f613d039dd05600734e0d1e218af441fe30877e717a26b7b37c2c071d62bf6d61dd17f7abfb81546d2c722c9a6dc581aa97fb6f3b513e5fbaf0d669fbf0714b2b016a0a8e356d55f267fa144f7501792f2a59269c5a22e555a914e2eb71eba5af519564f246cf58983ea3fa2674e3ab7d9969d8dffbb2bda2b2752657417937d46601eb8ebf1837221d4bdf55a4d6a97ecffde5a09bd409717fa19e440e55d775890aed89f72e65af515757e94a9b501e6bad048af55e1583adb2960a84f60fb5efd0352e77a34045fc6b221498f62810bd8294d995d9f513696f8633f29faaa9668d0c6fa0d0dd7fa13e2c185572485762bd2810dc12187f521fbefa9c320762ac1e107f7988d81c6ee201ab68a95d45d578027e271b6526154317877037dca17134ccd955a22a8481b8e1996d896fc4bf006154ed18ef279d4f255e3f1233d037aea2560011069a0ae56d6bfdd8327054ded12d85d120b8982bff970986db333baae7c95f85677726a8f74cc8bd1e5aca3d433c113048305ecce8e35caf0485a53df00284b52b42291a9ffe686b96442135b3107d8856bc652d674ee9a148e79b02c9972d3ca8c2b02609f3b358c4a67c540ba6769c4d83169bceda640b1d18b74d12b6df605b417dacf6f82d79d43bb40920898f818dc8344c036ae9c8bbf9ef52ea1ccf225c8825a4d8503df190b999e15a4be34c9d7bbf60d3b93bb7d6559f4a5916f5e40c3defeeca9337ccd1280e46d6727c5c91c2d898b685543d4ca7cfee23981323c43260b6387e7febb0fffb200a8c974ef36b3253d0fb9fe0c1c6017f2dbbdc169f3f061d9781521e8118164aeec31c3e59c199016f1025c295d8f7bdeb627d357105a2708c4c3a856b9e83ff37ed69f59f2d2e464ed1db5882925ebe2493a7ddb707e1a308fa445172a24b3ea60732f75f5c69b41fc11467ee93f37c9a6f7285ba42f716e2a0e30909056ea3e4f7985d14ca9ab280cc184ce98e2a0722d0447aa1a2eedc5e53ddfa53731df7eced406b10627b0bebd768a30bde0d470c0f1d10adc070f8d3029cacceec74e4833f4dc8c52c3f41733f5f896fceb425d0737e717a63bfb033df46286d99594dd01e2bd0a942ab792874177b32842f4833bc0340ddb74852e9cd6f29f1d997a4a4bf05dd5d12011f95e6ce18928e3a9b83b24d15f989bdf43370bcc657c3ac6601eaf5e951efdbd7ee69b1623dc5039b2dfc640692378ef032f17bc36cc00293ad90b7e18f5feb8f287a7061ed9713929aed9b14b8d566199fc7822b1c38daa16b6d83077b10af0e2b6e531ccc34ea248ea593128c9ff17defcee6618c29cd2d93cfed99b90319104b1fdcfea91e98b41d792782840fb7b25280d8565b0bcd874e79b1b323139e7fc88eb5f80f690ce30fcd81111076adb31de6aeced879b538c0b5f2b74c027cc582a540133952cb021424510312f13e15d403f700f3e15b41d677c5a1e7c4e692c5880cb4522c48e993381996a29615d2956781509cd74aec6a3c73b8536d1817e473dad4cbb1787e046606b692a44e5d21ef6b5219658b002f674367e90a2b610924e9ac543362257d4567728f2e61f61231cb5d7816e100bb6f6bd9a42329b728b18d7a696711650c16fd476e2f471f38af0f6b00d45c6e1fa492cc7962814953ab6ad1ce3d3f3dc950e64d18a8fdce6aabc14321576f06",
            Hex.encode(OnionRoutingPacketSerializer(OnionRoutingPacket.PaymentPacketLength).write(onionForBob))
        )
        // Bob decrypts the payment onion and forwards it to Carol.
        val onionForCarol = run {
            val priv = PrivateKey.fromHex("4242424242424242424242424242424242424242424242424242424242424242")
            val add = UpdateAddHtlc(randomBytes32(), 1, 100_006_000.msat, paymentHash, CltvExpiry(800_300), onionForBob)
            decryptChannelRelay(add, priv).second
        }
        assertEquals(
            "00036d7fa1507cac3a1fda3308b465b71d817a2ee8dfde598c6bdb1dec73c7acf0165136fdd0fd9f3f7eac074f42b015825614214ac3b7ec95234538c9cfd04fc1a5128fa47c8d56e21e51bb843da8252c0abafb72395cf6ca8a186bd1de72341cb0f988e79988c39e4d444a4495120ccf3577576177a45c2a0fdc88776291d3af9e62d700c06206c769260859715ba5e1e7c0dc5f97dbf80decb564c885d0d6f0e10bddb225ee3d82a1e02b6a3735ea81ab91dada382a5752a940814e38c709e62d3427d69bfd09a19955c507aea300bf10578e3bda3d632a5de159f3fc0ff9311b2fc5d4a6c03582c4cd85c92d29bc285971f1019cb468942a7d3706e096f6ab105e7d8d525586a4f7987135af70d166317dc2b5b6c58345c54e87615d277e7ade5f0b9f8baed5f16e1b340492c4fa6b443f94544a4f083e4dfb778badf1084c0c39e998cd67ff5f1a6526fb163cfd48e04ff34d928a91f061781463b9f668a0d084e6c5bb80413968ee3185abd545b38f63f496d9fa16e67d84c08414df8c03d0efb1925edcdd14a4134424f65372166be4a8e66906a428eb726ae43ea6cf81256f082382e18b765e78cd21819045b5bcc5f4464b812215f8838acf73c5a4748a09ee10b6bcec9c201dc38ef009b23b9072d653c81316a59b36533732f4c4eeb29863bcf420155aa90378a111f0393599fb9dd42f69808c3552654b7352a6a1e2a71db0a0214c8d9021ef52d667da4d351a9a44a0cdbff34894d1994e7cced665061b6979f9e508d98ac9b2193f01694597e8189122daf0bd3c82743f5994678b4efb309028be23987bc18720388bc78be39b02276a0f3577390e36a5cd0dbab97b08a5c7f45a5a952681a2669e653004977b2a1e098a5bfee2ee927c2f51fc9dc66af120b5a40b01738c5db1e091f7141096e3c4d5905a695f02c852fd40412c7288c15befb522eec41232899863c17f66cbfefb3597c346fc7483a03d0f3f2dcaa6ae56d508d0df9298d80b2bcfcb91b30b298ca2415a3cbc8284bb2f4a5cfc244efe2d78a446d36d350bebd7ff30d70a2015679f1a3a63d841e2333fa30ebabf9d84576616f3c93a78a42948d991e1c628c3dbb3ad856fe97f9a3ce3d2b7e8e3ff2e1c3b2eb494dd9c947878120a8912afda70ca7d7829b9011f13c848d10e69274f4bd918c4c5531c8382e5f1c0b72ecabbd34d14190cde1a3247e4473c8016a122077f4a9cddf21c11680c2c25c342dacc7676304dd2466b47a172641e33de3cf9c2f476f57e0a90cdb7f8398dc012fd65df9a685a73b8f6f02a2ba3045e0cb308a72645370c827ac43da67f614e2d68b7811805b8144e6f21e94b679003486aa79bad22db09735d72e5a32c5831c3e44c9100322ae70c74df52ba98653624361b62d9500b704450e6e23d3373aae9697fed5e6133d1d1677608be513344590fd72569c6e19c070d303e8aa6f7196e7ac0f4039912cf1e9f050c6927340f9a96de229adbe7906072bc87c2214dc476d8dc7d81f2cb56d5a7407fe9fb378703f04fe19f1bb4b8f938c84072a4ac0b18de581b4b8b5971ce411cc82a0484764a6df49f8ffc3c858a299b4ffc9f96b933bd12f4fc876b6ce34f7c022ded91d51a03c5f14c29a9f7b28e45395782d74a3d795ac596b44ba36f805d62e3ba7976f10904784af7f5994cc57817979a0adf87e3b3e32047f0a4d68c2609c9405612b264094c49dd27836f4bdab4d68256b2b4d8e10411ff166065265fdd0c04c6c3ad989530f258b9549128765f0cc6af5e50cf15d3fd856e91580bf66a7ebce267726aee798b580df6deaee59fa90c5a35e06f36d4960c326d0418adcbe3ff4248bf04dc24a3758de2c58f97fd9e4333beae43428d184e3872ad52d2b4dd4d770da0dca339bf70a6b22dd05cf8547ec0a7a8d49543",
            Hex.encode(OnionRoutingPacketSerializer(OnionRoutingPacket.PaymentPacketLength).write(onionForCarol))
        )
        // Carol decrypts the payment onion and the inner trampoline onion.
        val trampolineOnionForEve = run {
            val priv = PrivateKey.fromHex("4343434343434343434343434343434343434343434343434343434343434343")
            val add = UpdateAddHtlc(randomBytes32(), 1, 100_005_000.msat, paymentHash, CltvExpiry(800_250), onionForCarol)
            decryptRelayToTrampoline(add, priv).third
        }
        assertEquals(
            "00035e5c85814fdb522b4efeef99b44fe8a5d3d3412057bc213b98d6f605edb022c2ae4a9141f6ac403790afeed975061f024e2723d485f9cb35a3eaf881732f468dc19009bf195b561590798fb895b7b7065b5537018dec330e509e8618700c9c6e1df5d15b900ac3c34104b6abb1099fd2eca3b640d7d5fda9370e20c09035168fc64d954baa80361b965314c400da2d7a64d0536bf9e494aebb80aec358327a4a1a667fcff1daf241c99dd8c4fa907de5b931fb9daed083c157f5ea1dd960d142952f8ebe4e1ccaee4d565a093e2b91f94b04a884ce2e8c60aced3565e8d2d10de5",
            Hex.encode(OnionRoutingPacketSerializer(trampolineOnionForEve.payload.size()).write(trampolineOnionForEve)),
        )
        // Carol wraps the trampoline onion for Eve into a payment Carol -> Dave -> Eve.
        val onionForDave = run {
            val paymentSecret = ByteVector32("2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c2c")
            val dave = PublicKey.fromHex("032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991")
            val eve = PublicKey.fromHex("02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145")
            val payloads = listOf(
                PaymentOnion.ChannelRelayPayload.create(ShortChannelId("572330x42x1729"), 100_000_000.msat, CltvExpiry(800_000)),
                PaymentOnion.FinalPayload.Standard.createTrampolinePayload(100_000_000.msat, 100_000_000.msat, CltvExpiry(800_000), paymentSecret, trampolineOnionForEve),
            )
            val sessionKey = PrivateKey.fromHex("0505050505050505050505050505050505050505050505050505050505050505")
            OutgoingPaymentPacket.buildOnion(sessionKey, listOf(dave, eve), payloads, paymentHash, null, OnionRoutingPacket.PaymentPacketLength).packet
        }
        assertEquals(
            "000362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f74125c590a7877d17303ddcdce79b0b33e006eaf4eff557631c70c7ab9a61105ffd738239016e84c2639ff5246f3d2167ea7ea7932138435a9cc427f7f9d838daa2b7f4c3bfb8c44e8e48d2fd744c1c5a7626d188b5690d36900eb0a498cd0b4139424bc1b65d74409a72fca8e36f239f4c80644963e80391ca1c707f727e3dc9656de66bfdf77823b0b5746c55c31978faffd65937b2c526478e4f30d08cc371fb9d045f65316af2d416c9a82ac412db84e4386901877670c8a2fcdd1b2f3276c5384f2feb23d4c62788cce78edc1194bf4fbd2af5670d2917cc940c41897fea944ebf908a1a90a1bd208b42209ccf2d480d2590bfce320ce185f12e77703f906e98b1a9ff701490b792a60faba11d75d691c2cecf867bb63062ec8c3bd1c2665dbd380e59cdffbfd028e5c86a1371fd3d5141e50986247a9f21143df0d1099e6df7e2044f1f4a87bc759cb7c2354616e39ace2d06165a580206ae9c5bc5005a6654215e7ab1bb619eb2df5bc11e3a8cfbc0a9c7e515c0f6d9d02512ef856d4782e54192ea63a173b4fcf02a11e85d2da6de47a6f8dd9bbfb30dccecd5e2195d5c9b0bf0bfc8b571b4962deacba4669afa017294b45e2668ad87168b9589f00f56275022f049f0cdece8c9e1f0f35035aa1af4a70103a7e8aa2b7a6579accf554c6a4f305981f5732036894765e086c167f5f342f313e4617da53b79303c72e0a6f03c3f592cb9c035c509c02dc09e5ea20b158a3f47b1722db86d354f7dfccbdaf6be21c7f473e143b459b2b06a21984f29ba80dfcd52696c76fb2a11f66383e33d88226f451317125fcfa02671015c359db52ee1462b1b820588d5c874765de4e7cc83b84dde8630b2a21325116cf53fd1eb369bed1330dfcbe0633698c518a376312624d78011922621e32e9b316a9329c3d1f967069d35844e60caf53e7a2bbbe695808de2e91dc16a9dd408ab2a8c363f2a5c34124f9c79010db4706e1315e1ff230741a9ab7e069318db587004bd0ccb71aad37c616b276bc0fe883865ba730b4d86ce2ae710185747d0860e00bb37b97fe71d79492a2e9a3bc07e5693f92de886fab3802ac62a8a4adcbf041eec05152cd28fd77154799e99674c8ea571519186ad9eb84a26edcef86473621e05515f3278810f931c662d037d9da73612c2f9d7d64e573595c402e9166299cbe356119ca38a3c6da77d6f864d61062b4300e388b631f60c25cb364b76561b4064c13e9e25d1ecb491472047157ea04fbbf6ccfe36cb2c030250b0335ae00255cf3670a61a5f207d72fccaac0b36a74d041f62341bc3759cd17d6e1c81aafcbbdc0f29906e54bc66dc1217031f881c9782eabe09de6835cdf4426113fb28e3bc0a73b007521c9a5abdc4a602c3c3358f0d3d81c8d84da5cc8acf1d15c9dd038ca64229097c666099a701b47bcf3a35e2541d4554a7bc1e3d4693b031c35f33b063d339558911870dd8bc3a52895612bee20ea8a7b0110da64362a357a4f9dbd7ff01155278c1173c57dd3d1b0947e58b571673544dbeff1c19cdb0ab9901671b3d043c4173fbcdf8e9cb03585bb9987414080046b6f283fc7c3aa245152941138636cd1b201e59080b8a7257bc2d7046c18d738c64804b088ac0983fbaeb92624f3ddc175afa3afc85cc8d83815bea41a195e883a4044a6406dbcb67682fc0522d2c920bc1d2372e95ea31408fcbe53e91c787e6da85255c40d0c9dbb0d4a5ded5886c90664bec4396f94782851fcd8565562a9df646025ad224078add8a05b8614ad0ce33141213a4650590ebaef22ef10b9cca5c4025eeaf58796baf052824d239586d7c706431fa1240f36a4f882d36ca608ece021b803386356f13a22bf3f42ef39d",
            Hex.encode(OnionRoutingPacketSerializer(OnionRoutingPacket.PaymentPacketLength).write(onionForDave))
        )
        // Dave decrypts the payment onion and forwards it to Eve.
        val onionForEve = run {
            val priv = PrivateKey.fromHex("4444444444444444444444444444444444444444444444444444444444444444")
            val add = UpdateAddHtlc(randomBytes32(), 1, 100_001_000.msat, paymentHash, CltvExpiry(800_100), onionForDave)
            decryptChannelRelay(add, priv).second
        }
        assertEquals(
            "00037d517980f2321ce95c8ecea4aebceb2f62ebbbac598973439c79e9f66d28ce5c728d3226c796b85df07009baec8b4e46d73bf6bbf6f8dfa8bcf610bda5de6ebaf395b5a8572e30e91e402688834a13db55d04c28dc1bfdcc07c602532330ee6ce1bd6acce875c81fd53b8f7f4243ed940bb5e4897252763968a00c2b59d6cbfdf73bd86e4b9135a63dcf99612da557962da6b525c68b3159e4f56cd49cf654ca6240ec5a0c2365731266eb4263e16c90aed2fbd662de9aa22ce7bf8af18687d99550e48477c6c46f8a84957d24ac323381e69b57342d82e06082c645fcd96eb77ed4f563f04e7e7913e4bac16a78e56d223baead194b4cd80c97fa7d892d0288780ac90f1020e0cb43e267721bbbdd6fb759da9df2744882f4259a4e5bab60aaca2847311122c1d60a483c978d7b3042ae189892f85e1e7e3ad89d48769404b5dea1ddf1794b3c6b002286995e976b1de9c2457895a00952a06983986a619863e4c60f17e40a210e89273fa7f55ebd83887d451b5e658b9092e81540de49a4e4a05a757aa103ca5dc63194094869f067d5cc36e2d59de9d038f3b5a4b6fa4fd5b276db7b77182ddc96eaf53bfbfa2f988b785643047a5639965dde3baafeac2db4efbdf04da3520766c012c988d64c9e3aa2f723baa4926e413b18b93bdeec4e0761ef55bedea1de8751b49cb8a67a15ddeae511e06f03d36c2158aba897997c53a2f12e2db98214b093c0ee3c5fe6763dc7a3418db28a571a88da50a851eac78f78c29c489d6a09751976f4e456ffa23b71b3894e9263476d490e842de6a41fd085bf218691b1de3b4cf077f560fc86dd7f8d24c06912e5b9d53fc7b36d3f5bcde6cb9f22d5db09c0ec4e870466d0549f5fcd0e6849aa925f3f238b1a613c022ea22dc308899330113b60576b7fc8904233a77cf24ad2f9482cdc1265f6e74353d92d4fbff4a0a42dfebb92ac71c7fc2c79ccd1b187bd4542ed2d1808735179bebaba664f49a75d2823f7e7041e1cc0f717899b7eb2c2b9550be185f1a0b2245a48fdc205c5339742ad14e370193158997f4d4edff05297a4668705f667b2a858a0b8af56aa4b93fb41b30e16a50a75fdc0ce33dd94da254d8b1e55c40aa49444aacf4796a6979f0feca13924ff3a886d3e859e51d2d585ee919abcc82c396da1df7a4e97f415a01c25c1a5ae2fe65f4cc385e16d91e54836e12e7588d89ee41dfef277b97eb7d6c6ebfc6ed4f89b13d776904fad6e405123bca86068dc558dbbc284c65947f295b8828e7e35e80fd0981ba46229d47e646afa73f55070ae4a202b8c46719e6449632a4377eedbe83a69d1f422e73bc159172e631165dc5fe63e09dcace38218de2598204127255535d6c2197003383195af636cfd8615bd8a5db96057fe4ee67156685351d90c3db5bf61d3e573877572f58f982d9fbb35bd678143ccc1f2cccf1fd34c20e0a59b4c837540fac3964068eec3ffb8981a2ab774c542f74168ccd7fa9b08141cd0bda0d99ecee10a3857818370456c3c00d3f7b514f30ff6c31f11147851c8438411de3fc71719fbf79df3cab963231732c95850d59df90144161c2ef84a8b1c76a9494b8dd7234782bc61a6fc23222599a14163f78e117c99f33b3d2a4b11339903b41e7cfc253f1319d4c3ab1dc3d31a503c0bf9c233cb9216201d71abf915b8e50c0612b1fdba8ea8f248767256597151ba2f58dd67d470f8cfdfdc0bffceba618587f652a2155c58717a85e1eff38149b521f99449b35ed2a5ecb474fe60257d261017386ae08ea61cf907ebb7d2d5b1a55e50088449563d1d788d8b4f18ee57e24c6cab40dcd569495c6ea13fa1ca68dbeb6fed7462444ca94b6561471b4e1a75945d7327e5e56348bbd5cae106bf74976cc9288394a731b3555401e59c2718001171b6d6",
            Hex.encode(OnionRoutingPacketSerializer(OnionRoutingPacket.PaymentPacketLength).write(onionForEve))
        )
        // Eve decrypts the payment onion and the inner trampoline onion.
        run {
            val priv = PrivateKey.fromHex("4545454545454545454545454545454545454545454545454545454545454545")
            val add = UpdateAddHtlc(randomBytes32(), 1, 100_000_000.msat, paymentHash, CltvExpiry(800_000), onionForEve)
            val payload = IncomingPaymentPacket.decrypt(add, priv).right
            assertNotNull(payload)
            assertIs<PaymentOnion.FinalPayload.Standard>(payload)
            assertEquals(payload.amount, 100_000_000.msat)
            assertEquals(payload.totalAmount, 100_000_000.msat)
            assertEquals(payload.expiry, CltvExpiry(800_000))
            assertEquals(payload.paymentSecret, ByteVector32("2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a"))
        }
    }

    @Test
    fun `send a trampoline payment to legacy non-trampoline recipient`() {
        //        .-> c -.
        //       /        \
        // a -> b          d
        val routingHint = listOf(Bolt11Invoice.TaggedField.ExtraHop(c, channelUpdateCD.shortChannelId, channelUpdateCD.feeBaseMsat, channelUpdateCD.feeProportionalMillionths, channelUpdateCD.cltvExpiryDelta))
        val invoice = Bolt11Invoice.create(Chain.Regtest, finalAmount, paymentHash, privD, Either.Left("test"), CltvExpiryDelta(6), nonTrampolineFeatures, paymentSecret, paymentMetadata, extraHops = listOf(routingHint))
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacketToLegacyRecipient(invoice, finalAmount, finalExpiry, nodeHop_bd)
        assertEquals(amountAB, firstAmount)
        assertEquals(expiryAB, firstExpiry)

        // A sends an HTLC to its trampoline node B.
        val addB = UpdateAddHtlc(randomBytes32(), 1, amountAB, paymentHash, expiryAB, onion.packet)
        val (outerB, innerB) = decryptRelayToLegacy(addB, privB)
        assertEquals(amountAB, outerB.amount)
        assertEquals(amountAB, outerB.totalAmount)
        assertEquals(expiryAB, outerB.expiry)
        assertEquals(invoice.paymentSecret, outerB.paymentSecret)
        assertEquals(finalAmount, innerB.amountToForward)
        assertEquals(finalAmount, innerB.totalAmount)
        assertEquals(finalExpiry, innerB.outgoingCltv)
        assertEquals(d, innerB.outgoingNodeId)
        assertEquals(invoice.paymentSecret, outerB.paymentSecret)
        assertEquals(invoice.paymentMetadata, outerB.paymentMetadata)
        assertEquals(ByteVector("024100"), outerB.recipientFeatures) // var_onion_optin, payment_secret, basic_mpp
        assertEquals(listOf(routingHint), outerB.invoiceRoutingInfo)

        // B forwards the trampoline payment to D over an indirect channel route.
        val (amountC, expiryC, onionC) = run {
            val payloadD = PaymentOnion.FinalPayload.Standard.createSinglePartPayload(innerB.amountToForward, innerB.outgoingCltv, outerB.paymentSecret, outerB.paymentMetadata)
            encryptChannelRelay(paymentHash, listOf(ChannelHop(b, c, channelUpdateBC), ChannelHop(c, d, channelUpdateCD)), payloadD)
        }
        assertEquals(amountBC, amountC)
        assertEquals(expiryBC, expiryC)

        // C relays the payment to D.
        val addC = UpdateAddHtlc(randomBytes32(), 2, amountC, paymentHash, expiryC, onionC.packet)
        val (payloadC, packetD) = decryptChannelRelay(addC, privC)
        val addD = UpdateAddHtlc(randomBytes32(), 3, payloadC.amountToForward, paymentHash, payloadC.outgoingCltv, packetD)
        val payloadD = IncomingPaymentPacket.decrypt(addD, privD).right!!
        val expectedFinalPayload = PaymentOnion.FinalPayload.Standard(
            TlvStream(
                OnionPaymentPayloadTlv.AmountToForward(finalAmount),
                OnionPaymentPayloadTlv.OutgoingCltv(finalExpiry),
                OnionPaymentPayloadTlv.PaymentData(paymentSecret, finalAmount),
                OnionPaymentPayloadTlv.PaymentMetadata(paymentMetadata),
            )
        )
        assertEquals(payloadD, expectedFinalPayload)
    }

    @Test
    fun `send a trampoline payment to blinded paths`() {
        val features = Features(Feature.BasicMultiPartPayment to FeatureSupport.Optional)
        val offer = OfferTypes.Offer.createNonBlindedOffer(finalAmount, "test offer", d, features, Block.RegtestGenesisBlock.hash)
        // D uses a 1-hop blinded path from its trampoline node C.
        val (invoice, payerKey, blindedRoute) = run {
            val payerKey = randomKey()
            val request = OfferTypes.InvoiceRequest(offer, finalAmount, 1, features, payerKey, "hello", Block.RegtestGenesisBlock.hash)
            val paymentMetadata = OfferPaymentMetadata.V1(offer.offerId, finalAmount, paymentPreimage, payerKey.publicKey(), "hello", 1, currentTimestampMillis())
            val blindedPayloadC = RouteBlindingEncryptedData(
                TlvStream(
                    RouteBlindingEncryptedDataTlv.OutgoingChannelId(channelUpdateCD.shortChannelId),
                    RouteBlindingEncryptedDataTlv.PaymentRelay(channelUpdateCD.cltvExpiryDelta, channelUpdateCD.feeProportionalMillionths, channelUpdateCD.feeBaseMsat),
                    RouteBlindingEncryptedDataTlv.PaymentConstraints(finalExpiry, 1.msat),
                )
            )
            val blindedPayloadD = RouteBlindingEncryptedData(
                TlvStream(
                    RouteBlindingEncryptedDataTlv.PathId(paymentMetadata.toPathId(privD))
                )
            )
            val blindedRouteDetails = RouteBlinding.create(randomKey(), listOf(c, d), listOf(blindedPayloadC, blindedPayloadD).map { it.write().byteVector() })
            val paymentInfo = createBlindedPaymentInfo(channelUpdateCD)
            val path = Bolt12Invoice.Companion.PaymentBlindedContactInfo(OfferTypes.ContactInfo.BlindedPath(blindedRouteDetails.route), paymentInfo)
            val invoice = Bolt12Invoice(request, paymentPreimage, blindedRouteDetails.blindedPrivateKey(privD), 600, features, listOf(path))
            assertEquals(invoice.nodeId, blindedRouteDetails.route.blindedNodeIds.last())
            assertNotEquals(invoice.nodeId, d)
            Triple(invoice, payerKey, blindedRouteDetails.route)
        }

        // B pays that invoice using its trampoline node C to relay to the invoice's blinded path.
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacketToBlindedRecipient(invoice, payerKey, finalAmount, finalExpiry, nodeHop_cd)
        assertEquals(amountBC, firstAmount)
        assertEquals(expiryBC, firstExpiry)

        // C decrypts the onion that contains a blinded path in the trampoline onion.
        val addC = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val (outerC, innerC) = decryptRelayToBlinded(addC, privC)
        assertEquals(amountBC, outerC.amount)
        assertEquals(amountBC, outerC.totalAmount)
        assertEquals(expiryBC, outerC.expiry)
        assertEquals(finalAmount, innerC.amountToForward)
        assertEquals(finalExpiry, innerC.outgoingCltv)
        assertEquals(listOf(blindedRoute), outerC.outgoingBlindedPaths.map { it.route.route })
        assertEquals(invoice.features.toByteArray().toByteVector(), outerC.recipientFeatures)

        // C is the introduction node of the blinded path: it can decrypt the first blinded payload and relay to D.
        val addD = run {
            val (dataC, blindingD) = RouteBlinding.decryptPayload(privC, blindedRoute.blindingKey, blindedRoute.encryptedPayloads.first()).right!!
            val payloadC = RouteBlindingEncryptedData.read(dataC.toByteArray()).right!!
            assertEquals(channelUpdateCD.shortChannelId, payloadC.outgoingChannelId)
            // C would normally create this payload based on the payment_relay field it received.
            val payloadD = PaymentOnion.FinalPayload.Blinded(
                TlvStream(
                    OnionPaymentPayloadTlv.AmountToForward(innerC.amountToForward),
                    OnionPaymentPayloadTlv.TotalAmount(innerC.amountToForward),
                    OnionPaymentPayloadTlv.OutgoingCltv(innerC.outgoingCltv),
                    OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads.last()),
                ),
                // This dummy value is ignored when creating the htlc (C is not the recipient).
                RouteBlindingEncryptedData(TlvStream(RouteBlindingEncryptedDataTlv.PathId(ByteVector("deadbeef"))))
            )
            val onionD = OutgoingPaymentPacket.buildOnion(listOf(blindedRoute.blindedNodeIds.last()), listOf(payloadD), addC.paymentHash, OnionRoutingPacket.PaymentPacketLength)
            UpdateAddHtlc(randomBytes32(), 2, innerC.amountToForward, addC.paymentHash, innerC.outgoingCltv, onionD.packet, blindingD, null)
        }

        // D can correctly decrypt the blinded payment.
        val payloadD = IncomingPaymentPacket.decrypt(addD, privD).right!!
        assertIs<PaymentOnion.FinalPayload.Blinded>(payloadD)
        assertEquals(finalAmount, payloadD.amount)
        assertEquals(finalExpiry, payloadD.expiry)
        val paymentMetadata = OfferPaymentMetadata.fromPathId(d, payloadD.pathId)
        assertNotNull(paymentMetadata)
        assertEquals(offer.offerId, paymentMetadata.offerId)
        assertEquals(paymentMetadata.paymentHash, invoice.paymentHash)
    }

    @Test
    fun `send a trampoline payment to blinded paths -- recipient with trampoline`() {
        val features = Features(Feature.BasicMultiPartPayment to FeatureSupport.Optional, Feature.TrampolinePayment to FeatureSupport.Optional)
        val offer = OfferTypes.Offer.createNonBlindedOffer(finalAmount, "test offer", d, features, Block.RegtestGenesisBlock.hash)
        val payerKey = randomKey()
        // D uses a 1-hop blinded path from its trampoline node C.
        val (invoice, blindedRoute) = run {
            val request = OfferTypes.InvoiceRequest(offer, finalAmount, 1, features, payerKey, "hello", Block.RegtestGenesisBlock.hash)
            val paymentMetadata = OfferPaymentMetadata.V1(offer.offerId, finalAmount, paymentPreimage, payerKey.publicKey(), "hello", 1, currentTimestampMillis())
            val blindedPayloadC = RouteBlindingEncryptedData(
                TlvStream(
                    RouteBlindingEncryptedDataTlv.OutgoingChannelId(channelUpdateCD.shortChannelId),
                    RouteBlindingEncryptedDataTlv.PaymentRelay(channelUpdateCD.cltvExpiryDelta, channelUpdateCD.feeProportionalMillionths, channelUpdateCD.feeBaseMsat),
                    RouteBlindingEncryptedDataTlv.PaymentConstraints(finalExpiry, 1.msat),
                )
            )
            val blindedPayloadD = RouteBlindingEncryptedData(
                TlvStream(
                    RouteBlindingEncryptedDataTlv.PathId(paymentMetadata.toPathId(privD))
                )
            )
            val blindedRouteDetails = RouteBlinding.create(randomKey(), listOf(c, d), listOf(blindedPayloadC, blindedPayloadD).map { it.write().byteVector() })
            val paymentInfo = createBlindedPaymentInfo(channelUpdateCD)
            val path = Bolt12Invoice.Companion.PaymentBlindedContactInfo(OfferTypes.ContactInfo.BlindedPath(blindedRouteDetails.route), paymentInfo)
            val invoice = Bolt12Invoice(request, paymentPreimage, blindedRouteDetails.blindedPrivateKey(privD), 600, features, listOf(path))
            Pair(invoice, blindedRouteDetails.route)
        }

        // B pays that invoice using its trampoline node C to relay to the invoice's blinded path.
        // B doesn't know D's identity and instead uses the invoice node_id.
        val hop = nodeHop_cd.copy(nextNodeId = invoice.nodeId)
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacketToBlindedRecipient(invoice, payerKey, finalAmount, finalExpiry, hop)
        assertEquals(amountBC, firstAmount)
        assertEquals(expiryBC, firstExpiry)

        // C decrypts the onion that contains a blinded path in the trampoline onion.
        val addC = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val (outerC, innerC, trampolineOnionD) = decryptRelayToBlindedTrampoline(addC, privC)
        assertEquals(amountBC, outerC.amount)
        assertEquals(amountBC, outerC.totalAmount)
        assertEquals(expiryBC, outerC.expiry)
        assertEquals(finalAmount, innerC.amountToForward)
        assertEquals(finalExpiry, innerC.outgoingCltv)
        assertEquals(listOf(blindedRoute), outerC.outgoingBlindedPaths.map { it.route.route })
        assertEquals(invoice.features.toByteArray().toByteVector(), outerC.recipientFeatures)

        // C is the introduction node of the blinded path: it can decrypt the first blinded payload and relay to D.
        val addD = run {
            val (dataC, blindingD) = RouteBlinding.decryptPayload(privC, blindedRoute.blindingKey, blindedRoute.encryptedPayloads.first()).right!!
            val payloadC = RouteBlindingEncryptedData.read(dataC.toByteArray()).right!!
            assertEquals(channelUpdateCD.shortChannelId, payloadC.outgoingChannelId)
            // C would normally create this payload based on the payment_relay field it received.
            val payloadD = PaymentOnion.FinalPayload.Blinded(
                TlvStream(
                    OnionPaymentPayloadTlv.AmountToForward(innerC.amountToForward),
                    OnionPaymentPayloadTlv.TotalAmount(innerC.amountToForward),
                    OnionPaymentPayloadTlv.OutgoingCltv(innerC.outgoingCltv),
                    OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads.last()),
                    OnionPaymentPayloadTlv.TrampolineOnion(trampolineOnionD),
                ),
                // This dummy value is ignored when creating the htlc (C is not the recipient).
                RouteBlindingEncryptedData(TlvStream(RouteBlindingEncryptedDataTlv.PathId(ByteVector("deadbeef"))))
            )
            val onionD = OutgoingPaymentPacket.buildOnion(listOf(blindedRoute.blindedNodeIds.last()), listOf(payloadD), addC.paymentHash, OnionRoutingPacket.PaymentPacketLength)
            UpdateAddHtlc(randomBytes32(), 2, innerC.amountToForward, addC.paymentHash, innerC.outgoingCltv, onionD.packet, blindingD, null)
        }

        // D can correctly decrypt the blinded payment.
        val payloadD = IncomingPaymentPacket.decrypt(addD, privD).right!!
        assertIs<PaymentOnion.FinalPayload.Blinded>(payloadD)
        assertEquals(finalAmount, payloadD.amount)
        assertEquals(finalExpiry, payloadD.expiry)
        val paymentMetadata = OfferPaymentMetadata.fromPathId(d, payloadD.pathId)
        assertNotNull(paymentMetadata)
        assertEquals(offer.offerId, paymentMetadata.offerId)
        assertEquals(paymentMetadata.paymentHash, invoice.paymentHash)
    }

    // See bolt04/trampoline-to-blinded-path-payment-onion-test.json
    @Test
    fun `send a trampoline payment to blinded paths -- recipient without trampoline -- reference test vector`() {
        val preimage = ByteVector32.fromValidHex("8bb624f63457695115152f4bf9950bbd14972a5f49d882cb1a68aa064742c057")
        val paymentHash = Crypto.sha256(preimage).byteVector32()
        assertEquals(ByteVector32("e89bc505e84aaca09613833fc58c9069078fb43bfbea0488f34eec9db99b5f82"), paymentHash)
        val alicePayerKey = PrivateKey.fromHex("40086168e170767e1c2587d503fea0eaa66ef21069c5858ec6e532503d6a4bd6")
        // Eve creates a blinded path to herself going through Dave and advertises that she supports MPP.
        val (blindedPath, pathId) = run {
            val evePriv = PrivateKey.fromHex("4545454545454545454545454545454545454545454545454545454545454545")
            val eve = evePriv.publicKey()
            assertEquals(PublicKey.fromHex("02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145"), eve)
            val dave = PublicKey.fromHex("032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991")
            val features = Features(Feature.BasicMultiPartPayment to FeatureSupport.Optional)
            val offer = OfferTypes.Offer.createNonBlindedOffer(null, "bolt12", eve, features, Block.RegtestGenesisBlock.hash)
            val paymentMetadata = OfferPaymentMetadata.V1(offer.offerId, 150_000_000.msat, preimage, alicePayerKey.publicKey(), "hello", 1, 0)
            val blindedPayloadEve = RouteBlindingEncryptedData(TlvStream(RouteBlindingEncryptedDataTlv.PathId(paymentMetadata.toPathId(evePriv))))
            assertContentEquals(
                Hex.decode("06 bf 0149792a42a127e421026a0c616e9490fb560d8fa5374a3d38d97aa618056a2ad70000000008f0d1808bb624f63457695115152f4bf9950bbd14972a5f49d882cb1a68aa064742c05702414343fd4a723942a86d5f60d2cfecb6c5e8a65595c9995332ec2dba8fe004a20000000000000001000000000000000068656c6c6f7bcdd1f21161675ee57f03e449abd395867d703a0fa3c1c92fe9111ad9da9fe216f8c170fc25726261af0195732366dad38384c0ab24060c7cd65c49d1de8411"),
                blindedPayloadEve.write(),
            )
            val blindedPayloadDave = RouteBlindingEncryptedData(
                TlvStream(
                    RouteBlindingEncryptedDataTlv.OutgoingChannelId(ShortChannelId("572330x42x2465")),
                    RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(36), 1000, 500.msat),
                    RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(850_000), 1.msat),
                )
            )
            assertContentEquals(
                Hex.decode("020808bbaa00002a09a1 0a080024000003e801f4 0c05000cf85001"),
                blindedPayloadDave.write(),
            )
            val sessionKey = PrivateKey.fromHex("090a684b173ac8da6716859095a779208943cf88680c38c249d3e8831e2caf7e")
            val blindedRouteDetails = RouteBlinding.create(sessionKey, listOf(dave, eve), listOf(blindedPayloadDave, blindedPayloadEve).map { it.write().byteVector() })
            assertEquals(EncodedNodeId(dave), blindedRouteDetails.route.introductionNodeId)
            assertEquals(PublicKey.fromHex("02c952268f1501cf108839f4f5d0fbb41a97de778a6ead8caf161c569bd4df1ad7"), blindedRouteDetails.lastBlinding)
            assertEquals(PublicKey.fromHex("02988face71e92c345a068f740191fd8e53be14f0bb957ef730d3c5f76087b960e"), blindedRouteDetails.route.blindingKey)
            val blindedNodes = listOf(
                PublicKey.fromHex("0295d40514096a8be54859e7dfe947b376eaafea8afe5cb4eb2c13ff857ed0b4be"),
                PublicKey.fromHex("020e2dbadcc2005e859819ddebbe88a834ae8a6d2b049233c07335f15cd1dc5f22"),
            )
            assertEquals(blindedNodes, blindedRouteDetails.route.blindedNodeIds)
            val encryptedPayloads = listOf(
                ByteVector("0ae636dc5963bcfe2a4705538b3b6d2c5cd87dce29374d47cb64d16b3a0d95f21b1af81f31f61c01e81a86"),
                ByteVector("bcd747ba974bc6ac175df8d5dbd462acb1dc4f3fa1de21da4c5774d233d8ecd9b84b7420175f9ec920f2ef261cdb83dc28cc3a0eeb970107b3306489bf771ef5b1213bca811d345285405861d08a655b6c237fa247a8b4491beee20c878a60e9816492026d8feb9dafa84585b253978db6a0aa2945df5ef445c61e801fb82f43d59347cc1c013a2351f094cdafb5e0d1f5ccb1055d6a5dd086a69cd75d34ea06067659cb7bb02dda9c2d89978dc725168f93ab2fe22dff354bce6017b60d0cc5b29b01540595e6d024f3812adda1960b4d"),
            )
            assertEquals(encryptedPayloads, blindedRouteDetails.route.encryptedPayloads)
            val paymentInfo = OfferTypes.PaymentInfo(500.msat, 1000, CltvExpiryDelta(36), 1.msat, 500_000_000.msat, Features.empty)
            Pair(Bolt12Invoice.Companion.PaymentBlindedContactInfo(OfferTypes.ContactInfo.BlindedPath(blindedRouteDetails.route), paymentInfo), blindedPayloadEve.pathId)
        }
        // Alice creates a trampoline onion for Carol.
        val trampolineOnion = run {
            val carol = PublicKey.fromHex("027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007")
            val trampolinePayload = PaymentOnion.NodeRelayPayload.create(150_000_000.msat, CltvExpiry(800_000), blindedPath.route.route.blindedNodeIds.last())
            assertContentEquals(
                Hex.decode("2e 020408f0d180 04030c3500 0e21020e2dbadcc2005e859819ddebbe88a834ae8a6d2b049233c07335f15cd1dc5f22"),
                trampolinePayload.write(),
            )
            val sessionKey = PrivateKey.fromHex("a64feb81abd58e473df290e9e1c07dc3e56114495cadf33191f44ba5448ebe99")
            OutgoingPaymentPacket.buildOnion(sessionKey, listOf(carol), listOf(trampolinePayload), paymentHash).packet
        }
        assertEquals(
            "0002bc59a9abc893d75a8d4f56a6572f9a3507323a8de22abe0496ea8d37da166a8b4bba0e560cd679eb602bfdbcfe9166363bcd44474d21685d34c6596af9cc65b86ca99c0395638d8449c731d273a702716445d3a4a79423bf9fc28102ff6d0217a536016a49264510c8bd4a900463f9110eaac1d671dc115b8c156a46c61f0b2b94cb2e0bedfb5d62e18aec2ba35e1a",
            Hex.encode(OnionRoutingPacketSerializer(trampolineOnion.payload.size()).write(trampolineOnion)),
        )
        // Alice creates a payment onion for Carol (Alice -> Bob -> Carol).
        val onionForBob = run {
            val sessionKey = PrivateKey.fromHex("4f777e8dac16e6dfe333066d9efb014f7a51d11762ff76eca4d3a95ada99ba3e")
            val bob = PublicKey.fromHex("0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c")
            val carol = PublicKey.fromHex("027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007")
            val paymentSecret = ByteVector32("7494b65bc092b48a75465e43e29be807eb2cc535ce8aaba31012b8ff1ceac5da")
            val payloadBob = PaymentOnion.ChannelRelayPayload.create(ShortChannelId("572330x42x2821"), 150_153_000.msat, CltvExpiry(800_060))
            assertContentEquals(
                Hex.decode("15 020408f32728 04030c353c 060808bbaa00002a0b05"),
                payloadBob.write(),
            )
            val payloadCarol = PaymentOnion.FinalPayload.Standard(
                TlvStream(
                    OnionPaymentPayloadTlv.AmountToForward(150_153_000.msat),
                    OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(800_060)),
                    OnionPaymentPayloadTlv.PaymentData(paymentSecret, 150_153_000.msat),
                    OnionPaymentPayloadTlv.RecipientFeatures(Features(Feature.BasicMultiPartPayment to FeatureSupport.Optional).toByteArray().toByteVector()),
                    OnionPaymentPayloadTlv.OutgoingBlindedPaths(listOf(blindedPath)),
                    OnionPaymentPayloadTlv.TrampolineOnion(trampolineOnion),
                )
            )
            assertContentEquals(
                Hex.decode(
                    "fd026e 020408f32728 04030c353c 08247494b65bc092b48a75465e43e29be807eb2cc535ce8aaba31012b8ff1ceac5da08f32728 14910002bc59a9abc893d75a8d4f56a6572f9a3507323a8de22abe0496ea8d37da166a8b4bba0e560cd679eb602bfdbcfe9166363bcd44474d21685d34c6596af9cc65b86ca99c0395638d8449c731d273a702716445d3a4a79423bf9fc28102ff6d0217a536016a49264510c8bd4a900463f9110eaac1d671dc115b8c156a46c61f0b2b94cb2e0bedfb5d62e18aec2ba35e1a 1503020000 16fd01a1032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e66868099102988face71e92c345a068f740191fd8e53be14f0bb957ef730d3c5f76087b960e020295d40514096a8be54859e7dfe947b376eaafea8afe5cb4eb2c13ff857ed0b4be002b0ae636dc5963bcfe2a4705538b3b6d2c5cd87dce29374d47cb64d16b3a0d95f21b1af81f31f61c01e81a86020e2dbadcc2005e859819ddebbe88a834ae8a6d2b049233c07335f15cd1dc5f2200d1bcd747ba974bc6ac175df8d5dbd462acb1dc4f3fa1de21da4c5774d233d8ecd9b84b7420175f9ec920f2ef261cdb83dc28cc3a0eeb970107b3306489bf771ef5b1213bca811d345285405861d08a655b6c237fa247a8b4491beee20c878a60e9816492026d8feb9dafa84585b253978db6a0aa2945df5ef445c61e801fb82f43d59347cc1c013a2351f094cdafb5e0d1f5ccb1055d6a5dd086a69cd75d34ea06067659cb7bb02dda9c2d89978dc725168f93ab2fe22dff354bce6017b60d0cc5b29b01540595e6d024f3812adda1960b4d000001f4000003e800240000000000000001000000001dcd65000000"
                ),
                payloadCarol.write(),
            )
            OutgoingPaymentPacket.buildOnion(sessionKey, listOf(bob, carol), listOf(payloadBob, payloadCarol), paymentHash, null, OnionRoutingPacket.PaymentPacketLength).packet
        }
        assertEquals(
            "00025fd60556c134ae97e4baedba220a644037754ee67c54fd05e93bf40c17cbb73362fb9dee96001ff229945595b6edb59437a6bc1434064bdf3bcd29ba620556891aed0c145c1c82d2892999ca624f09f9bebd9a803d3587c5a757bcf20af7e63d7192b46cf171e4f73cb11f9f603915389105d91ad630224bea95d735e3988add1e24b5bf28f1d7128db642e8db283fc0b6b2b1b0d88245a466c812e2d513df89f886442115c7951736f098e724438f61031659aba9959587373a70fc71f529af69dde5f97081b8a852601816bea568f0b21ff9fbbbec0076d988f19b26079ecf60f321f41a9b8c4e59a5dbf3114b5e770f22ef330bb5ab251568c15632a19419edca606ea76581aab6356bf5bebe9d7cd9cb489168eb4d5338b5349543d26cd126e33ad24a52e5b90718a0c6e489090422a9874149382ef8142485c9d253953c224af912638988b0c23cba489b1310e956f4954b0d1b5a9742a2b7fb5ddc77cf687baeec388a8f183f9476731fb75262ad1a70e6a9c7cef54ac96641406d313271fc5f41026c6f45dff187370371006eff4e87eb46532d6aeca6c5ccf63e2d55fb52165a96e38b38a1697b8cafa167aea81d01d57bd4bbe8b40e73fbf7bb857b2635d8c6c2a0e1b69f0438757d89ebc293c45d02dcdd83352ddcbd9b266084824272541b57396a43d05c55c5a2c8f761d071b2f065fe9f2b72ff3fcd5c60f2110093599b5fedd31d615bfc62a69fe0517869921f629fd423791218f07ef2576c422deb3b326ee6e9fe46d4f981061f190772dff16a75675cc58925154f356083246d7b0fda9391dc7899c11e0d5861724be2c455ca265804e00ee5513ee91e242fea07a312e17bb47bb30efecf0e4e4b7e1c9301db6a238bba037920b76279c931e30b6a4a6401e3d694830de74864e3b3c68cb8a29fe1345be368abe28ed296080c881f9b0c815ebd622a40f38634ab93fe631d4fd4c74aebe535d6100014cd3d4f9ef1027dfe8faa98b4e9b2bee0156d254d7ded66dba77b0fe499849908dbbe8f6398e2347dd9cd6a5e59b7d27ad9bc8d255aca3c505b05a3bc837c856205a26da5af533511d22931608ab83ac6a37a6bf338c30ece3ead8a0f5d1aca1256e0d5bd184135dd9d14b4b537d8cc4aabd5f2d54a0d188b3f9833af1c4af49b5b2d4a39337be12ef7ae840e78e5d669c5935e16fae6354ae4c7df95692597602329f07b0a6cee232e3617c25af187b223ce4dbbba64d3cbe4b893bca459c20f91b35d65ad2e6d0c29df0d7120e53d0bb073d49d93cf78264ac56c701e930a78069acd86cf0f3fd347049121b99eacc6ed2e2fd08bb31f193b74b2cc7c8d4ce66a290299df216c15d999f07de49769413b7e41bae03357ac3adabc13c60345dd1c63d4aea4f5790bf4907510192bc76f19d8d0b6f4ff95e15845a46f617c18730e71d927254d59b3941098411a93ff51c1c17a1fe5cacb76e363d8384d7262b9639b85f2c26bfaaaad675fb9b7f4df0036587f53641e9ec6ea23f36b574a17b61df4818b8dc6ed9370bd1f2863b9c2c876e323b17a489478d3f3bd6548255d2b7644eb8563a5843df35c80b60fe75cfddf9d8d3a1354f586387ae8e65c7e6f117d37bae353167f7a1d3849532f0b754c1cf09dbe1237523082060f666bb1652c8de8ed71fd595c0fdbc313ad910c7472fde2a62591b00a1a75b21a9d89fc3da06c017a743ceddb806e98905a0862ad2c9fc5f580f57638743d8368bb3f89e280e69a88f4090300db455243f2e4ea16e2620ea2050077b3b57913f93445cd8d10df13fb010147679599fb3283bcf35e9a052f556f318fe718fc9ad873a6a80e8a5cb947b82915b34ef55ce76f8abb23c23ae3a0a1dd48be6de9baace842cee11fa42c5d46ae3342d38c6c919914678a5b1725910de4",
            Hex.encode(OnionRoutingPacketSerializer(OnionRoutingPacket.PaymentPacketLength).write(onionForBob)),
        )
        // Bob decrypts the onion and relays to Carol.
        val onionForCarol = run {
            val bobPriv = PrivateKey.fromHex("4242424242424242424242424242424242424242424242424242424242424242")
            val add = UpdateAddHtlc(randomBytes32(), 1, 150_155_000.msat, paymentHash, CltvExpiry(800_100), onionForBob)
            val (payload, onion) = decryptChannelRelay(add, bobPriv)
            assertEquals(ShortChannelId("572330x42x2821"), payload.outgoingChannelId)
            assertEquals(150_153_000.msat, payload.amountToForward)
            assertEquals(CltvExpiry(800_060), payload.outgoingCltv)
            onion
        }
        assertEquals(
            "0003dc6a4c9b34bdcd2191fc4dacfc1aeb20f71991acbd17847b9ab17d69579c1614da124dd18820e55534d7352839caa436aa79a6d5be26c6ecbd1c79f74442b1b7bf8ed8b739b736b9248769c2c422eebc85fb0d580ef9639371faabcdb5f7b924579641c6ea0e47d5f56c2c90330db5ef61a4310f5ab6024fd6d4d8812bea66ba5c7203ca05de3f777b473b307d21ef5b62a32621b198d22df8ba9962aa5bf087f47af172286d279716d71e4a105c051b6592344913386c1f45ce98d70646201ab173d4092f36f88d7221c47d6cc6a06bbff5add0e92b899407d4c16a8416264cdae173f5ab10228e5db9b0476c5dde3526ca0c9c4cbdaebca1c4766b8d8e9c585132af0ed904cb5968b71383ee14a8b7734754b6d12396d052179f43b265a613ec726414816c22d3c27c55f440043aee950da4552d09dea3e61500d62808849d40bce8662a74b5d572d51f407844bed6058dbeec98b8f8b2b02d94e6e1924311579e7e57f9d5a3c51b50e54f17c4072554d14f2f7a859310e5b4ef72fc6908c9ae5d78af8cbecb68f79236377ca75bd0479daf383b51e7935dadcf6212a7cdc9cc4d1c26835b45de2b7d310af6d0707fe850a81dacb1a059c7bc1dd8025d9896e9db220ef9389b452b2be48b672415e93a4552191d3d9959ad710e2f14e242124b7255ee18a16423fe59b71071346d7e28f9089fd2239362ab17a711f7ba4cb5aa72f1d6af335c1acee31f42d1fd5279e6d04a03b855b23e6de9fe91ffcc6c8a899ee8bd85d40ba0adf09d5c0c0f8a4a84bddb005834cd7c804b53ebb332086e6bc188baf28a2c7ec4b6a305e63c82fd84f37af8ce97dc73b3e23bc965493a5c6076a2879ce56fb703c22b209cf700893c1ca37b9c5bc1feffa90c385e5aaa1ee89cc6c44114d758fb899841b1cde8eeb32bcf3fedae6233097ece3dbe2d2c6ee41e66a0b4f91833b1f843c2e707776d7eb103619fad624a5e47a45a932c5f042e360f63fab7364975b0012434ccc132b3fd602715c6d894dccd4faf1ba97b55263860e0fdebe84a74173f0d0728d60598a36f39868cda23c87f7f3425204184813b0730bc707cf21a79b28b645586d1542aea352bc327faeb174b58971da5f1015a395a80840239bb90411eb700f5177f2e53325a17fc759bb260c7ad207e7f49c09f5c50fae107f9ba4e8757bc940ba2fa2aa554643872425861d927ada94be037f897879bf1c8f816b71ee2434ac3bcde788fe853ad9d95ed92ebbe1ded440b75755523923e7ad1fb289ed887954a2c27f3b3c110712a2b85304122a504907a827527d3e9fc52d86c7b87620af82d6cb4cbf16d9a207722b4cd18ec379298046c5326da367eb5c025eeb2abe8a40c024bff27408a964bd7093b1fb023688a784d446d94645a8a2a485ff2fef2eaa6726186fed7497facdaebc5799b9c76cbe28bbffc5c44b652d35f4e6665535ddf8e4a81d4105c91ca67c04460c8f6ed9f445a5fb550a810b9808912416e11020699522f253ed7253b53385bd5c1151c6e6d3562516445b6f0bbfeb675e9deb71ec29904ee9c4d6942e905e65265a3af1f4cb563384c77b741324c01d4b6700aada29a206c338b692b5b89df2c4fec378e77fe39af39cbc7e215a4144f2b8c0ecfa54b6a9a1c7b52c65a9d70a3d40af860ecb491cd5b3261b1ae1951877520abe39ac0534999f0a23ade8d02efc5f20d658669bc31285ce1257ecf326baf473738eb88a73f4cc40d9a47609a998fea11474ca1224a6b9f983582d318bae33df0e903070f2c992df498b2757addea35364b48cd93b6a26cb6888d5c4cc1d3c6a39cb442c2de12bb1ad396205c9a10023d7edc951fc60a28813867f635c3d74d4206f398849e0c533f210b6b0fb2114f143adac5bf065a86b673636bb78160cecf4fd3f953a2",
            Hex.encode(OnionRoutingPacketSerializer(OnionRoutingPacket.PaymentPacketLength).write(onionForCarol)),
        )
        // Carol decrypts the onion and relays to the blinded path's introduction node Dave.
        val onionForDave = run {
            val carolPriv = PrivateKey.fromHex("4343434343434343434343434343434343434343434343434343434343434343")
            val add = UpdateAddHtlc(randomBytes32(), 1, 150_153_000.msat, paymentHash, CltvExpiry(800_060), onionForCarol)
            val (payload, trampolinePayload) = decryptRelayToBlinded(add, carolPriv)
            assertEquals(150_153_000.msat, payload.amount)
            assertEquals(CltvExpiry(800_060), payload.expiry)
            assertEquals(ByteVector32("7494b65bc092b48a75465e43e29be807eb2cc535ce8aaba31012b8ff1ceac5da"), payload.paymentSecret)
            assertEquals(Features(Feature.BasicMultiPartPayment to FeatureSupport.Optional).toByteArray().toByteVector(), payload.recipientFeatures)
            assertEquals(1, payload.outgoingBlindedPaths.size)
            assertEquals(150_000_000.msat, trampolinePayload.amountToForward)
            assertEquals(CltvExpiry(800_000), trampolinePayload.outgoingCltv)
            val outgoingPath = payload.outgoingBlindedPaths.first()
            assertEquals(outgoingPath.route.nodeId, trampolinePayload.outgoingNodeId)
            assertEquals(outgoingPath.paymentInfo, OfferTypes.PaymentInfo(500.msat, 1000, CltvExpiryDelta(36), 1.msat, 500_000_000.msat, Features.empty))
            val sessionKey = PrivateKey.fromHex("e4acea94d5ddce1a557229bc39f8953ec1398171f9c2c6bb97d20152933be4c4")
            val payloadDave = PaymentOnion.BlindedChannelRelayPayload.create(outgoingPath.route.route.encryptedPayloads.first(), outgoingPath.route.route.blindingKey)
            assertContentEquals(
                Hex.decode("50 0a2b0ae636dc5963bcfe2a4705538b3b6d2c5cd87dce29374d47cb64d16b3a0d95f21b1af81f31f61c01e81a86 0c2102988face71e92c345a068f740191fd8e53be14f0bb957ef730d3c5f76087b960e"),
                payloadDave.write(),
            )
            val payloadEve = PaymentOnion.FinalPayload.Blinded(
                TlvStream(
                    OnionPaymentPayloadTlv.AmountToForward(trampolinePayload.amountToForward),
                    OnionPaymentPayloadTlv.OutgoingCltv(trampolinePayload.outgoingCltv),
                    OnionPaymentPayloadTlv.TotalAmount(trampolinePayload.amountToForward),
                    OnionPaymentPayloadTlv.EncryptedRecipientData(outgoingPath.route.route.encryptedPayloads.last())
                ),
                // ignored
                RouteBlindingEncryptedData(TlvStream(RouteBlindingEncryptedDataTlv.PathId(ByteVector("deadbeef"))))
            )
            assertContentEquals(
                Hex.decode("e4 020408f0d180 04030c3500 0ad1bcd747ba974bc6ac175df8d5dbd462acb1dc4f3fa1de21da4c5774d233d8ecd9b84b7420175f9ec920f2ef261cdb83dc28cc3a0eeb970107b3306489bf771ef5b1213bca811d345285405861d08a655b6c237fa247a8b4491beee20c878a60e9816492026d8feb9dafa84585b253978db6a0aa2945df5ef445c61e801fb82f43d59347cc1c013a2351f094cdafb5e0d1f5ccb1055d6a5dd086a69cd75d34ea06067659cb7bb02dda9c2d89978dc725168f93ab2fe22dff354bce6017b60d0cc5b29b01540595e6d024f3812adda1960b4d 120408f0d180"),
                payloadEve.write(),
            )
            val nodes = listOf((outgoingPath.route.route.introductionNodeId as EncodedNodeId.WithPublicKey).publicKey, outgoingPath.route.route.blindedNodeIds.last())
            OutgoingPaymentPacket.buildOnion(sessionKey, nodes, listOf(payloadDave, payloadEve), paymentHash, payloadLength = OnionRoutingPacket.PaymentPacketLength).packet
        }
        assertEquals(
            "0003626d5fa7ed0b8706f975ca58ae2c645889514153568aaab7835325ecd3739a5760171be581c1df1ce5267cd012e06f45a97fdbc5d8fc0c140c7432d34027b9ed0f2ab09fd388fe47525309f9c4509a7b9d74d88c28e40b1b6598e42fe2975f857e28b224316f266decf170cbfb5019dea2dab2ee7f1db089d44d1f974d6974bd06e515510cc1c178cd46c2442f07b9a083b4e4c7e9dd8d728508353959497fb8d25ebe8db83c60488566952fb1725267088ccb98acace7147d3846388464aa9512fe94f1962a54d8896d94105b185a41201e0dacf51f755da8e666a78261bc478ca77bd0ef5576bd7a4b24bb1fee9a97618bac0dcc4f1a34d64f5623446e2458089299be2f07592d69619bd6048c37a0460062b194f6f05da8f4ac1c5ff19681067398fdde459c60b4f448d5b3c1152988f6e29dc73b3a5407f1a502dcf2d656bcdba5f05eb4a7ecd3a1373c495dbc23109912aa456f0d9c1460f99f8151886ec8a69af2ac3ed76823ce372fb46a3c20ff114c04ee4a16ff673382b1abedcfc5e8d6f6e77c893dc346cf01f323bb043840546f9728b060514ddc4359c3ebc818abe56c8219260e26c833ff6faef7c02a3e669026dcc0a96ca4f0f8240185422355e0a5c9529bf65e7c52b384cfbe2eeb3ba32c118cfb6961068362b7bf41b2b1580cfc85757fd294840154cb8b13df456c6b86957a33391fd78e3aec6ff2fb9dabeefc63dc4faadfc12016d9d9501381c9751c581256cc24f8d8fecec2a1efaa9579935d5f5f7d14edb64ce97ab9deacb5c9d4c111325c70493ad7921966369315a1ec09c320c3cdc7a65ce52ff8ba5e9a71326e57f8b30766e6e5c77747b07e351c3e91efdf736a31410c9e278a683dfe1ca9c35d7868f11e1e429cb7655ff126438c83f69c3db2c5257e03f7d4f95e8d49400ee36e8d7f1629ac79f0b63430b115349df21e8d286a69b41d52e52e36553b16edf4c77acfb9d4596abd5054daf076b06abb3f84ecbea3e6d324965c7667ec7f83388ee05583f53f258291a806c025e300d63c81f5a411447b3ab3ee47b2dae485b8a87129224ad16fcc043a2d1b89e5c4f35f02675efa79730f5ee07d2de9d6ab503aa329f201ad0c9040d8c3437efde15c53b9212e93e0ece4a3ee7ae99a18b3fd75e8d1ee0ce9c73bfd5c2bbe30a91a3f92169a05887069dd31edf575265425d09998e2466bdf86919cdbfbbdb55c718b046197028b4370dd850833853b969a37e31f2cce96020a1fb22959b4529ae501f44d989b3f7473aaa787899ba200468b070079e2b9a3cd6b04b3caf2de5956aed477e4b3a9f0c93ac3f1042d16ee6a36744460e6d86144522215eeed052daebc7861d7189abe78edb67dd7ae47224b9bdb5907fdca6e6573dfe4bcf24ce1c6a4dfbae6991a8ac6976d9ec8a81f08dbdc34bf1cb18d93aa2e9d876335e0fce0d7a7b6c7080a70b1fc9bef912e4550931005210da7c46c76cf63fb02202df35d332e9ad779ef5ee086fd9fa993852be315691cf84c7e588ec61726b9fe5200ad30b2d43b1684f1dcd8df3f1598ae3841125eadfd534b074d560fd8e0eb9660c93a478ccfdd2308f587a45d5b933af280d39a77e19cd72c170931e4c8e44028bb6db77ec1e9b77af225e39db67bfb80afc6a0efe9864a80222fbfd6c4b6ad9afd43c76f2b9fc0cd0a4b07939147b005be7e6418295830a9bb114cc2c40bdb715077ef4219643455f2675ba00c0e6464f612c32cffa39f49d80ff91cf1363e109101e368114537fbd94428b6ed1934f6d8cd3b6cf2ec736ddcacf63007481fcd6dd9fca8ee39d9a4bbdd06349a5e86af75d8723eeeddc6f84575516997f7db931b91007bcfd21f1b5a8dc69ee846492493054b012e5a4ff3707d5aae44a4ca65210eb1c14c8d138441170f2e5e2920c1e4",
            Hex.encode(OnionRoutingPacketSerializer(OnionRoutingPacket.PaymentPacketLength).write(onionForDave)),
        )
        // Dave decrypts the onion and relays to Eve.
        val onionForEve = run {
            val davePriv = PrivateKey.fromHex("4444444444444444444444444444444444444444444444444444444444444444")
            val decrypted = Sphinx.peel(davePriv, paymentHash, onionForDave).right!!
            assertFalse(decrypted.isLastPacket)
            val decoded = PaymentOnion.BlindedChannelRelayPayload.read(decrypted.payload).right!!
            assertNotNull(decoded.records.get<OnionPaymentPayloadTlv.EncryptedRecipientData>())
            decrypted.nextPacket
        }
        assertEquals(
            "0002580900a2090fc8b09d77f87781aa5a5964372ee968bd8488da62e04e3f1d68bda5d48cb395a094d2d60f43d8af709c5b44bba1c51f3d590c462829104a18ec68d5c36989a3d6af086f2f61e791e619fd62bf6fccdcdb1dc01bb1798bff5550d385a3ea26ce909d6d218eb12cfa089d11d33a1cb1299510a4c5ac1f767ee18230960b2a37994dc05378ca9d6ce8c29c61dc543f11b676f1ffd3c0c0fe7d43168ecaa1760b115d397b4886c17daaf8dabaac2c5ce3e57f7b5441130e828c5368eb605c841045d84d137197512d9a6efa3bb8fb05a70af7b14f5d01518a61932717ebd04e5022e6925767f07f33b63894bbaff907967999001d6b4cb4b3bc42a9057b8b1d269172f638275688915ae9c07d276fa8aaf037a59069c3d2121a79e8eb869ade6b1dc5073922145d7e1246baa202544d5045fca6fb2974dabc145257d32f0ab5afdbb121b9d93dc1b3d345038714d70ed941be5dec56d4c5cb0582ebcb2d4a78356f75bf1696f82deecec4a97a23746b440082e07ee7d5ebac4c098a48fe0d64de53b303b960b52aabe8df029b9cb5b6079ecac2a2841dd662a2099e1c5995176b9dbc90054b789a07cfbd35e93c0da58eefa7150f7c793b37f4934e2541d6002be6953a0dfda018a881b4d7458d04d4ece3d6d570f1ac46e2eb7ad29652adae7f56ca804d88a1a92ecc17bad4ab7879e93aa56782c46f8b0fca6068a5c3593cddcd372db066bbd7ea615a0fc8b01b61849930959d3ec7951d619b93fc9feecd07c91ec6206a8a489023a55349c1d0b6c294104190090c2c82f1e00c1cf4c9349b09544157aedfed527fa5725408d38d8026916f6baac3218ab5469e157bc91475a5117947efab4af7a64373694cc62b08e0b8bfe1a35ba2f80fac95e043a17e850590bbfbb59d4c190a4fa1642d790e3403a34522f33de66e839c3e3706f9bea9d95efca2f9c7b012bfc39cb9f3e7dad4a1c7b52a8d02151a1b3524a64033d2868e9c450d496f66d71c414870c15911dee4365f1aab8a20b3968a67d04dd724955b0396a00dbdbaa0c0037a2bb8202061f6cc653a10e6ed8ce98a5b1315d5efa96603e989ce1cf315cf2e300f12696c96e45efd397776f8a781d12b8d4e3f265e49c8932cf54525af20977ab1c5b5e0a4f929074baf6b0d4fde175d02a78e0fccd4e814c0a2139475ea16517c33389a41160014f537c43c818f70ba1b9503987885f634f93b995c04f7302d1ff85add09232a2d2be27fbbf98d754c6a0e2c32f66b2a2cd6d5feef4ad10b62303ce05049e862e96987defc569cf6406585fedcc4bf4981ad67cb6af242e25f9bf701e5236deb61305bd0c20c2bfa0d17d6519979f3085427dcad1677959fa40565e16f2feee4b4974de401123f4b3e0f0e740305cdecc8f4b65d638cdd5b1af0013d5806c9d6661b96954463adee45cbacf33c16e836d8e544cab9eb47f9f661d415772a9dae0d4c3ffb44015bc6921e05e6bd8c5159893fd7e5291f6e40c84db19266a35c666afb1ec16d8c4bc507b887df09a2c71a599dbcdf75ced11eb8cd9c65f05a14a3a381971e615bdece5946affe0dbdbbb54a777e5d996e9cb9a5163bc503b88b15b31cd0fa3a8206701aa9f4068e6baac2b2f342e02f94ed22f43f285a6790ff1e216c917af77b5af726e403ce8615959b31e6d051c0a17f737ffef28264ec31c3f0f690f0f142c0b16c88507a44714516fdaee00b697288fdfea823a30bf11fa6cf3ae2215eb42b98aae1e80444c6f2688a5f8f80f1236fb3d12584f33bdfc33beb8c5b7bfdfeb94e25ed4c1fdf69f4a28f6cbb7fa0fb9424927e195908d0a8894555d02f285962a53a984fca3f6b3fb843e4d559e5294c2e01dd1dce5692664881c4dec168d52e42981c6d72f0a84caa78ebf409cb62584ec539f89147c1",
            Hex.encode(OnionRoutingPacketSerializer(OnionRoutingPacket.PaymentPacketLength).write(onionForEve)),
        )
        // Eve receives the payment.
        run {
            val evePriv = PrivateKey.fromHex("4545454545454545454545454545454545454545454545454545454545454545")
            val blinding = PublicKey.fromHex("02c952268f1501cf108839f4f5d0fbb41a97de778a6ead8caf161c569bd4df1ad7")
            val add = UpdateAddHtlc(randomBytes32(), 1, 150_000_000.msat, paymentHash, CltvExpiry(800_000), onionForEve, blinding, null)
            val payload = IncomingPaymentPacket.decrypt(add, evePriv).right
            assertNotNull(payload)
            assertIs<PaymentOnion.FinalPayload.Blinded>(payload)
            assertEquals(150_000_000.msat, payload.amount)
            assertEquals(CltvExpiry(800_000), payload.expiry)
            assertEquals(pathId, payload.pathId)
        }
    }

    // See bolt04/trampoline-to-blinded-path-payment-onion-test.json
    @Test
    fun `send a trampoline payment to blinded paths -- recipient with trampoline -- reference test vector`() {
        val preimage = ByteVector32.fromValidHex("8bb624f63457695115152f4bf9950bbd14972a5f49d882cb1a68aa064742c057")
        val paymentHash = Crypto.sha256(preimage).byteVector32()
        val alicePayerKey = PrivateKey.fromHex("40086168e170767e1c2587d503fea0eaa66ef21069c5858ec6e532503d6a4bd6")
        // Eve creates a blinded path to herself going through Dave and advertises that she supports MPP and trampoline.
        val (invoice, pathId) = run {
            val evePriv = PrivateKey.fromHex("4545454545454545454545454545454545454545454545454545454545454545")
            val eve = evePriv.publicKey()
            val dave = PublicKey.fromHex("032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991")
            val features = Features(Feature.BasicMultiPartPayment to FeatureSupport.Optional, Feature.TrampolinePayment to FeatureSupport.Optional)
            val offer = OfferTypes.Offer.createNonBlindedOffer(null, "bolt12", eve, features, Block.RegtestGenesisBlock.hash)
            val request = OfferTypes.InvoiceRequest(offer, 150_000_000.msat, 1, features, alicePayerKey, "hello", Block.RegtestGenesisBlock.hash)
            val paymentMetadata = OfferPaymentMetadata.V1(offer.offerId, 150_000_000.msat, preimage, alicePayerKey.publicKey(), "hello", 1, 0)
            val blindedPayloadEve = RouteBlindingEncryptedData(TlvStream(RouteBlindingEncryptedDataTlv.PathId(paymentMetadata.toPathId(evePriv))))
            val blindedPayloadDave = RouteBlindingEncryptedData(
                TlvStream(
                    RouteBlindingEncryptedDataTlv.OutgoingChannelId(ShortChannelId("572330x42x2465")),
                    RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(36), 1000, 500.msat),
                    RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(850_000), 1.msat),
                )
            )
            val sessionKey = PrivateKey.fromHex("090a684b173ac8da6716859095a779208943cf88680c38c249d3e8831e2caf7e")
            val blindedRouteDetails = RouteBlinding.create(sessionKey, listOf(dave, eve), listOf(blindedPayloadDave, blindedPayloadEve).map { it.write().byteVector() })
            val blindedNodes = listOf(
                PublicKey.fromHex("0295d40514096a8be54859e7dfe947b376eaafea8afe5cb4eb2c13ff857ed0b4be"),
                PublicKey.fromHex("020e2dbadcc2005e859819ddebbe88a834ae8a6d2b049233c07335f15cd1dc5f22"),
            )
            assertEquals(blindedNodes, blindedRouteDetails.route.blindedNodeIds)
            val encryptedPayloads = listOf(
                ByteVector("0ae636dc5963bcfe2a4705538b3b6d2c5cd87dce29374d47cb64d16b3a0d95f21b1af81f31f61c01e81a86"),
                ByteVector("bcd747394fbd4d99588da075a623316e15a576df5bc785cccc7cd6ec7b398acce6faf520175f9ec920f2ef261cdb83dc28cc3a0eeb970107b3306489bf771ef5b1213bca811d345285405861d08a655b6c237fa247a8b4491beee20c878a60e9816492026d8feb9dafa84585b253978db6a0aa2945df5ef445c61e801fb82f43d5f00716baf9fc9b3de50bc22950a36bda8fc27bfb1242e5860c7e687438d4133e058770361a19b6c271a2a07788d34dccc27e39b9829b061a4d960eac4a2c2b0f4de506c24f9af3868c0aff6dda27281c"),
            )
            assertEquals(encryptedPayloads, blindedRouteDetails.route.encryptedPayloads)
            val paymentInfo = OfferTypes.PaymentInfo(500.msat, 1000, CltvExpiryDelta(36), 1.msat, 500_000_000.msat, Features.empty)
            val path = Bolt12Invoice.Companion.PaymentBlindedContactInfo(OfferTypes.ContactInfo.BlindedPath(blindedRouteDetails.route), paymentInfo)
            val invoice = Bolt12Invoice(request, preimage, blindedRouteDetails.blindedPrivateKey(evePriv), 600, features, listOf(path))
            Pair(invoice, blindedPayloadEve.pathId)
        }
        // Alice creates a trampoline onion for Eve through Carol.
        val trampolineOnion = run {
            val carol = PublicKey.fromHex("027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007")
            assertEquals(PublicKey.fromHex("020e2dbadcc2005e859819ddebbe88a834ae8a6d2b049233c07335f15cd1dc5f22"), invoice.nodeId)
            val finalPayload = PaymentOnion.FinalPayload.TrampolineBlinded.create(150_000_000.msat, CltvExpiry(800_000))
            assertContentEquals(
                Hex.decode("0b 020408f0d180 04030c3500"),
                finalPayload.write(),
            )
            val trampolinePayload = PaymentOnion.NodeRelayPayload.create(150_000_000.msat, CltvExpiry(800_000), invoice.nodeId)
            assertContentEquals(
                Hex.decode("2e 020408f0d180 04030c3500 0e21020e2dbadcc2005e859819ddebbe88a834ae8a6d2b049233c07335f15cd1dc5f22"),
                trampolinePayload.write(),
            )
            val sessionKey = PrivateKey.fromHex("a64feb81abd58e473df290e9e1c07dc3e56114495cadf33191f44ba5448ebe99")
            val lastAssociatedData = OutgoingPaymentPacket.blindedTrampolineAssociatedData(invoice, alicePayerKey)
            assertEquals(ByteVector32.fromValidHex("d1818c04937ace92037fc46e57bafbfc7ec09521bf49ff1564b10832633c2a93"), lastAssociatedData)
            OutgoingPaymentPacket.buildOnion(sessionKey, listOf(carol, invoice.nodeId), listOf(trampolinePayload, finalPayload), paymentHash, lastAssociatedData).packet
        }
        assertEquals(
            "0002bc59a9abc893d75a8d4f56a6572f9a3507323a8de22abe0496ea8d37da166a8b4bba0e560cd679eb602bfdbcfe9166363bcd44474d21685d34c6596af9cc65b86ca99c0395638d8449c731d273a702d2e38fc1e3d0bf12633277284a3111abe85c02e4419329dfb4696babef292d73b27514fa2d0d0019e4bcea4938a530a9d68726705a1e5d79dff1b84cd76e76268b1f5b0349e3e42777634df197330fde4248dd98f106ae8d4dbdf6fb92178480658d0c5657133ba23a491ccf",
            Hex.encode(OnionRoutingPacketSerializer(trampolineOnion.payload.size()).write(trampolineOnion)),
        )
        // Alice creates a payment onion for Carol (Alice -> Bob -> Carol).
        val onionForBob = run {
            val sessionKey = PrivateKey.fromHex("4f777e8dac16e6dfe333066d9efb014f7a51d11762ff76eca4d3a95ada99ba3e")
            val bob = PublicKey.fromHex("0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c")
            val carol = PublicKey.fromHex("027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007")
            val paymentSecret = ByteVector32("7494b65bc092b48a75465e43e29be807eb2cc535ce8aaba31012b8ff1ceac5da")
            val payloadBob = PaymentOnion.ChannelRelayPayload.create(ShortChannelId("572330x42x2821"), 150_153_000.msat, CltvExpiry(800_060))
            assertContentEquals(
                Hex.decode("15 020408f32728 04030c353c 060808bbaa00002a0b05"),
                payloadBob.write(),
            )
            val payloadCarol = PaymentOnion.FinalPayload.Standard(
                TlvStream(
                    OnionPaymentPayloadTlv.AmountToForward(150_153_000.msat),
                    OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(800_060)),
                    OnionPaymentPayloadTlv.PaymentData(paymentSecret, 150_153_000.msat),
                    OnionPaymentPayloadTlv.RecipientFeatures(Features(Feature.BasicMultiPartPayment to FeatureSupport.Optional, Feature.TrampolinePayment to FeatureSupport.Optional).toByteArray().toByteVector()),
                    OnionPaymentPayloadTlv.OutgoingBlindedPaths(invoice.blindedPaths),
                    OnionPaymentPayloadTlv.TrampolineOnion(trampolineOnion),
                )
            )
            assertContentEquals(
                Hex.decode(
                    "fd029f 020408f32728 04030c353c 08247494b65bc092b48a75465e43e29be807eb2cc535ce8aaba31012b8ff1ceac5da08f32728 14bd0002bc59a9abc893d75a8d4f56a6572f9a3507323a8de22abe0496ea8d37da166a8b4bba0e560cd679eb602bfdbcfe9166363bcd44474d21685d34c6596af9cc65b86ca99c0395638d8449c731d273a702d2e38fc1e3d0bf12633277284a3111abe85c02e4419329dfb4696babef292d73b27514fa2d0d0019e4bcea4938a530a9d68726705a1e5d79dff1b84cd76e76268b1f5b0349e3e42777634df197330fde4248dd98f106ae8d4dbdf6fb92178480658d0c5657133ba23a491ccf 15080200000000020000 16fd01a1032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e66868099102988face71e92c345a068f740191fd8e53be14f0bb957ef730d3c5f76087b960e020295d40514096a8be54859e7dfe947b376eaafea8afe5cb4eb2c13ff857ed0b4be002b0ae636dc5963bcfe2a4705538b3b6d2c5cd87dce29374d47cb64d16b3a0d95f21b1af81f31f61c01e81a86020e2dbadcc2005e859819ddebbe88a834ae8a6d2b049233c07335f15cd1dc5f2200d1bcd747394fbd4d99588da075a623316e15a576df5bc785cccc7cd6ec7b398acce6faf520175f9ec920f2ef261cdb83dc28cc3a0eeb970107b3306489bf771ef5b1213bca811d345285405861d08a655b6c237fa247a8b4491beee20c878a60e9816492026d8feb9dafa84585b253978db6a0aa2945df5ef445c61e801fb82f43d5f00716baf9fc9b3de50bc22950a36bda8fc27bfb1242e5860c7e687438d4133e058770361a19b6c271a2a07788d34dccc27e39b9829b061a4d960eac4a2c2b0f4de506c24f9af3868c0aff6dda27281c000001f4000003e800240000000000000001000000001dcd65000000"
                ),
                payloadCarol.write(),
            )
            OutgoingPaymentPacket.buildOnion(sessionKey, listOf(bob, carol), listOf(payloadBob, payloadCarol), paymentHash, null, OnionRoutingPacket.PaymentPacketLength).packet
        }
        assertEquals(
            "00025fd60556c134ae97e4baedba220a644037754ee67c54fd05e93bf40c17cbb73362fb9dee96001ff229945595b6edb59437a6bc14340697104703259d5a316dba87ca0629895a1a066fe371e733fe70dc2fb209f0739d87c55657bcf20af7e63d7192b46cf171e4f73cb11f9f603915389105d91ad630224bea95d735e3988add1e24b5bf28f1d7128db642c4db283fc0b6b2b1b0d88245a466c812e2d513df89f886442115c7951736f098e724438f61031659aba9959587373a70fc71f529af69dde5f97081b8a852601816bea568f0b21ff9fbbbec0076d988f19b26a4190572b456df2b4721fbf0ed158fb8b4a743ea09353c91110af3f417ec18b802efa7d6311cb2afdab155954b08da1c438e91879abb374c565d619849f9bd7f4c708825aacfcb6c8485d8fdf36a9a1e6b3cdc83d3a5eeb0a99f250bf24b2647acbef783ac0c41a9ba50ae95a61ead8581d3acf49c621de905237155488409b7af38c1607e7abe3185c51a1672a77e71b6c1aee108895926702e23fb2703424df95dad80b71ba5bdf00e4d8e746653022e615dd520074a1a1c53af699faa757ee34492b8eebe55046085badcd798941b51154fc55ff6f88031b8946a9777271eb13dc86275669182020e8762056e335547007f2ed89b134acab8c646370cd993c109319befb55aae04ab11b0fec9a3313556e17433e77afa92245baa880deb0344777d5e294c98ec1a1399c08705124bd63289c423b8778bb61ef244d6c7c4e04e092ab1faa2b716d9da18a297743c045178a6eca50d3f7a96c6ca7ee37859110d03e758f1d314af72aa30980a820f864aee2c0822d31694fe266c45286bae5a5aa062bd7d1f4868ced886110212b31a99114cac278808eff8fb0d0392f2e04e032b3cc367630205ed0c3b4bad4f4bbc609074b50f5c4b278395100785b1ea6c18acf8fc9e496a1658348b13bd747ef600b09d89e43ddf1a76673d6336781ad37e2d74bfc9558e4febf023e0b49fe6ff5be6d722bfbefb616c24caa128cc858499092fbe8f6070e2107dde4c99ca82d5251e658aff18f7e852aafc757b53dd5afc351ddd239d9b23168cb8077a2486983a3d1a8b2a5cb88171e9a6aec0969becf5e29a878002d2ab84492850264b2f8a329be8cbec93f6436ae1f55bcfc25ac4d801d4baefe64a80167c1465a464ca58105e56f7022f9a5e6f077d1c7146cc1006286e059f1f1ca48fdf24b047e1722f143f24a46be8d8da5f27832655a1216efd1c8d9d742fb6e7841bcc2d1f115ecc8da3d3130f19696b87295bfb9743ffff153f353330b6d60db9144cb86cec0db9a2873bbf89574ee38306b46dddd535c7cc00eb8edb7145e878e2616f1a90c3b4751123963654cfbe0ac3140466ab9a1f653873fd8ef243434f82a47246c14d968b563cf30a000ece8a911ab42bb5c343ad324a6015e769febb5426378c9e09e859773b1afe0a43a7ded0380bf8a50dea41b7e894856d7a66ddecb15414b634c351b30bb5da2b42ca4da6782e32b8754ff0ff9147e78bfc4486c6485880b46d1690f32784315ee01269028ecca0d0829ebe25b14bc473ca5b597aeb2820ef978143eec68393d64aa5fe466f612958d97a30b15a6b5b73402e620991748a2e792ae4e860be4dd283b3d205c8b2dad8d2c8c4fb8d47e0610e984b8245d65f570a6351d803bbce991c655ece0816c1c049eea97074e825d4b0990bc4b88f67df2707d9f23be9e3dd557316147d3579b6aae3cec6f6761fbd98855d31b4dbc71665c9a716ea0e56e9f13d5a0bc87b9db98460197f924dda77e9e4a0f845811b004a18f3cce39d8977155474a6ff6601c71e12f7da767fb4cdf8419bf8c9808d83c0b317bf03cea0434537a22178c1ddc4f00ab458d24bc16b4166544c4b4c66f4fbef3915300d4b0da5",
            Hex.encode(OnionRoutingPacketSerializer(OnionRoutingPacket.PaymentPacketLength).write(onionForBob)),
        )
        // Bob decrypts the onion and relays to Carol.
        val onionForCarol = run {
            val bobPriv = PrivateKey.fromHex("4242424242424242424242424242424242424242424242424242424242424242")
            val add = UpdateAddHtlc(randomBytes32(), 1, 150_155_000.msat, paymentHash, CltvExpiry(800_100), onionForBob)
            val (payload, onion) = decryptChannelRelay(add, bobPriv)
            assertEquals(ShortChannelId("572330x42x2821"), payload.outgoingChannelId)
            assertEquals(150_153_000.msat, payload.amountToForward)
            assertEquals(CltvExpiry(800_060), payload.outgoingCltv)
            onion
        }
        assertEquals(
            "0003dc6a4c9b34bdcd2191fc4dacfc1aeb20f71991acbd17847b9ab17d69579c1614da12bcd18820e55534d7352839caa436aa79a6d5be26c6ecbd1c79f74442b1b7bf8ed8b739b736b9248769c2c422eebc85fb0d580ed5639371faabcdb5f7b924579641c6ea0e47d5f56c2c90330db5ef61a4310f5ab6024fd6d4d8812bea66ba5c7203ca05de3f777b473b307d21ef5b62a32621b198d22df8ba9962aa5bf087f47af172286d2734911d0c0d677734c7c8279d01dd44c5e0bcfa7dfcdc49babe10a535760278722e099fff86101aa8d48f0a8eae8a042b6914399f3b77b002f1cad3d30966381e1041e0b30e9944f8e346abf67786e1545e941cd711af2165c9e0efb0d817eb5ea6437cb2651b476284ab59032c75c6884291523d2b4533420d959473feb29ec8a08d725df1945633e1df0f8db3fc04b0a275d94cc4d1b70b2aa06a59884f77b8411e4aee0b3ca0014a6485ef6979dcf9edd11ebe8861331f5e295bfb6e966c2b187297a6f3bfcb95a65a533291999d27e0975582300b44f32cad21a636886222624f217277c2f01b72a8ac52396d17cf5db610727ed4b65bdef7be77cd3219f07fa6cee897179189422902c8bf30d733a4663cb99a57d707fa52abfa79cb1ec461ae47ba317227a61efac1f3c5e44d74107fa9b2653e8aab3b81a3682ac795fa9fa01f44c738b849c91408544e72e61635cca84cc625745c560be44abcf27f0222ad8763de79e62798dd3caa90fb44392ce2b7bd51e88f3252f7f64d9b0b2b6f1313b60fcb14e4f065278fb1b1447918ebdf4c81d0a83b3cfcb347312b794ad51f964351818e54cd74892be7c102d1875b47753d00b5babf401452fee145a4e1217dacdfb25d828ddd89c90da4c265c4787b9f16220098ffbc48eb4e170cd81426c2301ac77ab2a578bd878513c6a12fd60fbc81a74daba0641b73bc879ce51833b00c43c2e4ef77497eb682920b7600bd3afb9228ae817d6ad4917fbb15e9103022a87e6a0cf8b1112e974e6c51caf8942774beeeb429392a01a063aa642d1e7584db5858b8e23cb40c162bab789488dd193c612a639c0ff6ebf1c4ccd136f2de80f6252ebb2c7d2606c9211541719a3cdc842f04ae7d1dab4cc3e2f4532137a8f3dd6d71d3163d768324d562793f22e616b50ae1fe42c0f3a5a7019a4d7fd638eaa660ae1da75de2b51fd8b7341a11c2ea3b0102a3570db4ef26c66cb47a9dd7bf251ee3d25a48457bd5c47225e78a93d43b034d39d8bdd814fa6c3a3adb74fa377a8f47301d5c5edb9f05592c3752367b9dd836cc8a89cd15acabfd6bb5e0723d03e5dd3eed4e1be5e429c2bcce9d3b1b91e6c25acda387742ea2a9009441008161ff7e6ba417eee5f9d8339f99ededf1f3e09ba585059fde962c4d0924c2a924c350f2614ae781347db5b5d8ca482269d628dce3baa2188219b23d8f66b3b59ad3fd006522dfb4db72644b8c523e985c912372276cb7623e0760a8e562ab5a8e4e1d6e98b8fcdbde4a5b85674f4324402145cb2d4ced65ad627990dac0b2af1bd9ea9479cd8f03b115482de11b48d34d79a88209322b9f79ffd7edf2cecee0e54caed9a683cbba2f043520c910060ef1778644f361e2daa3839d74640bd576c5bcfd108b007a1fe19d7d6d995437b284395cb589aafd4e8cf427dc56a70b9de809bd103cb12dbe7031dacd7d71818b2a05021b2903478c38d8d455a59d4cb111826c4ad9af94ff8654dd16cf32e6371186284597cecad4f2a2de93df2722ccd2f9de4996eca4c1b53bd3467567fcaccf4c57dd08ad364b48cd93b6a26cb6888d5c4cc1d3c6a39cb442c2de12bb1ad396205c9a10023d7edc951fc60a28813867f635c3d74d4206f398849ed09c43ef074c37862a7c891dd0f86a40c25250b98b46e63019eb5e4040891d0a",
            Hex.encode(OnionRoutingPacketSerializer(OnionRoutingPacket.PaymentPacketLength).write(onionForCarol)),
        )
        // Carol decrypts the onion and relays to the blinded path's introduction node Dave.
        val onionForDave = run {
            val carolPriv = PrivateKey.fromHex("4343434343434343434343434343434343434343434343434343434343434343")
            val add = UpdateAddHtlc(randomBytes32(), 1, 150_153_000.msat, paymentHash, CltvExpiry(800_060), onionForCarol)
            val (payload, trampolinePayload, trampolineOnionForEve) = decryptRelayToBlindedTrampoline(add, carolPriv)
            assertEquals(150_153_000.msat, payload.amount)
            assertEquals(CltvExpiry(800_060), payload.expiry)
            assertEquals(ByteVector32("7494b65bc092b48a75465e43e29be807eb2cc535ce8aaba31012b8ff1ceac5da"), payload.paymentSecret)
            assertEquals(Features(Feature.BasicMultiPartPayment to FeatureSupport.Optional, Feature.TrampolinePayment to FeatureSupport.Optional).toByteArray().toByteVector(), payload.recipientFeatures)
            assertEquals(1, payload.outgoingBlindedPaths.size)
            assertEquals(150_000_000.msat, trampolinePayload.amountToForward)
            assertEquals(CltvExpiry(800_000), trampolinePayload.outgoingCltv)
            val outgoingPath = payload.outgoingBlindedPaths.first()
            assertEquals(outgoingPath.route.nodeId, trampolinePayload.outgoingNodeId)
            assertEquals(outgoingPath.paymentInfo, OfferTypes.PaymentInfo(500.msat, 1000, CltvExpiryDelta(36), 1.msat, 500_000_000.msat, Features.empty))
            val sessionKey = PrivateKey.fromHex("e4acea94d5ddce1a557229bc39f8953ec1398171f9c2c6bb97d20152933be4c4")
            val payloadDave = PaymentOnion.BlindedChannelRelayPayload.create(outgoingPath.route.route.encryptedPayloads.first(), outgoingPath.route.route.blindingKey)
            assertContentEquals(
                Hex.decode("50 0a2b0ae636dc5963bcfe2a4705538b3b6d2c5cd87dce29374d47cb64d16b3a0d95f21b1af81f31f61c01e81a86 0c2102988face71e92c345a068f740191fd8e53be14f0bb957ef730d3c5f76087b960e"),
                payloadDave.write(),
            )
            val payloadEve = PaymentOnion.FinalPayload.Blinded(
                TlvStream(
                    OnionPaymentPayloadTlv.AmountToForward(trampolinePayload.amountToForward),
                    OnionPaymentPayloadTlv.OutgoingCltv(trampolinePayload.outgoingCltv),
                    OnionPaymentPayloadTlv.TotalAmount(trampolinePayload.amountToForward),
                    OnionPaymentPayloadTlv.EncryptedRecipientData(outgoingPath.route.route.encryptedPayloads.last()),
                    OnionPaymentPayloadTlv.TrampolineOnion(trampolineOnionForEve),
                ),
                // ignored
                RouteBlindingEncryptedData(TlvStream(RouteBlindingEncryptedDataTlv.PathId(ByteVector("deadbeef"))))
            )
            assertContentEquals(
                Hex.decode("fd01a3 020408f0d180 04030c3500 0ad1bcd747394fbd4d99588da075a623316e15a576df5bc785cccc7cd6ec7b398acce6faf520175f9ec920f2ef261cdb83dc28cc3a0eeb970107b3306489bf771ef5b1213bca811d345285405861d08a655b6c237fa247a8b4491beee20c878a60e9816492026d8feb9dafa84585b253978db6a0aa2945df5ef445c61e801fb82f43d5f00716baf9fc9b3de50bc22950a36bda8fc27bfb1242e5860c7e687438d4133e058770361a19b6c271a2a07788d34dccc27e39b9829b061a4d960eac4a2c2b0f4de506c24f9af3868c0aff6dda27281c 120408f0d180 14bd0003868a4ec9883bc8332dcd63bafc80086fe046bf2795c070445381f98f5b4678ef5cb90f7e701aeb6d1d4f4e7e47075761d2ffe3842571c016213b65eae34facd629d74dfbd5456bfcf57abd0f30a44d9c2e8302221797a40b288426d157882014c83a5c7f84abe48a4418e92dd71cfd9fe25026b6776e850964b59a9d07cab4d09d36a6573ce93cb311d1f184afb4c36becf3418baec0b908170df6a387ca1247772b31dcadb5a948ce7ca9fff934e52bda0f9aa4a1d6e17f2d4e8a"),
                payloadEve.write(),
            )
            val nodes = listOf((outgoingPath.route.route.introductionNodeId as EncodedNodeId.WithPublicKey).publicKey, outgoingPath.route.route.blindedNodeIds.last())
            OutgoingPaymentPacket.buildOnion(sessionKey, nodes, listOf(payloadDave, payloadEve), paymentHash, payloadLength = OnionRoutingPacket.PaymentPacketLength).packet
        }
        assertEquals(
            "0003626d5fa7ed0b8706f975ca58ae2c645889514153568aaab7835325ecd3739a5760171be581c1df1ce5267cd012e06f45a97fdbc5d8fc0c140c7432d34027b9ed0f2ab09fd388fe47525309f9c4509a7b9d74d88c28e40b1b6598e42fe2975f857e28b224316f266decf170cbfb5019dea2168b0595057996a9d18498c5cc5c90fe1f9959535d181a872fb4707b85c2a7d3ade760e3295402508b3d0f5576ae0ed4a5d35dcab5711241c6cd5410b6dde7bad6514fadd6fadde680e8ddb0f34aa7956c4e13e9a3c266b2a240e3a6875dea300ccf7d6cf5e5ef9f31df7c7cc03ee8582d906a38e0390c68960533f45b4b9e19982d35c6bcfb9b71b6b446c28947b98581ff41a2814b695c8bb621d2a9e9816b1ceb5e62eddb469aafea1d67c16d7bd6e1a4e6c9c546cddf5d6071c408947f2f9d6802d69df89044eb7e21806ce884d9bc6b0c9eaf676948ead1132e0f227fcefeff365129ba4cdc1aa3bf82f107f078a883123f1a48d623c991f8ea91ed4ac50956f0373ac77f738e82b22d67810c60ed3ed451445e79ba4f099cc3fdbb1fd5941fd37b3193083d60f6c813bdd0fca48ef10f9ff1bf2739ae65123b5e548ae41b6ee60db118391d8052d3c7c160ec8486ece86a478f479d54f61fe0e456206dace4238fdacb02f12028ba9376c4492c63eb03957abc35a0b1a71c683da97321d1528e735387facfabf23c943de0ae460482dc6383c615dd2095d359c0bcd5b3b9cdd271f352094e5e267823d021302b1cde0827f53b661bc42a00e229f225cfe3fda395d664233d06206793fcb89ed1b163a6ecfda0869ca432ea0d5a251198f780c55635bedb34381b0c0f3dbb14ec98e6956d6e5f98eb98d348fe11aab7fb8b1f6864eba37ed80ec93ec1fb18488c7944e9e00f8badd40b5aa2191a972088b9da090e4f67963288dacdf8f0f6ac78aca9946b1b9bd7c160188293ea61647d8d77297f0aab7a62aa865677254978ac86f91f704051d01dbb9eb5e0fe923661263d4c4b758636e2379f87a0fe379964fa3c8d09ce3625a03c5560c48fd1a4fd82377c8dc425c18c009a69d7da3535e495a0bfcef6a2684e2185002098ab0051a1855251c506c6d4c97d6865b4f2fe7a311c19ff973caa1877e706d415652a0138a908cdc091692debb23c1be1cd1459defeb18c3ad50e27e0bffe30960fe7cbbb23a03780e34fa2ef3966d8e47b0e92da1fdbda2371e0cb68f8041d19e6e3db2a5de3b25da7820be06b3ba448cea92c8fd61172b0be71a14431bb54602a044a07204c25486c4bb2f83392074cadc0e9def52d62368f431961a49e359c9ac699ccf049c7efb2aeb7f270d6b0401c1dee8661ddafdacaa12e3fdb69df4fcfd12140e52306b9a44a0369f4c6de7de1c83225083ef8927d3f679ea2ebba854476dcb602853edb1274503796982a3eeab3e21684d3ac332fb6ace0358710a88071bda0e2c15d91bc25bdfaf92e3b16ff7f13aec55bd6cec74aa4c1eedde126d173dad534ab8fa968475a161aff8531f4ce31d91ce16195ddd7a4f03209ee57d3d101c3fb7fb89d60c5fdce00e9f4d7fc978a60cbab5ce1f443a447a28c2e55f59606f9c203e51b2ecd860e57b9f6f37aa1ff4ed090deb8220383820da2e9970ce612f9ec2745a9a9f582515aea9d6db2fd951d7464692d0f8ed2dd7a48eeb985cdac22413bd8cf95d74d22672431891fb084776e731422f6b9e9efbd5664fd96086f3598decfa81c134fa3db2cac3b59f1cdf1837af9b6948338558f2788723907365c0a178f6cc701d26ae063093aab1e93614f74051022ef2b38bd115600cc80fcf68597b809f852fe0644d1b3b0a0f93ac31ddaacdbe5c9da3e0d4cc0846831f2fbf2fefb4c1b89d22bfec201fa372816441a869d016d1875e4cf36cf53aa84ae45dcf5",
            Hex.encode(OnionRoutingPacketSerializer(OnionRoutingPacket.PaymentPacketLength).write(onionForDave)),
        )
        // Dave decrypts the onion and relays to Eve.
        val onionForEve = run {
            val davePriv = PrivateKey.fromHex("4444444444444444444444444444444444444444444444444444444444444444")
            val decrypted = Sphinx.peel(davePriv, paymentHash, onionForDave).right!!
            assertFalse(decrypted.isLastPacket)
            val decoded = PaymentOnion.BlindedChannelRelayPayload.read(decrypted.payload).right!!
            assertNotNull(decoded.records.get<OnionPaymentPayloadTlv.EncryptedRecipientData>())
            decrypted.nextPacket
        }
        assertEquals(
            "0002580900a2090fc8b09d77f87781aa5a5964372ee968bd8488da62e04e3f1d68bdbcd72bb96179e407550775d490a12a5dbfd671b7962c03c8e67052b18e976cda8f59be88d9cb6693d78184d306062b7af0658db3554da3fbd4658a766052fbc397613bbf24002f5af6376ad25b8a32c559d1a6669cdaa1b6188d979dcb308b48858a123d6aac6ff48128f8ea0fa17deccf32c09c3064b5ca39ed3728589d37a3d62899fd0a76224913c0c0459a03206a3e1047f465a5d63fe83baaf545d23e4c0bd61b01e31a89506b4c72710450285edf35f967113734d416e77eed330d7bc98c3dd2446451429f1daa5f3016b70a0ba1e0e7055d06be1d6cea63ed4793c33f6391e9ffe1a3762c9a568b17d0a709f51ca79b873d541f6ce9cbbdf25cc6cf7e46ea9a389c365e499ea1ccae445e5ead69c9c0d6924da70ab4eea729bdc6f70952f4757eaa007400a99736759767cc56729e2fe19dc7440d50915ee45ffa209d22824a364ed7dce135b220c2f9b2510e4c63eda405ec415a57263c4f627fbc0544d97fd7330de6a1457dfa5027c4317676cb09b852a608be849edce232a6ca6695449d083822bdb3cda02ce6be73af575940f0f645e167f90e1fd2af64bfc75f6bff62af02ad6979c157ed221b3ada819d8b7bc33eb59e2bf3c249c049e59d94d41f057e9bc75c1f565f6256b61cd00d2fa07d6b8b8995581db05dd651a2e2a054ef97e45efca2767630759cff1db6628ad30b920a868a8c10e334f3aff5edf96f74caeae8f42f04cbfcfc986567cf80784dbf5b9106be3b00a0b343feaf5e3442a8f010c485f48cd000d1980ab8d008f89af84b594453c8b2bb474edd88de13b18bdb065e771a262d0be8d5c9dceccd9319a6d7120f6fe58e4ab906339376af1b7287b069c45355ffd64a02d440b99b313c7509aa034a3228c1954145d7434b4eaa47a826fb13843117d4e74be9a180bc06227765423e1c6905ff2aadbe744ee28269b63be5fdceae349df700d077972f0eba6d4cf5cb38c345b75e3168756ed0a0a17242783b44895b5ae9b8a38f04d7638a30d1dc12f3f35d994c75648b4d041e962cd8bb474cb875c2ae35c9c097cf2598e5a0ad14030c333059467ca65ceb1f2cf006a4a17cb4d57ac3688970aac5199b681bd1a0e1673f5a40817c9fb67d902c3d4f1eda9188020697881fa27fb074e952b4ab62c392a12db376c2bc9492c4f0e5e70b79079803ffa9553559df91b325556eedf4d5665796d465aff21ea7266c14848744376ffbdd4e7266b2e7ca9863327cfb98cd6d9bcabaaf99a0f6604c8e892bd4a9b7595e320637e622a87437e3c68db6fced7560ff6bf0b4d75e864ed1e3337ceb31b4ef6734b34dd3caeefd237ec9ed58253927d424b7a7fa9663b73daf0e21f05a335ac72e057f6d91f7963a606faeea62f23f3dca5c46885e6fc3bf20696ff4ff9db1171485679f7adaed5c9992f2cf260a8c14cd2f57c277078a350f581f953bcc3fe1adbc24f16e017cff89b606cfb603762f29f5d35b53159b8c046c97a788ae11eaee14c3cc42307ad339d917030c4cf6c55878f85d27fe022560d085a69b42cf6dcd5efca5edf456e840dee92161b28ec44e5cfff0869cfa4129fd6941cd5b6d903121b9ede456d87e4c1394207e70635df05c0f3c4673e46475ee5d8dc510ed54679eba1133ea0ca5d1ea5df6feb1e6e70f8247b53ce2ac29714516fdaee00b697288fdfea823a30bf11fa6cf3ae2215eb42b98aae1e80444c6f2688a5f8f80f1236fb3d12584f33bdfc33beb8c5b7bfdfeb94e25ed4c1fdf69f4a28f6cbb7fa0fb9424927e195908d0a8894555d02f285962a53a984fca3f6b3fb843e4d559e5294c2e01dd1dce5692aa716a2ec60877a8b2d99794c85add50b62670b468d0a9283138f16d7aea4091",
            Hex.encode(OnionRoutingPacketSerializer(OnionRoutingPacket.PaymentPacketLength).write(onionForEve)),
        )
        // Eve receives the payment.
        run {
            val evePriv = PrivateKey.fromHex("4545454545454545454545454545454545454545454545454545454545454545")
            val blinding = PublicKey.fromHex("02c952268f1501cf108839f4f5d0fbb41a97de778a6ead8caf161c569bd4df1ad7")
            val add = UpdateAddHtlc(randomBytes32(), 1, 150_000_000.msat, paymentHash, CltvExpiry(800_000), onionForEve, blinding, null)
            val payload = IncomingPaymentPacket.decrypt(add, evePriv).right
            assertNotNull(payload)
            assertIs<PaymentOnion.FinalPayload.Blinded>(payload)
            assertEquals(150_000_000.msat, payload.amount)
            assertEquals(CltvExpiry(800_000), payload.expiry)
            assertEquals(pathId, payload.pathId)
        }
    }

    @Test
    fun `receive a channel payment`() {
        // c -> d
        val finalPayload = PaymentOnion.FinalPayload.Standard.createMultiPartPayload(finalAmount, finalAmount * 1.5, finalExpiry, paymentSecret, paymentMetadata)
        val (firstAmount, firstExpiry, onion) = encryptChannelRelay(paymentHash, listOf(ChannelHop(c, d, channelUpdateCD)), finalPayload)
        val addD = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val payloadD = IncomingPaymentPacket.decrypt(addD, privD).right!!
        assertEquals(finalPayload, payloadD)
    }

    @Test
    fun `receive a blinded channel payment`() {
        // c -> d
        val (addD, paymentMetadata) = createBlindedHtlcCD()
        val payloadD = IncomingPaymentPacket.decrypt(addD, privD).right!!
        assertIs<PaymentOnion.FinalPayload.Blinded>(payloadD)
        assertEquals(finalAmount, payloadD.amount)
        assertEquals(finalAmount, payloadD.totalAmount)
        assertEquals(finalExpiry, payloadD.expiry)
        assertEquals(paymentMetadata, OfferPaymentMetadata.fromPathId(d, payloadD.pathId))
    }

    @Test
    fun `fail to decrypt when the payment onion is invalid`() {
        val (firstAmount, firstExpiry, onion) = encryptChannelRelay(
            paymentHash,
            listOf(ChannelHop(c, d, channelUpdateCD)),
            PaymentOnion.FinalPayload.Standard.createSinglePartPayload(finalAmount, finalExpiry, paymentSecret, null)
        )
        val addD = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet.copy(payload = onion.packet.payload.reversed()))
        val failure = IncomingPaymentPacket.decrypt(addD, privD)
        assertTrue(failure.isLeft)
        assertEquals(InvalidOnionHmac.code, failure.left!!.code)
    }

    @Test
    fun `fail to decrypt when the trampoline onion is invalid`() {
        val invoice = Bolt11Invoice.create(Chain.Regtest, finalAmount, paymentHash, privD, Either.Left("test"), CltvExpiryDelta(6), trampolineFeatures, paymentSecret, paymentMetadata)
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacketToTrampolineRecipient(invoice, finalAmount, finalExpiry, nodeHop_cd)
        val addC = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val (_, innerC, packetD) = decryptRelayToTrampoline(addC, privC)
        // C modifies the trampoline onion before forwarding the trampoline payment to D.
        val (amountD, expiryD, onionD) = run {
            val payloadD = PaymentOnion.FinalPayload.Standard.createTrampolinePayload(innerC.amountToForward, innerC.amountToForward, innerC.outgoingCltv, randomBytes32(), packetD.copy(payload = packetD.payload.reversed()))
            encryptChannelRelay(paymentHash, listOf(ChannelHop(c, d, channelUpdateCD)), payloadD)
        }
        val addD = UpdateAddHtlc(randomBytes32(), 2, amountD, paymentHash, expiryD, onionD.packet)
        val failure = IncomingPaymentPacket.decrypt(addD, privD)
        assertTrue(failure.isLeft)
        assertEquals(InvalidOnionHmac.code, failure.left!!.code)
    }

    @Test
    fun `fail to decrypt when payment hash doesn't match associated data`() {
        val (firstAmount, firstExpiry, onion) = encryptChannelRelay(
            paymentHash.reversed(),
            listOf(ChannelHop(c, d, channelUpdateCD)),
            PaymentOnion.FinalPayload.Standard.createSinglePartPayload(finalAmount, finalExpiry, paymentSecret, paymentMetadata)
        )
        val addD = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val failure = IncomingPaymentPacket.decrypt(addD, privD)
        assertTrue(failure.isLeft)
        assertEquals(InvalidOnionHmac.code, failure.left!!.code)
    }

    @Test
    fun `fail to decrypt when blinded route data is invalid`() {
        val paymentMetadata = OfferPaymentMetadata.V1(randomBytes32(), finalAmount, paymentPreimage, randomKey().publicKey(), "hello", 1, currentTimestampMillis())
        val blindedPayload = RouteBlindingEncryptedData(TlvStream(RouteBlindingEncryptedDataTlv.PathId(paymentMetadata.toPathId(privD))))
        val blindedRoute = RouteBlinding.create(randomKey(), listOf(d), listOf(blindedPayload.write().byteVector())).route
        val payloadD = PaymentOnion.FinalPayload.Blinded(
            TlvStream(
                OnionPaymentPayloadTlv.AmountToForward(finalAmount),
                OnionPaymentPayloadTlv.TotalAmount(finalAmount),
                OnionPaymentPayloadTlv.OutgoingCltv(finalExpiry),
                // The encrypted data is invalid.
                OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads.last().reversed()),
            ),
            blindedPayload
        )
        val onionD = OutgoingPaymentPacket.buildOnion(listOf(blindedRoute.blindedNodeIds.last()), listOf(payloadD), paymentHash, OnionRoutingPacket.PaymentPacketLength)
        val addD = UpdateAddHtlc(randomBytes32(), 1, finalAmount, paymentHash, finalExpiry, onionD.packet, blindedRoute.blindingKey, null)
        val failure = IncomingPaymentPacket.decrypt(addD, privD)
        assertTrue(failure.isLeft)
        assertEquals(failure.left, InvalidOnionBlinding(hash(addD.onionRoutingPacket)))
    }

    @Test
    fun `fail to decrypt when amount has been modified by trampoline node`() {
        val invoice = Bolt11Invoice.create(Chain.Regtest, finalAmount, paymentHash, privD, Either.Left("test"), CltvExpiryDelta(6), trampolineFeatures, paymentSecret, paymentMetadata)
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacketToTrampolineRecipient(invoice, finalAmount, finalExpiry, nodeHop_cd)
        val addC = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val (_, innerC, packetD) = decryptRelayToTrampoline(addC, privC)
        val (amountD, expiryD, onionD) = run {
            val payloadD = PaymentOnion.FinalPayload.Standard.createTrampolinePayload(innerC.amountToForward, innerC.amountToForward, innerC.outgoingCltv, randomBytes32(), packetD)
            encryptChannelRelay(paymentHash, listOf(ChannelHop(c, d, channelUpdateCD)), payloadD)
        }
        val addD = UpdateAddHtlc(randomBytes32(), 2, amountD - 100.msat, paymentHash, expiryD, onionD.packet)
        val failure = IncomingPaymentPacket.decrypt(addD, privD)
        assertEquals(Either.Left(FinalIncorrectHtlcAmount(finalAmount - 100.msat)), failure)
    }

    @Test
    fun `fail to decrypt when expiry has been modified by trampoline node`() {
        val invoice = Bolt11Invoice.create(Chain.Regtest, finalAmount, paymentHash, privD, Either.Left("test"), CltvExpiryDelta(6), trampolineFeatures, paymentSecret, paymentMetadata)
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacketToTrampolineRecipient(invoice, finalAmount, finalExpiry, nodeHop_cd)
        val addC = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val (_, innerC, packetD) = decryptRelayToTrampoline(addC, privC)
        val (amountD, expiryD, onionD) = run {
            val payloadD = PaymentOnion.FinalPayload.Standard.createTrampolinePayload(innerC.amountToForward, innerC.amountToForward, innerC.outgoingCltv, randomBytes32(), packetD)
            encryptChannelRelay(paymentHash, listOf(ChannelHop(c, d, channelUpdateCD)), payloadD)
        }
        val addD = UpdateAddHtlc(randomBytes32(), 2, amountD, paymentHash, expiryD - CltvExpiryDelta(12), onionD.packet)
        val failure = IncomingPaymentPacket.decrypt(addD, privD)
        assertEquals(Either.Left(FinalIncorrectCltvExpiry(finalExpiry - CltvExpiryDelta(12))), failure)
    }

    @Test
    fun `fail to decrypt blinded payment when amount is too low`() {
        val (addD, _) = createBlindedHtlcCD()
        // D receives a smaller amount than expected and rejects the payment.
        val failure = IncomingPaymentPacket.decrypt(addD.copy(amountMsat = addD.amountMsat - 1.msat), privD).left
        assertEquals(InvalidOnionBlinding(hash(addD.onionRoutingPacket)), failure)
    }

    @Test
    fun `fail to decrypt blinded payment when expiry is too low`() {
        val (addD, _) = createBlindedHtlcCD()
        // E receives a smaller expiry than expected and rejects the payment.
        val failure = IncomingPaymentPacket.decrypt(addD.copy(cltvExpiry = addD.cltvExpiry - CltvExpiryDelta(1)), privD).left
        assertEquals(InvalidOnionBlinding(hash(addD.onionRoutingPacket)), failure)
    }

    @Test
    fun `fail to decrypt blinded payment when payer_key doesn't match`() {
        val features = Features(Feature.BasicMultiPartPayment to FeatureSupport.Optional, Feature.TrampolinePayment to FeatureSupport.Optional)
        val offer = OfferTypes.Offer.createNonBlindedOffer(finalAmount, "test offer", d, features, Block.RegtestGenesisBlock.hash)
        // D uses a 1-hop blinded path from its trampoline node C.
        val (invoice, blindedRoute) = run {
            val payerKey = randomKey()
            val request = OfferTypes.InvoiceRequest(offer, finalAmount, 1, features, payerKey, "hello", Block.RegtestGenesisBlock.hash)
            val paymentMetadata = OfferPaymentMetadata.V1(offer.offerId, finalAmount, paymentPreimage, payerKey.publicKey(), "hello", 1, currentTimestampMillis())
            val blindedPayloadC = RouteBlindingEncryptedData(
                TlvStream(
                    RouteBlindingEncryptedDataTlv.OutgoingChannelId(channelUpdateCD.shortChannelId),
                    RouteBlindingEncryptedDataTlv.PaymentRelay(channelUpdateCD.cltvExpiryDelta, channelUpdateCD.feeProportionalMillionths, channelUpdateCD.feeBaseMsat),
                    RouteBlindingEncryptedDataTlv.PaymentConstraints(finalExpiry, 1.msat),
                )
            )
            val blindedPayloadD = RouteBlindingEncryptedData(
                TlvStream(
                    RouteBlindingEncryptedDataTlv.PathId(paymentMetadata.toPathId(privD))
                )
            )
            val blindedRouteDetails = RouteBlinding.create(randomKey(), listOf(c, d), listOf(blindedPayloadC, blindedPayloadD).map { it.write().byteVector() })
            val paymentInfo = createBlindedPaymentInfo(channelUpdateCD)
            val path = Bolt12Invoice.Companion.PaymentBlindedContactInfo(OfferTypes.ContactInfo.BlindedPath(blindedRouteDetails.route), paymentInfo)
            val invoice = Bolt12Invoice(request, paymentPreimage, blindedRouteDetails.blindedPrivateKey(privD), 600, features, listOf(path))
            Pair(invoice, blindedRouteDetails.route)
        }

        // B pays that invoice using its trampoline node C to relay to the invoice's blinded path.
        // B uses a random payer_key instead of reusing the invoice_request's payer_key.
        val hop = nodeHop_cd.copy(nextNodeId = invoice.nodeId)
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacketToBlindedRecipient(invoice, randomKey(), finalAmount, finalExpiry, hop)
        // C decrypts the onion that contains a blinded path in the trampoline onion.
        val addC = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val (outerC, innerC, nextTrampolineOnion) = decryptRelayToBlindedTrampoline(addC, privC)
        assertEquals(listOf(blindedRoute), outerC.outgoingBlindedPaths.map { it.route.route })
        assertEquals(invoice.features.toByteArray().toByteVector(), outerC.recipientFeatures)
        // C is the introduction node of the blinded path: it can decrypt the first blinded payload and relay to D.
        val addD = run {
            val (dataC, blindingD) = RouteBlinding.decryptPayload(privC, blindedRoute.blindingKey, blindedRoute.encryptedPayloads.first()).right!!
            val payloadC = RouteBlindingEncryptedData.read(dataC.toByteArray()).right!!
            assertEquals(channelUpdateCD.shortChannelId, payloadC.outgoingChannelId)
            // C would normally create this payload based on the payment_relay field it received.
            val payloadD = PaymentOnion.FinalPayload.Blinded(
                TlvStream(
                    OnionPaymentPayloadTlv.AmountToForward(innerC.amountToForward),
                    OnionPaymentPayloadTlv.TotalAmount(innerC.amountToForward),
                    OnionPaymentPayloadTlv.OutgoingCltv(innerC.outgoingCltv),
                    OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads.last()),
                    OnionPaymentPayloadTlv.TrampolineOnion(nextTrampolineOnion),
                ),
                // This dummy value is ignored when creating the htlc (C is not the recipient).
                RouteBlindingEncryptedData(TlvStream(RouteBlindingEncryptedDataTlv.PathId(ByteVector("deadbeef"))))
            )
            val onionD = OutgoingPaymentPacket.buildOnion(listOf(blindedRoute.blindedNodeIds.last()), listOf(payloadD), addC.paymentHash, OnionRoutingPacket.PaymentPacketLength)
            UpdateAddHtlc(randomBytes32(), 2, innerC.amountToForward, addC.paymentHash, innerC.outgoingCltv, onionD.packet, blindingD, null)
        }

        // D cannot decrypt the blinded payment: the associated data doesn't match.
        val failure = IncomingPaymentPacket.decrypt(addD, privD).left
        assertNotNull(failure)
        assertEquals(InvalidOnionHmac(hash(nextTrampolineOnion)), failure)
    }

    @Test
    fun `prune outgoing blinded paths`() {
        // We create an invoice with a large number of blinded paths.
        val features = Features(Feature.BasicMultiPartPayment to FeatureSupport.Optional)
        val offer = OfferTypes.Offer.createNonBlindedOffer(finalAmount, "test offer", d, features, Block.RegtestGenesisBlock.hash)
        val invoice = run {
            val payerKey = randomKey()
            val request = OfferTypes.InvoiceRequest(offer, finalAmount, 1, features, payerKey, "hello", Block.RegtestGenesisBlock.hash)
            val paymentMetadata = OfferPaymentMetadata.V1(offer.offerId, finalAmount, paymentPreimage, payerKey.publicKey(), "hello", 1, currentTimestampMillis())
            val blindedPayloadD = RouteBlindingEncryptedData(TlvStream(RouteBlindingEncryptedDataTlv.PathId(paymentMetadata.toPathId(privD))))
            val blindedPaths = (0 until 20L).map { i ->
                val blindedPayloadC = RouteBlindingEncryptedData(
                    TlvStream(
                        RouteBlindingEncryptedDataTlv.OutgoingChannelId(ShortChannelId(i)),
                        RouteBlindingEncryptedDataTlv.PaymentRelay(channelUpdateCD.cltvExpiryDelta, channelUpdateCD.feeProportionalMillionths, channelUpdateCD.feeBaseMsat),
                        RouteBlindingEncryptedDataTlv.PaymentConstraints(finalExpiry, 1.msat),
                    )
                )
                val blindedRouteDetails = RouteBlinding.create(randomKey(), listOf(c, d), listOf(blindedPayloadC, blindedPayloadD).map { it.write().byteVector() })
                val paymentInfo = createBlindedPaymentInfo(channelUpdateCD)
                Bolt12Invoice.Companion.PaymentBlindedContactInfo(OfferTypes.ContactInfo.BlindedPath(blindedRouteDetails.route), paymentInfo)
            }
            Bolt12Invoice(request, paymentPreimage, randomKey(), 600, features, blindedPaths)
        }
        // B sends an HTLC to its trampoline node C: we prune the blinded paths to fit inside the onion.
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacketToBlindedRecipient(invoice, randomKey(), finalAmount, finalExpiry, nodeHop_cd)
        assertEquals(OnionRoutingPacket.PaymentPacketLength, onion.packet.payload.size())
        val addC = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val (outerC, _) = decryptRelayToBlinded(addC, privC)
        assertTrue(outerC.outgoingBlindedPaths.isNotEmpty())
        assertTrue(outerC.outgoingBlindedPaths.size < invoice.blindedPaths.size)
        outerC.outgoingBlindedPaths.forEach { assertTrue(invoice.blindedPaths.contains(it)) }
    }

    @Test
    fun `prune outgoing routing info`() {
        // We create an invoice with a large number of routing hints.
        val routingHints = (0 until 50L).map { i -> listOf(Bolt11Invoice.TaggedField.ExtraHop(randomKey().publicKey(), ShortChannelId(i), 5.msat, 25, CltvExpiryDelta(36))) }
        val invoice = Bolt11Invoice.create(Chain.Regtest, finalAmount, paymentHash, privD, Either.Left("test"), CltvExpiryDelta(6), nonTrampolineFeatures, paymentSecret, paymentMetadata, extraHops = routingHints)
        // A sends an HTLC to its trampoline node B: we prune the routing hints to fit inside the onion.
        val (firstAmount, firstExpiry, onion) = OutgoingPaymentPacket.buildPacketToLegacyRecipient(invoice, finalAmount, finalExpiry, nodeHop_bd)
        assertEquals(OnionRoutingPacket.PaymentPacketLength, onion.packet.payload.size())
        val addB = UpdateAddHtlc(randomBytes32(), 1, firstAmount, paymentHash, firstExpiry, onion.packet)
        val (outerB, _) = decryptRelayToLegacy(addB, privB)
        assertTrue(outerB.invoiceRoutingInfo.isNotEmpty())
        assertTrue(outerB.invoiceRoutingInfo.size < routingHints.size)
        outerB.invoiceRoutingInfo.forEach { assertTrue(routingHints.contains(it)) }
    }
}

