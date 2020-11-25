package fr.acinq.eclair.db

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.*
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.Eclair.randomKey
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.tests.utils.runSuspendTest
import fr.acinq.eclair.utils.currentTimestampMillis
import fr.acinq.eclair.utils.currentTimestampSeconds
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.toByteVector32
import kotlin.test.*

class PaymentsDbTestsCommon : EclairTestSuite() {

    @Test
    fun `receive incoming payments`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        assertNull(db.getIncomingPayment(pr.paymentHash))

        db.addIncomingPayment(pr, preimage, IncomingPayment.Details.Normal)
        val pending = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(pending)
        assertEquals(IncomingPayment.Status.Pending, pending.status)
        assertEquals(IncomingPayment.Details.Normal, pending.details)
        assertEquals(preimage, pending.paymentPreimage)
        assertEquals(pr, pending.paymentRequest)

        val now = currentTimestampMillis()
        db.receivePayment(pr.paymentHash, 200_000.msat, now)
        val received = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(received)
        assertEquals(pending.copy(status = IncomingPayment.Status.Received(200_000.msat, now)), received)
    }

    @Test
    fun `reject duplicate payment hash`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        db.addIncomingPayment(pr, preimage, IncomingPayment.Details.Normal)
        assertFails { db.addIncomingPayment(pr, preimage, IncomingPayment.Details.Normal) }
    }

    @Test
    fun `set expired invoices`() = runSuspendTest {
        val (db, preimage, _) = createFixture()
        val pr = createExpiredInvoice(preimage)
        db.addIncomingPayment(pr, preimage, IncomingPayment.Details.Normal)

        val expired = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(expired)
        assertEquals(IncomingPayment.Status.Expired, expired.status)
        assertEquals(IncomingPayment.Details.Normal, expired.details)
        assertEquals(preimage, expired.paymentPreimage)
        assertEquals(pr, expired.paymentRequest)
    }

    @Test
    fun `list received payments`() = runSuspendTest {
        val (db, pendingPreimage, pending) = createFixture()
        db.addIncomingPayment(pending, pendingPreimage, IncomingPayment.Details.Normal)
        assertEquals(IncomingPayment.Status.Pending, db.getIncomingPayment(pending.paymentHash)?.status)

        val expiredPreimage = randomBytes32()
        val expired = createExpiredInvoice(expiredPreimage)
        db.addIncomingPayment(expired, expiredPreimage, IncomingPayment.Details.Normal)
        assertEquals(IncomingPayment.Status.Expired, db.getIncomingPayment(expired.paymentHash)?.status)

        val preimage1 = randomBytes32()
        val received1 = createInvoice(preimage1)
        db.addIncomingPayment(received1, preimage1, IncomingPayment.Details.Normal)
        db.receivePayment(received1.paymentHash, 180_000.msat, 50)

        val preimage2 = randomBytes32()
        val received2 = createInvoice(preimage2)
        db.addIncomingPayment(received2, preimage2, IncomingPayment.Details.SwapIn("1PwLgmRdDjy5GAKWyp8eyAC4SFzWuboLLb"))
        db.receivePayment(received2.paymentHash, 180_000.msat, 60)

        val preimage3 = randomBytes32()
        val received3 = createInvoice(preimage3)
        db.addIncomingPayment(received3, preimage3, IncomingPayment.Details.Normal)
        db.receivePayment(received3.paymentHash, 180_000.msat, 70)

        val all = db.listReceivedPayments(count = 10, skip = 0)
        assertEquals(setOf(received1, received2, received3), all.map { it.paymentRequest }.toSet())
        assertTrue(all.all { it.status is IncomingPayment.Status.Received })

        val skipped = db.listReceivedPayments(count = 5, skip = 2)
        assertEquals(setOf(received1), skipped.map { it.paymentRequest }.toSet())

        val count = db.listReceivedPayments(count = 1, skip = 1)
        assertEquals(setOf(received2), count.map { it.paymentRequest }.toSet())

        val filtered = db.listReceivedPayments(count = 5, skip = 0, filters = setOf(PaymentTypeFilter.Normal))
        assertEquals(setOf(received1, received3), filtered.map { it.paymentRequest }.toSet())

        val filteredAndSkipped = db.listReceivedPayments(count = 5, skip = 1, filters = setOf(PaymentTypeFilter.Normal))
        assertEquals(setOf(received1), filteredAndSkipped.map { it.paymentRequest }.toSet())
    }

    companion object {
        private val defaultFeatures = Features(
            setOf(
                ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
                ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Optional),
                ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional)
            )
        )

        private fun createFixture(): Triple<PaymentsDb, ByteVector32, PaymentRequest> {
            val db = InMemoryPaymentsDb()
            val preimage = randomBytes32()
            val pr = createInvoice(preimage)
            return Triple(db, preimage, pr)
        }

        private fun createInvoice(preimage: ByteVector32): PaymentRequest {
            return PaymentRequest.create(Block.LivenetGenesisBlock.hash, 150_000.msat, Crypto.sha256(preimage).toByteVector32(), randomKey(), "invoice", CltvExpiryDelta(16), defaultFeatures)
        }

        private fun createExpiredInvoice(preimage: ByteVector32 = randomBytes32()): PaymentRequest {
            val now = currentTimestampSeconds()
            return PaymentRequest.create(Block.LivenetGenesisBlock.hash, 150_000.msat, Crypto.sha256(preimage).toByteVector32(), randomKey(), "invoice", CltvExpiryDelta(16), defaultFeatures, expirySeconds = 60, timestamp = now - 120)
        }
    }

}