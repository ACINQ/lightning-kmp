package fr.acinq.eclair.db

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.*
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.Eclair.randomKey
import fr.acinq.eclair.channel.TooManyAcceptedHtlcs
import fr.acinq.eclair.payment.FinalFailure
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.tests.utils.runSuspendTest
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.TemporaryNodeFailure
import kotlin.test.*

class PaymentsDbTestsCommon : EclairTestSuite() {

    @Test
    fun `receive incoming payments`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        assertNull(db.getIncomingPayment(pr.paymentHash))

        val incoming = IncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr), IncomingPayment.Status.Pending, 100)
        db.addIncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr), 100)
        val pending = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(pending)
        assertEquals(incoming, pending)

        db.receivePayment(pr.paymentHash, 200_000.msat, IncomingPayment.ReceivedWith.LightningPayment, 110)
        val received = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(received)
        assertEquals(pending.copy(status = IncomingPayment.Status.Received(200_000.msat, IncomingPayment.ReceivedWith.LightningPayment, 110)), received)
    }

    @Test
    fun `reject duplicate payment hash`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        db.addIncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr))
        assertFails { db.addIncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr)) }
    }

    @Test
    fun `set expired invoices`() = runSuspendTest {
        val (db, preimage, _) = createFixture()
        val pr = createExpiredInvoice(preimage)
        db.addIncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr))

        val expired = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(expired)
        assertEquals(IncomingPayment.Status.Expired, expired.status)
        assertEquals(IncomingPayment.Origin.Invoice(pr), expired.origin)
        assertEquals(preimage, expired.preimage)
    }

    @Test
    fun `list received payments`() = runSuspendTest {
        val (db, pendingPreimage, pending) = createFixture()
        db.addIncomingPayment(pendingPreimage, IncomingPayment.Origin.Invoice(pending))
        assertEquals(IncomingPayment.Status.Pending, db.getIncomingPayment(pending.paymentHash)?.status)

        val expiredPreimage = randomBytes32()
        val expired = createExpiredInvoice(expiredPreimage)
        db.addIncomingPayment(expiredPreimage, IncomingPayment.Origin.Invoice(expired))
        assertEquals(IncomingPayment.Status.Expired, db.getIncomingPayment(expired.paymentHash)?.status)

        val preimage1 = randomBytes32()
        val received1 = createInvoice(preimage1)
        db.addIncomingPayment(preimage1, IncomingPayment.Origin.Invoice(received1))
        db.receivePayment(received1.paymentHash, 180_000.msat, IncomingPayment.ReceivedWith.LightningPayment, 50)
        val payment1 = db.getIncomingPayment(received1.paymentHash)!!

        val preimage2 = randomBytes32()
        val received2 = createInvoice(preimage2)
        db.addIncomingPayment(preimage2, IncomingPayment.Origin.SwapIn(150_000.msat, "1PwLgmRdDjy5GAKWyp8eyAC4SFzWuboLLb", received2))
        db.receivePayment(received2.paymentHash, 180_000.msat, IncomingPayment.ReceivedWith.NewChannel(100.msat, channelId = null), 60)
        val payment2 = db.getIncomingPayment(received2.paymentHash)!!

        val preimage3 = randomBytes32()
        val received3 = createInvoice(preimage3)
        db.addIncomingPayment(preimage3, IncomingPayment.Origin.Invoice(received3))
        db.receivePayment(received3.paymentHash, 180_000.msat, IncomingPayment.ReceivedWith.LightningPayment, 70)
        val payment3 = db.getIncomingPayment(received3.paymentHash)!!

        val all = db.listReceivedPayments(count = 10, skip = 0)
        assertEquals(listOf(payment3, payment2, payment1), all)
        assertTrue(all.all { it.status is IncomingPayment.Status.Received })

        val skipped = db.listReceivedPayments(count = 5, skip = 2)
        assertEquals(listOf(payment1), skipped)

        val count = db.listReceivedPayments(count = 1, skip = 1)
        assertEquals(listOf(payment2), count)

        val filtered = db.listReceivedPayments(count = 5, skip = 0, filters = setOf(PaymentTypeFilter.Normal))
        assertEquals(listOf(payment3, payment1), filtered)

        val filteredAndSkipped = db.listReceivedPayments(count = 5, skip = 1, filters = setOf(PaymentTypeFilter.Normal))
        assertEquals(listOf(payment1), filteredAndSkipped)
    }

    @Test
    fun `send outgoing payment`() = runSuspendTest {
        val (db, _, pr) = createFixture()
        val (a, b, c) = listOf(randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey())
        val initialPayment = OutgoingPayment(
            UUID.randomUUID(),
            50_000.msat,
            pr.nodeId,
            OutgoingPayment.Details.Normal(pr),
            listOf(
                OutgoingPayment.Part(UUID.randomUUID(), 20_000.msat, listOf(HopDesc(a, c, ShortChannelId(42))), OutgoingPayment.Part.Status.Pending, 100),
                OutgoingPayment.Part(UUID.randomUUID(), 30_000.msat, listOf(HopDesc(a, b), HopDesc(b, c)), OutgoingPayment.Part.Status.Pending, 105)
            ),
            OutgoingPayment.Status.Pending
        )
        db.addOutgoingPayment(initialPayment)
        // We should never re-use ids.
        assertFails { db.addOutgoingPayment(initialPayment.copy(recipientAmount = 60_000.msat)) }
        assertFails { db.addOutgoingPayment(initialPayment.copy(id = UUID.randomUUID(), parts = initialPayment.parts.map { it.copy(id = initialPayment.parts[0].id) })) }

        assertEquals(initialPayment, db.getOutgoingPayment(initialPayment.id))
        assertNull(db.getOutgoingPayment(UUID.randomUUID()))
        initialPayment.parts.forEach { assertEquals(initialPayment, db.getOutgoingPart(it.id)) }
        assertNull(db.getOutgoingPart(UUID.randomUUID()))

        // One of the parts fails.
        val onePartFailed = initialPayment.copy(
            parts = listOf(
                initialPayment.parts[0].copy(status = OutgoingPayment.Part.Status.Failed(Either.Right(TemporaryNodeFailure), 110)),
                initialPayment.parts[1]
            )
        )
        db.updateOutgoingPart(initialPayment.parts[0].id, Either.Right(TemporaryNodeFailure), 110)
        assertEquals(onePartFailed, db.getOutgoingPayment(initialPayment.id))
        initialPayment.parts.forEach { assertEquals(onePartFailed, db.getOutgoingPart(it.id)) }

        // We should never update non-existing parts.
        assertFails { db.updateOutgoingPart(UUID.randomUUID(), Either.Right(TemporaryNodeFailure)) }
        assertFails { db.updateOutgoingPart(UUID.randomUUID(), randomBytes32()) }

        // Other payment parts are added.
        val newParts = listOf(
            OutgoingPayment.Part(UUID.randomUUID(), 5_000.msat, listOf(HopDesc(a, c)), OutgoingPayment.Part.Status.Pending, 115),
            OutgoingPayment.Part(UUID.randomUUID(), 10_000.msat, listOf(HopDesc(a, b)), OutgoingPayment.Part.Status.Pending, 120),
        )
        assertFails { db.addOutgoingParts(UUID.randomUUID(), newParts) }
        assertFails { db.addOutgoingParts(onePartFailed.id, newParts.map { it.copy(id = initialPayment.parts[0].id) }) }
        val withMoreParts = onePartFailed.copy(parts = onePartFailed.parts + newParts)
        db.addOutgoingParts(onePartFailed.id, newParts)
        assertEquals(withMoreParts, db.getOutgoingPayment(initialPayment.id))
        withMoreParts.parts.forEach { assertEquals(withMoreParts, db.getOutgoingPart(it.id)) }

        // Payment parts succeed.
        val preimage = randomBytes32()
        val partsSettled = withMoreParts.copy(
            parts = listOf(
                withMoreParts.parts[0], // this one was failed
                withMoreParts.parts[1].copy(status = OutgoingPayment.Part.Status.Succeeded(preimage, 125)),
                withMoreParts.parts[2].copy(status = OutgoingPayment.Part.Status.Succeeded(preimage, 126)),
                withMoreParts.parts[3].copy(status = OutgoingPayment.Part.Status.Succeeded(preimage, 127)),
            )
        )
        assertEquals(OutgoingPayment.Status.Pending, partsSettled.status)
        db.updateOutgoingPart(withMoreParts.parts[1].id, preimage, 125)
        db.updateOutgoingPart(withMoreParts.parts[2].id, preimage, 126)
        db.updateOutgoingPart(withMoreParts.parts[3].id, preimage, 127)
        assertEquals(partsSettled, db.getOutgoingPayment(initialPayment.id))
        partsSettled.parts.forEach { assertEquals(partsSettled, db.getOutgoingPart(it.id)) }

        // Payment succeeds: failed parts are removed.
        val paymentSucceeded = partsSettled.copy(
            status = OutgoingPayment.Status.Succeeded(preimage, 130),
            parts = partsSettled.parts.drop(1)
        )
        db.updateOutgoingPayment(initialPayment.id, preimage, 130)
        assertFails { db.updateOutgoingPayment(UUID.randomUUID(), preimage, 130) }
        assertEquals(paymentSucceeded, db.getOutgoingPayment(initialPayment.id))
        assertNull(db.getOutgoingPart(partsSettled.parts[0].id))
        partsSettled.parts.drop(1).forEach { assertEquals(paymentSucceeded, db.getOutgoingPart(it.id)) }
    }

    @Test
    fun `fail outgoing payment`() = runSuspendTest {
        val (db, _, pr) = createFixture()
        val initialPayment = OutgoingPayment(
            UUID.randomUUID(),
            50_000.msat,
            pr.nodeId,
            OutgoingPayment.Details.Normal(pr),
            listOf(
                OutgoingPayment.Part(UUID.randomUUID(), 20_000.msat, listOf(HopDesc(randomKey().publicKey(), randomKey().publicKey())), OutgoingPayment.Part.Status.Pending, 100),
                OutgoingPayment.Part(UUID.randomUUID(), 30_000.msat, listOf(HopDesc(randomKey().publicKey(), randomKey().publicKey())), OutgoingPayment.Part.Status.Pending, 105)
            ),
            OutgoingPayment.Status.Pending
        )
        db.addOutgoingPayment(initialPayment)
        assertEquals(initialPayment, db.getOutgoingPayment(initialPayment.id))

        val channelId = randomBytes32()
        val partsFailed = initialPayment.copy(
            parts = listOf(
                initialPayment.parts[0].copy(status = OutgoingPayment.Part.Status.Failed(Either.Right(TemporaryNodeFailure), 110)),
                initialPayment.parts[1].copy(status = OutgoingPayment.Part.Status.Failed(Either.Left(TooManyAcceptedHtlcs(channelId, 10)), 111)),
            )
        )
        db.updateOutgoingPart(initialPayment.parts[0].id, Either.Right(TemporaryNodeFailure), 110)
        db.updateOutgoingPart(initialPayment.parts[1].id, Either.Left(TooManyAcceptedHtlcs(channelId, 10)), 111)
        assertEquals(partsFailed, db.getOutgoingPayment(initialPayment.id))
        initialPayment.parts.forEach { assertEquals(partsFailed, db.getOutgoingPart(it.id)) }

        val paymentFailed = partsFailed.copy(status = OutgoingPayment.Status.Failed(FinalFailure.NoRouteToRecipient, 120))
        db.updateOutgoingPayment(initialPayment.id, FinalFailure.NoRouteToRecipient, 120)
        assertFails { db.updateOutgoingPayment(UUID.randomUUID(), FinalFailure.NoRouteToRecipient, 120) }
        assertEquals(paymentFailed, db.getOutgoingPayment(initialPayment.id))
        initialPayment.parts.forEach { assertEquals(paymentFailed, db.getOutgoingPart(it.id)) }
    }

    @Test
    fun `list outgoing payments by payment hash`() = runSuspendTest {
        val (db, _, pr) = createFixture()
        assertTrue(db.listOutgoingPayments(pr.paymentHash).isEmpty())

        val payment1 = OutgoingPayment(UUID.randomUUID(), 50_000.msat, pr.nodeId, OutgoingPayment.Details.Normal(pr))
        db.addOutgoingPayment(payment1)
        assertEquals(listOf(payment1), db.listOutgoingPayments(pr.paymentHash))

        val payment2 = payment1.copy(
            id = UUID.randomUUID(),
            parts = listOf(OutgoingPayment.Part(UUID.randomUUID(), 50_000.msat, listOf(), OutgoingPayment.Part.Status.Pending, 100))
        )
        db.addOutgoingPayment(payment2)
        assertEquals(setOf(payment1, payment2), db.listOutgoingPayments(pr.paymentHash).toSet())
    }

    @Test
    fun `list outgoing payments`() = runSuspendTest {
        val (db, _, _) = createFixture()
        assertTrue(db.listOutgoingPayments(count = 10, skip = 0).isEmpty())
        assertTrue(db.listOutgoingPayments(count = 5, skip = 5).isEmpty())

        val pending1 = OutgoingPayment(UUID.randomUUID(), 50_000.msat, randomKey().publicKey(), OutgoingPayment.Details.Normal(createInvoice(randomBytes32())))
        val pending2 = OutgoingPayment(UUID.randomUUID(), 55_000.msat, randomKey().publicKey(), OutgoingPayment.Details.KeySend(randomBytes32()))
        val pending3 = OutgoingPayment(UUID.randomUUID(), 30_000.msat, randomKey().publicKey(), OutgoingPayment.Details.Normal(createInvoice(randomBytes32())))
        val pending4 = OutgoingPayment(UUID.randomUUID(), 60_000.msat, randomKey().publicKey(), OutgoingPayment.Details.SwapOut("1PwLgmRdDjy5GAKWyp8eyAC4SFzWuboLLb", randomBytes32()))
        val pending5 = OutgoingPayment(UUID.randomUUID(), 55_000.msat, randomKey().publicKey(), OutgoingPayment.Details.KeySend(randomBytes32()))
        val pending6 = OutgoingPayment(UUID.randomUUID(), 45_000.msat, randomKey().publicKey(), OutgoingPayment.Details.Normal(createInvoice(randomBytes32())))
        val pending7 = OutgoingPayment(UUID.randomUUID(), 35_000.msat, randomKey().publicKey(), OutgoingPayment.Details.Normal(createInvoice(randomBytes32())))
        listOf(pending1, pending2, pending3, pending4, pending5, pending6, pending7).forEach { db.addOutgoingPayment(it) }

        // Pending payments should not be listed.
        assertTrue(db.listOutgoingPayments(count = 10, skip = 0).isEmpty())

        db.updateOutgoingPayment(pending1.id, randomBytes32(), completedAt = 100)
        val payment1 = db.getOutgoingPayment(pending1.id)!!
        db.updateOutgoingPayment(pending2.id, FinalFailure.NoRouteToRecipient, completedAt = 101)
        val payment2 = db.getOutgoingPayment(pending2.id)!!
        // payment3 is still pending
        db.updateOutgoingPayment(pending4.id, FinalFailure.InsufficientBalance, completedAt = 102)
        val payment4 = db.getOutgoingPayment(pending4.id)!!
        db.updateOutgoingPayment(pending5.id, randomBytes32(), completedAt = 103)
        val payment5 = db.getOutgoingPayment(pending5.id)!!
        db.updateOutgoingPayment(pending6.id, randomBytes32(), completedAt = 104)
        val payment6 = db.getOutgoingPayment(pending6.id)!!
        db.updateOutgoingPayment(pending7.id, randomBytes32(), completedAt = 105)
        val payment7 = db.getOutgoingPayment(pending7.id)!!

        assertEquals(listOf(payment7, payment6, payment5, payment4, payment2, payment1), db.listOutgoingPayments(count = 10, skip = 0))
        assertEquals(listOf(payment7, payment6), db.listOutgoingPayments(count = 2, skip = 0))
        assertEquals(listOf(payment5, payment4), db.listOutgoingPayments(count = 2, skip = 2))
        assertEquals(listOf(payment5, payment4, payment2), db.listOutgoingPayments(count = 10, skip = 0, setOf(PaymentTypeFilter.KeySend, PaymentTypeFilter.SwapOut)))
        assertEquals(listOf(payment5), db.listOutgoingPayments(count = 1, skip = 0, setOf(PaymentTypeFilter.KeySend, PaymentTypeFilter.SwapOut)))
        assertEquals(listOf(payment4, payment2), db.listOutgoingPayments(count = 5, skip = 1, setOf(PaymentTypeFilter.KeySend, PaymentTypeFilter.SwapOut)))
    }

    @Test
    fun `list payments`() = runSuspendTest {
        val (db, _, _) = createFixture()

        val (preimage1, preimage2, preimage3, preimage4) = listOf(randomBytes32(), randomBytes32(), randomBytes32(), randomBytes32())
        val incoming1 = IncomingPayment(preimage1, IncomingPayment.Origin.Invoice(createInvoice(preimage1)), IncomingPayment.Status.Pending, createdAt = 20)
        val incoming2 = IncomingPayment(preimage2, IncomingPayment.Origin.SwapIn(20_000.msat, "1PwLgmRdDjy5GAKWyp8eyAC4SFzWuboLLb", null), IncomingPayment.Status.Pending, createdAt = 21)
        val incoming3 = IncomingPayment(preimage3, IncomingPayment.Origin.Invoice(createInvoice(preimage3)), IncomingPayment.Status.Pending, createdAt = 22)
        val incoming4 = IncomingPayment(preimage4, IncomingPayment.Origin.Invoice(createInvoice(preimage4)), IncomingPayment.Status.Pending, createdAt = 23)
        listOf(incoming1, incoming2, incoming3, incoming4).forEach { db.addIncomingPayment(it.preimage, it.origin, it.createdAt) }

        val outgoing1 = OutgoingPayment(UUID.randomUUID(), 50_000.msat, randomKey().publicKey(), OutgoingPayment.Details.Normal(createInvoice(randomBytes32())))
        val outgoing2 = OutgoingPayment(UUID.randomUUID(), 55_000.msat, randomKey().publicKey(), OutgoingPayment.Details.KeySend(randomBytes32()))
        val outgoing3 = OutgoingPayment(UUID.randomUUID(), 60_000.msat, randomKey().publicKey(), OutgoingPayment.Details.SwapOut("1PwLgmRdDjy5GAKWyp8eyAC4SFzWuboLLb", randomBytes32()))
        val outgoing4 = OutgoingPayment(UUID.randomUUID(), 45_000.msat, randomKey().publicKey(), OutgoingPayment.Details.Normal(createInvoice(randomBytes32())))
        val outgoing5 = OutgoingPayment(UUID.randomUUID(), 35_000.msat, randomKey().publicKey(), OutgoingPayment.Details.Normal(createInvoice(randomBytes32())))
        listOf(outgoing1, outgoing2, outgoing3, outgoing4, outgoing5).forEach { db.addOutgoingPayment(it) }

        // Pending payments should not be listed.
        assertTrue(db.listPayments(count = 10, skip = 0).isEmpty())

        db.receivePayment(incoming1.paymentHash, 20_000.msat, IncomingPayment.ReceivedWith.LightningPayment, receivedAt = 100)
        val inFinal1 = incoming1.copy(status = IncomingPayment.Status.Received(20_000.msat, IncomingPayment.ReceivedWith.LightningPayment, 100))
        db.updateOutgoingPayment(outgoing1.id, randomBytes32(), completedAt = 102)
        val outFinal1 = db.getOutgoingPayment(outgoing1.id)!!
        db.updateOutgoingPayment(outgoing2.id, FinalFailure.UnknownError, completedAt = 103)
        val outFinal2 = db.getOutgoingPayment(outgoing2.id)!!
        db.receivePayment(incoming2.paymentHash, 25_000.msat, IncomingPayment.ReceivedWith.NewChannel(250.msat, channelId = null), receivedAt = 105)
        val inFinal2 = incoming2.copy(status = IncomingPayment.Status.Received(25_000.msat, IncomingPayment.ReceivedWith.NewChannel(250.msat, channelId = null), 105))
        db.updateOutgoingPayment(outgoing3.id, randomBytes32(), completedAt = 106)
        val outFinal3 = db.getOutgoingPayment(outgoing3.id)!!
        db.receivePayment(incoming4.paymentHash, 10_000.msat, IncomingPayment.ReceivedWith.LightningPayment, receivedAt = 110)
        val inFinal4 = incoming4.copy(status = IncomingPayment.Status.Received(10_000.msat, IncomingPayment.ReceivedWith.LightningPayment, 110))
        db.updateOutgoingPayment(outgoing5.id, randomBytes32(), completedAt = 112)
        val outFinal5 = db.getOutgoingPayment(outgoing5.id)!!
        // outgoing4 and incoming3 are still pending.

        assertEquals(listOf(outFinal5, inFinal4, outFinal3, inFinal2, outFinal2, outFinal1, inFinal1), db.listPayments(count = 10, skip = 0))
        assertEquals(listOf(outFinal1, inFinal1), db.listPayments(count = 5, skip = 5))
        assertEquals(listOf(outFinal5, inFinal4, outFinal1, inFinal1), db.listPayments(count = 10, skip = 0, setOf(PaymentTypeFilter.Normal)))
        assertEquals(listOf(outFinal5, inFinal4, outFinal2, outFinal1, inFinal1), db.listPayments(count = 10, skip = 0, setOf(PaymentTypeFilter.Normal, PaymentTypeFilter.KeySend)))
        assertEquals(listOf(outFinal1, inFinal1), db.listPayments(count = 5, skip = 3, setOf(PaymentTypeFilter.Normal, PaymentTypeFilter.KeySend)))
        assertEquals(listOf(), db.listPayments(count = 5, skip = 5, setOf(PaymentTypeFilter.Normal, PaymentTypeFilter.KeySend)))
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
            return PaymentRequest.create(Block.LivenetGenesisBlock.hash, 150_000.msat, Crypto.sha256(preimage).toByteVector32(), randomKey(), "invoice", CltvExpiryDelta(16), defaultFeatures, expirySeconds = 60, timestampSeconds = now - 120)
        }
    }

}