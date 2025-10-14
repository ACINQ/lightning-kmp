package fr.acinq.lightning.payment

import fr.acinq.bitcoin.ByteVector
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.utils.msat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfferPaymentMetadataTestsCommon {

    @Test
    fun `encode - decode v1 metadata`() {
        val nodeKey = randomKey()
        val metadata = OfferPaymentMetadata.V1(
            offerId = randomBytes32(),
            amount = 50_000_000.msat,
            preimage = randomBytes32(),
            payerKey = randomKey().publicKey(),
            payerNote = null,
            quantity = 1,
            createdAtMillis = 0
        )
        assertEquals(metadata, OfferPaymentMetadata.decode(metadata.encode()))
        val pathId = metadata.toPathId(nodeKey)
        assertEquals(metadata, OfferPaymentMetadata.fromPathId(nodeKey, pathId, metadata.paymentHash))
    }

    @Test
    fun `encode - decode v1 metadata with payer note`() {
        val nodeKey = randomKey()
        val metadata = OfferPaymentMetadata.V1(
            offerId = randomBytes32(),
            amount = 100_000_000.msat,
            preimage = randomBytes32(),
            payerKey = randomKey().publicKey(),
            payerNote = "Thanks for all the fish",
            quantity = 42,
            createdAtMillis = 0
        )
        assertEquals(metadata, OfferPaymentMetadata.decode(metadata.encode()))
        val pathId = metadata.toPathId(nodeKey)
        assertEquals(metadata, OfferPaymentMetadata.fromPathId(nodeKey, pathId, metadata.paymentHash))
    }

    @Test
    fun `encode - decode v2 metadata`() {
        val nodeKey = randomKey()
        val metadata = OfferPaymentMetadata.V2(
            offerId = randomBytes32(),
            amount = 50_000_000.msat,
            preimage = randomBytes32(),
            createdAtSeconds = 0,
            relativeExpirySeconds = null,
            description = null,
            payerKey = null,
            payerNote = null,
            quantity = null
        )
        assertEquals(metadata, OfferPaymentMetadata.decode(metadata.encode()))
        val pathId = metadata.toPathId(nodeKey)
        assertEquals(metadata, OfferPaymentMetadata.fromPathId(nodeKey, pathId, metadata.paymentHash))
    }

    @Test
    fun `encode - decode v2 metadata with description`() {
        val nodeKey = randomKey()
        val metadata = OfferPaymentMetadata.V2(
            offerId = randomBytes32(),
            amount = 100_000_000.msat,
            preimage = randomBytes32(),
            createdAtSeconds = 0,
            relativeExpirySeconds = null,
            description = "Invoice #: 152043",
            payerKey = randomKey().publicKey(),
            payerNote = null,
            quantity = null,
        )
        assertEquals(metadata, OfferPaymentMetadata.decode(metadata.encode()))
        val pathId = metadata.toPathId(nodeKey)
        assertEquals(metadata, OfferPaymentMetadata.fromPathId(nodeKey, pathId, metadata.paymentHash))
    }

    @Test
    fun `encode - decode v2 metadata with payer note`() {
        val nodeKey = randomKey()
        val metadata = OfferPaymentMetadata.V2(
            offerId = randomBytes32(),
            amount = 100_000_000.msat,
            preimage = randomBytes32(),
            createdAtSeconds = 0,
            relativeExpirySeconds = null,
            description = null,
            payerKey = randomKey().publicKey(),
            payerNote = "Thanks for all the fish",
            quantity = 42,
        )
        assertEquals(metadata, OfferPaymentMetadata.decode(metadata.encode()))
        val pathId = metadata.toPathId(nodeKey)
        assertEquals(metadata, OfferPaymentMetadata.fromPathId(nodeKey, pathId, metadata.paymentHash))
    }

    @Test
    fun `encode - decode v2 metadata with UTF-8 description and payer note`() {
        val nodeKey = randomKey()
        val metadata = OfferPaymentMetadata.V2(
            offerId = randomBytes32(),
            amount = 100_000_000.msat,
            preimage = randomBytes32(),
            createdAtSeconds = 0,
            relativeExpirySeconds = 60,
            description = "法国很棒",
            payerKey = randomKey().publicKey(),
            payerNote = "雷击再次",
            quantity = null,
        )
        assertEquals(metadata, OfferPaymentMetadata.decode(metadata.encode()))
        val pathId = metadata.toPathId(nodeKey)
        assertEquals(metadata, OfferPaymentMetadata.fromPathId(nodeKey, pathId, metadata.paymentHash))
    }

    @Test
    fun `encode - decode v2 metadata with all fields`() {
        val nodeKey = randomKey()
        val metadata = OfferPaymentMetadata.V2(
            offerId = randomBytes32(),
            amount = 100_000_000.msat,
            preimage = randomBytes32(),
            createdAtSeconds = 0,
            relativeExpirySeconds = 30,
            description = "Invoice #: 152043",
            payerKey = randomKey().publicKey(),
            payerNote = "Thanks for all the fish",
            quantity = 2,
        )
        assertEquals(metadata, OfferPaymentMetadata.decode(metadata.encode()))
        val pathId = metadata.toPathId(nodeKey)
        assertEquals(metadata, OfferPaymentMetadata.fromPathId(nodeKey, pathId, metadata.paymentHash))
    }

    @Test
    fun `truncate long description or payerNote`() {
        val longString = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
        // Long description + Null payerNote
        val (_, desc1) = OfferPaymentMetadata.truncateNotes(null, longString)
        assertEquals(64, desc1!!.encodeToByteArray().size)
        // Null description + Long payerNote
        val (payerNote2, _) = OfferPaymentMetadata.truncateNotes(longString, null)
        assertEquals(64, payerNote2!!.encodeToByteArray().size)
        // Long description + Long payerNote
        val (payerNote3, desc3) = OfferPaymentMetadata.truncateNotes(longString, longString)
        assertEquals(32, desc3!!.encodeToByteArray().size)
        assertEquals(32, payerNote3!!.encodeToByteArray().size)
        // Long description + Short payerNote
        val (payerNote4, desc4) = OfferPaymentMetadata.truncateNotes("tea", longString)
        assertEquals(61, desc4!!.encodeToByteArray().size)
        assertEquals(3, payerNote4!!.encodeToByteArray().size)
        assertEquals("tea", payerNote4)
        // Short description + Long payerNote
        val (payerNote5, desc5) = OfferPaymentMetadata.truncateNotes(longString, "tea")
        assertEquals(3, desc5!!.encodeToByteArray().size)
        assertEquals(61, payerNote5!!.encodeToByteArray().size)
        assertEquals("tea", desc5)
        // Short description + Short payerNote
        val (payerNote6, desc6) = OfferPaymentMetadata.truncateNotes("tea", "coffee")
        assertEquals("coffee", desc6)
        assertEquals("tea", payerNote6)
        // String where UTF-8 representation is different than string length.
        val trickyLongString = "Â🏀cdefghijklmnopqrstuvwxyz1234567890abcdefghijklmnopqrstuvwxyz中" // str.length = 63
        val (payerNote7, _) = OfferPaymentMetadata.truncateNotes(trickyLongString, null)
        assertTrue(payerNote7!!.encodeToByteArray().size <= 64)
    }

    @Test
    fun `decode invalid path_id`() {
        val nodeKey = randomKey()
        val metadata = OfferPaymentMetadata.V1(
            offerId = randomBytes32(),
            amount = 50_000_000.msat,
            preimage = randomBytes32(),
            payerKey = randomKey().publicKey(),
            payerNote = null,
            quantity = 1,
            createdAtMillis = 0
        )
        val testCases = listOf(
            ByteVector.empty,
            ByteVector("02deadbeef"), // invalid version
            metadata.toPathId(nodeKey).dropRight(1), // not enough bytes
            metadata.toPathId(nodeKey).concat(ByteVector("deadbeef")), // too many bytes
            metadata.toPathId(randomKey()), // signed with different key
        )
        testCases.forEach {
            assertNull(OfferPaymentMetadata.fromPathId(nodeKey, it, metadata.paymentHash))
        }
    }
}