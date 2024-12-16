package fr.acinq.lightning.db

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.payment.FinalFailure
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.LiquidityAds
import kotlin.test.*

class PaymentsDbTestsCommon : LightningTestSuite() {

    @Test
    fun `receive incoming lightning payment with 1 htlc`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        assertNull(db.getLightningIncomingPayment(pr.paymentHash))

        val channelId = randomBytes32()
        val incoming = Bolt11IncomingPayment(preimage, pr, createdAt = 100)
        db.addIncomingPayment(incoming)
        val pending = db.getLightningIncomingPayment(pr.paymentHash)
        assertIs<Bolt11IncomingPayment>(pending)
        assertEquals(incoming, pending)

        val parts = LightningIncomingPayment.Part.Htlc(200_000.msat, channelId, 1, fundingFee = null, receivedAt = 110)
        db.receiveLightningPayment(pr.paymentHash, listOf(parts))
        val received = db.getLightningIncomingPayment(pr.paymentHash)
        assertNotNull(received)
        assertEquals(pending.addReceivedParts(listOf(parts)), received)
    }

    @Test
    fun `receive incoming lightning payment with several parts`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        assertNull(db.getLightningIncomingPayment(pr.paymentHash))

        val (channelId1, channelId2) = listOf(randomBytes32(), randomBytes32())
        val incoming = Bolt11IncomingPayment(preimage, pr, createdAt = 200)
        db.addIncomingPayment(incoming)
        val pending = db.getLightningIncomingPayment(pr.paymentHash)
        assertIs<Bolt11IncomingPayment>(pending)
        assertEquals(incoming, pending)

        db.receiveLightningPayment(
            pr.paymentHash, listOf(
                LightningIncomingPayment.Part.Htlc(57_000.msat, channelId1, 1, fundingFee = null, receivedAt = 110),
                LightningIncomingPayment.Part.Htlc(43_000.msat, channelId2, 54, fundingFee = null, receivedAt = 110),
            )
        )
        val received = db.getLightningIncomingPayment(pr.paymentHash)
        assertNotNull(received)
        assertEquals(100_000.msat, received.amount)
        assertEquals(0.msat, received.fees)
        assertEquals(2, received.parts.size)
        assertEquals(57_000.msat, received.parts.elementAt(0).amountReceived)
        assertEquals(0.msat, received.parts.elementAt(0).fees)
        assertEquals(channelId1, (received.parts.elementAt(0) as LightningIncomingPayment.Part.Htlc).channelId)
        assertEquals(54, (received.parts.elementAt(1) as LightningIncomingPayment.Part.Htlc).htlcId)
    }

    @Test
    fun `receive several incoming lightning payments with the same payment hash`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        val channelId = randomBytes32()

        val incoming = Bolt11IncomingPayment(preimage, pr, createdAt = 200)
        db.addIncomingPayment(incoming)
        val parts = listOf(
            LightningIncomingPayment.Part.Htlc(200_000.msat, channelId, 1, fundingFee = null, receivedAt = 110),
            LightningIncomingPayment.Part.Htlc(100_000.msat, channelId, 2, fundingFee = null, receivedAt = 150)
        )
        db.receiveLightningPayment(pr.paymentHash, listOf(parts.first()))
        val received1 = db.getLightningIncomingPayment(pr.paymentHash)
        assertNotNull(received1)
        assertEquals(200_000.msat, received1.amount)

        db.receiveLightningPayment(pr.paymentHash, listOf(parts.last()))
        val received2 = db.getLightningIncomingPayment(pr.paymentHash)
        assertNotNull(received2)
        assertEquals(300_000.msat, received2.amount)
        assertEquals(150, received2.completedAt)
        assertEquals(parts, received2.parts)
    }

    @Test
    fun `receive lightning payment with funding fee`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        val incoming = Bolt11IncomingPayment(preimage, pr, createdAt = 200)
        db.addIncomingPayment(incoming)
        val parts = LightningIncomingPayment.Part.Htlc(40_000_000.msat, randomBytes32(), 3, LiquidityAds.FundingFee(10_000_000.msat, TxId(randomBytes32())), receivedAt = 110)
        db.receiveLightningPayment(pr.paymentHash, listOf(parts))
        val received = db.getLightningIncomingPayment(pr.paymentHash)
        assertEquals(40_000_000.msat, received!!.amount)
        assertEquals(10_000_000.msat, received.fees)
    }

    @Test
    fun `reject duplicate payment hash`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        db.addIncomingPayment(Bolt11IncomingPayment(preimage, pr))
        assertFails { db.addIncomingPayment(Bolt11IncomingPayment(preimage, pr)) }
    }

    @Test
    fun `set expired invoices`() = runSuspendTest {
        val (db, preimage, _) = createFixture()
        val pr = createExpiredInvoice(preimage)
        db.addIncomingPayment(Bolt11IncomingPayment(preimage, pr))

        val expired = db.getLightningIncomingPayment(pr.paymentHash)
        assertIs<Bolt11IncomingPayment>(expired)
        assertTrue(expired.isExpired())
        assertEquals(pr, expired.paymentRequest)
        assertEquals(preimage, expired.paymentPreimage)
    }

    @Test
    fun `send outgoing payment`() = runSuspendTest {
        val (db, _, pr) = createFixture()
        val (a, b, c) = listOf(randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey())
        val initialParts = listOf(
            LightningOutgoingPayment.Part(UUID.randomUUID(), 20_000.msat, listOf(HopDesc(a, c, ShortChannelId(42))), LightningOutgoingPayment.Part.Status.Pending, 100),
            LightningOutgoingPayment.Part(UUID.randomUUID(), 30_000.msat, listOf(HopDesc(a, b), HopDesc(b, c)), LightningOutgoingPayment.Part.Status.Pending, 105)
        )
        val initialPayment = LightningOutgoingPayment(
            id = UUID.randomUUID(),
            recipientAmount = 50_000.msat,
            recipient = pr.nodeId,
            details = LightningOutgoingPayment.Details.Normal(pr),
            parts = initialParts,
            status = LightningOutgoingPayment.Status.Pending
        )
        db.addOutgoingPayment(initialPayment)
        // We should never re-use ids.
        assertFails { db.addOutgoingPayment(initialPayment.copy(recipientAmount = 60_000.msat)) }
        assertFails { db.addOutgoingPayment(initialPayment.copy(id = UUID.randomUUID(), parts = initialParts.map { it.copy(id = initialPayment.parts[0].id) })) }

        assertEquals(initialPayment, db.getLightningOutgoingPayment(initialPayment.id))
        assertNull(db.getLightningOutgoingPayment(UUID.randomUUID()))
        initialPayment.parts.forEach { assertEquals(initialPayment, db.getLightningOutgoingPaymentFromPartId(it.id)) }
        assertNull(db.getLightningOutgoingPaymentFromPartId(UUID.randomUUID()))

        // One of the parts fails.
        val onePartFailed = initialPayment.copy(
            parts = listOf(
                initialParts[0].copy(status = LightningOutgoingPayment.Part.Status.Failed(LightningOutgoingPayment.Part.Status.Failed.Failure.TemporaryRemoteFailure, 110)),
                initialParts[1]
            )
        )
        db.completeLightningOutgoingPaymentPart(initialPayment.id, initialPayment.parts[0].id, LightningOutgoingPayment.Part.Status.Failed(LightningOutgoingPayment.Part.Status.Failed.Failure.TemporaryRemoteFailure, 110))
        assertEquals(onePartFailed, db.getLightningOutgoingPayment(initialPayment.id))
        initialPayment.parts.forEach { assertEquals(onePartFailed, db.getLightningOutgoingPaymentFromPartId(it.id)) }

        // We should never update non-existing parts.
        assertFails { db.completeLightningOutgoingPaymentPart(initialPayment.id, UUID.randomUUID(), LightningOutgoingPayment.Part.Status.Failed(LightningOutgoingPayment.Part.Status.Failed.Failure.TemporaryRemoteFailure)) }
        assertFails { db.completeLightningOutgoingPaymentPart(initialPayment.id, UUID.randomUUID(), LightningOutgoingPayment.Part.Status.Succeeded(randomBytes32())) }

        // Other payment parts are added.
        val newParts = listOf(
            LightningOutgoingPayment.Part(UUID.randomUUID(), 5_000.msat, listOf(HopDesc(a, c)), LightningOutgoingPayment.Part.Status.Pending, 115),
            LightningOutgoingPayment.Part(UUID.randomUUID(), 10_000.msat, listOf(HopDesc(a, b)), LightningOutgoingPayment.Part.Status.Pending, 120),
        )
        assertFails { db.addOutgoingLightningParts(UUID.randomUUID(), newParts) }
        assertFails { db.addOutgoingLightningParts(onePartFailed.id, newParts.map { it.copy(id = initialPayment.parts[0].id) }) }
        val withMoreParts = onePartFailed.copy(parts = onePartFailed.parts + newParts)
        db.addOutgoingLightningParts(onePartFailed.id, newParts)
        assertEquals(withMoreParts, db.getLightningOutgoingPayment(initialPayment.id))
        withMoreParts.parts.forEach { assertEquals(withMoreParts, db.getLightningOutgoingPaymentFromPartId(it.id)) }

        // Payment parts succeed.
        val preimage = randomBytes32()
        val partsSettled = withMoreParts.copy(
            parts = listOf(
                withMoreParts.parts[0], // this one was failed
                withMoreParts.parts[1].copy(status = LightningOutgoingPayment.Part.Status.Succeeded(preimage, 125)),
                withMoreParts.parts[2].copy(status = LightningOutgoingPayment.Part.Status.Succeeded(preimage, 126)),
                withMoreParts.parts[3].copy(status = LightningOutgoingPayment.Part.Status.Succeeded(preimage, 127)),
            )
        )
        assertEquals(LightningOutgoingPayment.Status.Pending, partsSettled.status)
        db.completeLightningOutgoingPaymentPart(initialPayment.id, withMoreParts.parts[1].id, LightningOutgoingPayment.Part.Status.Succeeded(preimage, 125))
        db.completeLightningOutgoingPaymentPart(initialPayment.id, withMoreParts.parts[2].id, LightningOutgoingPayment.Part.Status.Succeeded(preimage, 126))
        db.completeLightningOutgoingPaymentPart(initialPayment.id, withMoreParts.parts[3].id, LightningOutgoingPayment.Part.Status.Succeeded(preimage, 127))
        assertEquals(partsSettled, db.getLightningOutgoingPayment(initialPayment.id))
        partsSettled.parts.forEach { assertEquals(partsSettled, db.getLightningOutgoingPaymentFromPartId(it.id)) }

        // Payment succeeds: failed parts will be ignored.
        val paymentSucceeded = partsSettled.copy(
            status = LightningOutgoingPayment.Status.Succeeded(preimage, 130),
            parts = partsSettled.parts.drop(1)
        )
        db.completeLightningOutgoingPayment(initialPayment.id, LightningOutgoingPayment.Status.Succeeded(preimage, 130))
        assertFails { db.completeLightningOutgoingPayment(UUID.randomUUID(), LightningOutgoingPayment.Status.Succeeded(preimage, 130)) }
        assertEquals(paymentSucceeded, db.getLightningOutgoingPayment(initialPayment.id))
        partsSettled.parts.forEach { assertEquals(paymentSucceeded, db.getLightningOutgoingPaymentFromPartId(it.id)) }
    }

    @Test
    fun `outgoing normal payment fee and amount computation`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        val (a, b, c) = listOf(randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey())

        // test normal payment fee computation
        val hops1 = listOf(HopDesc(a, c, ShortChannelId(42)), HopDesc(b, c))
        val hops2 = listOf(HopDesc(a, b))
        val normalPayment = LightningOutgoingPayment(
            id = UUID.randomUUID(),
            recipientAmount = 180_000.msat,
            recipient = pr.nodeId,
            details = LightningOutgoingPayment.Details.Normal(pr),
            parts = listOf(),
            status = LightningOutgoingPayment.Status.Pending
        )
        db.addOutgoingPayment(normalPayment)
        val normalParts = listOf(
            LightningOutgoingPayment.Part(UUID.randomUUID(), amount = 115_000.msat, route = hops1, status = LightningOutgoingPayment.Part.Status.Pending, createdAt = 100),
            LightningOutgoingPayment.Part(UUID.randomUUID(), amount = 75_000.msat, route = hops2, status = LightningOutgoingPayment.Part.Status.Pending, createdAt = 105)
        )
        db.addOutgoingLightningParts(parentId = normalPayment.id, parts = normalParts)
        db.completeLightningOutgoingPaymentPart(normalPayment.id, normalParts[0].id, LightningOutgoingPayment.Part.Status.Succeeded(preimage, 110))
        db.completeLightningOutgoingPaymentPart(normalPayment.id, normalParts[1].id, LightningOutgoingPayment.Part.Status.Succeeded(preimage, 115))
        db.completeLightningOutgoingPayment(normalPayment.id, LightningOutgoingPayment.Status.Succeeded(preimage, 120))
        val normalPaymentInDb = db.getLightningOutgoingPayment(normalPayment.id)

        assertNotNull(normalPaymentInDb)
        assertEquals(10_000.msat, normalPaymentInDb.fees)
        assertEquals(190_000.msat, normalPaymentInDb.amount)
    }

    @Test
    fun `outgoing swap-out payment fee and amount computation`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        val (a, b, c) = listOf(randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey())
        val hops = listOf(HopDesc(a, c, ShortChannelId(42)), HopDesc(b, c))
        // test swap-out payment fee computation
        val swapOutPayment = LightningOutgoingPayment(
            id = UUID.randomUUID(),
            recipientAmount = 150_000.msat,
            recipient = pr.nodeId,
            details = LightningOutgoingPayment.Details.SwapOut("2NCnVWgTuCptq1y7Cie4KwSBADcBqXKTNCR", pr, 15.sat),
            parts = listOf(),
            status = LightningOutgoingPayment.Status.Pending
        )
        db.addOutgoingPayment(swapOutPayment)
        val swapOutParts = listOf(
            LightningOutgoingPayment.Part(UUID.randomUUID(), amount = 157_000.msat, route = hops, status = LightningOutgoingPayment.Part.Status.Pending, createdAt = 100),
        )
        db.addOutgoingLightningParts(parentId = swapOutPayment.id, parts = swapOutParts)
        db.completeLightningOutgoingPaymentPart(swapOutPayment.id, swapOutParts[0].id, LightningOutgoingPayment.Part.Status.Succeeded(preimage, 110))
        db.completeLightningOutgoingPayment(swapOutPayment.id, LightningOutgoingPayment.Status.Succeeded(preimage, 120))
        val swapOutPaymentInDb = db.getLightningOutgoingPayment(swapOutPayment.id)

        assertNotNull(swapOutPaymentInDb)
        // the service receives 150 000 msat
        // the swap amount received by the on-chain address is 135 000 msat
        // the mining fee are contained in the swap-out service fee
        // total fees is 7 000 msat for routing with trampoline + 15 000 msat for the swap-out service
        assertEquals(22_000.msat, swapOutPaymentInDb.fees)
        assertEquals(157_000.msat, swapOutPaymentInDb.amount)
    }

    @Test
    fun `fail outgoing payment`() = runSuspendTest {
        val (db, _, pr) = createFixture()
        val initialParts = listOf(
            LightningOutgoingPayment.Part(UUID.randomUUID(), 20_000.msat, listOf(HopDesc(randomKey().publicKey(), randomKey().publicKey())), LightningOutgoingPayment.Part.Status.Pending, 100),
            LightningOutgoingPayment.Part(UUID.randomUUID(), 30_000.msat, listOf(HopDesc(randomKey().publicKey(), randomKey().publicKey())), LightningOutgoingPayment.Part.Status.Pending, 105)
        )
        val initialPayment = LightningOutgoingPayment(
            id = UUID.randomUUID(),
            recipientAmount = 50_000.msat,
            recipient = pr.nodeId,
            details = LightningOutgoingPayment.Details.Normal(pr),
            parts = initialParts,
            status = LightningOutgoingPayment.Status.Pending
        )
        db.addOutgoingPayment(initialPayment)
        assertEquals(initialPayment, db.getLightningOutgoingPayment(initialPayment.id))

        val partsFailed = initialPayment.copy(
            parts = listOf(
                initialParts[0].copy(status = LightningOutgoingPayment.Part.Status.Failed(LightningOutgoingPayment.Part.Status.Failed.Failure.TemporaryRemoteFailure, 110)),
                initialParts[1].copy(status = LightningOutgoingPayment.Part.Status.Failed(LightningOutgoingPayment.Part.Status.Failed.Failure.TooManyPendingPayments, 111)),
            )
        )
        db.completeLightningOutgoingPaymentPart(initialPayment.id, initialPayment.parts[0].id, LightningOutgoingPayment.Part.Status.Failed(LightningOutgoingPayment.Part.Status.Failed.Failure.TemporaryRemoteFailure, 110))
        db.completeLightningOutgoingPaymentPart(initialPayment.id, initialPayment.parts[1].id, LightningOutgoingPayment.Part.Status.Failed(LightningOutgoingPayment.Part.Status.Failed.Failure.TooManyPendingPayments, 111))
        assertEquals(partsFailed, db.getLightningOutgoingPayment(initialPayment.id))
        initialPayment.parts.forEach { assertEquals(partsFailed, db.getLightningOutgoingPaymentFromPartId(it.id)) }

        val paymentFailed = partsFailed.copy(status = LightningOutgoingPayment.Status.Failed(FinalFailure.RetryExhausted, 120))
        db.completeLightningOutgoingPayment(initialPayment.id, LightningOutgoingPayment.Status.Failed(FinalFailure.RetryExhausted, 120))
        assertFails { db.completeLightningOutgoingPayment(UUID.randomUUID(), LightningOutgoingPayment.Status.Failed(FinalFailure.RetryExhausted, 120)) }
        assertEquals(paymentFailed, db.getLightningOutgoingPayment(initialPayment.id))
        initialPayment.parts.forEach { assertEquals(paymentFailed, db.getLightningOutgoingPaymentFromPartId(it.id)) }
    }

    @Test
    fun `list outgoing payments by payment hash`() = runSuspendTest {
        val (db, _, pr) = createFixture()
        assertTrue(db.listLightningOutgoingPayments(pr.paymentHash).isEmpty())

        val payment1 = LightningOutgoingPayment(UUID.randomUUID(), 50_000.msat, pr.nodeId, LightningOutgoingPayment.Details.Normal(pr))
        db.addOutgoingPayment(payment1)
        assertEquals(listOf(payment1), db.listLightningOutgoingPayments(pr.paymentHash))

        val payment2 = payment1.copy(
            id = UUID.randomUUID(),
            parts = listOf(LightningOutgoingPayment.Part(UUID.randomUUID(), 50_000.msat, listOf(), LightningOutgoingPayment.Part.Status.Pending, 100))
        )
        db.addOutgoingPayment(payment2)
        assertEquals(setOf(payment1, payment2), db.listLightningOutgoingPayments(pr.paymentHash).toSet())
    }

    companion object {
        private val defaultFeatures = Features(
            Feature.VariableLengthOnion to FeatureSupport.Optional,
            Feature.PaymentSecret to FeatureSupport.Optional,
            Feature.BasicMultiPartPayment to FeatureSupport.Optional
        )

        private fun createFixture(): Triple<PaymentsDb, ByteVector32, Bolt11Invoice> {
            val db = InMemoryPaymentsDb()
            val preimage = randomBytes32()
            val pr = createInvoice(preimage)
            return Triple(db, preimage, pr)
        }

        private fun createInvoice(preimage: ByteVector32): Bolt11Invoice {
            return Bolt11Invoice.create(Chain.Mainnet, 150_000.msat, Crypto.sha256(preimage).toByteVector32(), randomKey(), Either.Left("invoice"), CltvExpiryDelta(16), defaultFeatures)
        }

        private fun createExpiredInvoice(preimage: ByteVector32 = randomBytes32()): Bolt11Invoice {
            val now = currentTimestampSeconds()
            return Bolt11Invoice.create(
                Chain.Mainnet,
                150_000.msat,
                Crypto.sha256(preimage).toByteVector32(),
                randomKey(),
                Either.Left("invoice"),
                CltvExpiryDelta(16),
                defaultFeatures,
                expirySeconds = 60,
                timestampSeconds = now - 120
            )
        }
    }

}