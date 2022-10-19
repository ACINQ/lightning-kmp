package fr.acinq.lightning.payment

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.*
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.TestsHelper.processSameState
import fr.acinq.lightning.crypto.sphinx.FailurePacket
import fr.acinq.lightning.crypto.sphinx.Sphinx
import fr.acinq.lightning.db.InMemoryPaymentsDb
import fr.acinq.lightning.db.OutgoingPayment
import fr.acinq.lightning.db.OutgoingPaymentsDb
import fr.acinq.lightning.io.SendPayment
import fr.acinq.lightning.io.SendPaymentNormal
import fr.acinq.lightning.io.SendPaymentSwapOut
import fr.acinq.lightning.io.WrappedChannelCommand
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.tests.utils.runSuspendTest
import fr.acinq.lightning.transactions.CommitmentSpec
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*
import kotlin.test.*

class OutgoingPaymentHandlerTestsCommon : LightningTestSuite() {

    private val defaultWalletParams = WalletParams(NodeUri(TestConstants.Bob.nodeParams.nodeId, "bob.com", 9735), TestConstants.trampolineFees, InvoiceDefaultRoutingFees(1_000.msat, 100, CltvExpiryDelta(144)))

    @Test
    fun `invalid payment amount`() = runSuspendTest {
        val (alice, _) = TestsHelper.reachNormal()
        val invoice = makeInvoice(amount = 100_000.msat, supportsTrampoline = true)
        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams, defaultWalletParams, InMemoryPaymentsDb())
        val payment = SendPaymentNormal(UUID.randomUUID(), MilliSatoshi(-1), invoice.nodeId, OutgoingPayment.Details.Normal(invoice))
        val result = outgoingPaymentHandler.sendPayment(payment, mapOf(), alice.currentBlockHeight)
        assertFailureEquals(result as OutgoingPaymentHandler.Failure, OutgoingPaymentHandler.Failure(payment, FinalFailure.InvalidPaymentAmount.toPaymentFailure()))
        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        assertNull(outgoingPaymentHandler.db.getOutgoingPayment(payment.paymentId))
    }

    @Test
    fun `features not supported`() = runSuspendTest {
        val (alice, _) = TestsHelper.reachNormal()
        val features = Features(
            Feature.ChannelType to FeatureSupport.Mandatory,
            Feature.VariableLengthOnion to FeatureSupport.Mandatory,
            Feature.PaymentSecret to FeatureSupport.Mandatory,
            Feature.BasicMultiPartPayment to FeatureSupport.Optional,
            Feature.DualFunding to FeatureSupport.Mandatory
        )
        // The following invoice requires payment_metadata.
        val invoice1 = PaymentRequestTestsCommon.createInvoiceUnsafe(features = Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory, Feature.PaymentSecret to FeatureSupport.Mandatory, Feature.PaymentMetadata to FeatureSupport.Mandatory))
        // The following invoice requires unknown feature bit 188.
        val invoice2 = PaymentRequestTestsCommon.createInvoiceUnsafe(features = Features(mapOf(Feature.VariableLengthOnion to FeatureSupport.Mandatory, Feature.PaymentSecret to FeatureSupport.Mandatory), setOf(UnknownFeature(188))))
        for (invoice in listOf(invoice1, invoice2)) {
            val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams.copy(features = features), defaultWalletParams, InMemoryPaymentsDb())
            val payment = SendPaymentNormal(UUID.randomUUID(), 15_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))
            val result = outgoingPaymentHandler.sendPayment(payment, mapOf(), alice.currentBlockHeight)
            assertFailureEquals(result as OutgoingPaymentHandler.Failure, OutgoingPaymentHandler.Failure(payment, FinalFailure.FeaturesNotSupported.toPaymentFailure()))
            assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
            assertNull(outgoingPaymentHandler.db.getOutgoingPayment(payment.paymentId))
        }
    }

    @Test
    fun `no available channels`() = runSuspendTest {
        val (alice, _) = TestsHelper.reachNormal()
        val invoice = makeInvoice(amount = 100_000.msat, supportsTrampoline = true)
        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams, defaultWalletParams, InMemoryPaymentsDb())
        val payment = SendPaymentNormal(UUID.randomUUID(), 100_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))
        val result = outgoingPaymentHandler.sendPayment(payment, mapOf(alice.channelId to Offline(alice.state)), alice.currentBlockHeight)
        assertFailureEquals(result as OutgoingPaymentHandler.Failure, OutgoingPaymentHandler.Failure(payment, FinalFailure.NoAvailableChannels.toPaymentFailure()))
        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))

        val dbPayment = outgoingPaymentHandler.db.getOutgoingPayment(payment.paymentId)
        assertNotNull(dbPayment)
        assertEquals(100_000.msat, dbPayment.recipientAmount)
        assertEquals(invoice.nodeId, dbPayment.recipient)
        assertTrue(dbPayment.status is OutgoingPayment.Status.Completed.Failed)
        assertEquals(FinalFailure.NoAvailableChannels, (dbPayment.status as OutgoingPayment.Status.Completed.Failed).reason)
        assertTrue(dbPayment.parts.isEmpty())
    }

    @Test
    fun `insufficient funds`() = runSuspendTest {
        val (alice, _) = TestsHelper.reachNormal()
        val amount = alice.commitments.availableBalanceForSend() + 10.msat
        val invoice = makeInvoice(amount = amount, supportsTrampoline = true)
        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams, defaultWalletParams, InMemoryPaymentsDb())
        val payment = SendPaymentNormal(UUID.randomUUID(), amount, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))
        val result = outgoingPaymentHandler.sendPayment(payment, mapOf(alice.channelId to alice.state), alice.currentBlockHeight)
        assertFailureEquals(result as OutgoingPaymentHandler.Failure, OutgoingPaymentHandler.Failure(payment, FinalFailure.InsufficientBalance.toPaymentFailure()))
        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))

        val dbPayment = outgoingPaymentHandler.db.getOutgoingPayment(payment.paymentId)
        assertNotNull(dbPayment)
        assertEquals(amount, dbPayment.recipientAmount)
        assertTrue(dbPayment.status is OutgoingPayment.Status.Completed.Failed)
        assertEquals(FinalFailure.InsufficientBalance, (dbPayment.status as OutgoingPayment.Status.Completed.Failed).reason)
        assertTrue(dbPayment.parts.isEmpty())
    }

    @Test
    fun `invoice already paid`() = runSuspendTest {
        val channels = makeChannels()
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultWalletParams, InMemoryPaymentsDb())
        val invoice = makeInvoice(amount = 100_000.msat, supportsTrampoline = true)
        val payment = SendPaymentNormal(UUID.randomUUID(), 100_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))
        val result = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val (channelId, add) = filterAddHtlcCommands(result).first()
        outgoingPaymentHandler.processAddSettled(createRemoteFulfill(channelId, add, randomBytes32())) as OutgoingPaymentHandler.Success

        val duplicatePayment = payment.copy(paymentId = UUID.randomUUID())
        val error = outgoingPaymentHandler.sendPayment(duplicatePayment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Failure
        assertEquals(error.failure.reason, FinalFailure.AlreadyPaid)
    }

    @Test
    fun `channel restrictions -- maxAcceptedHtlcs`() = runSuspendTest {
        var (alice, _) = TestsHelper.reachNormal()
        alice = alice.copy(state = alice.state.copy(commitments = alice.commitments.copy(remoteParams = alice.commitments.remoteParams.copy(maxAcceptedHtlcs = 1))))
        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams, defaultWalletParams, InMemoryPaymentsDb())

        run {
            // Send payment 1 of 2: this should work because we're still under the maxAcceptedHtlcs.
            val invoice = makeInvoice(amount = null, supportsTrampoline = true)
            val payment = SendPaymentNormal(UUID.randomUUID(), 100_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))
            val result = outgoingPaymentHandler.sendPayment(payment, mapOf(alice.channelId to alice.state), alice.currentBlockHeight)
            assertTrue { result is OutgoingPaymentHandler.Progress }

            val progress = result as OutgoingPaymentHandler.Progress
            assertEquals(1, result.actions.size)
            val processResult = alice.processSameState(progress.actions.first().channelCommand)
            assertTrue { processResult.second.filterIsInstance<ChannelAction.ProcessCmdRes>().isEmpty() }
            alice = processResult.first
            assertNotNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))

            val dbPayment = outgoingPaymentHandler.db.getOutgoingPayment(payment.paymentId)
            assertNotNull(dbPayment)
            assertEquals(OutgoingPayment.Status.Pending, dbPayment.status)
            assertEquals(1, dbPayment.parts.size)
            assertTrue(dbPayment.parts.all { (it as OutgoingPayment.LightningPart).status is OutgoingPayment.LightningPart.Status.Pending })
        }
        run {
            // Send payment 2 of 2: this should exceed the configured maxAcceptedHtlcs.
            val invoice = makeInvoice(amount = null, supportsTrampoline = true)
            val payment = SendPaymentNormal(UUID.randomUUID(), 50_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))
            val result1 = outgoingPaymentHandler.sendPayment(payment, mapOf(alice.channelId to alice.state), alice.currentBlockHeight)
            assertTrue { result1 is OutgoingPaymentHandler.Progress }

            val progress = result1 as OutgoingPaymentHandler.Progress
            assertEquals(1, result1.actions.size)
            val cmdAdd = progress.actions.first().channelCommand
            val processResult = alice.processSameState(cmdAdd)
            alice = processResult.first

            val addFailure = processResult.second.filterIsInstance<ChannelAction.ProcessCmdRes.AddFailed>().firstOrNull()
            assertNotNull(addFailure)
            // Now the channel error gets sent back to the OutgoingPaymentHandler.
            val result2 = outgoingPaymentHandler.processAddFailed(alice.channelId, addFailure, mapOf(alice.channelId to alice.state))
            val expected = OutgoingPaymentHandler.Failure(payment, OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Left(TooManyAcceptedHtlcs(alice.channelId, 1)))))
            assertFailureEquals(result2 as OutgoingPaymentHandler.Failure, expected)

            assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
            assertDbPaymentFailed(outgoingPaymentHandler.db, payment.paymentId, 1)
        }
    }

    @Test
    fun `channel restrictions -- maxHtlcValueInFlight`() = runSuspendTest {
        var (alice, _) = TestsHelper.reachNormal()
        val maxHtlcValueInFlightMsat = 150_000L
        alice = alice.copy(state = alice.state.copy(commitments = alice.commitments.copy(remoteParams = alice.commitments.remoteParams.copy(maxHtlcValueInFlightMsat = maxHtlcValueInFlightMsat))))
        val outgoingPaymentHandler = OutgoingPaymentHandler(alice.staticParams.nodeParams, defaultWalletParams, InMemoryPaymentsDb())

        run {
            // Send payment 1 of 2: this should work because we're still under the maxHtlcValueInFlightMsat.
            val invoice = makeInvoice(amount = null, supportsTrampoline = true)
            val payment = SendPaymentNormal(UUID.randomUUID(), 100_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))
            val result = outgoingPaymentHandler.sendPayment(payment, mapOf(alice.channelId to alice.state), alice.currentBlockHeight)
            assertTrue { result is OutgoingPaymentHandler.Progress }

            val progress = result as OutgoingPaymentHandler.Progress
            assertEquals(1, result.actions.size)
            val processResult = alice.processSameState(progress.actions.first().channelCommand)
            assertTrue { processResult.second.filterIsInstance<ChannelAction.ProcessCmdRes>().isEmpty() }
            alice = processResult.first
            assertNotNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))

            val dbPayment = outgoingPaymentHandler.db.getOutgoingPayment(payment.paymentId)
            assertNotNull(dbPayment)
            assertEquals(OutgoingPayment.Status.Pending, dbPayment.status)
            assertEquals(1, dbPayment.parts.size)
            assertTrue(dbPayment.parts.all { (it as OutgoingPayment.LightningPart).status is OutgoingPayment.LightningPart.Status.Pending })
        }
        run {
            // Send payment 2 of 2: this should exceed the configured maxHtlcValueInFlightMsat.
            val invoice = makeInvoice(amount = null, supportsTrampoline = true)
            val payment = SendPaymentNormal(UUID.randomUUID(), 100_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))
            val result1 = outgoingPaymentHandler.sendPayment(payment, mapOf(alice.channelId to alice.state), alice.currentBlockHeight)
            assertTrue { result1 is OutgoingPaymentHandler.Progress }

            val progress = result1 as OutgoingPaymentHandler.Progress
            assertEquals(1, result1.actions.size)
            val cmdAdd = progress.actions.first().channelCommand
            val processResult = alice.processSameState(cmdAdd)
            alice = processResult.first

            val addFailure = processResult.second.filterIsInstance<ChannelAction.ProcessCmdRes.AddFailed>().firstOrNull()
            assertNotNull(addFailure)
            // Now the channel error gets sent back to the OutgoingPaymentHandler.
            val result2 = outgoingPaymentHandler.processAddFailed(alice.channelId, addFailure, mapOf(alice.channelId to alice.state))
            val expected = OutgoingPaymentHandler.Failure(payment, OutgoingPaymentFailure(FinalFailure.NoAvailableChannels, listOf(Either.Left(HtlcValueTooHighInFlight(alice.channelId, maxHtlcValueInFlightMsat.toULong(), 200_000.msat)))))
            assertFailureEquals(result2 as OutgoingPaymentHandler.Failure, expected)

            assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
            assertDbPaymentFailed(outgoingPaymentHandler.db, payment.paymentId, 1)
        }
    }

    @Test
    fun `successful first attempt -- single part`() = runSuspendTest {
        val recipientKey = randomKey()
        val invoice = makeInvoice(amount = 195_000.msat, supportsTrampoline = true, privKey = recipientKey)
        val payment = SendPaymentNormal(UUID.randomUUID(), 200_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice)) // we slightly overpay the invoice amount
        testSinglePartTrampolinePayment(payment, invoice, recipientKey)
    }

    @Test
    fun `successful first attempt -- single part + backwards-compatibility trampoline bit`() = runSuspendTest {
        val recipientKey = randomKey()
        val invoice = run {
            // Invoices generated by older versions of wallets based on lightning-kmp will generate invoices with the following feature bits.
            val invoiceFeatures = mapOf(
                Feature.VariableLengthOnion to FeatureSupport.Optional,
                Feature.PaymentSecret to FeatureSupport.Mandatory,
                Feature.BasicMultiPartPayment to FeatureSupport.Optional,
                Feature.TrampolinePayment to FeatureSupport.Optional
            )
            PaymentRequest.create(
                chainHash = Block.LivenetGenesisBlock.hash,
                amount = 195_000.msat,
                paymentHash = randomBytes32(),
                privateKey = recipientKey,
                description = "trampoline backwards-compatibility",
                minFinalCltvExpiryDelta = PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
                features = Features(invoiceFeatures.toMap()),
            )
        }
        val payment = SendPaymentNormal(UUID.randomUUID(), 200_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice)) // we slightly overpay the invoice amount
        testSinglePartTrampolinePayment(payment, invoice, recipientKey)
    }

    private suspend fun testSinglePartTrampolinePayment(payment: SendPayment, invoice: PaymentRequest, recipientKey: PrivateKey) {
        val channels = makeChannels()
        val walletParams = defaultWalletParams.copy(trampolineFees = listOf(TrampolineFees(3.sat, 10_000, CltvExpiryDelta(144))))
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, walletParams, InMemoryPaymentsDb())

        val result = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds = filterAddHtlcCommands(result)
        assertEquals(1, adds.size)
        val (channelId, add) = adds.first()
        assertEquals(205_000.msat, add.amount)
        assertEquals(payment.paymentHash, add.paymentHash)

        // The trampoline node should receive the right forwarding information.
        val (outerB, innerB, packetC) = PaymentPacketTestsCommon.decryptNodeRelay(makeUpdateAddHtlc(channelId, add), TestConstants.Bob.nodeParams.nodePrivateKey)
        assertEquals(205_000.msat, outerB.amount)
        assertEquals(205_000.msat, outerB.totalAmount)
        assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + CltvExpiryDelta(144) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, outerB.expiry)
        assertEquals(200_000.msat, innerB.amountToForward)
        assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, innerB.outgoingCltv)
        assertEquals(payment.recipient, innerB.outgoingNodeId)
        assertNull(innerB.invoiceRoutingInfo)
        assertNull(innerB.invoiceFeatures)
        assertNull(innerB.paymentSecret)

        // The recipient should receive the right amount and expiry.
        val payloadBytesC = Sphinx.peel(recipientKey, payment.paymentHash, packetC, OnionRoutingPacket.TrampolinePacketLength).right!!
        val payloadC = PaymentOnion.FinalPayload.read(payloadBytesC.payload.toByteArray())
        assertEquals(200_000.msat, payloadC.amount)
        assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, payloadC.expiry)
        assertEquals(payloadC.amount, payloadC.totalAmount)
        assertEquals(invoice.paymentSecret, payloadC.paymentSecret)

        val preimage = randomBytes32()
        val success = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(channelId, add, preimage)) as OutgoingPaymentHandler.Success
        assertEquals(preimage, success.preimage)
        assertEquals(5_000.msat, success.payment.fees)
        assertEquals(200_000.msat, success.payment.recipientAmount)
        assertEquals(invoice.nodeId, success.payment.recipient)
        assertEquals(invoice.paymentHash, success.payment.paymentHash)
        assertEquals(OutgoingPayment.Details.Normal(invoice), success.payment.details)
        assertEquals(preimage, (success.payment.status as OutgoingPayment.Status.Completed.Succeeded.OffChain).preimage)
        assertEquals(1, success.payment.parts.size)
        val part = success.payment.parts.first() as OutgoingPayment.LightningPart
        assertNotEquals(part.id, payment.paymentId)
        assertEquals(205_000.msat, part.amount)
        assertEquals(preimage, (part.status as OutgoingPayment.LightningPart.Status.Succeeded).preimage)

        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        assertDbPaymentSucceeded(outgoingPaymentHandler.db, payment.paymentId, amount = 200_000.msat, fees = 5_000.msat, partsCount = 1)
    }

    @Test
    fun `successful first attempt -- multiple parts`() = runSuspendTest {
        val channels = makeChannels()
        val walletParams = defaultWalletParams.copy(trampolineFees = listOf(TrampolineFees(10.sat, 0, CltvExpiryDelta(144))))
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, walletParams, InMemoryPaymentsDb())
        val recipientKey = randomKey()
        val invoice = makeInvoice(amount = null, supportsTrampoline = true, privKey = recipientKey)
        val payment = SendPaymentNormal(UUID.randomUUID(), 300_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))

        val result = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds = filterAddHtlcCommands(result)
        assertEquals(2, adds.size)
        assertEquals(310_000.msat, adds.map { it.second.amount }.sum())
        adds.forEach { assertEquals(payment.paymentHash, it.second.paymentHash) }

        adds.forEach { (channelId, add) ->
            // The trampoline node should receive the right forwarding information.
            val (outerB, innerB, packetC) = PaymentPacketTestsCommon.decryptNodeRelay(makeUpdateAddHtlc(channelId, add), TestConstants.Bob.nodeParams.nodePrivateKey)
            assertEquals(add.amount, outerB.amount)
            assertEquals(310_000.msat, outerB.totalAmount)
            assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + CltvExpiryDelta(144) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, outerB.expiry)
            assertEquals(300_000.msat, innerB.amountToForward)
            assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, innerB.outgoingCltv)
            assertEquals(payment.recipient, innerB.outgoingNodeId)
            assertNull(innerB.invoiceRoutingInfo)
            assertNull(innerB.invoiceFeatures)
            assertNull(innerB.paymentSecret)

            // The recipient should receive the right amount and expiry.
            val payloadBytesC = Sphinx.peel(recipientKey, payment.paymentHash, packetC, OnionRoutingPacket.TrampolinePacketLength).right!!
            val payloadC = PaymentOnion.FinalPayload.read(payloadBytesC.payload.toByteArray())
            assertEquals(300_000.msat, payloadC.amount)
            assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, payloadC.expiry)
            assertEquals(payloadC.amount, payloadC.totalAmount)
            assertEquals(invoice.paymentSecret, payloadC.paymentSecret)
        }

        val preimage = randomBytes32()
        val (channelId1, add1) = adds[0]
        val fulfill1 = createRemoteFulfill(channelId1, add1, preimage)
        val success1 = outgoingPaymentHandler.processAddSettled(fulfill1)
        assertEquals(OutgoingPaymentHandler.PreimageReceived(payment, preimage), success1)
        val (channelId2, add2) = adds[1]
        val fulfill2 = ChannelAction.ProcessCmdRes.AddSettledFulfill(add2.paymentId, makeUpdateAddHtlc(channelId2, add2), ChannelAction.HtlcResult.Fulfill.OnChainFulfill(preimage))
        val success2 = outgoingPaymentHandler.processAddSettled(fulfill2) as OutgoingPaymentHandler.Success
        assertEquals(preimage, success2.preimage)
        assertEquals(10_000.msat, success2.payment.fees)
        assertEquals(300_000.msat, success2.payment.recipientAmount)
        assertEquals(invoice.nodeId, success2.payment.recipient)
        assertEquals(invoice.paymentHash, success2.payment.paymentHash)
        assertEquals(OutgoingPayment.Details.Normal(invoice), success2.payment.details)
        assertEquals(preimage, (success2.payment.status as OutgoingPayment.Status.Completed.Succeeded.OffChain).preimage)
        assertEquals(2, success2.payment.parts.size)
        assertEquals(310_000.msat, success2.payment.parts.map { (it as OutgoingPayment.LightningPart).amount }.sum())
        assertEquals(setOf(preimage), success2.payment.parts.map { ((it as OutgoingPayment.LightningPart).status as OutgoingPayment.LightningPart.Status.Succeeded).preimage }.toSet())

        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        assertDbPaymentSucceeded(outgoingPaymentHandler.db, payment.paymentId, amount = 300_000.msat, fees = 10_000.msat, partsCount = 2)
    }

    @Test
    fun `successful first attempt -- multiple parts + legacy recipient`() = runSuspendTest {
        val channels = makeChannels()
        val walletParams = defaultWalletParams.copy(trampolineFees = listOf(TrampolineFees(10.sat, 0, CltvExpiryDelta(144))))
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, walletParams, InMemoryPaymentsDb())
        val recipientKey = randomKey()
        val extraHops = listOf(listOf(PaymentRequest.TaggedField.ExtraHop(randomKey().publicKey(), ShortChannelId(42), 10.msat, 100, CltvExpiryDelta(48))))
        val invoice = makeInvoice(amount = null, supportsTrampoline = false, privKey = recipientKey, extraHops = extraHops)
        val payment = SendPaymentNormal(UUID.randomUUID(), 300_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))

        val result = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds = filterAddHtlcCommands(result)
        assertEquals(2, adds.size)
        assertEquals(310_000.msat, adds.map { it.second.amount }.sum())
        adds.forEach { assertEquals(payment.paymentHash, it.second.paymentHash) }

        adds.forEach { (channelId, add) ->
            // The trampoline node should receive the right forwarding information.
            val (outerB, innerB, _) = PaymentPacketTestsCommon.decryptNodeRelay(makeUpdateAddHtlc(channelId, add), TestConstants.Bob.nodeParams.nodePrivateKey)
            assertEquals(add.amount, outerB.amount)
            assertEquals(310_000.msat, outerB.totalAmount)
            assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + CltvExpiryDelta(144) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, outerB.expiry)
            assertEquals(300_000.msat, innerB.amountToForward)
            assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, innerB.outgoingCltv)
            assertEquals(payment.recipient, innerB.outgoingNodeId)
            assertEquals(invoice.paymentSecret, innerB.paymentSecret)
            assertEquals(invoice.features, innerB.invoiceFeatures)
            assertFalse(innerB.invoiceRoutingInfo.isNullOrEmpty())
            assertEquals(invoice.routingInfo.map { it.hints }, innerB.invoiceRoutingInfo)
        }

        val preimage = randomBytes32()
        val (channelId1, add1) = adds[0]
        val success1 = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(channelId1, add1, preimage))
        assertEquals(OutgoingPaymentHandler.PreimageReceived(payment, preimage), success1)
        val (channelId2, add2) = adds[1]
        val success2 = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(channelId2, add2, preimage)) as OutgoingPaymentHandler.Success
        assertEquals(preimage, success2.preimage)
        assertEquals(10_000.msat, success2.payment.fees)
        assertEquals(300_000.msat, success2.payment.recipientAmount)
        assertEquals(invoice.nodeId, success2.payment.recipient)
        assertEquals(invoice.paymentHash, success2.payment.paymentHash)
        assertEquals(OutgoingPayment.Details.Normal(invoice), success2.payment.details)
        assertEquals(preimage, (success2.payment.status as OutgoingPayment.Status.Completed.Succeeded.OffChain).preimage)
        assertEquals(2, success2.payment.parts.size)
        assertEquals(310_000.msat, success2.payment.parts.map { (it as OutgoingPayment.LightningPart).amount }.sum())
        assertEquals(setOf(preimage), success2.payment.parts.map { ((it as OutgoingPayment.LightningPart).status as OutgoingPayment.LightningPart.Status.Succeeded).preimage }.toSet())

        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        assertDbPaymentSucceeded(outgoingPaymentHandler.db, payment.paymentId, amount = 300_000.msat, fees = 10_000.msat, partsCount = 2)
    }

    @Test
    fun `prune routing hints when sending to legacy recipient`() = runSuspendTest {
        val channels = makeChannels()
        val walletParams = defaultWalletParams.copy(trampolineFees = listOf(TrampolineFees(10.sat, 0, CltvExpiryDelta(144))))
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, walletParams, InMemoryPaymentsDb())
        val recipientKey = randomKey()
        val extraHops = listOf(
            listOf(PaymentRequest.TaggedField.ExtraHop(randomKey().publicKey(), ShortChannelId(10), 10.msat, 100, CltvExpiryDelta(48))),
            listOf(PaymentRequest.TaggedField.ExtraHop(randomKey().publicKey(), ShortChannelId(12), 10.msat, 110, CltvExpiryDelta(48))),
            listOf(PaymentRequest.TaggedField.ExtraHop(randomKey().publicKey(), ShortChannelId(13), 10.msat, 120, CltvExpiryDelta(48))),
            listOf(PaymentRequest.TaggedField.ExtraHop(randomKey().publicKey(), ShortChannelId(14), 10.msat, 130, CltvExpiryDelta(48))),
            listOf(PaymentRequest.TaggedField.ExtraHop(randomKey().publicKey(), ShortChannelId(15), 10.msat, 140, CltvExpiryDelta(48))),
            listOf(PaymentRequest.TaggedField.ExtraHop(randomKey().publicKey(), ShortChannelId(16), 10.msat, 150, CltvExpiryDelta(48))),
            listOf(PaymentRequest.TaggedField.ExtraHop(randomKey().publicKey(), ShortChannelId(17), 10.msat, 160, CltvExpiryDelta(48))),
            listOf(PaymentRequest.TaggedField.ExtraHop(randomKey().publicKey(), ShortChannelId(18), 10.msat, 170, CltvExpiryDelta(48))),
        )
        val invoice = makeInvoice(amount = 200_000.msat, supportsTrampoline = false, privKey = recipientKey, extraHops = extraHops)
        val payment = SendPaymentNormal(UUID.randomUUID(), 200_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))

        val result = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val (channelId, htlc) = run {
            val adds = filterAddHtlcCommands(result)
            assertEquals(1, adds.size)
            adds.first()
        }

        val (outerB, innerB, _) = PaymentPacketTestsCommon.decryptNodeRelay(makeUpdateAddHtlc(channelId, htlc), TestConstants.Bob.nodeParams.nodePrivateKey)
        assertEquals(htlc.amount, outerB.amount)
        assertEquals(210_000.msat, outerB.totalAmount)
        assertEquals(200_000.msat, innerB.amountToForward)
        assertEquals(payment.recipient, innerB.outgoingNodeId)
        assertEquals(invoice.paymentSecret, innerB.paymentSecret)
        assertEquals(invoice.features, innerB.invoiceFeatures)
        // The trampoline node should receive a subset of the routing hints that fits inside the onion.
        assertEquals(4, innerB.invoiceRoutingInfo?.flatten()?.toSet()?.size)
        innerB.invoiceRoutingInfo?.flatten()?.forEach { assertTrue(extraHops.flatten().contains(it)) }
    }

    @Test
    fun `successful first attempt -- multiple parts + recipient is our peer`() = runSuspendTest {
        val channels = makeChannels()
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultWalletParams, InMemoryPaymentsDb())
        // The invoice comes from Bob, our direct peer (and trampoline node).
        val preimage = randomBytes32()
        val incomingPaymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams, defaultWalletParams, InMemoryPaymentsDb())
        val invoice = incomingPaymentHandler.createInvoice(preimage, amount = null, "phoenix to phoenix", listOf())
        val payment = SendPaymentNormal(UUID.randomUUID(), 300_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))

        val result = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds = filterAddHtlcCommands(result)
        assertEquals(2, adds.size)
        assertEquals(300_000.msat, adds.map { it.second.amount }.sum())
        adds.forEach { assertEquals(payment.paymentHash, it.second.paymentHash) }
        adds.forEach { (channelId, add) ->
            // Bob should receive the right final information.
            val payloadB = IncomingPaymentPacket.decrypt(makeUpdateAddHtlc(channelId, add), TestConstants.Bob.nodeParams.nodePrivateKey).right!!
            assertEquals(add.amount, payloadB.amount)
            assertEquals(300_000.msat, payloadB.totalAmount)
            assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, payloadB.expiry)
            assertEquals(invoice.paymentSecret, payloadB.paymentSecret)
        }

        // Bob receives these 2 HTLCs.
        val process1 = incomingPaymentHandler.process(makeUpdateAddHtlc(adds[0].first, adds[0].second, 3), TestConstants.defaultBlockHeight)
        assertTrue(process1 is IncomingPaymentHandler.ProcessAddResult.Pending)
        val process2 = incomingPaymentHandler.process(makeUpdateAddHtlc(adds[1].first, adds[1].second, 5), TestConstants.defaultBlockHeight)
        assertTrue(process2 is IncomingPaymentHandler.ProcessAddResult.Accepted)
        val fulfills = process2.actions.filterIsInstance<WrappedChannelCommand>().mapNotNull { (it.channelCommand as? ChannelCommand.ExecuteCommand)?.command as? CMD_FULFILL_HTLC }
        assertEquals(2, fulfills.size)

        // Alice receives the fulfill for these 2 HTLCs.
        val (channelId1, add1) = adds[0]
        val fulfill1 = createRemoteFulfill(channelId1, add1, preimage)
        val success1 = outgoingPaymentHandler.processAddSettled(fulfill1)
        assertEquals(OutgoingPaymentHandler.PreimageReceived(payment, preimage), success1)
        val (channelId2, add2) = adds[1]
        val fulfill2 = ChannelAction.ProcessCmdRes.AddSettledFulfill(add2.paymentId, makeUpdateAddHtlc(channelId2, add2), ChannelAction.HtlcResult.Fulfill.OnChainFulfill(preimage))
        val success2 = outgoingPaymentHandler.processAddSettled(fulfill2) as OutgoingPaymentHandler.Success
        assertEquals(preimage, success2.preimage)
        assertEquals(0.msat, success2.payment.fees)
        assertEquals(300_000.msat, success2.payment.recipientAmount)
        assertEquals(TestConstants.Bob.nodeParams.nodeId, success2.payment.recipient)
        assertEquals(invoice.paymentHash, success2.payment.paymentHash)
        assertEquals(OutgoingPayment.Details.Normal(invoice), success2.payment.details)
        assertEquals(preimage, (success2.payment.status as OutgoingPayment.Status.Completed.Succeeded.OffChain).preimage)
        assertEquals(2, success2.payment.parts.size)
        assertEquals(300_000.msat, success2.payment.parts.map { (it as OutgoingPayment.LightningPart).amount }.sum())
        assertEquals(setOf(preimage), success2.payment.parts.map { ((it as OutgoingPayment.LightningPart).status as OutgoingPayment.LightningPart.Status.Succeeded).preimage }.toSet())

        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        assertDbPaymentSucceeded(outgoingPaymentHandler.db, payment.paymentId, amount = 300_000.msat, fees = 0.msat, partsCount = 2)
    }

    @Test
    fun `successful second attempt`() = runSuspendTest {
        val channels = makeChannels()
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultWalletParams, InMemoryPaymentsDb())
        val recipientKey = randomKey()
        val invoice = makeInvoice(amount = null, supportsTrampoline = true, privKey = recipientKey)
        val payment = SendPaymentNormal(UUID.randomUUID(), 300_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))

        val progress1 = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds1 = filterAddHtlcCommands(progress1)
        assertEquals(2, adds1.size)
        assertEquals(300_000.msat, adds1.map { it.second.amount }.sum())

        // This first attempt fails because fees are too low.
        val attempt = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
        val fail1 = outgoingPaymentHandler.processAddSettled(adds1[0].first, createRemoteFailure(adds1[0].second, attempt, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight)
        assertNull(fail1)
        val progress2 = outgoingPaymentHandler.processAddSettled(adds1[1].first, createRemoteFailure(adds1[1].second, attempt, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds2 = filterAddHtlcCommands(progress2)
        assertEquals(2, adds2.size)
        assertEquals(301_030.msat, adds2.map { it.second.amount }.sum())
        adds2.forEach { assertEquals(payment.paymentHash, it.second.paymentHash) }
        adds2.forEach { (channelId, add) ->
            // The trampoline node should receive the right forwarding information.
            val (outerB, innerB, packetC) = PaymentPacketTestsCommon.decryptNodeRelay(makeUpdateAddHtlc(channelId, add), TestConstants.Bob.nodeParams.nodePrivateKey)
            assertEquals(add.amount, outerB.amount)
            assertEquals(301_030.msat, outerB.totalAmount)
            assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + CltvExpiryDelta(576) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, outerB.expiry)
            assertEquals(300_000.msat, innerB.amountToForward)
            assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, innerB.outgoingCltv)
            assertEquals(payment.recipient, innerB.outgoingNodeId)
            assertNull(innerB.invoiceRoutingInfo)
            assertNull(innerB.invoiceFeatures)
            assertNull(innerB.paymentSecret)

            // The recipient should receive the right amount and expiry.
            val payloadBytesC = Sphinx.peel(recipientKey, payment.paymentHash, packetC, OnionRoutingPacket.TrampolinePacketLength).right!!
            val payloadC = PaymentOnion.FinalPayload.read(payloadBytesC.payload.toByteArray())
            assertEquals(300_000.msat, payloadC.amount)
            assertEquals(CltvExpiry(TestConstants.defaultBlockHeight.toLong()) + PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA, payloadC.expiry)
            assertEquals(payloadC.amount, payloadC.totalAmount)
            assertEquals(invoice.paymentSecret, payloadC.paymentSecret)
        }

        val dbPayment1 = outgoingPaymentHandler.db.getOutgoingPayment(payment.paymentId)
        assertNotNull(dbPayment1)
        assertTrue(dbPayment1.status is OutgoingPayment.Status.Pending)
        assertEquals(2, dbPayment1.parts.filter { (it as OutgoingPayment.LightningPart).status is OutgoingPayment.LightningPart.Status.Failed }.size)
        assertEquals(2, dbPayment1.parts.filter { (it as OutgoingPayment.LightningPart).status is OutgoingPayment.LightningPart.Status.Pending }.size)

        // The second attempt succeeds.
        val preimage = randomBytes32()
        val success1 = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(adds2[0].first, adds2[0].second, preimage))
        assertEquals(OutgoingPaymentHandler.PreimageReceived(payment, preimage), success1)
        assertNotNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        val success2 = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(adds2[1].first, adds2[1].second, preimage)) as OutgoingPaymentHandler.Success
        assertEquals(preimage, success2.preimage)
        assertEquals(1_030.msat, success2.payment.fees)
        assertEquals(300_000.msat, success2.payment.recipientAmount)
        assertEquals(invoice.nodeId, success2.payment.recipient)
        assertEquals(invoice.paymentHash, success2.payment.paymentHash)
        assertEquals(OutgoingPayment.Details.Normal(invoice), success2.payment.details)
        assertEquals(preimage, (success2.payment.status as OutgoingPayment.Status.Completed.Succeeded.OffChain).preimage)
        assertEquals(2, success2.payment.parts.size)
        assertEquals(301_030.msat, success2.payment.parts.map { (it as OutgoingPayment.LightningPart).amount }.sum())
        assertEquals(setOf(preimage), success2.payment.parts.map { ((it as OutgoingPayment.LightningPart).status as OutgoingPayment.LightningPart.Status.Succeeded).preimage }.toSet())

        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        val dbPayment2 = outgoingPaymentHandler.db.getOutgoingPayment(payment.paymentId)
        assertNotNull(dbPayment2)
        assertTrue(dbPayment2.status is OutgoingPayment.Status.Completed.Succeeded.OffChain)
        assertEquals(2, dbPayment2.parts.size)
        assertTrue(dbPayment2.parts.all { (it as OutgoingPayment.LightningPart).status is OutgoingPayment.LightningPart.Status.Succeeded })
    }

    @Test
    fun `successful second attempt -- recipient is our peer`() = runSuspendTest {
        val channels = makeChannels()
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultWalletParams, InMemoryPaymentsDb())
        // The invoice comes from Bob, our direct peer (and trampoline node).
        val invoice = makeInvoice(amount = null, supportsTrampoline = true, privKey = TestConstants.Bob.nodeParams.nodePrivateKey)
        val payment = SendPaymentNormal(UUID.randomUUID(), 300_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))

        val result1 = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds1 = filterAddHtlcCommands(result1)
        assertEquals(2, adds1.size)

        // The first attempt fails because of a local channel error.
        val result2 = outgoingPaymentHandler.processAddFailed(adds1[1].first, ChannelAction.ProcessCmdRes.AddFailed(adds1[1].second, TooManyAcceptedHtlcs(adds1[1].first, 10), null), channels) as OutgoingPaymentHandler.Progress
        val adds2 = filterAddHtlcCommands(result2)
        assertEquals(1, adds2.size)

        // The other HTLCs succeed.
        val preimage = randomBytes32()
        val (channelId1, add1) = adds1[0]
        val fulfill1 = createRemoteFulfill(channelId1, add1, preimage)
        val success1 = outgoingPaymentHandler.processAddSettled(fulfill1)
        assertEquals(OutgoingPaymentHandler.PreimageReceived(payment, preimage), success1)

        val (channelId2, add2) = adds2[0]
        val fulfill2 = createRemoteFulfill(channelId2, add2, preimage)
        val success2 = outgoingPaymentHandler.processAddSettled(fulfill2) as OutgoingPaymentHandler.Success
        assertEquals(preimage, success2.preimage)
        assertEquals(0.msat, success2.payment.fees)
        assertEquals(300_000.msat, success2.payment.recipientAmount)
        assertEquals(TestConstants.Bob.nodeParams.nodeId, success2.payment.recipient)
        assertEquals(invoice.paymentHash, success2.payment.paymentHash)
        assertEquals(OutgoingPayment.Details.Normal(invoice), success2.payment.details)
        assertEquals(preimage, (success2.payment.status as OutgoingPayment.Status.Completed.Succeeded.OffChain).preimage)
        assertEquals(2, success2.payment.parts.size)
        assertTrue(success2.payment.parts.all { it is OutgoingPayment.LightningPart })
        assertEquals(300_000.msat, success2.payment.parts.map { (it as OutgoingPayment.LightningPart).amount }.sum())
        assertEquals(setOf(preimage), success2.payment.parts.map { ((it as OutgoingPayment.LightningPart).status as OutgoingPayment.LightningPart.Status.Succeeded).preimage }.toSet())

        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        assertDbPaymentSucceeded(outgoingPaymentHandler.db, payment.paymentId, amount = 300_000.msat, fees = 0.msat, partsCount = 2)
    }

    @Test
    fun `insufficient funds when retrying with higher fees`() = runSuspendTest {
        val channels = makeChannels()
        val walletParams = defaultWalletParams.copy(
            trampolineFees = listOf(
                TrampolineFees(10.sat, 0, CltvExpiryDelta(144)),
                TrampolineFees(100.sat, 0, CltvExpiryDelta(144)),
            )
        )
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, walletParams, InMemoryPaymentsDb())
        val invoice = makeInvoice(amount = null, supportsTrampoline = true)
        val payment = SendPaymentNormal(UUID.randomUUID(), 550_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))

        val progress1 = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds1 = filterAddHtlcCommands(progress1)
        assertEquals(3, adds1.size)
        assertEquals(560_000.msat, adds1.map { it.second.amount }.sum())

        val attempt = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
        assertNull(outgoingPaymentHandler.processAddSettled(adds1[0].first, createRemoteFailure(adds1[0].second, attempt, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight))
        assertNull(outgoingPaymentHandler.processAddSettled(adds1[1].first, createRemoteFailure(adds1[1].second, attempt, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight))
        val fail = outgoingPaymentHandler.processAddSettled(adds1[2].first, createRemoteFailure(adds1[2].second, attempt, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Failure
        val expected = OutgoingPaymentHandler.Failure(payment, OutgoingPaymentFailure(FinalFailure.InsufficientBalance, listOf(Either.Right(TrampolineFeeInsufficient))))
        assertFailureEquals(expected, fail)

        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        assertDbPaymentFailed(outgoingPaymentHandler.db, payment.paymentId, 3)
    }

    @Test
    fun `retries exhausted`() = runSuspendTest {
        val channels = makeChannels()
        val walletParams = defaultWalletParams.copy(
            trampolineFees = listOf(
                TrampolineFees(10.sat, 0, CltvExpiryDelta(144)),
                TrampolineFees(20.sat, 0, CltvExpiryDelta(144)),
            )
        )
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, walletParams, InMemoryPaymentsDb())
        val invoice = makeInvoice(amount = null, supportsTrampoline = true)
        val payment = SendPaymentNormal(UUID.randomUUID(), 220_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))

        val progress1 = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds1 = filterAddHtlcCommands(progress1)
        assertEquals(1, adds1.size)
        assertEquals(230_000.msat, adds1.map { it.second.amount }.sum())

        val attempt1 = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
        val progress2 = outgoingPaymentHandler.processAddSettled(adds1[0].first, createRemoteFailure(adds1[0].second, attempt1, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds2 = filterAddHtlcCommands(progress2)
        assertEquals(1, adds2.size)
        assertEquals(240_000.msat, adds2.map { it.second.amount }.sum())

        val attempt2 = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
        val fail = outgoingPaymentHandler.processAddSettled(adds2[0].first, createRemoteFailure(adds2[0].second, attempt2, TrampolineFeeInsufficient), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Failure
        val expected = OutgoingPaymentHandler.Failure(payment, OutgoingPaymentFailure(FinalFailure.RetryExhausted, listOf(Either.Right(TrampolineFeeInsufficient))))
        assertFailureEquals(expected, fail)

        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        assertDbPaymentFailed(outgoingPaymentHandler.db, payment.paymentId, 2)
    }

    @Test
    fun `non-retriable remote failure`() = runSuspendTest {
        val fatalFailures = listOf(
            Pair(UnknownNextPeer, FinalFailure.RecipientUnreachable),
            Pair(IncorrectOrUnknownPaymentDetails(50_000.msat, TestConstants.defaultBlockHeight.toLong()), FinalFailure.UnknownError)
        )
        fatalFailures.forEach { (remoteFailure, userFailure) ->
            val channels = makeChannels()
            val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultWalletParams, InMemoryPaymentsDb())
            val invoice = makeInvoice(amount = null, supportsTrampoline = true)
            val payment = SendPaymentNormal(UUID.randomUUID(), 50_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))

            val progress = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
            val adds = filterAddHtlcCommands(progress)
            assertEquals(1, adds.size)
            assertEquals(50_000.msat, adds.map { it.second.amount }.sum())

            val attempt = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
            val fail = outgoingPaymentHandler.processAddSettled(adds[0].first, createRemoteFailure(adds[0].second, attempt, remoteFailure), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Failure
            val expected = OutgoingPaymentHandler.Failure(payment, OutgoingPaymentFailure(userFailure, listOf(Either.Right(remoteFailure))))
            assertFailureEquals(expected, fail)

            assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
            assertDbPaymentFailed(outgoingPaymentHandler.db, payment.paymentId, 1)
        }
    }

    private suspend fun testLocalChannelFailures(invoice: PaymentRequest) {
        val channels = makeChannels()
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultWalletParams, InMemoryPaymentsDb())
        val payment = SendPaymentNormal(UUID.randomUUID(), 5_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))

        var progress = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        assertEquals(1, progress.actions.size)
        assertEquals(5_000.msat, filterAddHtlcCommands(progress).map { it.second.amount }.sum())

        // Channels fail, so we retry with different channels, without raising the fees.
        val localFailures = listOf(
            { channelId: ByteVector32 -> TooManyAcceptedHtlcs(channelId, 15) },
            { channelId: ByteVector32 -> InsufficientFunds(channelId, 5_000.msat, 1.sat, 20.sat, 1.sat) },
            { channelId: ByteVector32 -> HtlcValueTooHighInFlight(channelId, 150_000U, 155_000.msat) },
        )
        localFailures.forEach { localFailure ->
            val (channelId, add) = filterAddHtlcCommands(progress).first()
            progress = outgoingPaymentHandler.processAddFailed(channelId, ChannelAction.ProcessCmdRes.AddFailed(add, localFailure(channelId), null), channels) as OutgoingPaymentHandler.Progress
            assertEquals(5_000.msat, add.amount)
        }

        // The last channel fails: we don't have any channels available to retry.
        val (channelId, add) = filterAddHtlcCommands(progress).first()
        val fail = outgoingPaymentHandler.processAddFailed(channelId, ChannelAction.ProcessCmdRes.AddFailed(add, TooManyAcceptedHtlcs(channelId, 15), null), channels) as OutgoingPaymentHandler.Failure
        assertEquals(FinalFailure.InsufficientBalance, fail.failure.reason)
        assertEquals(4, fail.failure.failures.filter { it.isLocalFailure() }.size)

        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        assertDbPaymentFailed(outgoingPaymentHandler.db, payment.paymentId, 4)
    }

    @Test
    fun `local channel failures`() = runSuspendTest {
        testLocalChannelFailures(makeInvoice(amount = null, supportsTrampoline = true))
    }

    @Test
    fun `local channel failures -- recipient is our peer`() = runSuspendTest {
        // The invoice comes from Bob, our direct peer (and trampoline node).
        testLocalChannelFailures(makeInvoice(amount = null, supportsTrampoline = true, privKey = TestConstants.Bob.nodeParams.nodePrivateKey))
    }

    @Test
    fun `local channel failure followed by success`() = runSuspendTest {
        val channels = makeChannels()
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultWalletParams, InMemoryPaymentsDb())
        val invoice = makeInvoice(amount = null, supportsTrampoline = true)
        val payment = SendPaymentNormal(UUID.randomUUID(), 5_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))

        val progress1 = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        assertEquals(1, progress1.actions.size)
        assertEquals(5_000.msat, filterAddHtlcCommands(progress1).map { it.second.amount }.sum())

        // This first payment fails:
        val (channelId, add) = filterAddHtlcCommands(progress1).first()
        val progress2 = outgoingPaymentHandler.processAddFailed(channelId, ChannelAction.ProcessCmdRes.AddFailed(add, TooManyAcceptedHtlcs(channelId, 1), null), channels) as OutgoingPaymentHandler.Progress
        assertEquals(1, progress2.actions.size)
        val adds = filterAddHtlcCommands(progress2)
        assertEquals(5_000.msat, adds.map { it.second.amount }.sum())

        // This second attempt succeeds:
        val preimage = randomBytes32()
        val success = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(adds[0].first, adds[0].second, preimage)) as OutgoingPaymentHandler.Success
        assertEquals(0.msat, success.payment.fees)
        assertEquals(5_000.msat, success.payment.recipientAmount)
        assertEquals(preimage, success.preimage)

        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        assertDbPaymentSucceeded(outgoingPaymentHandler.db, payment.paymentId, amount = 5_000.msat, fees = 0.msat, partsCount = 1)
    }

    @Test
    fun `partial failure then fulfill -- spec violation`() = runSuspendTest {
        val channels = makeChannels()
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultWalletParams, InMemoryPaymentsDb())
        val invoice = makeInvoice(amount = null, supportsTrampoline = true)
        val payment = SendPaymentNormal(UUID.randomUUID(), 310_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))

        val progress = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds = filterAddHtlcCommands(progress)
        assertEquals(2, adds.size)
        assertEquals(310_000.msat, adds.map { it.second.amount }.sum())

        val attempt = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
        val remoteFailure = IncorrectOrUnknownPaymentDetails(310_000.msat, TestConstants.defaultBlockHeight.toLong())
        assertNull(outgoingPaymentHandler.processAddSettled(adds[0].first, createRemoteFailure(adds[0].second, attempt, remoteFailure), channels, TestConstants.defaultBlockHeight))

        // The recipient released the preimage without receiving the full payment amount.
        // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
        val preimage = randomBytes32()
        val success = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(adds[1].first, adds[1].second, preimage)) as OutgoingPaymentHandler.Success
        assertEquals(preimage, success.preimage)
        assertEquals((-250_000).msat, success.payment.fees) // since we paid much less than the expected amount, it results in negative fees

        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        assertDbPaymentSucceeded(outgoingPaymentHandler.db, payment.paymentId, amount = 310_000.msat, fees = (-250_000).msat, partsCount = 1)
    }

    @Test
    fun `partial fulfill then failure -- spec violation`() = runSuspendTest {
        val channels = makeChannels()
        val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultWalletParams, InMemoryPaymentsDb())
        val invoice = makeInvoice(amount = null, supportsTrampoline = true)
        val payment = SendPaymentNormal(UUID.randomUUID(), 310_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))

        val progress = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
        val adds = filterAddHtlcCommands(progress)
        assertEquals(2, adds.size)
        assertEquals(310_000.msat, adds.map { it.second.amount }.sum())

        val preimage = randomBytes32()
        val expected = OutgoingPaymentHandler.PreimageReceived(payment, preimage)
        val result = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(adds[0].first, adds[0].second, preimage))
        assertEquals(expected, result)

        // The recipient released the preimage without receiving the full payment amount.
        // This is a spec violation and is too bad for them, we obtained a proof of payment without paying the full amount.
        val attempt = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
        val remoteFailure = IncorrectOrUnknownPaymentDetails(310_000.msat, TestConstants.defaultBlockHeight.toLong())
        val success = outgoingPaymentHandler.processAddSettled(adds[1].first, createRemoteFailure(adds[1].second, attempt, remoteFailure), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Success
        assertEquals(preimage, success.preimage)
        assertEquals((-60_000).msat, success.payment.fees) // since we paid much less than the expected amount, it results in negative fees

        assertNull(outgoingPaymentHandler.getPendingPayment(payment.paymentId))
        assertDbPaymentSucceeded(outgoingPaymentHandler.db, payment.paymentId, amount = 310_000.msat, fees = (-60_000).msat, partsCount = 1)
    }

    @Test
    fun `failure after a wallet restart`() = runSuspendTest {
        val channels = makeChannels()
        val db = InMemoryPaymentsDb()

        // Step 1: a payment attempt is made.
        val (adds, attempt) = run {
            val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultWalletParams, db)
            val invoice = makeInvoice(amount = null, supportsTrampoline = true)
            val payment = SendPaymentSwapOut(UUID.randomUUID(), 550_000.msat, invoice.nodeId, OutgoingPayment.Details.SwapOut("1PwLgmRdDjy5GAKWyp8eyAC4SFzWuboLLb", invoice, 10.sat))

            val progress = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
            val attempt = outgoingPaymentHandler.getPendingPayment(payment.paymentId)!!
            val adds = filterAddHtlcCommands(progress)
            assertEquals(3, adds.size)
            Pair(adds, attempt)
        }

        // Step 2: the wallet restarts and payment fails.
        run {
            val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultWalletParams, db)
            assertNull(outgoingPaymentHandler.processAddFailed(adds[0].first, ChannelAction.ProcessCmdRes.AddFailed(adds[0].second, ChannelUnavailable(adds[0].first), null), channels))
            assertNull(outgoingPaymentHandler.processAddSettled(adds[1].first, createRemoteFailure(adds[1].second, attempt, TemporaryNodeFailure), channels, TestConstants.defaultBlockHeight))
            val result = outgoingPaymentHandler.processAddSettled(adds[2].first, createRemoteFailure(adds[2].second, attempt, PermanentNodeFailure), channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Failure
            val failures: List<Either<ChannelException, FailureMessage>> = listOf(
                Either.Left(ChannelUnavailable(adds[0].first)),
                // Since we've lost the shared secrets, we can't decrypt remote failures.
                Either.Right(UnknownFailureMessage(0)),
                Either.Right(UnknownFailureMessage(0))
            )
            assertFailureEquals(OutgoingPaymentHandler.Failure(attempt.request, OutgoingPaymentFailure(FinalFailure.WalletRestarted, failures)), result)
        }
    }

    @Test
    fun `success after a wallet restart`() = runSuspendTest {
        val channels = makeChannels()
        val preimage = randomBytes32()
        val invoice = makeInvoice(amount = null, supportsTrampoline = true)
        val payment = SendPaymentNormal(UUID.randomUUID(), 550_000.msat, invoice.nodeId, OutgoingPayment.Details.Normal(invoice))
        val db = InMemoryPaymentsDb()

        // Step 1: a payment attempt is made.
        val adds = run {
            val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultWalletParams, db)
            val progress = outgoingPaymentHandler.sendPayment(payment, channels, TestConstants.defaultBlockHeight) as OutgoingPaymentHandler.Progress
            val adds = filterAddHtlcCommands(progress)
            assertEquals(3, adds.size)
            // A first part is fulfilled before the wallet restarts.
            val result = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(adds[0].first, adds[0].second, preimage))
            assertEquals(OutgoingPaymentHandler.PreimageReceived(payment, preimage), result)
            adds
        }

        // Step 2: the wallet restarts and payment succeeds.
        run {
            val outgoingPaymentHandler = OutgoingPaymentHandler(TestConstants.Alice.nodeParams, defaultWalletParams, db)
            val result1 = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(adds[1].first, adds[1].second, preimage))
            assertEquals(OutgoingPaymentHandler.PreimageReceived(payment, preimage), result1)
            val result2 = outgoingPaymentHandler.processAddSettled(createRemoteFulfill(adds[2].first, adds[2].second, preimage)) as OutgoingPaymentHandler.Success
            assertEquals(preimage, result2.preimage)
            assertEquals(3, result2.payment.parts.size)
            assertEquals(payment, SendPaymentNormal(result2.payment.id, result2.payment.recipientAmount, result2.payment.recipient, result2.payment.details as OutgoingPayment.Details.Normal))
            assertEquals(preimage, (result2.payment.status as OutgoingPayment.Status.Completed.Succeeded.OffChain).preimage)
        }
    }

    private fun makeInvoice(amount: MilliSatoshi?, supportsTrampoline: Boolean, privKey: PrivateKey = randomKey(), extraHops: List<List<PaymentRequest.TaggedField.ExtraHop>> = listOf()): PaymentRequest {
        val paymentPreimage: ByteVector32 = randomBytes32()
        val paymentHash = Crypto.sha256(paymentPreimage).toByteVector32()

        val invoiceFeatures = mutableMapOf(
            Feature.VariableLengthOnion to FeatureSupport.Optional,
            Feature.PaymentSecret to FeatureSupport.Mandatory,
            Feature.BasicMultiPartPayment to FeatureSupport.Optional
        )
        if (supportsTrampoline) {
            invoiceFeatures[Feature.ExperimentalTrampolinePayment] = FeatureSupport.Optional
        }

        return PaymentRequest.create(
            chainHash = Block.LivenetGenesisBlock.hash,
            amount = amount,
            paymentHash = paymentHash,
            privateKey = privKey,
            description = "unit test",
            minFinalCltvExpiryDelta = PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
            features = Features(invoiceFeatures.toMap()),
            extraHops = extraHops
        )
    }

    private fun makeChannels(): Map<ByteVector32, Normal> {
        val (alice, _) = TestsHelper.reachNormal()
        val reserve = alice.commitments.localChannelReserve
        val channelDetails = listOf(
            Pair(ShortChannelId(1), 250_000.msat),
            Pair(ShortChannelId(2), 150_000.msat),
            Pair(ShortChannelId(3), 0.msat),
            Pair(ShortChannelId(4), 10_000.msat),
            Pair(ShortChannelId(5), 200_000.msat),
        )
        return channelDetails.associate {
            val channelId = randomBytes32()
            val channel = alice.state.copy(
                shortChannelId = it.first,
                commitments = alice.commitments.copy(
                    channelId = channelId,
                    remoteCommit = alice.commitments.remoteCommit.copy(spec = CommitmentSpec(setOf(), FeeratePerKw(0.sat), 50_000.msat, (it.second + ((Commitments.ANCHOR_AMOUNT * 2) + reserve).toMilliSatoshi())))
                )
            )
            channelId to channel
        }
    }

    private fun filterAddHtlcCommands(progress: OutgoingPaymentHandler.Progress): List<Pair<ByteVector32, CMD_ADD_HTLC>> {
        val addCommands = mutableListOf<Pair<ByteVector32, CMD_ADD_HTLC>>()
        for (action in progress.actions) {
            val addCommand = (action.channelCommand as? ChannelCommand.ExecuteCommand)?.command as? CMD_ADD_HTLC
            if (addCommand != null) {
                addCommands.add(Pair(action.channelId, addCommand))
            }
        }
        return addCommands.toList()
    }

    private fun makeUpdateAddHtlc(channelId: ByteVector32, cmd: CMD_ADD_HTLC, htlcId: Long = 0): UpdateAddHtlc =
        UpdateAddHtlc(channelId, htlcId, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)

    private fun createRemoteFulfill(channelId: ByteVector32, add: CMD_ADD_HTLC, preimage: ByteVector32): ChannelAction.ProcessCmdRes.AddSettledFulfill {
        val updateAddHtlc = makeUpdateAddHtlc(channelId, add)
        return ChannelAction.ProcessCmdRes.AddSettledFulfill(add.paymentId, updateAddHtlc, ChannelAction.HtlcResult.Fulfill.RemoteFulfill(UpdateFulfillHtlc(channelId, updateAddHtlc.id, preimage)))
    }

    private fun createRemoteFailure(add: CMD_ADD_HTLC, attempt: OutgoingPaymentHandler.PaymentAttempt, failureMessage: FailureMessage): ChannelAction.ProcessCmdRes.AddSettledFail {
        val sharedSecrets = attempt.pending.getValue(add.paymentId).second
        val reason = FailurePacket.create(sharedSecrets.perHopSecrets.last().first, failureMessage)
        val updateAddHtlc = makeUpdateAddHtlc(randomBytes32(), add)
        return ChannelAction.ProcessCmdRes.AddSettledFail(
            add.paymentId,
            updateAddHtlc,
            ChannelAction.HtlcResult.Fail.RemoteFail(UpdateFailHtlc(updateAddHtlc.channelId, updateAddHtlc.id, reason.toByteVector()))
        )
    }

    private suspend fun assertDbPaymentFailed(db: OutgoingPaymentsDb, paymentId: UUID, partsCount: Int) {
        val dbPayment = db.getOutgoingPayment(paymentId)
        assertNotNull(dbPayment)
        assertTrue(dbPayment.status is OutgoingPayment.Status.Completed.Failed)
        assertEquals(partsCount, dbPayment.parts.size)
        assertTrue(dbPayment.parts.all { (it as OutgoingPayment.LightningPart).status is OutgoingPayment.LightningPart.Status.Failed })
    }

    private suspend fun assertDbPaymentSucceeded(db: OutgoingPaymentsDb, paymentId: UUID, amount: MilliSatoshi, fees: MilliSatoshi, partsCount: Int) {
        val dbPayment = db.getOutgoingPayment(paymentId)
        assertNotNull(dbPayment)
        assertEquals(amount, dbPayment.recipientAmount)
        assertEquals(fees, dbPayment.fees)
        assertTrue(dbPayment.status is OutgoingPayment.Status.Completed.Succeeded.OffChain)
        assertEquals(partsCount, dbPayment.parts.size)
        assertTrue(dbPayment.parts.all { (it as OutgoingPayment.LightningPart).status is OutgoingPayment.LightningPart.Status.Succeeded })
    }

    private fun assertFailureEquals(f1: OutgoingPaymentHandler.Failure, f2: OutgoingPaymentHandler.Failure) {
        val f1b = f1.copy(failure = f1.failure.copy(failures = f1.failure.failures.map { it.copy(completedAt = 0) }))
        val f2b = f2.copy(failure = f2.failure.copy(failures = f2.failure.failures.map { it.copy(completedAt = 0) }))
        assertEquals(f1b, f2b)
    }

}
