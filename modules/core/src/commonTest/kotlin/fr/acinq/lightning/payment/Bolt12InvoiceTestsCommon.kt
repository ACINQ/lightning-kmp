package fr.acinq.lightning.payment

import fr.acinq.bitcoin.*
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomBytes64
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.crypto.RouteBlinding
import fr.acinq.lightning.payment.Bolt12Invoice.Companion.PaymentBlindedContactInfo
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.currentTimestampSeconds
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.*
import fr.acinq.lightning.wire.OfferTypes.ContactInfo
import fr.acinq.lightning.wire.OfferTypes.FallbackAddress
import fr.acinq.lightning.wire.OfferTypes.InvoiceAmount
import fr.acinq.lightning.wire.OfferTypes.InvoiceBlindedPay
import fr.acinq.lightning.wire.OfferTypes.InvoiceCreatedAt
import fr.acinq.lightning.wire.OfferTypes.InvoiceFallbacks
import fr.acinq.lightning.wire.OfferTypes.InvoiceFeatures
import fr.acinq.lightning.wire.OfferTypes.InvoiceNodeId
import fr.acinq.lightning.wire.OfferTypes.InvoicePaths
import fr.acinq.lightning.wire.OfferTypes.InvoicePaymentHash
import fr.acinq.lightning.wire.OfferTypes.InvoiceRelativeExpiry
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequest
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestAmount
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestChain
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestMetadata
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestPayerId
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestPayerNote
import fr.acinq.lightning.wire.OfferTypes.InvoiceRequestQuantity
import fr.acinq.lightning.wire.OfferTypes.InvoiceTlv
import fr.acinq.lightning.wire.OfferTypes.Offer
import fr.acinq.lightning.wire.OfferTypes.OfferAmount
import fr.acinq.lightning.wire.OfferTypes.OfferChains
import fr.acinq.lightning.wire.OfferTypes.OfferDescription
import fr.acinq.lightning.wire.OfferTypes.OfferFeatures
import fr.acinq.lightning.wire.OfferTypes.OfferIssuer
import fr.acinq.lightning.wire.OfferTypes.OfferIssuerId
import fr.acinq.lightning.wire.OfferTypes.OfferQuantityMax
import fr.acinq.lightning.wire.OfferTypes.PaymentInfo
import fr.acinq.lightning.wire.OfferTypes.Signature
import fr.acinq.lightning.wire.OfferTypes.rootHash
import fr.acinq.lightning.wire.OfferTypes.signSchnorr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Bolt12InvoiceTestsCommon : LightningTestSuite() {

    private fun signInvoiceTlvs(tlvs: TlvStream<InvoiceTlv>, key: PrivateKey): TlvStream<InvoiceTlv> {
        val signature = signSchnorr(Bolt12Invoice.signatureTag, rootHash(tlvs), key)
        return tlvs.copy(records = tlvs.records + Signature(signature))
    }

    private fun signInvoice(invoice: Bolt12Invoice, key: PrivateKey): Bolt12Invoice {
        val tlvs = OfferTypes.removeSignature(invoice.records)
        val signedInvoice = Bolt12Invoice(signInvoiceTlvs(tlvs, key))
        assertTrue(signedInvoice.checkSignature())
        return signedInvoice
    }

    private fun createPaymentBlindedRoute(
        nodeId: PublicKey,
        sessionKey: PrivateKey = randomKey(),
        pathId: ByteVector = randomBytes32()
    ): PaymentBlindedContactInfo {
        val selfPayload = RouteBlindingEncryptedData(
            TlvStream(
                RouteBlindingEncryptedDataTlv.PathId(pathId),
                RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(1234567), 0.msat),
                RouteBlindingEncryptedDataTlv.AllowedFeatures(Features.empty)
            )
        ).write().toByteVector()
        val blindedRoute = RouteBlinding.create(sessionKey, listOf(nodeId), listOf(selfPayload)).route
        val paymentInfo = PaymentInfo(1.msat, 2, CltvExpiryDelta(3), 4.msat, 5.msat, ByteVector.empty)
        return PaymentBlindedContactInfo(ContactInfo.BlindedPath(blindedRoute), paymentInfo)
    }

    @Test
    fun `check invoice signature`() {
        val nodeKey = randomKey()
        val payerKey = randomKey()
        val chain = BlockHash(randomBytes32())
        val offer = Offer.createNonBlindedOffer(10000.msat, "test offer", nodeKey.publicKey(), Features.empty, chain)
        val request = InvoiceRequest(offer, 11000.msat, 1, Features.empty, payerKey, null, chain)
        val invoice = Bolt12Invoice(
            request,
            randomBytes32(),
            nodeKey,
            300,
            Features.empty,
            listOf(createPaymentBlindedRoute(nodeKey.publicKey()))
        )
        assertTrue(invoice.checkSignature())
        assertEquals(Bolt12Invoice.fromString(invoice.toString()).get().toString(), invoice.toString())
        // changing signature makes check fail
        val withInvalidSignature = Bolt12Invoice(TlvStream(invoice.records.records.map {
            when (it) {
                is Signature -> Signature(randomBytes64())
                else -> it
            }
        }.toSet(), invoice.records.unknown))
        assertFalse(withInvalidSignature.checkSignature())
        // changing fields makes the signature invalid
        val withModifiedUnknownTlv = Bolt12Invoice(invoice.records.copy(unknown = setOf(GenericTlv(7, ByteVector.fromHex("ade4")))))
        assertFalse(withModifiedUnknownTlv.checkSignature())
        val withModifiedAmount = Bolt12Invoice(TlvStream(invoice.records.records.map {
            when (it) {
                is OfferAmount -> OfferAmount(it.amount + 100.msat)
                else -> it
            }
        }.toSet(), invoice.records.unknown))
        assertFalse(withModifiedAmount.checkSignature())
    }

    @Test
    fun `check invoice signature with unknown field from invoice request`() {
        val nodeKey = randomKey()
        val payerKey = randomKey()
        val chain = BlockHash(randomBytes32())
        val offer = Offer.createNonBlindedOffer(10000.msat, "test offer", nodeKey.publicKey(), Features.empty, chain)
        val basicRequest = InvoiceRequest(offer, 11000.msat, 1, Features.empty, payerKey, null, chain)
        val requestWithUnknownTlv = basicRequest.copy(records = TlvStream(basicRequest.records.records, setOf(GenericTlv(87, ByteVector.fromHex("0404")))))
        val invoice = Bolt12Invoice(
            requestWithUnknownTlv,
            randomBytes32(),
            nodeKey,
            300,
            Features.empty,
            listOf(createPaymentBlindedRoute(nodeKey.publicKey()))
        )
        assertEquals(invoice.records.unknown, setOf(GenericTlv(87, ByteVector.fromHex("0404"))))
        assertTrue(invoice.validateFor(requestWithUnknownTlv).isRight)
        assertEquals(Bolt12Invoice.fromString(invoice.toString()).get().toString(), invoice.toString())
    }

    @Test
    fun `check that invoice matches offer`() {
        val nodeKey = randomKey()
        val payerKey = randomKey()
        val chain = BlockHash(randomBytes32())
        val offer = Offer.createNonBlindedOffer(10000.msat, "test offer", nodeKey.publicKey(), Features.empty, chain)
        val request = InvoiceRequest(offer, 11000.msat, 1, Features.empty, payerKey, null, chain)
        val invoice = Bolt12Invoice(
            request,
            randomBytes32(),
            nodeKey,
            300,
            Features.empty,
            listOf(createPaymentBlindedRoute(nodeKey.publicKey()))
        )
        assertTrue(invoice.validateFor(request).isRight)
        // amount must match the request
        val withOtherAmount = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map {
            when (it) {
                is OfferAmount -> OfferAmount(9000.msat)
                else -> it
            }
        }.toSet())), nodeKey)
        assertTrue(withOtherAmount.validateFor(request).isLeft)
        // description must match the offer
        val withOtherDescription = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map {
            when (it) {
                is OfferDescription -> OfferDescription("other description")
                else -> it
            }
        }.toSet())), nodeKey)
        assertTrue(withOtherDescription.validateFor(request).isLeft)
        // nodeId must match the offer
        val otherNodeKey = randomKey()
        val withOtherNodeId = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map {
            when (it) {
                is OfferIssuerId -> OfferIssuerId(otherNodeKey.publicKey())
                else -> it
            }
        }.toSet())), nodeKey)
        assertTrue(withOtherNodeId.validateFor(request).isLeft)
        // issuer must match the offer
        val withOtherIssuer = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records + OfferIssuer("spongebob"))), nodeKey)
        assertTrue(withOtherIssuer.validateFor(request).isLeft)
    }

    @Test
    fun `check that invoice matches invoice request`() {
        val nodeKey = randomKey()
        val payerKey = randomKey()
        val chain = BlockHash(randomBytes32())
        val offer = Offer.createNonBlindedOffer(15000.msat, "test offer", nodeKey.publicKey(), Features.empty, chain)
        val request = InvoiceRequest(offer, 15000.msat, 1, Features.empty, payerKey, null, chain)
        assertTrue(request.quantity_opt == null) // when paying for a single item, the quantity field must not be present
        val invoice = Bolt12Invoice(
            request,
            randomBytes32(),
            nodeKey,
            300,
            Features(Feature.BasicMultiPartPayment to FeatureSupport.Optional),
            listOf(createPaymentBlindedRoute(nodeKey.publicKey()))
        )
        assertTrue(invoice.validateFor(request).isRight)
        val withInvalidFeatures = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map {
            when (it) {
                is InvoiceFeatures -> InvoiceFeatures(Features(Feature.BasicMultiPartPayment to FeatureSupport.Mandatory).toByteArray().toByteVector())
                else -> it
            }
        }.toSet())), nodeKey)
        assertTrue(withInvalidFeatures.validateFor(request).isLeft)
        val withAmountTooBig = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map {
            when (it) {
                is InvoiceRequestAmount -> InvoiceRequestAmount(20000.msat)
                else -> it
            }
        }.toSet())), nodeKey)
        assertTrue(withAmountTooBig.validateFor(request).isLeft)
        val withQuantity = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records + InvoiceRequestQuantity(2))), nodeKey)
        assertTrue(withQuantity.validateFor(request).isLeft)
        val withOtherPayerKey = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map {
            when (it) {
                is InvoiceRequestPayerId -> InvoiceRequestPayerId(randomKey().publicKey())
                else -> it
            }
        }.toSet())), nodeKey)
        assertTrue(withOtherPayerKey.validateFor(request).isLeft)
        val withPayerNote = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records + InvoiceRequestPayerNote("I am Batman"))), nodeKey)
        assertTrue(withPayerNote.validateFor(request).isLeft)
        val withOtherMetadata = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map {
            when (it) {
                is InvoiceRequestMetadata -> InvoiceRequestMetadata(ByteVector.fromHex("ae46c46b86"))
                else -> it
            }
        }.toSet())), nodeKey)
        assertTrue(withOtherMetadata.validateFor(request).isLeft)
        // Invoice request with more details about the payer.
        val tlvs = setOf(
            InvoiceRequestMetadata(ByteVector.fromHex("010203040506")),
            OfferDescription("offer description"),
            OfferIssuerId(nodeKey.publicKey()),
            InvoiceRequestAmount(15000.msat),
            InvoiceRequestPayerId(payerKey.publicKey()),
            InvoiceRequestPayerNote("I am Batman"),
            OfferFeatures(Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory).toByteArray().toByteVector())
        )
        val signature =
            signSchnorr(InvoiceRequest.signatureTag, rootHash(TlvStream(tlvs)), payerKey)
        val requestWithPayerDetails = InvoiceRequest(TlvStream(tlvs + Signature(signature)))
        val withPayerDetails = Bolt12Invoice(
            requestWithPayerDetails,
            randomBytes32(),
            nodeKey,
            300,
            Features.empty,
            listOf(createPaymentBlindedRoute(nodeKey.publicKey()))
        )
        assertTrue(withPayerDetails.validateFor(requestWithPayerDetails).isRight)
        assertTrue(withPayerDetails.validateFor(request).isLeft)
        val withOtherPayerNote = signInvoice(Bolt12Invoice(TlvStream(withPayerDetails.records.records.map {
            when (it) {
                is InvoiceRequestPayerNote -> InvoiceRequestPayerNote("Or am I Bruce Wayne?")
                else -> it
            }
        }.toSet())), nodeKey)
        assertTrue(withOtherPayerNote.validateFor(requestWithPayerDetails).isLeft)
        assertTrue(withOtherPayerNote.validateFor(request).isLeft)
    }

    @Test
    fun `check invoice expiry`() {
        val nodeKey = randomKey()
        val payerKey = randomKey()
        val chain = BlockHash(randomBytes32())
        val offer = Offer.createNonBlindedOffer(5000.msat, "test offer", nodeKey.publicKey(), Features.empty, chain)
        val request = InvoiceRequest(offer, 5000.msat, 1, Features.empty, payerKey, null, chain)
        val invoice = Bolt12Invoice(
            request,
            randomBytes32(),
            nodeKey,
            300,
            Features.empty,
            listOf(createPaymentBlindedRoute(nodeKey.publicKey()))
        )
        assertFalse(invoice.isExpired())
        assertTrue(invoice.validateFor(request).isRight)
        val expiredInvoice1 = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map {
            when (it) {
                is InvoiceCreatedAt -> InvoiceCreatedAt(0)
                else -> it
            }
        }.toSet())), nodeKey)
        assertTrue(expiredInvoice1.isExpired())
        assertTrue(expiredInvoice1.validateFor(request).isLeft) // when an invoice is expired, we mark it as invalid as well
        val expiredInvoice2 = signInvoice(Bolt12Invoice(TlvStream(invoice.records.records.map {
            when (it) {
                is InvoiceCreatedAt -> InvoiceCreatedAt(currentTimestampSeconds() - 2000)
                is InvoiceRelativeExpiry -> InvoiceRelativeExpiry(1800)
                else -> it
            }
        }.toSet())), nodeKey)
        assertTrue(expiredInvoice2.isExpired())
        assertTrue(expiredInvoice2.validateFor(request).isLeft) // when an invoice is expired, we mark it as invalid as well
    }

    @Test
    fun `decode invalid invoice`() {
        val nodeKey = randomKey()
        val tlvs = setOf(
            InvoiceRequestMetadata(ByteVector.fromHex("012345")),
            OfferIssuerId(nodeKey.publicKey()),
            InvoiceRequestPayerId(randomKey().publicKey()),
            InvoicePaths(listOf(createPaymentBlindedRoute(randomKey().publicKey()).route)),
            InvoiceBlindedPay(listOf(PaymentInfo(0.msat, 0, CltvExpiryDelta(0), 0.msat, 765432.msat, ByteVector.empty))),
            InvoiceCreatedAt(123456789L),
            InvoicePaymentHash(randomBytes32()),
            InvoiceAmount(1684.msat),
            InvoiceNodeId(nodeKey.publicKey())
        )
        // This minimal invoice is valid.
        val signed = signInvoiceTlvs(TlvStream(tlvs), nodeKey)
        val signedEncoded = Bech32.encodeBytes(Bolt12Invoice.hrp, OfferTypes.Invoice.tlvSerializer.write(signed), Bech32.Encoding.Beck32WithoutChecksum)
        Bolt12Invoice.fromString(signedEncoded)
        // But removing any TLV makes it invalid.
        for (tlv in tlvs) {
            val incomplete = tlvs.filterNot { it == tlv }.toSet()
            val incompleteSigned = signInvoiceTlvs(TlvStream(incomplete), nodeKey)
            val incompleteSignedEncoded = Bech32.encodeBytes(Bolt12Invoice.hrp, OfferTypes.Invoice.tlvSerializer.write(incompleteSigned), Bech32.Encoding.Beck32WithoutChecksum)
            assertTrue(Bolt12Invoice.fromString(incompleteSignedEncoded).isFailure)
        }
        // Missing signature is also invalid.
        val unsignedEncoded = Bech32.encodeBytes(Bolt12Invoice.hrp, OfferTypes.Invoice.tlvSerializer.write(TlvStream(tlvs)), Bech32.Encoding.Beck32WithoutChecksum)
        assertTrue(Bolt12Invoice.fromString(unsignedEncoded).isFailure)
    }

    @Test
    fun `encode decode invoice with many fields`() {
        val chain = Block.Testnet3GenesisBlock.hash
        val amount = 123456.msat
        val description = "invoice with many fields"
        val features = Features.empty
        val issuer = "alice"
        val nodeKey = PrivateKey.fromHex("998cf8ecab46f949bb960813b79d3317cabf4193452a211795cd8af1b9a25d90")
        val path = createPaymentBlindedRoute(
            nodeKey.publicKey(),
            PrivateKey.fromHex("f0442c17bdd2cefe4a4ede210f163b068bb3fea6113ffacea4f322de7aa9737b"),
            ByteVector.fromHex("76030536ba732cdc4e7bb0a883750bab2e88cb3dddd042b1952c44b4849c86bb")
        ).copy(paymentInfo = PaymentInfo(2345.msat, 765, CltvExpiryDelta(324), 1000.msat, amount, ByteVector.empty))
        val quantity = 57L
        val payerKey = PublicKey.fromHex("024a8d96f4d13c4219f211b8a8e7b4ab7a898fd1b2e90274ca5a8737a9eda377f8")
        val payerNote = "I'm Bob"
        val payerInfo = ByteVector.fromHex("a9eb6e526eac59cd9b89fb20")
        val createdAt = 1654654654L
        val paymentHash = ByteVector32.fromValidHex("51951d4c53c904035f0b293dc9df1c0e7967213430ae07a5f3e134cd33325341")
        val relativeExpiry = 3600L
        val fallbacks = listOf(FallbackAddress(4, ByteVector.fromHex(("123d56f8"))), FallbackAddress(6, ByteVector.fromHex("eb3adc68945ef601")))
        val tlvs = TlvStream(
            setOf(
                InvoiceRequestMetadata(payerInfo),
                OfferChains(listOf(chain)),
                OfferAmount(amount),
                OfferDescription(description),
                OfferFeatures(ByteVector.empty),
                OfferIssuer(issuer),
                OfferIssuerId(nodeKey.publicKey()),
                InvoiceRequestChain(chain),
                InvoiceRequestAmount(amount),
                InvoiceRequestQuantity(quantity),
                InvoiceRequestPayerId(payerKey),
                InvoiceRequestPayerNote(payerNote),
                InvoicePaths(listOf(path.route)),
                InvoiceBlindedPay(listOf(path.paymentInfo)),
                InvoiceCreatedAt(createdAt),
                InvoiceRelativeExpiry(relativeExpiry),
                InvoicePaymentHash(paymentHash),
                InvoiceAmount(amount),
                InvoiceFallbacks(fallbacks),
                InvoiceFeatures(ByteVector.empty),
                InvoiceNodeId(nodeKey.publicKey()),
            ), setOf(GenericTlv(121, ByteVector.fromHex("010203")), GenericTlv(313, ByteVector.fromHex("baba")))
        )
        val signature = signSchnorr(Bolt12Invoice.signatureTag, rootHash(tlvs), nodeKey)
        val invoice = Bolt12Invoice(tlvs.copy(records = tlvs.records + Signature(signature)))
        assertTrue(invoice.toString() == "lni1qqx2n6mw2fh2ckwdnwylkgqzypp5jl7hlqnf2ugg7j3slkwwcwht57vhyzzwjr4dq84rxzgqqqqqqzqrq83yqzscd9h8vmmfvdjjqamfw35zqmtpdeujqenfv4kxgucvqqfq2ctvd93k293pq0zxw03kpc8tc2vv3kfdne0kntqhq8p70wtdncwq2zngaqp529mmc5pqgdyhl4lcy62hzz855v8annkr46a8n9eqsn5satgpagesjqqqqqq9yqcpufq9vqfetqssyj5djm6dz0zzr8eprw9gu762k75f3lgm96gzwn994peh48k6xalctyr5jfmdyppx7cneqvqsyqaq5qpugee7xc8qa0pf3jxe9k0976dvzuqu8eaedk0pcpg2dr5qx3gh00qzn8pc426xsh6l6ekdhr2hdpge0euhhp9frv6w04zjcqhhf6ru2wrqzqnjsxh8zmlm0gkeuq8qyxcy28uzhzljqkq22epc4mmdrx6vtm0eyyqr4agrvpkfuutftvf7f6paqewk3ysql3h8ukfz3phgmap5we4wsq3c97205a96r6f3hsd705jl29xt8yj3cu8vpm6z8lztjw3pcqqqpy5sqqqzl5q5gqqqqqqqqqqraqqqqqqqqqq7ysqqqzjqgc4qq6l2vqswzz5zq5v4r4x98jgyqd0sk2fae803crnevusngv9wq7jl8cf5e5eny56p4gpsrcjq4sfqgqqyzg74d7qxqqywkwkudz29aasp4cqtqggrc3nnudswp67znrydjtv7ta56c9cpc0nmjmv7rszs568gqdz3w770qsx3axhvq3e7npme2pwslgxa8kfcnqjqyeztg5r5wgzjpufjswx4crvd6kzlqjzukq5e707kp9ez98mj0zkckeggkm8cp6g6vgzh3j2q0lgp8ypt4ws")
        val codedDecoded = Bolt12Invoice.fromString(invoice.toString()).get()
        assertEquals(codedDecoded.invoiceRequest.chain, chain)
        assertEquals(codedDecoded.amount, amount)
        assertEquals(codedDecoded.description, description)
        assertEquals(codedDecoded.features, features)
        assertEquals(codedDecoded.invoiceRequest.offer.issuer, issuer)
        assertEquals(codedDecoded.nodeId.value.drop(1), nodeKey.publicKey().value.drop(1))
        assertEquals(codedDecoded.blindedPaths, listOf(path))
        assertEquals(codedDecoded.invoiceRequest.quantity, quantity)
        assertEquals(codedDecoded.invoiceRequest.payerId, payerKey)
        assertEquals(codedDecoded.invoiceRequest.payerNote, payerNote)
        assertEquals(codedDecoded.invoiceRequest.metadata, payerInfo)
        assertEquals(codedDecoded.createdAtSeconds, createdAt)
        assertEquals(codedDecoded.paymentHash, paymentHash)
        assertEquals(codedDecoded.relativeExpirySeconds, relativeExpiry)
        assertEquals(codedDecoded.fallbacks, fallbacks)
        assertEquals(codedDecoded.records.unknown, setOf(GenericTlv(121, ByteVector.fromHex("010203")), GenericTlv(313, ByteVector.fromHex("baba"))))
    }

    @Test
    fun `minimal tip`() {
        val nodeKey = PrivateKey.fromHex("48c6e5fcf499f50436f54c3b3edecdb0cb5961ca29d74bea5ab764828f08bf47")
        assertEquals(nodeKey.publicKey(), PublicKey.fromHex("024ff5317f051c7f6eac0266c5cceaeb6c5775a940fab9854e47bfebf6bc7a0407"))
        val payerKey = PrivateKey.fromHex("d817e8896c67d0bcabfdb93da7eb7fc698c829a181f994dd0ad866a8eda745e8")
        assertEquals(payerKey.publicKey(), PublicKey.fromHex("031ef4439f638914de79220483dda32dfb7a431e799a5ce5a7643fbd70b2118e4e"))
        val preimage = ByteVector32.fromValidHex("317d1fd8fec5f3ea23044983c2ba2a8043395b2a0790a815c9b12719aa5f1516")
        val offer = Offer.createNonBlindedOffer(null, "minimal tip", nodeKey.publicKey(), Features.empty, Block.LivenetGenesisBlock.hash)
        val encodedOffer = "lno1pg9k66twd9kkzmpqw35hq93pqf8l2vtlq5w87m4vqfnvtn82adk9wadfgratnp2wg7l7ha4u0gzqw"
        assertEquals(offer.toString(), encodedOffer)
        assertEquals(Offer.decode(encodedOffer).get(), offer)
        val request = InvoiceRequest(offer, 12000000.msat, 1, Features.empty, payerKey, null, Block.LivenetGenesisBlock.hash)
        // Invoice request generation is not reproducible because we add randomness in the first TLV.
        val encodedRequest = "lnr1qqs289chx8swkpmwf3uzexfxr0kk9syavsjcmkuur5qgjqt60ayjdec2pdkkjmnfd4skcgr5d9cpvggzfl6nzlc9r3lkatqzvmzue6htd3tht22ql2uc2nj8hl4ld0r6qsr4qgr0u2xq4dh3kdevrf4zg6hx8a60jv0gxe0ptgyfc6xkryqqqqqqqpfq8dcmqpvzzqc773pe7cufzn08jgsys0w6xt0m0fp3u7v6tnj6weplh4ctyyvwfmcypemfjk6kryqxycnnmu2vp9tuw00eslf0grp6rf3hk6v76aynyn4lclra0fyyk2gxyf9hx73rnm775204tn8cltacw4s0fzd5c0lxm58s"
        val decodedRequest = InvoiceRequest.decode(encodedRequest).get()
        assertEquals(decodedRequest.unsigned().records.filterNot { it is InvoiceRequestMetadata }, request.unsigned().records.filterNot { it is InvoiceRequestMetadata })
        assertTrue(request.isValid())
        assertEquals(request.offer, offer)
        val invoice = Bolt12Invoice(
            decodedRequest,
            preimage,
            nodeKey,
            300,
            Features.empty,
            listOf(createPaymentBlindedRoute(nodeKey.publicKey()))
        )
        assertEquals(Bolt12Invoice.fromString(invoice.toString()).get().records, invoice.records)
        assertTrue(invoice.validateFor(decodedRequest).isRight)
        // Invoice generation is not reproducible as the timestamp and blinding point will change but all other fields should be the same.
        val encodedInvoice = "lni1qqs289chx8swkpmwf3uzexfxr0kk9syavsjcmkuur5qgjqt60ayjdec2pdkkjmnfd4skcgr5d9cpvggzfl6nzlc9r3lkatqzvmzue6htd3tht22ql2uc2nj8hl4ld0r6qsr4qgr0u2xq4dh3kdevrf4zg6hx8a60jv0gxe0ptgyfc6xkryqqqqqqqpfq8dcmqpvzzqc773pe7cufzn08jgsys0w6xt0m0fp3u7v6tnj6weplh4ctyyvwf6s2qqj075ch7pgu0ah2cqnxchxw46mv2a66js86hxz5u3ala0mtc7syqup2a4g7lywy0zytzjzdhlar5uegx8qj8el2a2hpl7z30cv56fxkhwqpqgpnv93lzfep3m5ppkt3jry0kanpk3uxku733nr03snlzqjls3pejqp65tnf8nf8te9h67ge0lgzum5kypuvqrdz50t238n6g0wrdtv49nrgjk7k26rw7a24arfx9z4dup8379etdpw0tfkg3mwtngsuqqqqqqgqqqqqyqqrqqqqqqqqqqqqgqqqqqqqqqqqq5qqpfqyvwv9m2dxqgqje2pqshlyweee7p4m365legtkdgvy6s02rdqsv38mwnmk8p88cz03dt725qahrvqtqggzfl6nzlc9r3lkatqzvmzue6htd3tht22ql2uc2nj8hl4ld0r6qsrlqsxuf5rcjutppkh79vr6q7vma5yccxhf79ghfg5zkc6z4u3zqzyh0nf50g7w7q4gk32hqg97pn7p9kaz0ddm5fza65ztdqj2sry3gw6l2"
        val decodedInvoice = Bolt12Invoice.fromString(encodedInvoice).get()
        assertEquals(decodedInvoice.amount, invoice.amount)
        assertEquals(decodedInvoice.nodeId, invoice.nodeId)
        assertEquals(decodedInvoice.paymentHash, invoice.paymentHash)
        assertEquals(decodedInvoice.description, invoice.description)
        assertEquals(decodedInvoice.invoiceRequest.unsigned(), invoice.invoiceRequest.unsigned())
    }

    @Test
    fun `minimal offer`() {
        val nodeKey = PrivateKey.fromHex("3b7a19e8320bb86431cf92cd7c69cc1dc0181c37d5a09875e4603c4e37d3705d")
        assertEquals(nodeKey.publicKey(), PublicKey.fromHex("03c48ac97e09f3cbbaeb35b02aaa6d072b57726841a34d25952157caca60a1caf5"))
        val payerKey = PrivateKey.fromHex("0e00a9ef505292f90a0e8a7aa99d31750e885c42a3ef8866dd2bf97919aa3891")
        assertEquals(payerKey.publicKey(), PublicKey.fromHex("033e94f2afd568d128f02ece844ad4a0a1ddf2a4e3a08beb2dba11b3f1134b0517"))
        val preimage = ByteVector32.fromValidHex("09ad5e952ec39d45461ebdeceac206fb45574ae9054b5a454dd02c65f5ba1b7c")
        val offer = Offer.createNonBlindedOffer(456000000.msat, "minimal offer", nodeKey.publicKey(), Features.empty, Block.LivenetGenesisBlock.hash)
        val encodedOffer = "lno1pqzpktszqq9q6mtfde5k6ctvyphkven9wgtzzq7y3tyhuz0newawkdds924x6pet2aexssdrf5je2g2het9xpgw275"
        assertEquals(offer.toString(), encodedOffer)
        assertEquals(Offer.decode(encodedOffer).get(), offer)
        val request = InvoiceRequest(offer, 456001234.msat, 1, Features.empty, payerKey, null, Block.LivenetGenesisBlock.hash)
        // Invoice request generation is not reproducible because we add randomness in the first TLV.
        val encodedRequest = "lnr1qqsf4h8fsnpjkj057gjg9c3eqhv889440xh0z6f5kng9vsaad8pgq7sgqsdjuqsqpgxk66twd9kkzmpqdanxvetjzcss83y2e9lqnu7tht4ntvp24fksw26hwf5yrg6dyk2jz472efs2rjh42qsxlc5vp2m0rvmjcxn2y34wv0m5lyc7sdj7zksgn35dvxgqqqqqqqzjqsdjupkjtqssx05572ha26x39rczan5yft22pgwa72jw8gytavkm5ydn7yf5kpgh7pq2hlvh7twke5830a44wc0zlrs2kph4ghndm60ahwcznhcd0pcpl332qv5xuemksazy3zx5s63kqmqkphrn9jg4ln55pc6syrwqukejeq"
        val decodedRequest = InvoiceRequest.decode(encodedRequest).get()
        assertEquals(decodedRequest.unsigned().records.filterNot { it is InvoiceRequestMetadata }, request.unsigned().records.filterNot { it is InvoiceRequestMetadata })
        assertTrue(request.isValid())
        assertEquals(request.offer, offer)
        val invoice = Bolt12Invoice(
            decodedRequest,
            preimage,
            nodeKey,
            300,
            Features.empty,
            listOf(createPaymentBlindedRoute(nodeKey.publicKey()))
        )
        assertEquals(Bolt12Invoice.fromString(invoice.toString()).get().records, invoice.records)
        assertTrue(invoice.validateFor(decodedRequest).isRight)
        // Invoice generation is not reproducible as the timestamp and blinding point will change but all other fields should be the same.
        val encodedInvoice = "lni1qqsf4h8fsnpjkj057gjg9c3eqhv889440xh0z6f5kng9vsaad8pgq7sgqsdjuqsqpgxk66twd9kkzmpqdanxvetjzcss83y2e9lqnu7tht4ntvp24fksw26hwf5yrg6dyk2jz472efs2rjh42qsxlc5vp2m0rvmjcxn2y34wv0m5lyc7sdj7zksgn35dvxgqqqqqqqzjqsdjupkjtqssx05572ha26x39rczan5yft22pgwa72jw8gytavkm5ydn7yf5kpgh5zsq83y2e9lqnu7tht4ntvp24fksw26hwf5yrg6dyk2jz472efs2rjh4qfjynufc627cuspz9lqzyk387xgzs4txcw0q97ugxfqm8x5zgj02gqgz4mnucmtxr620e5ttewtsg0s5n88euljnf7puagqje9j6gvaxk3pqqwsmahw79nhuq05zh8k29jk5qngpuny5l2vhjdrexg8hejukaee8fr7963dfag9q3lpcq9tt23f8s4h89cmjqa43u4fhk6l2y8qqqqqqzqqqqqpqqqcqqqqqqqqqqqzqqqqqqqqqqqq9qqq2gprrnp0zefszqyk2sgpvkrnmq53kv7r52rpnmtmd9ukredsnygsnymsurdy6e9la6l4hyz4qgxewqmftqggrcj9vjlsf709m46e4kq425mg89dthy6zp5dxjt9fp2l9v5c9pet6lqsy3s64amqgnlel7hn6fjrnk32xrn0ugr2xzct22ew28zftgmj70q9x2akqm34que8u2qe643cm38jpka6nfca4lfhuq6hgpnpwkpexrc"
        val decodedInvoice = Bolt12Invoice.fromString(encodedInvoice).get()
        assertEquals(decodedInvoice.amount, invoice.amount)
        assertEquals(decodedInvoice.nodeId, invoice.nodeId)
        assertEquals(decodedInvoice.paymentHash, invoice.paymentHash)
        assertEquals(decodedInvoice.description, invoice.description)
        assertEquals(decodedInvoice.invoiceRequest.unsigned(), invoice.invoiceRequest.unsigned())
    }

    @Test
    fun `offer with quantity`() {
        val nodeKey = PrivateKey.fromHex("334a488858f260a2bb262493f6edcd35470f110bba62c7a5f90c78a047b364df")
        assertEquals(nodeKey.publicKey(), PublicKey.fromHex("0327afd599da3226f4608b96ab042fe558bf558211d3c5e67ecc8be9963220434f"))
        val payerKey = PrivateKey.fromHex("4b4129a801ea631e25903cd59dd7f7a6820c19d73aa0b095496e21027934becf")
        assertEquals(payerKey.publicKey(), PublicKey.fromHex("027c6d03fa8f366e2ef8017cdfaf5d3cf1a3b0123db1318263b662c0aa9ec9c959"))
        val preimage = ByteVector32.fromValidHex("99221825b86576e94391b179902be8b22c7cfa7c3d14aec6ae86657dfd9bd2a8")
        val offer = Offer(
            TlvStream(
                OfferChains(listOf(Block.Testnet3GenesisBlock.hash)),
                OfferAmount(100000.msat),
                OfferDescription("offer with quantity"),
                OfferIssuer("alice@bigshop.com"),
                OfferQuantityMax(1000),
                OfferIssuerId(nodeKey.publicKey())
            )
        )
        val encodedOffer = "lno1qgsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqgqvqcdgq2zdhkven9wgs8w6t5dqs8zatpde6xjarezggkzmrfvdj5qcnfvaeksmms9e3k7mg5qgp7s93pqvn6l4vemgezdarq3wt2kpp0u4vt74vzz8futen7ej97n93jypp57"
        assertEquals(offer.toString(), encodedOffer)
        assertEquals(Offer.decode(encodedOffer).get(), offer)
        val request = InvoiceRequest(offer, 7200000.msat, 72, Features.empty, payerKey, null, Block.Testnet3GenesisBlock.hash)
        // Invoice request generation is not reproducible because we add randomness in the first TLV.
        val encodedRequest = "lnr1qqs8lqvnh3kg9uj003lxlxyj8hthymgq4p9ms0ag0ryx5uw8gsuus4gzypp5jl7hlqnf2ugg7j3slkwwcwht57vhyzzwjr4dq84rxzgqqqqqqzqrqxr2qzsndanxvetjypmkjargypch2ctww35hg7gjz9skc6trv4qxy6t8wd5x7upwvdhk69qzq05pvggry7hatxw6xgn0gcytj64sgtl9tzl4tqs360z7vlkv305evv3qgd84qgzrf9la07pxj4cs3a9rplvuasawhfuewgyyay826q02xvysqqqqqpfqxmwaqptqzjzcyyp8cmgrl28nvm3wlqqheha0t570rgaszg7mzvvzvwmx9s92nmyujk0sgpef8dt57nygu3dnfhglymt6mnle6j8s28rler8wv3zygen07v4ddfplc9qs7nkdzwcelm2rs552slkpv45xxng65ne6y4dlq2764gqv"
        val decodedRequest = InvoiceRequest.decode(encodedRequest).get()
        assertEquals(decodedRequest.unsigned().records.filterNot { it is InvoiceRequestMetadata }, request.unsigned().records.filterNot { it is InvoiceRequestMetadata })
        assertTrue(request.isValid())
        assertEquals(request.offer, offer)
        val invoice = Bolt12Invoice(
            decodedRequest,
            preimage,
            nodeKey,
            300,
            Features.empty,
            listOf(createPaymentBlindedRoute(nodeKey.publicKey()))
        )
        assertEquals(Bolt12Invoice.fromString(invoice.toString()).get().records, invoice.records)
        assertTrue(invoice.validateFor(decodedRequest).isRight)
        // Invoice generation is not reproducible as the timestamp and blinding point will change but all other fields should be the same.
        val encodedInvoice = "lni1qqs8lqvnh3kg9uj003lxlxyj8hthymgq4p9ms0ag0ryx5uw8gsuus4gzypp5jl7hlqnf2ugg7j3slkwwcwht57vhyzzwjr4dq84rxzgqqqqqqzqrqxr2qzsndanxvetjypmkjargypch2ctww35hg7gjz9skc6trv4qxy6t8wd5x7upwvdhk69qzq05pvggry7hatxw6xgn0gcytj64sgtl9tzl4tqs360z7vlkv305evv3qgd84qgzrf9la07pxj4cs3a9rplvuasawhfuewgyyay826q02xvysqqqqqpfqxmwaqptqzjzcyyp8cmgrl28nvm3wlqqheha0t570rgaszg7mzvvzvwmx9s92nmyujkdq5qpj0t74n8dryfh5vz9ed2cy9lj43064sgga830x0mxgh6vkxgsyxnczgew6pkkhja3cl3dfxthumcmp6gkp446ha4tcj884eqch6g57newqzquqmar5nynwtg9lknq98yzslwla3vdxefulhq2jkwnqnsf7umpl5cqr58qkj63hkpl7ffyd6f3qgn3m5kuegehhakvxw7fuw29tf3r5wgj37uecjdw2th4t5fp7f99xvk4f3gwl0wyf2a558wqa9w3pcqqqqqqsqqqqqgqqxqqqqqqqqqqqqsqqqqqqqqqqqpgqqzjqgcuctck2vqsp9j5zqlsxsv7uy23npygenelt4q5sdh8ftc3x7rpd0hqlachjnj9z834s4gpkmhgqkqssxfa06kva5v3x73sgh94tqsh72k9l2kppr579uelvezlfjcezqs607pqxa3afljxyf2ua9dlqs33wrfzakt5tpraklpzfpn63uxa7el475x4sc0w4hs75e3nhe689slfz4ldqlwja3zaq0w3mnz79f4ne0c3r3c"
        val decodedInvoice = Bolt12Invoice.fromString(encodedInvoice).get()
        assertEquals(decodedInvoice.amount, invoice.amount)
        assertEquals(decodedInvoice.nodeId, invoice.nodeId)
        assertEquals(decodedInvoice.paymentHash, invoice.paymentHash)
        assertEquals(decodedInvoice.description, invoice.description)
        assertEquals(decodedInvoice.invoiceRequest.unsigned(), invoice.invoiceRequest.unsigned())
    }

    @Test
    fun `cln invoice`() {
        val encodedInvoice = "lni1qqgds4gweqxey37gexf5jus4kcrwuq3qqc3xu3s3rg94nj40zfsy866mhu5vxne6tcej5878k2mneuvgjy8s5predakx793pqfxv2rtqfajhp98c5tlsxxkkmzy0ntpzp2rtt9yum2495hqrq4wkj5pqqc3xu3s3rg94nj40zfsy866mhu5vxne6tcej5878k2mneuvgjy84yqucj6q9sggrnl24r93kfmdnatwpy72mxg7ygr9waxu0830kkpqx84pd5j65fhg2pxqzfnzs6cz0v4cff79zlup344kc3ru6cgs2s66ef8x64fd9cqc9t45s954fef6n3ql8urpc4r2vvunc0uv9yq37g485heph6lpuw34ywxadqypwq3hlcrpyk32zdvlrgfsdnx5jegumenll49v502862l9sq5erz3qqxte8tyk308ykd6fqy2lxkrsmeq77d8s5977pzmc68lgvs2xcn0kfvnlzud9fvkv900ggwe7yf9hf7lr6qz3pcqqqqqqqqqqqqqqq5qqqqqqqqqqqqqwjfvkl43fqqqqqqzjqgcuhrdv2sgq5spd8qp4ev2rw0v9r7cvvrntlzpvlwmd8vczycklu87336h55g24q8xykszczzqjvc5xkqnm9wz203ghlqvdddkyglxkzyz5xkk2fek42tfwqxp2ad8cypv26x5zxkyk675ep3v48grwydze6nvvg56cklgmvztuny58t5j0fl3hemx3lvd0ryx89jtf0h069z6r2qwqvjlyrewvzsfqmmfajs70q"
        val invoice = Bolt12Invoice.fromString(encodedInvoice).get()
        assertTrue(invoice.checkSignature())
        assertEquals(invoice.amount, 10000000.msat)
        assertEquals(invoice.nodeId, PublicKey.fromHex("024cc50d604f657094f8a2ff031ad6d888f9ac220a86b5949cdaaa5a5c03055d69"))
        assertEquals(invoice.paymentHash, ByteVector32.fromValidHex("14805a7006b96286e7b0a3f618c1cd7f1059f76da766044c5bfc3fa31d5e9442"))
        assertTrue(invoice.description == "yolo")
    }

    @Test
    fun `invoice with non-minimally encoded feature bits`(){
        val encodedInvoice = "lni1qqsyzre2s0lc77w5h33ck6540xxsyjehjl66f9tfp83w85zcxqyhltczyqrzymjxzydqkkw24ufxqslttwlj3s608f0rx2slc7etw0833zgs7zqyqh67zqq2qqgwsqktzd0na8g54f2r8secsaemc7ww2d6spl397celwcv20egnau2z8gp83d0dg7gvtkkvklnqlvp0erhq9nh9928rexerg578wnyew6dj6xczq2nqtavvd94k7jq2slng76uk560g6qeu38ru2gjjtdd4w9jxfqcc5qpnvvduearw4k75xdsgrc9ntzs274hwumtk5zwlrcr8yzwn8q0ry40f6lcmarq2nqkz9j2anajrlpchwwfguypms9x0uptvcsspwzjp3vg8srqx27crkqe8v9nzqaktzwwy5szk0rsq9sq7vhqncvv63mseqsx9lzmjraxhfnhc6f9tgnm05v7x0s4dhzwac9gruy44n9yht645cd4jzcssyjvcf2ptqztsenmzyw0e6kpx209mmmpal9ptutxpeygerepwh5rc2qsqvgnwgcg35z6ee2h3yczraddm72xrfua9uve2rlrm9deu7xyfzr6jqsae4jsq2spsyqqqtqss9g4l2s06jx69u2vtvezfmh07puh8pzhp76yddr7yvjpt2q38puqx5r7sgacrnpvghfhfzdzm9rertx4egjnarr2plwp26yfzcnv4ef536h9nu8lq9xyejhphnyv97axrqwr982vvedhfzj3cn5uhdymxwejfh55p2putqvpeskyt5m53x3dj3u34n2u5ff7334qlhq4dzy3vfk2u56gatje7rlsqgllx5cs3433fgn37scpz5ysn7df4tcfvgw5hgn998qut5l63vvmlv85xj4gj9rs6ja6gj45ddfjvwrcq9qthepk3xtpy4x8tsmmaqhas3v8k6chxp4ds8367lgw3q4mtpm5zmlr84tx4xpshtaxa0es0kcjuah80xt23pm08qprase5e2euq8ndvymuzcdznh78qyg28lw65wve2fpphd5zpwy4v3gfpa245dgtmqkp34gg8s4tfxytnx5vxhclwzmpzdy80jlfyznklk9t0karg42yvxqey68py3t0yg5rew5jke2sr6l5akw3r4x4cyp5f9ty27yjqtsn5ucqywkk84sxudl89xdxw34kvvtq67pk64r3kmyzz5dum0c66sjh7a5ylr6u38ycdmdq5rm7pp5m87rmsg7ntkqr4dcateeafrchaw085my236hxg47745nsrdtmjvnhy4a9ppd95g5m3u40wa0pcnmlhcm99xd0flh0484vht6ysx5cg5nmjxzaqsqv33sgptsrgmfuqgwjuvw5v58k379638h6hda8tqvpk4aexfmj27jsskj6y2dc72hhwhsufalmudjjnxh5lmh6n6kt4azgqg7en2fg446vmtj2zgncc9wv4sa8zhyxm60zadqlf664d8mhdx6g5g6cls2glkqdmayuvypt7fuljtswlmz4w5e8nkkpzr8m6txz7gzvfcexj9dmdhuhsx35lnwnmzm52vq2wgr49g25dwk4jlh0n2yq6yufpewngg7llkgxwqpr5nlruajj55sel09axp2tmkhaf2hkh2lsjyth098l2r2kfg7u9440ymwswpwd20j9zdp562ejm0yy0x68q4knmd6a6g4nz0a2nm3842yw4pdx8udqggqkxa03jwmrzzuzwp2mn6az3exhunlqcpmphsks3cur22l3hvzn74vqy0kf70r6hd5cy2va94czl9g594856j287cefqej8qlre5ewyc5l02wtsx0hcjr4jhup6z4rj46lmrylsr034r5w2csnsgcy83yz848lafh5wue9aue8grnpvghfhfzdzm9rertx4egjnarr2plwp26yfzcnv4ef536h9nu8lq86u0a3w8zcxwy9hj9gvdwv8fhahpdauyzmuegpkefl3xc798mft7qvpeskyt5m53x3dj3u34n2u5ff7334qlhq4dzy3vfk2u56gatje7rlsqg0xlmw039msmmqtt4jqkgqts08ervu9dsx05qwzr67dazwklna9yjzdker5mhmeghxde2jlu5gvl4wrshvrg6x6a0j7hqsgpcc3ngm0ucvftuq6k8q0tpgxknk3d3t8nc9p9frafrfndz788hkaut704urzsj06t45qy8qk5hewf9p3sej3m2xrwyk6ny5hg8t24aq50a7re8evssrd0nmtrpjttuj04nlhs8ygteqepyc6sg5lsdajrc63xjp26j7surx83vx5u4326qfk6vw0sqhme6cw9247ef75ymtz4mp3esduvl07ykrnzzre3aq5jgqzrzcj59yjdcvp38nq7uvdqwmnhvy0h7t9062znl8ly02k9d02tyxev6mf6we8ztfjrdu73wc6gctxg5lmgj4a8v8z9lzqdfvlsmcwzyznagl929pqqqqyfjqqqqqpszqcqqqqqqqqqq3xqqqqqqz0490jqqqgqqqqqqqqqqqqqqqqqqqqqqqqqqqqqpzvsqqqqqvqsxqqqqqqqqqqyfsqqqqqqnaftusqqzqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqgnyqqqqqrqypsqqqqqqqqqpzvqqqqqqp4rw2vqqqsqqqqqqqqqqqqqqqqqqqqqqqqqzjqg6zm7ju2sgrmk0u67xstmskz34gfjfnjfxwvjltp3jsrd8rn40s7pgk8tzxwt64qgwu6egqtqggzfxvy4q4sp9cvea3z88uatqn98jaaas7ljs479nqujyv3usht6pu0qs8wdac52sykqfjnxg0xhva4fcv00hr4tqzjwkjnkayykkm9dnr97ladr5jjjx4xyjtun7ucye660akfv4nl9tupwnyemp0sasfxapvcw"
        val invoice = Bolt12Invoice.fromString(encodedInvoice).get()
        assertTrue(invoice.checkSignature())
        assertEquals(invoice.amount, 1000000000.msat)
    }
}