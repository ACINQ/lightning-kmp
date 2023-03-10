package fr.acinq.lightning.db

import fr.acinq.bitcoin.*
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.channel.TooManyAcceptedHtlcs
import fr.acinq.lightning.payment.FinalFailure
import fr.acinq.lightning.payment.PaymentRequest
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.TemporaryNodeFailure
import kotlin.test.*

class PaymentsDbTestsCommon : LightningTestSuite() {

    @Test
    fun `receive incoming payment with 1 htlc`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        assertNull(db.getIncomingPayment(pr.paymentHash))

        val channelId = randomBytes32()
        val incoming = IncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr), null, 100)
        db.addIncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr), 100)
        val pending = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(pending)
        assertEquals(incoming, pending)

        db.receivePayment(
            pr.paymentHash, setOf(
                IncomingPayment.ReceivedWith.LightningPayment(
                    amount = 200_000.msat,
                    channelId = channelId,
                    htlcId = 1L
                )
            ), 110
        )
        val received = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(received)
        assertEquals(
            pending.copy(
                received = IncomingPayment.Received(
                    setOf(
                        IncomingPayment.ReceivedWith.LightningPayment(
                            amount = 200_000.msat,
                            channelId = channelId,
                            htlcId = 1L
                        )
                    ), 110
                )
            ), received
        )
    }

    @Test
    fun `receive incoming payment with several parts`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        assertNull(db.getIncomingPayment(pr.paymentHash))

        val (channelId1, channelId2, channelId3) = listOf(randomBytes32(), randomBytes32(), randomBytes32())
        val incoming = IncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr), null, 200)
        db.addIncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr), 200)
        val pending = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(pending)
        assertEquals(incoming, pending)

        db.receivePayment(
            pr.paymentHash, setOf(
                IncomingPayment.ReceivedWith.LightningPayment(amount = 57_000.msat, channelId = channelId1, htlcId = 1L),
                IncomingPayment.ReceivedWith.LightningPayment(amount = 43_000.msat, channelId = channelId2, htlcId = 54L),
                IncomingPayment.ReceivedWith.NewChannel(amount = 99_000.msat, channelId = channelId3, serviceFee = 1_000.msat, id = UUID.randomUUID())
            ), 110
        )
        val received = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(received)
        assertEquals(199_000.msat, received.amount)
        assertEquals(1_000.msat, received.fees)
        assertEquals(3, received.received!!.receivedWith.size)
        assertEquals(57_000.msat, received.received!!.receivedWith.elementAt(0).amount)
        assertEquals(0.msat, received.received!!.receivedWith.elementAt(0).fees)
        assertEquals(channelId1, (received.received!!.receivedWith.elementAt(0) as IncomingPayment.ReceivedWith.LightningPayment).channelId)
        assertEquals(54L, (received.received!!.receivedWith.elementAt(1) as IncomingPayment.ReceivedWith.LightningPayment).htlcId)
        assertEquals(channelId3, (received.received!!.receivedWith.elementAt(2) as IncomingPayment.ReceivedWith.NewChannel).channelId)
    }

    @Test
    fun `receiving several payments on the same payment hash is additive`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        val channelId = randomBytes32()

        db.addIncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr), 200)
        db.receivePayment(
            pr.paymentHash, setOf(
                IncomingPayment.ReceivedWith.LightningPayment(
                    amount = 200_000.msat,
                    channelId = channelId,
                    htlcId = 1L
                )
            ), 110
        )
        val received1 = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(received1)
        assertNotNull(received1.received)
        assertEquals(200_000.msat, received1.amount)

        db.receivePayment(
            pr.paymentHash, setOf(
                IncomingPayment.ReceivedWith.LightningPayment(
                    amount = 100_000.msat,
                    channelId = channelId,
                    htlcId = 2L
                )
            ), 150
        )
        val received2 = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(received2)
        assertNotNull(received2.received)
        assertEquals(300_000.msat, received2.amount)
        assertEquals(150, received2.received!!.receivedAt)
        assertEquals(
            setOf(
                IncomingPayment.ReceivedWith.LightningPayment(
                    amount = 200_000.msat,
                    channelId = channelId,
                    htlcId = 1L
                ),
                IncomingPayment.ReceivedWith.LightningPayment(
                    amount = 100_000.msat,
                    channelId = channelId,
                    htlcId = 2L
                )
            ), received2.received!!.receivedWith
        )
    }

    @Test
    fun `received total amount accounts for the fee`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        db.addIncomingPayment(preimage, IncomingPayment.Origin.Invoice(pr), 200)
        db.receivePayment(
            pr.paymentHash, setOf(
                IncomingPayment.ReceivedWith.NewChannel(
                    id = UUID.randomUUID(),
                    amount = 500_000.msat,
                    serviceFee = 15_000.msat,
                    channelId = randomBytes32()
                )
            ), 110
        )
        val received1 = db.getIncomingPayment(pr.paymentHash)
        assertNotNull(received1?.received)
        assertEquals(500_000.msat, received1!!.amount)
        assertEquals(15_000.msat, received1.fees)
    }

    @Test
    fun `simultaneously add and receive incoming payment`() = runSuspendTest {
        val db = InMemoryPaymentsDb()
        val preimage = randomBytes32()
        val channelId = randomBytes32()
        val origin = IncomingPayment.Origin.DualSwapIn(setOf(OutPoint(randomBytes32(), 3)))
        val receivedWith = setOf(IncomingPayment.ReceivedWith.NewChannel(amount = 50_000_000.msat, serviceFee = 1_234.msat, channelId = channelId, id = UUID.randomUUID()))
        assertNull(db.getIncomingPayment(randomBytes32()))

        db.addAndReceivePayment(preimage = preimage, origin = origin, receivedWith = receivedWith)
        val payment = db.getIncomingPayment(Crypto.sha256(preimage).toByteVector32())
        assertNotNull(payment)
        assertEquals(origin, payment.origin)
        assertNotNull(payment.received)
        assertEquals(receivedWith, payment.received?.receivedWith)
        assertEquals(50_000_000.msat, payment.amount)
        assertEquals(1234.msat, payment.fees)
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
        assertTrue(expired.isExpired())
        assertEquals(IncomingPayment.Origin.Invoice(pr), expired.origin)
        assertEquals(preimage, expired.preimage)
    }

    @Test
    fun `send outgoing payment`() = runSuspendTest {
        val (db, _, pr) = createFixture()
        val (a, b, c) = listOf(randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey())
        val initialParts = listOf(
            OutgoingPayment.LightningPart(UUID.randomUUID(), 20_000.msat, listOf(HopDesc(a, c, ShortChannelId(42))), OutgoingPayment.LightningPart.Status.Pending, 100),
            OutgoingPayment.LightningPart(UUID.randomUUID(), 30_000.msat, listOf(HopDesc(a, b), HopDesc(b, c)), OutgoingPayment.LightningPart.Status.Pending, 105)
        )
        val initialPayment = OutgoingPayment(
            id = UUID.randomUUID(),
            recipientAmount = 50_000.msat,
            recipient = pr.nodeId,
            details = OutgoingPayment.Details.Normal(pr),
            parts = initialParts,
            status = OutgoingPayment.Status.Pending
        )
        db.addOutgoingPayment(initialPayment)
        // We should never re-use ids.
        assertFails { db.addOutgoingPayment(initialPayment.copy(recipientAmount = 60_000.msat)) }
        assertFails { db.addOutgoingPayment(initialPayment.copy(id = UUID.randomUUID(), parts = initialParts.map { it.copy(id = initialPayment.parts[0].id) })) }

        assertEquals(initialPayment, db.getOutgoingPayment(initialPayment.id))
        assertNull(db.getOutgoingPayment(UUID.randomUUID()))
        initialPayment.parts.forEach { assertEquals(initialPayment, db.getOutgoingPaymentFromPartId(it.id)) }
        assertNull(db.getOutgoingPaymentFromPartId(UUID.randomUUID()))

        // One of the parts fails.
        val onePartFailed = initialPayment.copy(
            parts = listOf(
                initialParts[0].copy(status = OutgoingPayment.LightningPart.Status.Failed(TemporaryNodeFailure.code, TemporaryNodeFailure.message, 110)),
                initialParts[1]
            )
        )
        db.completeOutgoingLightningPart(initialPayment.parts[0].id, Either.Right(TemporaryNodeFailure), 110)
        assertEquals(onePartFailed, db.getOutgoingPayment(initialPayment.id))
        initialPayment.parts.forEach { assertEquals(onePartFailed, db.getOutgoingPaymentFromPartId(it.id)) }

        // We should never update non-existing parts.
        assertFails { db.completeOutgoingLightningPart(UUID.randomUUID(), Either.Right(TemporaryNodeFailure)) }
        assertFails { db.completeOutgoingLightningPart(UUID.randomUUID(), randomBytes32()) }

        // Other payment parts are added.
        val newParts = listOf(
            OutgoingPayment.LightningPart(UUID.randomUUID(), 5_000.msat, listOf(HopDesc(a, c)), OutgoingPayment.LightningPart.Status.Pending, 115),
            OutgoingPayment.LightningPart(UUID.randomUUID(), 10_000.msat, listOf(HopDesc(a, b)), OutgoingPayment.LightningPart.Status.Pending, 120),
        )
        assertFails { db.addOutgoingLightningParts(UUID.randomUUID(), newParts) }
        assertFails { db.addOutgoingLightningParts(onePartFailed.id, newParts.map { it.copy(id = initialPayment.parts[0].id) }) }
        val withMoreParts = onePartFailed.copy(parts = onePartFailed.parts + newParts)
        db.addOutgoingLightningParts(onePartFailed.id, newParts)
        assertEquals(withMoreParts, db.getOutgoingPayment(initialPayment.id))
        withMoreParts.parts.forEach { assertEquals(withMoreParts, db.getOutgoingPaymentFromPartId(it.id)) }

        // Payment parts succeed.
        val preimage = randomBytes32()
        val partsSettled = withMoreParts.copy(
            parts = listOf(
                withMoreParts.parts[0], // this one was failed
                (withMoreParts.parts[1] as OutgoingPayment.LightningPart).copy(status = OutgoingPayment.LightningPart.Status.Succeeded(preimage, 125)),
                (withMoreParts.parts[2] as OutgoingPayment.LightningPart).copy(status = OutgoingPayment.LightningPart.Status.Succeeded(preimage, 126)),
                (withMoreParts.parts[3] as OutgoingPayment.LightningPart).copy(status = OutgoingPayment.LightningPart.Status.Succeeded(preimage, 127)),
            )
        )
        assertEquals(OutgoingPayment.Status.Pending, partsSettled.status)
        db.completeOutgoingLightningPart(withMoreParts.parts[1].id, preimage, 125)
        db.completeOutgoingLightningPart(withMoreParts.parts[2].id, preimage, 126)
        db.completeOutgoingLightningPart(withMoreParts.parts[3].id, preimage, 127)
        assertEquals(partsSettled, db.getOutgoingPayment(initialPayment.id))
        partsSettled.parts.forEach { assertEquals(partsSettled, db.getOutgoingPaymentFromPartId(it.id)) }

        // Payment succeeds: failed parts will be ignored.
        val paymentSucceeded = partsSettled.copy(
            status = OutgoingPayment.Status.Completed.Succeeded.OffChain(preimage, 130),
            parts = partsSettled.parts.drop(1)
        )
        db.completeOutgoingPaymentOffchain(initialPayment.id, preimage, 130)
        assertFails { db.completeOutgoingPaymentOffchain(UUID.randomUUID(), preimage, 130) }
        assertEquals(paymentSucceeded, db.getOutgoingPayment(initialPayment.id))
        partsSettled.parts.forEach { assertEquals(paymentSucceeded, db.getOutgoingPaymentFromPartId(it.id)) }
    }

    @Test
    fun `outgoing payment from closed channel`() = runSuspendTest {
        // When a channel is closed, a corresponding OutgoingPayment is
        // automatically injected into the database (the user's ledger).
        // The payment.recipientAmount is set to the channel's local balance
        // at the time the channel is closed.
        val (db, _, pr) = createFixture()
        val paymentId = UUID.randomUUID()
        val channelBalance = 100_000_000.msat
        val pendingPayment = OutgoingPayment(
            id = paymentId,
            recipientAmount = channelBalance,
            recipient = pr.nodeId,
            details = OutgoingPayment.Details.ChannelClosing(
                channelId = randomBytes32(),
                closingAddress = "",
                isSentToDefaultAddress = true
            ),
            parts = listOf(),
            status = OutgoingPayment.Status.Pending
        )
        db.addOutgoingPayment(pendingPayment)

        // Fees should be zero at this point, since the payment is still Pending.
        // It's not completed until the transactions are confirmed on the blockchain.
        assertEquals(pendingPayment.fees, MilliSatoshi(0))

        val fundsLost = Satoshi(100)
        val closingTx = OutgoingPayment.ClosingTxPart(
            id = UUID.randomUUID(),
            txId = randomBytes32(),
            claimed = channelBalance.truncateToSatoshi() - fundsLost,
            closingType = ChannelClosingType.Mutual,
            createdAt = currentTimestampMillis()
        )
        db.completeOutgoingPaymentForClosing(id = paymentId, parts = listOf(closingTx), completedAt = currentTimestampMillis())

        val completedPayment = db.getOutgoingPayment(paymentId)
        assertNotNull(completedPayment)
        assertEquals(listOf(closingTx), completedPayment.parts)

        // Now that the payment has been settled on-chain, the fees can be calculated.
        // If we failed to claim any amount of the channel balance,
        // we can consider these as fees (in a generic sense).
        // The UI is expected to provide a more detailed explanation.
        assertEquals(fundsLost.toMilliSatoshi(), completedPayment.fees)
        assertEquals(channelBalance, completedPayment.amount)
    }

    @Test
    fun `outgoing payment from closed channel without parts`() = runSuspendTest {
        val (db, _, pr) = createFixture()
        val paymentId = UUID.randomUUID()
        val channelBalance = 100_000_000.msat
        val pendingPayment = OutgoingPayment(
            id = paymentId,
            recipientAmount = channelBalance,
            recipient = pr.nodeId,
            details = OutgoingPayment.Details.ChannelClosing(
                channelId = randomBytes32(),
                closingAddress = "",
                isSentToDefaultAddress = true
            ),
            parts = listOf(),
            status = OutgoingPayment.Status.Pending
        )
        db.addOutgoingPayment(pendingPayment)
        db.completeOutgoingPaymentForClosing(id = paymentId, parts = listOf(), completedAt = currentTimestampMillis())

        val completedPayment = db.getOutgoingPayment(paymentId)
        assertNotNull(completedPayment)
        assertEquals(channelBalance, completedPayment.amount)
    }

    @Test
    fun `outgoing normal payment fee and amount computation`() = runSuspendTest {
        val (db, preimage, pr) = createFixture()
        val (a, b, c) = listOf(randomKey().publicKey(), randomKey().publicKey(), randomKey().publicKey())

        // test normal payment fee computation
        val hops1 = listOf(HopDesc(a, c, ShortChannelId(42)), HopDesc(b, c))
        val hops2 = listOf(HopDesc(a, b))
        val normalPayment = OutgoingPayment(
            id = UUID.randomUUID(),
            recipientAmount = 180_000.msat,
            recipient = pr.nodeId,
            details = OutgoingPayment.Details.Normal(pr),
            parts = listOf(),
            status = OutgoingPayment.Status.Pending
        )
        db.addOutgoingPayment(normalPayment)
        val normalParts = listOf(
            OutgoingPayment.LightningPart(UUID.randomUUID(), amount = 115_000.msat, route = hops1, status = OutgoingPayment.LightningPart.Status.Pending, createdAt = 100),
            OutgoingPayment.LightningPart(UUID.randomUUID(), amount = 75_000.msat, route = hops2, status = OutgoingPayment.LightningPart.Status.Pending, createdAt = 105)
        )
        db.addOutgoingLightningParts(parentId = normalPayment.id, parts = normalParts)
        db.completeOutgoingLightningPart(normalParts[0].id, preimage, 110)
        db.completeOutgoingLightningPart(normalParts[1].id, preimage, 115)
        db.completeOutgoingPaymentOffchain(normalPayment.id, preimage, 120)
        val normalPaymentInDb = db.getOutgoingPayment(normalPayment.id)

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
        val swapOutPayment = OutgoingPayment(
            id = UUID.randomUUID(),
            recipientAmount = 150_000.msat,
            recipient = pr.nodeId,
            details = OutgoingPayment.Details.SwapOut("2NCnVWgTuCptq1y7Cie4KwSBADcBqXKTNCR", pr, 15.sat),
            parts = listOf(),
            status = OutgoingPayment.Status.Pending
        )
        db.addOutgoingPayment(swapOutPayment)
        val swapOutParts = listOf(
            OutgoingPayment.LightningPart(UUID.randomUUID(), amount = 157_000.msat, route = hops, status = OutgoingPayment.LightningPart.Status.Pending, createdAt = 100),
        )
        db.addOutgoingLightningParts(parentId = swapOutPayment.id, parts = swapOutParts)
        db.completeOutgoingLightningPart(swapOutParts[0].id, preimage, 110)
        db.completeOutgoingPaymentOffchain(swapOutPayment.id, preimage, 120)
        val swapOutPaymentInDb = db.getOutgoingPayment(swapOutPayment.id)

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
            OutgoingPayment.LightningPart(UUID.randomUUID(), 20_000.msat, listOf(HopDesc(randomKey().publicKey(), randomKey().publicKey())), OutgoingPayment.LightningPart.Status.Pending, 100),
            OutgoingPayment.LightningPart(UUID.randomUUID(), 30_000.msat, listOf(HopDesc(randomKey().publicKey(), randomKey().publicKey())), OutgoingPayment.LightningPart.Status.Pending, 105)
        )
        val initialPayment = OutgoingPayment(
            id = UUID.randomUUID(),
            recipientAmount = 50_000.msat,
            recipient = pr.nodeId,
            details = OutgoingPayment.Details.Normal(pr),
            parts = initialParts,
            status = OutgoingPayment.Status.Pending
        )
        db.addOutgoingPayment(initialPayment)
        assertEquals(initialPayment, db.getOutgoingPayment(initialPayment.id))

        val channelId = randomBytes32()
        val partsFailed = initialPayment.copy(
            parts = listOf(
                initialParts[0].copy(status = OutgoingPayment.LightningPart.Status.Failed(TemporaryNodeFailure.code, TemporaryNodeFailure.message, 110)),
                initialParts[1].copy(status = OutgoingPayment.LightningPart.Status.Failed(null, TooManyAcceptedHtlcs(channelId, 10).details(), 111)),
            )
        )
        db.completeOutgoingLightningPart(initialPayment.parts[0].id, Either.Right(TemporaryNodeFailure), 110)
        db.completeOutgoingLightningPart(initialPayment.parts[1].id, Either.Left(TooManyAcceptedHtlcs(channelId, 10)), 111)
        assertEquals(partsFailed, db.getOutgoingPayment(initialPayment.id))
        initialPayment.parts.forEach { assertEquals(partsFailed, db.getOutgoingPaymentFromPartId(it.id)) }

        val paymentFailed = partsFailed.copy(status = OutgoingPayment.Status.Completed.Failed(FinalFailure.NoRouteToRecipient, 120))
        db.completeOutgoingPaymentOffchain(initialPayment.id, FinalFailure.NoRouteToRecipient, 120)
        assertFails { db.completeOutgoingPaymentOffchain(UUID.randomUUID(), FinalFailure.NoRouteToRecipient, 120) }
        assertEquals(paymentFailed, db.getOutgoingPayment(initialPayment.id))
        initialPayment.parts.forEach { assertEquals(paymentFailed, db.getOutgoingPaymentFromPartId(it.id)) }
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
            parts = listOf(OutgoingPayment.LightningPart(UUID.randomUUID(), 50_000.msat, listOf(), OutgoingPayment.LightningPart.Status.Pending, 100))
        )
        db.addOutgoingPayment(payment2)
        assertEquals(setOf(payment1, payment2), db.listOutgoingPayments(pr.paymentHash).toSet())
    }

    companion object {
        private val defaultFeatures = Features(
            Feature.VariableLengthOnion to FeatureSupport.Optional,
            Feature.PaymentSecret to FeatureSupport.Optional,
            Feature.BasicMultiPartPayment to FeatureSupport.Optional
        )

        private fun createFixture(): Triple<PaymentsDb, ByteVector32, PaymentRequest> {
            val db = InMemoryPaymentsDb()
            val preimage = randomBytes32()
            val pr = createInvoice(preimage)
            return Triple(db, preimage, pr)
        }

        private fun createInvoice(preimage: ByteVector32): PaymentRequest {
            return PaymentRequest.create(Block.LivenetGenesisBlock.hash, 150_000.msat, Crypto.sha256(preimage).toByteVector32(), randomKey(), Either.Left("invoice"), CltvExpiryDelta(16), defaultFeatures)
        }

        private fun createExpiredInvoice(preimage: ByteVector32 = randomBytes32()): PaymentRequest {
            val now = currentTimestampSeconds()
            return PaymentRequest.create(Block.LivenetGenesisBlock.hash, 150_000.msat, Crypto.sha256(preimage).toByteVector32(), randomKey(), Either.Left("invoice"), CltvExpiryDelta(16), defaultFeatures, expirySeconds = 60, timestampSeconds = now - 120)
        }
    }

}