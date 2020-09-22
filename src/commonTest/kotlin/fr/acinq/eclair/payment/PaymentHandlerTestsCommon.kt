package fr.acinq.eclair.payment

import fr.acinq.bitcoin.*
import fr.acinq.eclair.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.sat
import fr.acinq.eclair.utils.toByteVector32
import fr.acinq.eclair.wire.*
import kotlin.test.*


class PaymentHandlerTestsCommon {

	/**
	 * Creates a multipart htlc, and wraps it in CMD_ADD_HTLC.
	 * The result is ready to be processed thru the sender's channel.
	 */
	fun makeMpp(
		amount             : MilliSatoshi,
		totalAmount        : MilliSatoshi,
		destination        : PublicKey,
		currentBlockHeight : Long,
		paymentHash        : ByteVector32,
		paymentSecret      : ByteVector32
	): CMD_ADD_HTLC
	{
		val expiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)

		val dummyKey = PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")).publicKey()
		val dummyUpdate = ChannelUpdate(
			signature                 = ByteVector64.Zeroes,
			chainHash                 = ByteVector32.Zeroes,
			shortChannelId            = ShortChannelId(144,0,0),
			timestamp                 = 0,
			messageFlags              = 0,
			channelFlags              = 0,
			cltvExpiryDelta           = CltvExpiryDelta(1),
			htlcMinimumMsat           = 0.msat,
			feeBaseMsat               = 0.msat,
			feeProportionalMillionths = 0,
			htlcMaximumMsat           = null
		)
		val channelHop = ChannelHop(dummyKey, destination, dummyUpdate)

		var tlvRecords = listOf<OnionTlv>(
			OnionTlv.OutgoingCltv(cltv = expiry),
			OnionTlv.AmountToForward(amount = amount),
			OnionTlv.PaymentData(secret = paymentSecret, totalAmount = totalAmount)
		)

		val finalPayload = FinalTlvPayload(TlvStream(records = tlvRecords))

		val id = UUID.randomUUID()
		val cmdAdd = OutgoingPacket.buildCommand(id, paymentHash, listOf(channelHop), finalPayload).first.copy(commit = false)

		return cmdAdd
	}

	/**
	 * Walks thru the following steps (assuming alice=sender, bob=receiver):
	 *
	 * 1) alice => update_add_htlc   => bob
	 * 2) alice => commitment_signed => bob
	 * 3) alice <= revoke_and_ack    <= bob
	 * 4) alice <= commitment_signed <= bob
	 * 5) alice => revoke_and_ack    => bob
	 *
	 * Returns Triple(sender, receiver, ProcessAdd_result_for_receiver)
	 */
	fun sendMppAndDualCommit(
		sender     : ChannelState,
		receiver   : ChannelState,
		cmdAddHtlc : CMD_ADD_HTLC
	): Triple<ChannelState, ChannelState, ProcessAdd>
	{
		var alice = sender
		var bob = receiver

		var processResult: Pair<ChannelState, List<ChannelAction>>
		var actions: List<ChannelAction>

		// Step 1 of 5:
		//
		// alice => update_add_htlc => bob

		processResult = alice.process(ExecuteCommand(cmdAddHtlc))

		alice = processResult.first
		assertTrue { alice is Normal }

		actions = processResult.second.filterIsInstance<SendMessage>()
		assertTrue { actions.size == 1 }

		processResult = bob.process(MessageReceived(actions.first().message))

		bob = processResult.first
		assertTrue { bob is Normal }

		actions = processResult.second.filterIsInstance<SendMessage>()
		assertTrue { actions.size == 0 }

		assertTrue { (alice as Normal).commitments.localChanges.proposed.size == 1 }
		assertTrue { (alice as Normal).commitments.localChanges.signed.size   == 0 }
		assertTrue { (alice as Normal).commitments.localChanges.acked.size    == 0 }

		assertTrue { (bob as Normal).commitments.remoteChanges.proposed.size  == 1 }
		assertTrue { (bob as Normal).commitments.remoteChanges.acked.size     == 0 }
		assertTrue { (bob as Normal).commitments.remoteChanges.signed.size    == 0 }

		// Step 2 of 5:
		//
		// alice => commitment_signed => bob

		processResult = alice.process(ExecuteCommand(CMD_SIGN))

		alice = processResult.first
		assertTrue { alice is Normal }

		actions = processResult.second.filterIsInstance<SendMessage>()
		assertTrue { actions.size == 1 }

		processResult = bob.process(MessageReceived(actions.first().message))

		bob = processResult.first
		assertTrue { bob is Normal }

		actions = processResult.second.filterIsInstance<SendMessage>()
		assertTrue { actions.size == 1 }

		assertTrue { (alice as Normal).commitments.localChanges.proposed.size == 0 }
		assertTrue { (alice as Normal).commitments.localChanges.signed.size   == 1 }
		assertTrue { (alice as Normal).commitments.localChanges.acked.size    == 0 }

		assertTrue { (bob as Normal).commitments.remoteChanges.proposed.size  == 0 }
		assertTrue { (bob as Normal).commitments.remoteChanges.acked.size     == 1 }
		assertTrue { (bob as Normal).commitments.remoteChanges.signed.size    == 0 }

		// Step 3 of 5
		//
		// alice <= revoke_and_ack <= bob

		processResult = alice.process(MessageReceived(actions.first().message))

		alice = processResult.first
		assertTrue { alice is Normal }

		actions = processResult.second.filterIsInstance<SendMessage>()
		assertTrue { actions.size == 0 }

		assertTrue { (alice as Normal).commitments.localChanges.proposed.size == 0 }
		assertTrue { (alice as Normal).commitments.localChanges.signed.size   == 0 }
		assertTrue { (alice as Normal).commitments.localChanges.acked.size    == 1 }

		assertTrue { (bob as Normal).commitments.remoteChanges.proposed.size  == 0 }
		assertTrue { (bob as Normal).commitments.remoteChanges.acked.size     == 1 }
		assertTrue { (bob as Normal).commitments.remoteChanges.signed.size    == 0 }

		// Step 4 of 5:
		//
		// alice <= commitment_signed <= bob

		processResult = bob.process(ExecuteCommand(CMD_SIGN))

		bob = processResult.first
		assertTrue { bob is Normal }

		actions = processResult.second.filterIsInstance<SendMessage>()
		assertTrue { actions.size == 1 }

		processResult = alice.process(MessageReceived(actions.first().message))

		alice = processResult.first
		assertTrue { alice is Normal }

		actions = processResult.second.filterIsInstance<SendMessage>()
		assertTrue { actions.size == 1 }

		assertTrue { (alice as Normal).commitments.localChanges.proposed.size == 0 }
		assertTrue { (alice as Normal).commitments.localChanges.signed.size   == 0 }
		assertTrue { (alice as Normal).commitments.localChanges.acked.size    == 0 }

		assertTrue { (bob as Normal).commitments.remoteChanges.proposed.size  == 0 }
		assertTrue { (bob as Normal).commitments.remoteChanges.acked.size     == 0 }
		assertTrue { (bob as Normal).commitments.remoteChanges.signed.size    == 1 }

		// Step 5 of 5:
		//
		// alice => revoke_and_ack => bob

		processResult = bob.process(MessageReceived(actions.first().message))

		bob = processResult.first
		assertTrue { bob is Normal }

		actions = processResult.second
		assertTrue { actions.filterIsInstance<SendMessage>().size == 0 }
		assertTrue { actions.filterIsInstance<ProcessAdd>().size == 1 }

		assertTrue { (alice as Normal).commitments.localChanges.proposed.size == 0 }
		assertTrue { (alice as Normal).commitments.localChanges.signed.size   == 0 }
		assertTrue { (alice as Normal).commitments.localChanges.acked.size    == 0 }

		assertTrue { (bob as Normal).commitments.remoteChanges.proposed.size  == 0 }
		assertTrue { (bob as Normal).commitments.remoteChanges.acked.size     == 0 }
		assertTrue { (bob as Normal).commitments.remoteChanges.signed.size    == 0 }

		val processAdd = actions.filterIsInstance<ProcessAdd>().first()
		return Triple(alice, bob, processAdd)
	}

	@Test
	fun `PaymentHandler should accept payment after all MPPs received`() {

		val paymentPreimage: ByteVector32 = Eclair.randomBytes32()
		val paymentHash = Crypto.sha256(paymentPreimage).toByteVector32()

		val nodeParams_alice = TestConstants.Alice.nodeParams
		val nodeParams_bob = TestConstants.Bob.nodeParams

		val privKey_alice = nodeParams_alice.privateKey

		val paymentHandler_bob = PaymentHandler(nodeParams_bob)

		val amount_1 = MilliSatoshi(100.sat)
		val amount_2 = MilliSatoshi(100.sat)
		val total_amount = amount_1 + amount_2

		val invoiceFeatures = mutableSetOf(
			ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
			ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Optional),
			ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional)
		)
		val paymentRequest = PaymentRequest.create(
			chainHash = nodeParams_alice.chainHash,
			amount = total_amount,
			paymentHash = paymentHash,
			privateKey = privKey_alice,
			description = "unit test",
			minFinalCltvExpiryDelta = PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
			features = Features(invoiceFeatures)
		)
		val paymentSecret = paymentRequest.paymentSecret!!
		val incomingPayment = IncomingPayment(
			paymentRequest = paymentRequest,
			paymentPreimage = paymentPreimage
		)

		val ab = TestsHelper.reachNormal()

		var alice: ChannelState = ab.first
		var bob: ChannelState = ab.second

		// alice & bob: type => Normal: ChannelState
		//
		// - so we can call alice.process(event: ChannelEvent)
		// - where ChannelEvent is going to be one of:
		//   - ExecuteCommand: ChannelEvent()
		//   - MessageReceived: ChannelEvent()
		//
		// After reachNormal():
		// - alice has : 1_000_000 sat
		// - bob has   :         0 sat

		val cmdAddHtlc_1 = makeMpp(
			amount             = amount_1,
			totalAmount        = total_amount,
			destination        = bob.staticParams.nodeParams.nodeId,
			currentBlockHeight = alice.currentBlockHeight.toLong(),
			paymentHash        = paymentHash,
			paymentSecret      = paymentSecret
		)
		val cmdAddHtlc_2 = makeMpp(
			amount             = amount_2,
			totalAmount        = total_amount,
			destination        = bob.staticParams.nodeParams.nodeId,
			currentBlockHeight = alice.currentBlockHeight.toLong(),
			paymentHash        = paymentHash,
			paymentSecret      = paymentSecret
		)

		assertTrue { cmdAddHtlc_1.paymentHash == cmdAddHtlc_2.paymentHash }

		// Step 1 of 2:
		//
		// Alice sends first multipart htlc to Bob.
		// Ensure that:
		// - Bob adds it to his list
		// - but doesn't accept the MPP set yet

		val (alice1, bob1, processAdd1) = sendMppAndDualCommit(
			sender = alice,
			receiver = bob,
			cmdAddHtlc = cmdAddHtlc_1
		)

		alice = alice1
		assertTrue { alice is Normal }

		bob = bob1
		assertTrue { bob is Normal }

		if (bob is Normal) {

			val par: PaymentHandler.ProcessAddResult = paymentHandler_bob.processAdd(
				htlc = processAdd1.add,
				incomingPayment = incomingPayment,
				currentBlockHeight = bob.currentBlockHeight
			)

			assertTrue { par.status == PaymentHandler.ProcessedStatus.PENDING }
			assertTrue { par.actions.count() == 0 }

		} else {
			assertTrue { false }
		}

		// Step 2 of 2:
		//
		// Alice sends second multipart htlc to Bob.
		// Ensure that:
		// - Bob adds it to his list
		// - but now accepts the MPP set

		val (alice2, bob2, processAdd2) = sendMppAndDualCommit(
			sender = alice,
			receiver = bob,
			cmdAddHtlc = cmdAddHtlc_2
		)

		alice = alice2
		assertTrue { alice is Normal }

		bob = bob2
		assertTrue { bob is Normal }

		if (bob is Normal) {

			val par: PaymentHandler.ProcessAddResult = paymentHandler_bob.processAdd(
				htlc = processAdd2.add,
				incomingPayment = incomingPayment,
				currentBlockHeight = bob.currentBlockHeight
			)

			assertTrue { par.status == PaymentHandler.ProcessedStatus.ACCEPTED } // Yay!
			assertTrue { par.actions.count() >= 2 }

		} else {
			assertTrue { false }
		}
	}

	@Test
	fun `PaymentHandler should reject MPP set if total_amount's don't match`() {

		val paymentPreimage: ByteVector32 = Eclair.randomBytes32()
		val paymentHash = Crypto.sha256(paymentPreimage).toByteVector32()

		val nodeParams_alice = TestConstants.Alice.nodeParams
		val nodeParams_bob = TestConstants.Bob.nodeParams

		val privKey_alice = nodeParams_alice.privateKey

		val paymentHandler_bob = PaymentHandler(nodeParams_bob)

		val amount_1 = MilliSatoshi(100.sat)
		val amount_2 = MilliSatoshi(100.sat)
		val amount_3 = MilliSatoshi(100.sat)
		val total_amount = amount_1 + amount_2 + amount_3

		val invoiceFeatures = mutableSetOf(
			ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
			ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Optional),
			ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional)
		)
		val paymentRequest = PaymentRequest.create(
			chainHash = nodeParams_alice.chainHash,
			amount = total_amount,
			paymentHash = paymentHash,
			privateKey = privKey_alice,
			description = "unit test",
			minFinalCltvExpiryDelta = PaymentRequest.DEFAULT_MIN_FINAL_EXPIRY_DELTA,
			features = Features(invoiceFeatures)
		)
		val paymentSecret = paymentRequest.paymentSecret!!
		val incomingPayment = IncomingPayment(
			paymentRequest = paymentRequest,
			paymentPreimage = paymentPreimage
		)

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
		// - alice has : 1_000_000 sat ?
		// - bob has   :         0 sat ?

		val cmdAddHtlc_1 = makeMpp(
			amount             = amount_1,
			totalAmount        = total_amount,
			destination        = bob.staticParams.nodeParams.nodeId,
			currentBlockHeight = alice.currentBlockHeight.toLong(),
			paymentHash        = paymentHash,
			paymentSecret      = paymentSecret
		)
		val cmdAddHtlc_2 = makeMpp(
			amount             = amount_2,
			totalAmount        = total_amount - amount_3, // trying to cheat here !!
			destination        = bob.staticParams.nodeParams.nodeId,
			currentBlockHeight = alice.currentBlockHeight.toLong(),
			paymentHash        = paymentHash,
			paymentSecret      = paymentSecret
		)

		assertTrue { cmdAddHtlc_1.paymentHash == cmdAddHtlc_2.paymentHash }

		// Step 1 of 2:
		//
		// Alice sends first multipart htlc to Bob.
		// Ensure that:
		// - Bob adds it to his list
		// - but doesn't accept the MPP set yet

		val (alice1, bob1, processAdd1) = sendMppAndDualCommit(
			sender = alice,
			receiver = bob,
			cmdAddHtlc = cmdAddHtlc_1
		)

		alice = alice1
		assertTrue { alice is Normal }

		bob = bob1
		assertTrue { bob is Normal }

		if (bob is Normal) {

			val par: PaymentHandler.ProcessAddResult = paymentHandler_bob.processAdd(
				htlc = processAdd1.add,
				incomingPayment = incomingPayment,
				currentBlockHeight = bob.currentBlockHeight
			)

			assertTrue { par.status == PaymentHandler.ProcessedStatus.PENDING }
			assertTrue { par.actions.count() == 0 }

		} else {
			assertTrue { false }
		}

		// Step 2 of 2:
		//
		// Alice sends second multipart htlc to Bob.
		// Ensure that:
		// - Bob adds it to his list
		// - Bob detects some shenanigans
		// - Bob rejects the entire MPP set (as per spec)

		val (alice2, bob2, processAdd2) = sendMppAndDualCommit(
			sender = alice,
			receiver = bob,
			cmdAddHtlc = cmdAddHtlc_2
		)

		alice = alice2
		assertTrue { alice is Normal }

		bob = bob2
		assertTrue { bob is Normal }

		if (bob is Normal) {

			val par: PaymentHandler.ProcessAddResult = paymentHandler_bob.processAdd(
				htlc = processAdd2.add,
				incomingPayment = incomingPayment,
				currentBlockHeight = bob.currentBlockHeight
			)

			assertTrue { par.status == PaymentHandler.ProcessedStatus.REJECTED } // should fail due to non-matching total_amounts
			assertTrue { par.actions.count() >= 2 }

		} else {
			assertTrue { false }
		}
	}
}