package fr.acinq.eclair.payment

import fr.acinq.bitcoin.*
import fr.acinq.eclair.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.io.WrappedChannelEvent
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.*
//import fr.acinq.eclair.utils.UUID
//import fr.acinq.eclair.utils.msat
//import fr.acinq.eclair.utils.sat
//import fr.acinq.eclair.utils.toByteVector32
import fr.acinq.eclair.wire.*
import kotlin.test.*


class PaymentHandlerTestsCommon : EclairTestSuite() {

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
        amount: MilliSatoshi,
        totalAmount: MilliSatoshi,
        destination: PublicKey,
        currentBlockHeight: Long,
        paymentHash: ByteVector32,
        paymentSecret: ByteVector32
    ): CMD_ADD_HTLC {

        val expiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
        val finalPayload = FinalPayload.createMultiPartPayload(amount, totalAmount, expiry, paymentSecret)

        return OutgoingPacket.buildCommand(
            id = UUID.randomUUID(),
            paymentHash = paymentHash,
            hops = channelHops(destination),
            finalPayload = finalPayload
        ).first.copy(commit = true)
    }

    private fun makeUpdateAddHtlc(
        channelId: ByteVector32,
        id: Long,
        amount: MilliSatoshi,
        totalAmount: MilliSatoshi,
        destination: PublicKey,
        currentBlockHeight: Long,
        paymentHash: ByteVector32,
        paymentSecret: ByteVector32,
        cltvExpiryDelta: CltvExpiryDelta = CltvExpiryDelta(144)
    ): UpdateAddHtlc {

        val expiry = cltvExpiryDelta.toCltvExpiry(currentBlockHeight)
        val finalPayload = FinalPayload.createMultiPartPayload(amount, totalAmount, expiry, paymentSecret)

        val (_, _, packetAndSecrets) = OutgoingPacket.buildPacket(
            paymentHash = paymentHash,
            hops = channelHops(destination),
            finalPayload = finalPayload,
            payloadLength = OnionRoutingPacket.PaymentPacketLength
        )

        return UpdateAddHtlc(
            channelId = channelId,
            id = id,
            amountMsat = amount,
            paymentHash = paymentHash,
            cltvExpiry = expiry,
            onionRoutingPacket = packetAndSecrets.packet
        )
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

        val cmdAddHtlc = makeCmdAddHtlc(
            amount = amount,
            totalAmount = totalAmount,
            destination = bob.staticParams.nodeParams.nodeId,
            currentBlockHeight = alice.currentBlockHeight.toLong(),
            paymentHash = paymentHash,
            paymentSecret = paymentSecret
        )

        var processResult: Pair<ChannelState, List<ChannelAction>>
        var actions: List<ChannelAction>

        val a2b = object {
            var updateAddHtlc: SendMessage? = null
            var commitmentSigned: SendMessage? = null
            var revokeAndAck: SendMessage? = null
        }
        val a2a = object {
            var cmdSign: ProcessCommand? = null
        }
        val b2a = object {
            var revokeAndAck: SendMessage? = null
            var commitmentSigned: SendMessage? = null
        }
        val b2b = object {
            var cmdSign: ProcessCommand? = null
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

        a2a.cmdSign = actions.filterIsInstance<ProcessCommand>().firstOrNull { it.command == CMD_SIGN }
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

        b2b.cmdSign = actions.filterIsInstance<ProcessCommand>().firstOrNull { it.command == CMD_SIGN }
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
        payee: NodeParams,
        amount: MilliSatoshi?,
        timestamp: Long = currentTimestampSeconds(),
        expirySeconds: Long? = null,
    ): IncomingPayment {

        val paymentPreimage: ByteVector32 = Eclair.randomBytes32()
        val paymentHash = Crypto.sha256(paymentPreimage).toByteVector32()

        val invoiceFeatures = setOf(
            ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
            ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Optional),
            ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional)
        )
        val paymentRequest = PaymentRequest.create(
            chainHash = payee.chainHash,
            amount = amount,
            paymentHash = paymentHash,
            privateKey = payee.nodePrivateKey, // Payee creates invoice, sends to payer
            description = "unit test",
            minFinalCltvExpiryDelta = PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
            features = Features(invoiceFeatures),
            timestamp = timestamp,
            expirySeconds = expirySeconds
        )

        val incomingPayment = IncomingPayment(
            paymentRequest = paymentRequest,
            paymentPreimage = paymentPreimage
        )

        return incomingPayment
    }

    @Test
    fun `PaymentHandler should accept non-mpp payment`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val currentBlockHeight = TestConstants.defaultBlockHeight

        val bob = TestConstants.Bob

        val totalAmount = MilliSatoshi(100.sat)
        val incomingPayment = makeIncomingPayment(payee = bob.nodeParams, amount = totalAmount)

        // Bob should accept full payment
        run {

            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                id = 0,
                amount = totalAmount,
                totalAmount = totalAmount,
                destination = bob.nodeParams.nodeId,
                currentBlockHeight = currentBlockHeight.toLong(),
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
            )

            val par: PaymentHandler.ProcessAddResult = bob.paymentHandler.processAdd(
                htlc = updateAddHtlc,
                incomingPayment = incomingPayment,
                currentBlockHeight = currentBlockHeight
            )

            assertTrue { par.status == PaymentHandler.ProcessedStatus.ACCEPTED } // Yay!
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(CMD_FULFILL_HTLC(0, incomingPayment.paymentPreimage, commit = true))
                    )
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `PaymentHandler should accept payment after all MPPs received`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val currentBlockHeight = TestConstants.defaultBlockHeight

        val bob = TestConstants.Bob

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2

        val incomingPayment = makeIncomingPayment(payee = bob.nodeParams, amount = totalAmount)

        // Step 1 of 2:
        //
        // Alice sends first multipart htlc to Bob.
        // Ensure that:
        // - Bob doesn't accept the MPP set yet
        run {

            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                id = 0,
                amount = amount1,
                totalAmount = totalAmount,
                destination = bob.nodeParams.nodeId,
                currentBlockHeight = currentBlockHeight.toLong(),
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
            )

            val par: PaymentHandler.ProcessAddResult = bob.paymentHandler.processAdd(
                htlc = updateAddHtlc,
                incomingPayment = incomingPayment,
                currentBlockHeight = currentBlockHeight
            )

            assertTrue { par.status == PaymentHandler.ProcessedStatus.PENDING }
            assertTrue { par.actions.count() == 0 }
        }

        // Step 2 of 2:
        //
        // Alice sends second multipart htlc to Bob.
        // Ensure that:
        // - Bob now accepts the MPP set
        run {

            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                id = 1,
                amount = amount2,
                totalAmount = totalAmount,
                destination = bob.nodeParams.nodeId,
                currentBlockHeight = currentBlockHeight.toLong(),
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
            )

            val par: PaymentHandler.ProcessAddResult = bob.paymentHandler.processAdd(
                htlc = updateAddHtlc,
                incomingPayment = incomingPayment,
                currentBlockHeight = currentBlockHeight
            )

            assertTrue { par.status == PaymentHandler.ProcessedStatus.ACCEPTED } // Yay!
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(CMD_FULFILL_HTLC(0, incomingPayment.paymentPreimage, commit = true))
                    ),
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(CMD_FULFILL_HTLC(1, incomingPayment.paymentPreimage, commit = true))
                    ),
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `PaymentHandler should reject MPP set if total_amount's don't match`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val currentBlockHeight = TestConstants.defaultBlockHeight

        val bob = TestConstants.Bob

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val amount3 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2 + amount3

        val incomingPayment = makeIncomingPayment(payee = bob.nodeParams, amount = totalAmount)

        // Step 1 of 2:
        //
        // Alice sends first multipart htlc to Bob.
        // Ensure that:
        // - Bob doesn't accept the MPP set yet
        run {

            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                id = 0,
                amount = amount1,
                totalAmount = totalAmount,
                destination = bob.nodeParams.nodeId,
                currentBlockHeight = currentBlockHeight.toLong(),
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
            )

            val par: PaymentHandler.ProcessAddResult = bob.paymentHandler.processAdd(
                htlc = updateAddHtlc,
                incomingPayment = incomingPayment,
                currentBlockHeight = currentBlockHeight
            )

            assertTrue { par.status == PaymentHandler.ProcessedStatus.PENDING }
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
                id = 1,
                amount = amount2,
                totalAmount = totalAmount + MilliSatoshi(1), // goofy mismatch. (not less than totalAmount)
                destination = bob.nodeParams.nodeId,
                currentBlockHeight = currentBlockHeight.toLong(),
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
            )

            val par: PaymentHandler.ProcessAddResult = bob.paymentHandler.processAdd(
                htlc = updateAddHtlc,
                incomingPayment = incomingPayment,
                currentBlockHeight = currentBlockHeight
            )

            assertTrue { par.status == PaymentHandler.ProcessedStatus.REJECTED } // should fail due to non-matching total_amounts
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(
                            CMD_FAIL_HTLC(
                                0,
                                CMD_FAIL_HTLC.Reason.Failure(
                                    IncorrectOrUnknownPaymentDetails(
                                        totalAmount,
                                        currentBlockHeight.toLong()
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
                                1,
                                CMD_FAIL_HTLC.Reason.Failure(
                                    IncorrectOrUnknownPaymentDetails(
                                        totalAmount + 1.msat,
                                        currentBlockHeight.toLong()
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
    fun `PaymentHandler should reject payment after invoice expiration`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val currentBlockHeight = TestConstants.defaultBlockHeight

        val bob = TestConstants.Bob

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2

        val incomingPayment = makeIncomingPayment(
            payee = bob.nodeParams,
            amount = totalAmount,
            timestamp = currentTimestampSeconds() - 3600 - 60, // over one hour ago
            expirySeconds = 3600 // one hour expiration
        )

        // Bob rejects incoming HTLC because the corresponding invoice is expired
        run {

            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                id = 0,
                amount = amount1,
                totalAmount = totalAmount,
                destination = bob.nodeParams.nodeId,
                currentBlockHeight = currentBlockHeight.toLong(),
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
            )

            val par: PaymentHandler.ProcessAddResult = bob.paymentHandler.processAdd(
                htlc = updateAddHtlc,
                incomingPayment = incomingPayment,
                currentBlockHeight = currentBlockHeight
            )

            assertTrue { par.status == PaymentHandler.ProcessedStatus.REJECTED }
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(
                            CMD_FAIL_HTLC(
                                0, CMD_FAIL_HTLC.Reason.Failure(
                                    IncorrectOrUnknownPaymentDetails(
                                        totalAmount,
                                        currentBlockHeight.toLong()
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
    fun `PaymentHandler should reject payment if paymentSecret is invalid`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val currentBlockHeight = TestConstants.defaultBlockHeight

        val bob = TestConstants.Bob

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2

        val incomingPayment = makeIncomingPayment(payee = bob.nodeParams, amount = totalAmount)

        // Bob rejects incoming HTLC because the paymentSecret doesn't match
        run {

            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                id = 0,
                amount = amount1,
                totalAmount = totalAmount,
                destination = bob.nodeParams.nodeId,
                currentBlockHeight = currentBlockHeight.toLong(),
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                paymentSecret = Eclair.randomBytes32() // <== Wrong !
            )

            val par: PaymentHandler.ProcessAddResult = bob.paymentHandler.processAdd(
                htlc = updateAddHtlc,
                incomingPayment = incomingPayment,
                currentBlockHeight = currentBlockHeight
            )

            assertTrue { par.status == PaymentHandler.ProcessedStatus.REJECTED }
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(
                            CMD_FAIL_HTLC(
                                0, CMD_FAIL_HTLC.Reason.Failure(
                                    IncorrectOrUnknownPaymentDetails(
                                        totalAmount,
                                        currentBlockHeight.toLong()
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
    fun `PaymentHandler should reject payment if CLTV is too low`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val currentBlockHeight = TestConstants.defaultBlockHeight

        val bob = TestConstants.Bob

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2

        val incomingPayment = makeIncomingPayment(payee = bob.nodeParams, amount = totalAmount)

        // Bob rejects incoming HTLC because the CLTV is too low
        run {

            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                id = 0,
                amount = amount1,
                totalAmount = totalAmount,
                destination = bob.nodeParams.nodeId,
                currentBlockHeight = currentBlockHeight.toLong(),
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                paymentSecret = incomingPayment.paymentRequest.paymentSecret!!,
                cltvExpiryDelta = CltvExpiryDelta(2) // <== Too low
            )

            val par: PaymentHandler.ProcessAddResult = bob.paymentHandler.processAdd(
                htlc = updateAddHtlc,
                incomingPayment = incomingPayment,
                currentBlockHeight = currentBlockHeight
            )

            assertTrue { par.status == PaymentHandler.ProcessedStatus.REJECTED }
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(
                            CMD_FAIL_HTLC(
                                0, CMD_FAIL_HTLC.Reason.Failure(
                                    IncorrectOrUnknownPaymentDetails(
                                        totalAmount,
                                        currentBlockHeight.toLong()
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
    fun `PaymentHandler should fail unfinished payments after expiration`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val currentBlockHeight = TestConstants.defaultBlockHeight

        val bob = TestConstants.Bob

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2

        val incomingPayment = makeIncomingPayment(payee = bob.nodeParams, amount = totalAmount)

        // Step 1 of 3:
        // Alice sends single (unfinished) multipart htlc to Bob.
        run {

            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                id = 0,
                amount = amount1,
                totalAmount = totalAmount,
                destination = bob.nodeParams.nodeId,
                currentBlockHeight = currentBlockHeight.toLong(),
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
            )

            val par: PaymentHandler.ProcessAddResult = bob.paymentHandler.processAdd(
                htlc = updateAddHtlc,
                incomingPayment = incomingPayment,
                currentBlockHeight = currentBlockHeight
            )

            assertTrue { par.status == PaymentHandler.ProcessedStatus.PENDING }
            assertTrue { par.actions.count() == 0 }
        }

        // Step 2 of 3:
        // Ensure PaymentHandler doesn't expire the multipart htlc too soon.
        run {

            val expiry = bob.nodeParams.multiPartPaymentExpiry
            val currentTimestampSeconds = incomingPayment.paymentRequest.timestamp + expiry - 1

            val actions = bob.paymentHandler.checkPaymentsTimeout(
                incomingPayments = mapOf(incomingPayment.paymentRequest.paymentHash to incomingPayment),
                currentTimestampSeconds = currentTimestampSeconds
            )

            assertTrue { actions.isEmpty() }
        }

        // Step 3 of 3:
        // Ensure PaymentHandler expires the htlc-set after configured expiration.
        run {

            val expiry = bob.nodeParams.multiPartPaymentExpiry
            assertTrue { expiry >= 60 } // BOLT 04 suggests minimum of 60 seconds
            val currentTimestampSeconds = incomingPayment.paymentRequest.timestamp + expiry + 1

            val actions = bob.paymentHandler.checkPaymentsTimeout(
                incomingPayments = mapOf(incomingPayment.paymentRequest.paymentHash to incomingPayment),
                currentTimestampSeconds = currentTimestampSeconds
            )

            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(CMD_FAIL_HTLC(0, CMD_FAIL_HTLC.Reason.Failure(PaymentTimeout), commit = true))
                    ),
                ), actions.toSet()
            )
        }
    }

    @Test
    fun `PaymentHandler should accept single-part keysend payment`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val currentBlockHeight = TestConstants.defaultBlockHeight

        val bob = TestConstants.Bob
        val amount = MilliSatoshi(100.sat)

        val incomingPayment = makeIncomingPayment(payee = bob.nodeParams, amount = null)

        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                id = 0,
                amount = amount,
                totalAmount = amount,
                destination = bob.nodeParams.nodeId,
                currentBlockHeight = currentBlockHeight.toLong(),
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
            )

            val par: PaymentHandler.ProcessAddResult = bob.paymentHandler.processAdd(
                htlc = updateAddHtlc,
                incomingPayment = incomingPayment,
                currentBlockHeight = currentBlockHeight
            )

            assertTrue { par.status == PaymentHandler.ProcessedStatus.ACCEPTED } // Yay!
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(CMD_FULFILL_HTLC(0, incomingPayment.paymentPreimage, commit = true))
                    )
                ), par.actions.toSet()
            )
        }
    }

    @Test
    fun `PaymentHandler should accept multi-part keysend payment`() {

        val channelId: ByteVector32 = Eclair.randomBytes32()
        val currentBlockHeight = TestConstants.defaultBlockHeight

        val bob = TestConstants.Bob

        val amount1 = MilliSatoshi(100.sat)
        val amount2 = MilliSatoshi(100.sat)
        val totalAmount = amount1 + amount2

        val incomingPayment = makeIncomingPayment(payee = bob.nodeParams, amount = null)

        // Step 1 of 2:
        //
        // Alice sends first multipart htlc to Bob.
        // Ensure that:
        // - Bob doesn't accept the MPP set yet
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                id = 0,
                amount = amount1,
                totalAmount = totalAmount,
                destination = bob.nodeParams.nodeId,
                currentBlockHeight = currentBlockHeight.toLong(),
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
            )

            val par: PaymentHandler.ProcessAddResult = bob.paymentHandler.processAdd(
                htlc = updateAddHtlc,
                incomingPayment = incomingPayment,
                currentBlockHeight = currentBlockHeight
            )

            assertTrue { par.status == PaymentHandler.ProcessedStatus.PENDING }
            assertTrue { par.actions.count() == 0 }
        }

        // Step 2 of 2:
        //
        // Alice sends second multipart htlc to Bob.
        // Ensure that:
        // - Bob now accepts the MPP set
        run {
            val updateAddHtlc = makeUpdateAddHtlc(
                channelId = channelId,
                id = 1,
                amount = amount2,
                totalAmount = totalAmount,
                destination = bob.nodeParams.nodeId,
                currentBlockHeight = currentBlockHeight.toLong(),
                paymentHash = incomingPayment.paymentRequest.paymentHash,
                paymentSecret = incomingPayment.paymentRequest.paymentSecret!!
            )

            val par: PaymentHandler.ProcessAddResult = bob.paymentHandler.processAdd(
                htlc = updateAddHtlc,
                incomingPayment = incomingPayment,
                currentBlockHeight = currentBlockHeight
            )

            assertTrue { par.status == PaymentHandler.ProcessedStatus.ACCEPTED } // Yay !!
            assertEquals(
                setOf(
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(CMD_FULFILL_HTLC(0, incomingPayment.paymentPreimage, commit = true))
                    ),
                    WrappedChannelEvent(
                        channelId,
                        ExecuteCommand(CMD_FULFILL_HTLC(1, incomingPayment.paymentPreimage, commit = true))
                    ),
                ), par.actions.toSet()
            )
        }
    }
}
