package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.TestHelpers
import fr.acinq.lightning.wire.OfferTypes
import kotlinx.serialization.json.Json
import kotlin.test.*

class PayerProofTestsCommon : LightningTestSuite() {

    @Test
    fun `official test vectors`() {
        val payerKey = PrivateKey.fromHex("4242424242424242424242424242424242424242424242424242424242424242")
        testCases.valid_vectors.forEach { t ->
            val invoice = Bolt12Invoice.fromString(t.input.invoice).get()
            assertEquals(OfferTypes.rootHash(OfferTypes.removeSignature(invoice.records)), ByteVector32.fromValidHex(t.working.invoice_merkle_root))
            val preimage = ByteVector32.fromValidHex(t.input.preimage)
            val includedFields = PayerProof.Companion.IncludedFields(
                offerChains = t.input.invoice_fields.any { it.type == 2L && it.included },
                offerMetadata = t.input.invoice_fields.any { it.type == 4L && it.included },
                offerCurrency = t.input.invoice_fields.any { it.type == 6L && it.included },
                offerAmount = t.input.invoice_fields.any { it.type == 8L && it.included },
                offerDescription = t.input.invoice_fields.any { it.type == 10L && it.included },
                offerFeatures = t.input.invoice_fields.any { it.type == 12L && it.included },
                offerAbsoluteExpiry = t.input.invoice_fields.any { it.type == 14L && it.included },
                offerPaths = t.input.invoice_fields.any { it.type == 16L && it.included },
                offerIssuer = t.input.invoice_fields.any { it.type == 18L && it.included },
                offerQuantityMax = t.input.invoice_fields.any { it.type == 20L && it.included },
                offerNodeId = t.input.invoice_fields.any { it.type == 22L && it.included },
                invoiceRequestChain = t.input.invoice_fields.any { it.type == 80L && it.included },
                invoiceRequestAmount = t.input.invoice_fields.any { it.type == 82L && it.included },
                invoiceRequestFeatures = t.input.invoice_fields.any { it.type == 84L && it.included },
                invoiceRequestQuantity = t.input.invoice_fields.any { it.type == 86L && it.included },
                invoiceRequestPayerNote = t.input.invoice_fields.any { it.type == 89L && it.included },
                invoicePaths = t.input.invoice_fields.any { it.type == 160L && it.included },
                invoiceBlindedPay = t.input.invoice_fields.any { it.type == 162L && it.included },
                invoiceCreatedAt = t.input.invoice_fields.any { it.type == 164L && it.included },
                invoiceRelativeExpiry = t.input.invoice_fields.any { it.type == 166L && it.included },
                invoiceAmount = t.input.invoice_fields.any { it.type == 170L && it.included },
                invoiceFallbacks = t.input.invoice_fields.any { it.type == 172L && it.included },
                unknown = t.input.invoice_fields.filter { it.type > 250L && it.included }.map { it.type }.toSet()
            )
            if (t.name == "empty_proof_omitted_tlvs_explicit") {
                // This test vector verifies that we correctly handle an explicitly included empty proof_omitted_tlvs field.
                val decoded = PayerProof.decode(t.result.bech32)
                assertTrue(decoded.isSuccess)
                assertEquals(ByteVector64.fromValidHex(t.result.payer_sig), decoded.get().records.get<OfferTypes.ProofSignature>()?.signature)
                assertTrue(decoded.get().verifySigs())
                // Since we omit the proof_omitted_tlvs field when it's empty, we don't generate the same proof, which is fine.
                assertNotEquals(t.result.bech32, PayerProof.create(invoice, preimage, payerKey, includedFields, t.input.note).toString())
            } else {
                val payerProof = PayerProof.create(invoice, preimage, payerKey, includedFields, t.input.note)
                assertNotNull(payerProof.records.get<OfferTypes.LeafHashes>())
                payerProof.records.get<OfferTypes.LeafHashes>()?.let { assertEquals(it.hashes, t.working.proof_leaf_hashes.map { h -> ByteVector32.fromValidHex(h) }.toList()) }
                payerProof.records.get<OfferTypes.OmittedTlvs>()?.let { assertEquals(it.missing, t.working.proof_omitted_tlvs.toList()) }
                payerProof.records.get<OfferTypes.MissingHashes>()?.let { assertEquals(it.missing, t.working.proof_missing_hashes.map { h -> ByteVector32.fromValidHex(h) }.toList()) }
                assertNotNull(payerProof.records.get<OfferTypes.ProofSignature>())
                assertEquals(ByteVector64.fromValidHex(t.result.payer_sig), payerProof.records.get<OfferTypes.ProofSignature>()?.signature)
                assertEquals(t.result.bech32, payerProof.toString())
                assertTrue(PayerProof.decode(t.result.bech32).isSuccess)
                assertEquals(payerProof, PayerProof.decode(t.result.bech32).get())
                assertTrue(payerProof.verifySigs())
            }
        }
        testCases.invalid_vectors.forEach { t ->
            val isValid = PayerProof.decode(t.bech32).map { it.verifySigs() }.getOrElse { false }
            assertFalse(isValid)
        }
    }

    companion object {
        @kotlinx.serialization.Serializable
        data class InvalidTestVector(val reason: String, val bech32: String)

        @kotlinx.serialization.Serializable
        data class TestVectorResult(val payer_sig: String, val bech32: String)

        @kotlinx.serialization.Serializable
        data class TestVectorWorking(val invoice_merkle_root: String, val proof_leaf_hashes: Array<String>, val proof_omitted_tlvs: Array<Long>, val proof_missing_hashes: Array<String>)

        @kotlinx.serialization.Serializable
        data class InvoiceField(val type: Long, val included: Boolean)

        @kotlinx.serialization.Serializable
        data class TestVectorInput(val invoice: String, val preimage: String, val note: String?, val invoice_fields: Array<InvoiceField>)

        @kotlinx.serialization.Serializable
        data class ValidTestVector(val name: String, val input: TestVectorInput, val working: TestVectorWorking, val result: TestVectorResult)

        @kotlinx.serialization.Serializable
        data class TestVectors(val valid_vectors: Array<ValidTestVector>, val invalid_vectors: Array<InvalidTestVector>)

        val format = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val testCases = format.decodeFromString<TestVectors>(TestHelpers.readResourceAsString("payer_proof_tests.json"))
    }

}