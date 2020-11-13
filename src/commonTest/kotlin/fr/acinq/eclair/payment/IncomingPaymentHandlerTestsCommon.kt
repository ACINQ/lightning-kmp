package fr.acinq.eclair.payment

import fr.acinq.bitcoin.*
import fr.acinq.eclair.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.crypto.sphinx.Sphinx
import fr.acinq.eclair.io.PayToOpenResponseEvent
import fr.acinq.eclair.io.WrappedChannelEvent
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


class IncomingPaymentHandlerTestsCommon : EclairTestSuite() {

    private var nextId: Long = 0

    private fun channelHops(
        destination: PublicKey,
    ): List<ChannelHop> {

        val dummyKey =
            PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")).publicKey()
        val dummyUpdate = ChannelUpdate(
            signature = ByteVector64.Zeroes,
            chainHash = ByteVector32.Zeroes,
            shortChannelId = ShortChannelId(144, 0, 0),
            timestamp = 0,
            messageFlags = 0,
            channelFlags = 0,
            cltvExpiryDelta = CltvExpiryDelta(1),
            htlcMinimumMsat = 0.msat,
            feeBaseMsat = 0.msat,
            feeProportionalMillionths = 0,
            htlcMaximumMsat = null
        )
        val channelHop = ChannelHop(dummyKey, destination, dummyUpdate)

        return listOf(channelHop)
    }

    /**
     * Creates a multipart htlc, and wraps it in CMD_ADD_HTLC.
     * The result is ready to be processed thru the sender's channel.
     */
    private fun makeCmdAddHtlc(
        destination: PublicKey,
        paymentHash: ByteVector32,
        finalPayload: FinalPayload
    ): CMD_ADD_HTLC {

        return OutgoingPacket.buildCommand(
            paymentId = UUID.randomUUID(),
            paymentHash = paymentHash,
            hops = channelHops(destination),
            finalPayload = finalPayload
        ).first.copy(commit = true)
    }

    private fun makeUpdateAddHtlc(
        channelId: ByteVector32,
        destination: IncomingPaymentHandler,
        paymentHash: ByteVector32,
        finalPayload: FinalPayload
    ): UpdateAddHtlc {

        val (_, _, packetAndSecrets) = OutgoingPacket.buildPacket(
            paymentHash = paymentHash,
            hops = channelHops(destination.nodeParams.nodeId),
            finalPayload = finalPayload,
            payloadLength = OnionRoutingPacket.PaymentPacketLength
        )

        return UpdateAddHtlc(
            channelId = channelId,
            id = nextId++,
            amountMsat = finalPayload.amount,
            paymentHash = paymentHash,
            cltvExpiry = finalPayload.expiry,
            onionRoutingPacket = packetAndSecrets.packet
        )
    }

    private fun makeLegacyPayload(
        amount: MilliSatoshi,
        cltvExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(144),
        currentBlockHeight: Int = TestConstants.defaultBlockHeight
    ): FinalPayload {

        val expiry = cltvExpiryDelta.toCltvExpiry(currentBlockHeight.toLong())
        return FinalPayload.createSinglePartPayload(amount, expiry)
    }

    private fun makeMppPayload(
        amount: MilliSatoshi,
        totalAmount: MilliSatoshi,
        paymentSecret: ByteVector32,
        cltvExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(144),
        currentBlockHeight: Int = TestConstants.defaultBlockHeight
    ): FinalPayload {

        val expiry = cltvExpiryDelta.toCltvExpiry(currentBlockHeight.toLong())
        return FinalPayload.createMultiPartPayload(amount, totalAmount, expiry, paymentSecret)
    }

    /**
     * Walks thru the following steps:
     *
     * 1) alice => update_add_htlc   => bob
     * 2) alice => commitment_signed => bob
     * 3) alice <= revoke_and_ack    <= bob
     * 4) alice <= commitment_signed <= bob
     * 5) alice => revoke_and_ack    => bob
     *
     * Along the way it verifies the state of the htlc as it flows thru the commitment process.
     */
    @Test
    fun `Commitments should pass thru MPP as normal htlc`() {

        val paymentSecret = Eclair.randomBytes32()
        val paymentPreimage = Eclair.randomBytes32()
        val paymentHash = Crypto.sha256(paymentPreimage).toByteVector32()

        val ab = TestsHelper.reachNormal()

        var alice: ChannelState = ab.first
        var bob: ChannelState = ab.second

        // alice & bob: type => Normal: ChannelState, HasCommitment
        //
        // - so we can call alice.process(event: ChannelEvent)
        // - where ChannelEvent is going to be one of:
        //   - ExecuteCommand: ChannelEvent()
        //   - MessageReceived: ChannelEvent()
        //
        // After reachNormal():
        // - alice has : 1_000_000 sat
        // - bob has   :         0 sat

        val amount = MilliSatoshi(100.sat)
        val totalAmount = amount * 2

        val mppPayload = makeMppPayload(
            amount = amount,
            totalAmount = totalAmount,
            currentBlockHeight = alice.currentBlockHeight,
            paymentSecret = paymentSecret
        )
        val cmdAddHtlc = makeCmdAddHtlc(
            destination = bob.staticParams.nodeParams.nodeId,
            paymentHash = paymentHash,
            finalPayload = mppPayload
        )

        var processResult: Pair<ChannelState, List<ChannelAction>>
        var actions: List<ChannelAction>

        val a2b = object {
            var updateAddHtlc: SendMessage? = null
            var commitmentSigned: SendMessage? = null
            var revokeAndAck: SendMessage? = null
        }
        val a2a = object {
            var cmdSign: SendToSelf? = null
        }
        val b2a = object {
            var revokeAndAck: SendMessage? = null
            var commitmentSigned: SendMessage? = null
        }
        val b2b = object {
            var cmdSign: SendToSelf? = null
        }

        // Step 1 of 5:
        //
        // alice => update_add_htlc => bob

        processResult = alice.process(ExecuteCommand(cmdAddHtlc))

        alice = processResult.first
        assertTrue { alice is Normal }

        actions = processResult.second
        assertTrue { actions.isNotEmpty() }

        a2b.updateAddHtlc = actions.filterIsInstance<SendMessage>().firstOrNull { it.message is UpdateAddHtlc }
        assertNotNull(a2b.updateAddHtlc)

        a2a.cmdSign = actions.filterIsInstance<SendToSelf>().firstOrNull { it.command == CMD_SIGN }
        assertNotNull(a2a.cmdSign)

        processResult = bob.process(MessageReceived(a2b.updateAddHtlc!!.message))

        bob = processResult.first
        assertTrue { bob is Normal }

        actions = processResult.second
        assertTrue { actions.filterIsInstance<SendMessage>().isEmpty() }

        assertTrue { (alice as Normal).commitments.localChanges.proposed.size == 1 } // size == 1
        assertTrue { (alice as Normal).commitments.localChanges.signed.isEmpty() }   // size == 0
        assertTrue { (alice as Normal).commitments.localChanges.acked.isEmpty() }    // size == 0

        assertTrue { (bob as Normal).commitments.remoteChanges.proposed.size == 1 }  // size == 1
        assertTrue { (bob as Normal).commitments.remoteChanges.acked.isEmpty() }     // size == 0
        assertTrue { (bob as Normal).commitments.remoteChanges.signed.isEmpty() }    // size == 0

        // Step 2 of 5:
        //
        // alice => commitment_signed => bob

        processResult = alice.process(ExecuteCommand(a2a.cmdSign!!.command))

        alice = processResult.first
        assertTrue { alice is Normal }

        actions = processResult.second
        assertTrue { actions.isNotEmpty() }

        a2b.commitmentSigned = actions.filterIsInstance<SendMessage>().firstOrNull { it.message is CommitSig }
        assertNotNull(a2b.commitmentSigned)

        processResult = bob.process(MessageReceived(a2b.commitmentSigned!!.message))

        bob = processResult.first
        assertTrue { bob is Normal }

        actions = processResult.second
        assertTrue { actions.isNotEmpty() }

        b2a.revokeAndAck = actions.filterIsInstance<SendMessage>().firstOrNull { it.message is RevokeAndAck }
        assertNotNull(b2a.revokeAndAck)

        b2b.cmdSign = actions.filterIsInstance<SendToSelf>().firstOrNull { it.command == CMD_SIGN }
        assertNotNull(b2b.cmdSign)

        assertTrue { (alice as Normal).commitments.localChanges.proposed.isEmpty() } // size == 0
        assertTrue { (alice as Normal).commitments.localChanges.signed.size == 1 }   // size == 1
        assertTrue { (alice as Normal).commitments.localChanges.acked.isEmpty() }    // size == 0

        assertTrue { (bob as Normal).commitments.remoteChanges.proposed.isEmpty() }  // size == 0
        assertTrue { (bob as Normal).commitments.remoteChanges.acked.size == 1 }     // size == 1
        assertTrue { (bob as Normal).commitments.remoteChanges.signed.isEmpty() }    // size == 0

        // Step 3 of 5
        //
        // alice <= revoke_and_ack <= bob

        processResult = alice.process(MessageReceived(b2a.revokeAndAck!!.message))

        alice = processResult.first
        assertTrue { alice is Normal }

        actions = processResult.second
        assertTrue { actions.filterIsInstance<SendMessage>().isEmpty() }

        assertTrue { (alice as Normal).commitments.localChanges.proposed.isEmpty() } // size == 0
        assertTrue { (alice as Normal).commitments.localChanges.signed.isEmpty() }   // size == 0
        assertTrue { (alice as Normal).commitments.localChanges.acked.size == 1 }    // size == 1

        assertTrue { (bob as Normal).commitments.remoteChanges.proposed.isEmpty() }  // size == 0
        assertTrue { (bob as Normal).commitments.remoteChanges.acked.size == 1 }     // size == 1
        assertTrue { (bob as Normal).commitments.remoteChanges.signed.isEmpty() }    // size == 0

        // Step 4 of 5:
        //
        // alice <= commitment_signed <= bob

        processResult = bob.process(ExecuteCommand(b2b.cmdSign!!.command))

        bob = processResult.first
        assertTrue { bob is Normal }

        actions = processResult.second.filterIsInstance<SendMessage>()
        assertTrue { actions.size == 1 }

        b2a.commitmentSigned = actions.filterIsInstance<SendMessage>().firstOrNull { it.message is CommitSig }
        assertNotNull(b2a.commitmentSigned)

        processResult = alice.process(MessageReceived(b2a.commitmentSigned!!.message))

        alice = processResult.first
        assertTrue { alice is Normal }

        actions = processResult.second
        assertTrue { actions.isNotEmpty() }

        a2b.revokeAndAck = actions.filterIsInstance<SendMessage>().firstOrNull { it.message is RevokeAndAck }
        assertNotNull(a2b.revokeAndAck)

        assertTrue { (alice as Normal).commitments.localChanges.proposed.isEmpty() } // size == 0
        assertTrue { (alice as Normal).commitments.localChanges.signed.isEmpty() }   // size == 0
        assertTrue { (alice as Normal).commitments.localChanges.acked.isEmpty() }    // size == 0

        assertTrue { (bob as Normal).commitments.remoteChanges.proposed.isEmpty() }  // size == 0
        assertTrue { (bob as Normal).commitments.remoteChanges.acked.isEmpty() }     // size == 0
        assertTrue { (bob as Normal).commitments.remoteChanges.signed.size == 1 }    // size == 1

        // Step 5 of 5:
        //
        // alice => revoke_and_ack => bob

        processResult = bob.process(MessageReceived(a2b.revokeAndAck!!.message))

        bob = processResult.first
        assertTrue { bob is Normal }

        actions = processResult.second
        assertTrue { actions.filterIsInstance<SendMessage>().isEmpty() }
        assertTrue { actions.filterIsInstance<ProcessAdd>().isNotEmpty() }

        assertTrue { (alice as Normal).commitments.localChanges.proposed.isEmpty() } // size == 0
        assertTrue { (alice as Normal).commitments.localChanges.signed.isEmpty() }   // size == 0
        assertTrue { (alice as Normal).commitments.localChanges.acked.isEmpty() }    // size == 0

        assertTrue { (bob as Normal).commitments.remoteChanges.proposed.isEmpty() }  // size == 0
        assertTrue { (bob as Normal).commitments.remoteChanges.acked.isEmpty() }     // size == 0
        assertTrue { (bob as Normal).commitments.remoteChanges.signed.isEmpty() }    // size == 0
    }

    private fun makeIncomingPayment(
        payee: IncomingPaymentHandler,
        amount: MilliSatoshi?,
        expirySeconds: Long? = null,
        timestamp: Long = currentTimestampSeconds()
    ): IncomingPayment {
        val paymentPreimage: ByteVector32 = Eclair.randomBytes32()
        val paymentRequest = payee.createInvoice(paymentPreimage, amount, "unit test", expirySeconds, timestamp)
        return IncomingPayment(paymentRequest, paymentPreimage)
    }

    @Test
    fun `unsupported legacy onion (without paymentSecret)`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val totalAmount = MilliSatoshi(100.sat)

        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = totalAmount)

        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeLegacyPayload(
                    amount = totalAmount
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.REJECTED }
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        updateAddHtlc.channelId,
                        ExecuteCommand(
                            CMD_FAIL_HTLC(
                                updateAddHtlc.id, CMD_FAIL_HTLC.Reason.Failure(
                                    IncorrectOrUnknownPaymentDetails(
                                        totalAmount,
                                        TestConstants.defaultBlockHeight.toLong()
                                    )
                                ), commit = true
                            )
                        )
                    ),
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `receive mpp payment with single HTLC`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val totalAmount = MilliSatoshi(100.sat)
        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = totalAmount)

        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = totalAmount,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.ACCEPTED } // Yay!
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        updateAddHtlc.channelId,
                        ExecuteCommand(
                            CMD_FULFILL_HTLC(
                                updateAddHtlc.id,
                                incomingPayment.paymentPreimage,
                                commit = true
                            )
                        )
                    )
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `receive pay-to-open payment with single HTLC`() {

        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val totalAmount = MilliSatoshi(100.sat)
        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = totalAmount)

        run {
            val payToOpenRequest = PayToOpenRequest(
                chainHash = ByteVector32.Zeroes,
                fundingSatoshis = 100000.sat,
                amountMsat = totalAmount,
                feeSatoshis = 100.sat,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                feeThresholdSatoshis = 1000.sat,
                feeProportionalMillionths = 100,
                expireAt = Long.MAX_VALUE,
                finalPacket = OutgoingPacket.buildPacket(
                    paymentHash = incomingPayment.paymentRequest.paymentHash,
                    hops = channelHops(paymentHandler.nodeParams.nodeId),
                    finalPayload = makeMppPayload(
                        amount = totalAmount,
                        totalAmount = totalAmount,
                        paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                    ),
                    payloadLength = OnionRoutingPacket.PaymentPacketLength
                ).third.packet
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                payToOpenRequest = payToOpenRequest,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.ACCEPTED } // Yay!
            assertEquals(
                setOf(
                    PayToOpenResponseEvent(
                        PayToOpenResponse(
                            payToOpenRequest.chainHash,
                            payToOpenRequest.paymentHash,
                            PayToOpenResponse.Result.Success(incomingPayment.paymentPreimage)
                        )
                    )
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `receive pay-to-open payment with an unknown payment hash`() {

        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val totalAmount = MilliSatoshi(100.sat)
        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = totalAmount)

        run {
            val payToOpenRequest = PayToOpenRequest(
                chainHash = ByteVector32.Zeroes,
                fundingSatoshis = 100000.sat,
                amountMsat = totalAmount,
                feeSatoshis = 100.sat,
                paymentHash = ByteVector32.One, // <-- not associated to a pending invoice
                feeThresholdSatoshis = 1000.sat,
                feeProportionalMillionths = 100,
                expireAt = Long.MAX_VALUE,
                finalPacket = OutgoingPacket.buildPacket(
                    paymentHash = ByteVector32.One, // <-- has to be the same as the one above otherwise encryption fails
                    hops = channelHops(paymentHandler.nodeParams.nodeId),
                    finalPayload = makeMppPayload(
                        amount = totalAmount,
                        totalAmount = totalAmount,
                        paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                    ),
                    payloadLength = OnionRoutingPacket.PaymentPacketLength
                ).third.packet
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                payToOpenRequest = payToOpenRequest,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.REJECTED }
            assertEquals(
                setOf(
                    PayToOpenResponseEvent(
                        PayToOpenResponse(
                            payToOpenRequest.chainHash,
                            payToOpenRequest.paymentHash,
                            PayToOpenResponse.Result.Failure(
                                OutgoingPacket.buildHtlcFailure(
                                    paymentHandler.nodeParams.nodePrivateKey,
                                    payToOpenRequest.paymentHash,
                                    payToOpenRequest.finalPacket,
                                    CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(payToOpenRequest.amountMsat, TestConstants.defaultBlockHeight.toLong()))).get())
                        )
                    )
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `receive pay-to-open payment with an incorrect payment secret`() {

        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val totalAmount = MilliSatoshi(100.sat)
        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = totalAmount)

        run {
            val payToOpenRequest = PayToOpenRequest(
                chainHash = ByteVector32.Zeroes,
                fundingSatoshis = 100000.sat,
                amountMsat = totalAmount,
                feeSatoshis = 100.sat,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                feeThresholdSatoshis = 1000.sat,
                feeProportionalMillionths = 100,
                expireAt = Long.MAX_VALUE,
                finalPacket = OutgoingPacket.buildPacket(
                    paymentHash = incomingPayment.paymentRequest.paymentHash,
                    hops = channelHops(paymentHandler.nodeParams.nodeId),
                    finalPayload = makeMppPayload(
                        amount = totalAmount,
                        totalAmount = totalAmount,
                        paymentSecret = ByteVector32.One // <-- wrong value
                    ),
                    payloadLength = OnionRoutingPacket.PaymentPacketLength
                ).third.packet
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                payToOpenRequest = payToOpenRequest,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.REJECTED }
            assertEquals(
                setOf(
                    PayToOpenResponseEvent(
                        PayToOpenResponse(
                            payToOpenRequest.chainHash,
                            payToOpenRequest.paymentHash,
                            PayToOpenResponse.Result.Failure(
                                OutgoingPacket.buildHtlcFailure(
                                    paymentHandler.nodeParams.nodePrivateKey,
                                    payToOpenRequest.paymentHash,
                                    payToOpenRequest.finalPacket,
                                    CMD_FAIL_HTLC.Reason.Failure(IncorrectOrUnknownPaymentDetails(payToOpenRequest.amountMsat, TestConstants.defaultBlockHeight.toLong()))).get())
                        )
                    )
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `receive mpp payment with multiple HTLC's via same channel`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val firstId = nextId
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2

        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = totalAmount)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount1,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { par.actions.count() == 0 }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount2,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.ACCEPTED } // Yay!
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(CMD_FULFILL_HTLC(firstId, incomingPayment.paymentPreimage, commit = true))
                    ),
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(CMD_FULFILL_HTLC(firstId + 1, incomingPayment.paymentPreimage, commit = true))
                    ),
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `receive mpp payment with multiple HTLC's via different channels`() {

        val channelId1: ByteVector32 = Eclair.randomBytes32()
        val channelId2: ByteVector32 = Eclair.randomBytes32()
        val firstId = nextId
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2

        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = totalAmount)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId1,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount1,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { par.actions.count() == 0 }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId2,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount2,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.ACCEPTED } // Yay!
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId1,
                        ExecuteCommand(CMD_FULFILL_HTLC(firstId, incomingPayment.paymentPreimage, commit = true))
                    ),
                    WrappedChannelEvent(
                        channelId2,
                        ExecuteCommand(CMD_FULFILL_HTLC(firstId + 1, incomingPayment.paymentPreimage, commit = true))
                    ),
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `receive mpp payment via pay-to-open`() {

        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2

        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = totalAmount)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val payToOpenRequest = PayToOpenRequest(
                chainHash = ByteVector32.Zeroes,
                fundingSatoshis = 100000.sat,
                amountMsat = amount1,
                feeSatoshis = 100.sat,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                feeThresholdSatoshis = 1000.sat,
                feeProportionalMillionths = 100,
                expireAt = Long.MAX_VALUE,
                finalPacket = OutgoingPacket.buildPacket(
                    paymentHash = incomingPayment.paymentRequest.paymentHash,
                    hops = channelHops(paymentHandler.nodeParams.nodeId),
                    finalPayload = makeMppPayload(
                        amount = amount1,
                        totalAmount = totalAmount,
                        paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                    ),
                    payloadLength = OnionRoutingPacket.PaymentPacketLength
                ).third.packet
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                payToOpenRequest = payToOpenRequest,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { par.actions.count() == 0 }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val payToOpenRequest = PayToOpenRequest(
                chainHash = ByteVector32.Zeroes,
                fundingSatoshis = 100000.sat,
                amountMsat = amount2,
                feeSatoshis = 100.sat,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                feeThresholdSatoshis = 1000.sat,
                feeProportionalMillionths = 100,
                expireAt = Long.MAX_VALUE,
                finalPacket = OutgoingPacket.buildPacket(
                    paymentHash = incomingPayment.paymentRequest.paymentHash,
                    hops = channelHops(paymentHandler.nodeParams.nodeId),
                    finalPayload = makeMppPayload(
                        amount = amount2,
                        totalAmount = totalAmount,
                        paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                    ),
                    payloadLength = OnionRoutingPacket.PaymentPacketLength
                ).third.packet
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                payToOpenRequest = payToOpenRequest,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.ACCEPTED } // Yay!
            assertEquals(
                setOf(
                    PayToOpenResponseEvent(
                        PayToOpenResponse(
                            payToOpenRequest.chainHash,
                            payToOpenRequest.paymentHash,
                            PayToOpenResponse.Result.Success(incomingPayment.paymentPreimage)
                        )
                    )
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `receive mpp payment with a mix of HTLC and pay-to-open`() {

        val channelId1: ByteVector32 = Eclair.randomBytes32()
        val firstId = nextId
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2

        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = totalAmount)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId1,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount1,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { par.actions.count() == 0 }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val payToOpenRequest = PayToOpenRequest(
                chainHash = ByteVector32.Zeroes,
                fundingSatoshis = 100000.sat,
                amountMsat = amount2,
                feeSatoshis = 100.sat,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                feeThresholdSatoshis = 1000.sat,
                feeProportionalMillionths = 100,
                expireAt = Long.MAX_VALUE,
                finalPacket = OutgoingPacket.buildPacket(
                    paymentHash = incomingPayment.paymentRequest.paymentHash,
                    hops = channelHops(paymentHandler.nodeParams.nodeId),
                    finalPayload = makeMppPayload(
                        amount = amount2,
                        totalAmount = totalAmount,
                        paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                    ),
                    payloadLength = OnionRoutingPacket.PaymentPacketLength
                ).third.packet
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                payToOpenRequest = payToOpenRequest,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.ACCEPTED } // Yay!
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId1,
                        ExecuteCommand(CMD_FULFILL_HTLC(firstId, incomingPayment.paymentPreimage, commit = true))
                    ),
                    PayToOpenResponseEvent(
                        PayToOpenResponse(
                            payToOpenRequest.chainHash,
                            payToOpenRequest.paymentHash,
                            PayToOpenResponse.Result.Success(incomingPayment.paymentPreimage)
                        )
                    ),
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `receive normal single HTLC, with amountless invoice`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val amount = MilliSatoshi(100.sat)
        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = null)

        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount,
                    totalAmount = amount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.ACCEPTED } // Yay!
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(
                            CMD_FULFILL_HTLC(
                                updateAddHtlc.id,
                                incomingPayment.paymentPreimage,
                                commit = true
                            )
                        )
                    )
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `receive mpp payment, with amountless invoice`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val firstId = nextId
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2

        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = null)

        // Step 1 of 2:
        // - Alice sends first multipart htlc to Bob
        // - Bob doesn't accept the MPP set yet
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount1,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { par.actions.count() == 0 }
        }

        // Step 2 of 2:
        // - Alice sends second multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount2,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.ACCEPTED } // Yay !!
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(CMD_FULFILL_HTLC(firstId, incomingPayment.paymentPreimage, commit = true))
                    ),
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(CMD_FULFILL_HTLC(firstId + 1, incomingPayment.paymentPreimage, commit = true))
                    ),
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `receive mpp payment, with amount greater than totalAmount`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val firstId = nextId
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val amount3 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2 + amount3 // requested <= received <= requested*2
        val requestedAmount = MilliSatoshi(250.sat)

        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = requestedAmount)

        // Step 1 of 2:
        // - Alice sends first 2 multipart htlc's to Bob.
        // - Bob doesn't accept the MPP set yet
        listOf(amount1, amount2).forEach { amount ->

            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { par.actions.count() == 0 }
        }

        // Step 2 of 2:
        // - Alice sends third multipart htlc to Bob
        // - Bob now accepts the MPP set
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount3,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.ACCEPTED } // Yay !!
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(CMD_FULFILL_HTLC(firstId, incomingPayment.paymentPreimage, commit = true))
                    ),
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(CMD_FULFILL_HTLC(firstId + 1, incomingPayment.paymentPreimage, commit = true))
                    ),
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(CMD_FULFILL_HTLC(firstId + 2, incomingPayment.paymentPreimage, commit = true))
                    )
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `invoice expired`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2

        val incomingPayment = makeIncomingPayment(
            payee = paymentHandler,
            amount = totalAmount,
            timestamp = currentTimestampSeconds() - 3600 - 60, // over one hour ago
            expirySeconds = 3600 // one hour expiration
        )

        // Bob rejects incoming HTLC because the corresponding invoice is expired
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount1,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.REJECTED }
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(
                            CMD_FAIL_HTLC(
                                updateAddHtlc.id, CMD_FAIL_HTLC.Reason.Failure(
                                    IncorrectOrUnknownPaymentDetails(
                                        totalAmount,
                                        TestConstants.defaultBlockHeight.toLong()
                                    )
                                ), commit = true
                            )
                        )
                    ),
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `invoice unknown`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        run {
            val amount = MilliSatoshi(100.sat)
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = Eclair.randomBytes32(),
                finalPayload = makeMppPayload(
                    amount = amount,
                    totalAmount = amount,
                    paymentSecret = Eclair.randomBytes32()
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.REJECTED }
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(
                            CMD_FAIL_HTLC(
                                updateAddHtlc.id, CMD_FAIL_HTLC.Reason.Failure(
                                    IncorrectOrUnknownPaymentDetails(
                                        amount,
                                        TestConstants.defaultBlockHeight.toLong()
                                    )
                                ), commit = true
                            )
                        )
                    ),
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `invalid onion`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val amount = MilliSatoshi(100.sat)
        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = amount)

        run {
            val cltvExpiry = CltvExpiryDelta(144).toCltvExpiry(TestConstants.defaultBlockHeight.toLong())
            val badOnion = OnionRoutingPacket(
                version = 0,
                publicKey = ByteVector("0x02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"),
                payload = Eclair.randomBytes(OnionRoutingPacket.PaymentPacketLength).toByteVector(),
                hmac = Eclair.randomBytes32()
            )
            val updateAddHtlc = UpdateAddHtlc(
                channelId = channelId,
                id = 0,
                amountMsat = amount,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                cltvExpiry = cltvExpiry,
                onionRoutingPacket = badOnion
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.REJECTED }
            // The current flow of error checking within the codebase would be:
            // 1. InvalidOnionKey
            // 2. InvalidOnionHmac
            // Since we used a valid pubKey, we should get an hmac failure.
            val expectedErr = InvalidOnionHmac(Sphinx.hash(badOnion))
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(
                            CMD_FAIL_MALFORMED_HTLC(
                                updateAddHtlc.id,
                                onionHash = expectedErr.onionHash,
                                failureCode = expectedErr.code,
                                commit = true
                            )
                        )
                    ),
                ), par.actions.toSet()
            )
        }

    }

    @Test
    fun `invalid cltv expiry`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2

        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = totalAmount)

        // Bob rejects incoming HTLC because the CLTV is too low
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount1,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!,
                    cltvExpiryDelta = CltvExpiryDelta(2) // <== Too low
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.REJECTED }
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(
                            CMD_FAIL_HTLC(
                                updateAddHtlc.id, CMD_FAIL_HTLC.Reason.Failure(
                                    IncorrectOrUnknownPaymentDetails(
                                        totalAmount,
                                        TestConstants.defaultBlockHeight.toLong()
                                    )
                                ), commit = true
                            )
                        )
                    ),
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `amount too low or too high`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val lowAmount = MilliSatoshi(100.sat)
        val requestedAmount = MilliSatoshi(150.sat)
        val highAmount = MilliSatoshi(400.sat)

        val incomingPayment = makeIncomingPayment(
            payee = paymentHandler,
            amount = requestedAmount
        )

        val payloads = listOf(
            makeMppPayload(
                amount = lowAmount,
                totalAmount = lowAmount, // too low
                paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
            ),
            makeMppPayload(
                amount = highAmount,
                totalAmount = highAmount, // too high
                paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
            )
        )

        payloads.forEach { payload ->

            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = payload
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.REJECTED }
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(
                            CMD_FAIL_HTLC(
                                updateAddHtlc.id, CMD_FAIL_HTLC.Reason.Failure(
                                    IncorrectOrUnknownPaymentDetails(
                                        payload.totalAmount,
                                        TestConstants.defaultBlockHeight.toLong()
                                    )
                                ), commit = true
                            )
                        )
                    ),
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `mpp total_amount mismatch`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val firstId = nextId
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val amount3 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2 + amount3

        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = totalAmount)

        // Step 1 of 2:
        //
        // Alice sends first multipart htlc to Bob.
        // Ensure that:
        // - Bob doesn't accept the MPP set yet
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount1,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { par.actions.count() == 0 }
        }

        // Step 2 of 2:
        //
        // Alice sends second multipart htlc to Bob.
        // Ensure that:
        // - Bob detects some shenanigans
        // - Bob rejects the entire MPP set (as per spec)
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount2,
                    totalAmount = totalAmount + MilliSatoshi(1), // goofy mismatch. (not less than totalAmount)
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.REJECTED } // should fail due to non-matching total_amounts
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(
                            CMD_FAIL_HTLC(
                                firstId,
                                CMD_FAIL_HTLC.Reason.Failure(
                                    IncorrectOrUnknownPaymentDetails(
                                        totalAmount,
                                        TestConstants.defaultBlockHeight.toLong()
                                    )
                                ),
                                commit = true
                            )
                        )
                    ),
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(
                            CMD_FAIL_HTLC(
                                firstId + 1,
                                CMD_FAIL_HTLC.Reason.Failure(
                                    IncorrectOrUnknownPaymentDetails(
                                        totalAmount + 1.msat,
                                        TestConstants.defaultBlockHeight.toLong()
                                    )
                                ),
                                commit = true
                            )
                        )
                    ),
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `invalid payment secret`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2

        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = totalAmount)

        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount1,
                    totalAmount = totalAmount,
                    paymentSecret = Eclair.randomBytes32() // <== Wrong !
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.REJECTED }
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(
                            CMD_FAIL_HTLC(
                                updateAddHtlc.id, CMD_FAIL_HTLC.Reason.Failure(
                                    IncorrectOrUnknownPaymentDetails(
                                        totalAmount,
                                        TestConstants.defaultBlockHeight.toLong()
                                    )
                                ), commit = true
                            )
                        )
                    ),
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `mpp timeout`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val firstId = nextId
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val amount1 = MilliSatoshi(100.sat)
        val totalAmount = amount1 * 2

        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = totalAmount)
        val startTime = currentTimestampSeconds()

        // Step 1 of 3:
        // - Alice sends single (unfinished) multipart htlc to Bob.
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount1,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { par.actions.count() == 0 }
        }

        // Step 2 of 3:
        // - don't expire the multipart htlc too soon.
        run {
            val expiry = paymentHandler.nodeParams.multiPartPaymentExpiry
            val currentTimestampSeconds = startTime + expiry - 2

            val actions = paymentHandler.checkPaymentsTimeout(currentTimestampSeconds)

            assertTrue { actions.isEmpty() }
        }

        // Step 3 of 3:
        // - expire the htlc-set after configured expiration.
        run {
            val expiry = paymentHandler.nodeParams.multiPartPaymentExpiry
            val currentTimestampSeconds = startTime + expiry + 2

            val actions = paymentHandler.checkPaymentsTimeout(currentTimestampSeconds)

            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(
                            CMD_FAIL_HTLC(
                                firstId,
                                CMD_FAIL_HTLC.Reason.Failure(PaymentTimeout),
                                commit = true
                            )
                        )
                    ),
                ), actions.toSet()
            )
        }
    }

    @Test
    fun `mpp timeout then success`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val firstId = nextId
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2

        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = totalAmount)
        val startTime = currentTimestampSeconds()

        // Step 1 of 4:
        // - Alice sends single (unfinished) multipart htlc to Bob.
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount1,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { par.actions.count() == 0 }
        }

        // Step 2 of 4:
        // - the MPP set times out
        run {
            val expiry = paymentHandler.nodeParams.multiPartPaymentExpiry
            val currentTimestampSeconds = startTime + expiry + 2

            val actions = paymentHandler.checkPaymentsTimeout(currentTimestampSeconds)

            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(
                            CMD_FAIL_HTLC(
                                firstId,
                                CMD_FAIL_HTLC.Reason.Failure(PaymentTimeout),
                                commit = true
                            )
                        )
                    ),
                ), actions.toSet()
            )
        }

        // Step 3 of 4:
        // - Alice tries again, and sends another single (unfinished) multipart htlc to Bob.
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount1,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { par.actions.count() == 0 }
        }

        // Step 3 of 4:
        // - Alice sends second and last part of mpp
        // - Bob accepts htlc set
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount2,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.ACCEPTED } // Yay !!
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(CMD_FULFILL_HTLC(firstId + 1, incomingPayment.paymentPreimage, commit = true))
                    ),
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(CMD_FULFILL_HTLC(firstId + 2, incomingPayment.paymentPreimage, commit = true))
                    ),
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `mpp success then additional HTLC`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val firstId = nextId
        val paymentHandler = IncomingPaymentHandler(TestConstants.Bob.nodeParams)

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2
        val spuriousAmount = MilliSatoshi(50.sat)

        val incomingPayment = makeIncomingPayment(payee = paymentHandler, amount = totalAmount)

        // Step 1 of 2:
        // - Alice receives first mpp (unfinished at this point)
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount1,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.PENDING }
            assertTrue { par.actions.count() == 0 }
        }

        // Step 2 of 3:
        // - Alice receives another mpp, which concludes the set
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = amount2,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.ACCEPTED } // Yay !!
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(CMD_FULFILL_HTLC(firstId, incomingPayment.paymentPreimage, commit = true))
                    ),
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(CMD_FULFILL_HTLC(firstId + 1, incomingPayment.paymentPreimage, commit = true))
                    ),
                ), par.actions.toSet()
            )
        }

        // Step 3 of 3:
        // - Alice receives additional htlc for the invoice, which she already completed
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                destination = paymentHandler,
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                finalPayload = makeMppPayload(
                    amount = spuriousAmount,
                    totalAmount = totalAmount,
                    paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
                )
            )

            // NB: When the PaymentHandler returns a result of ProcessedStatus.ACCEPTED,
            // the Peer is expected to remove the invoice from the database,
            // or otherwise mark the invoice as Paid.
            // Whatever the case, it must not re-use that invoice for processing again.

            val par: IncomingPaymentHandler.ProcessAddResult = paymentHandler.process(
                htlc = updateAddHtlc,
                currentBlockHeight = TestConstants.defaultBlockHeight
            )

            assertTrue { par.status == IncomingPaymentHandler.Status.REJECTED }
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(
                            CMD_FAIL_HTLC(
                                updateAddHtlc.id, CMD_FAIL_HTLC.Reason.Failure(
                                    IncorrectOrUnknownPaymentDetails(
                                        totalAmount,
                                        TestConstants.defaultBlockHeight.toLong()
                                    )
                                ), commit = true
                            )
                        )
                    ),
                ), par.actions.toSet()
            )
        }
    }
}
