package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eclair.*
import fr.acinq.eclair.blockchain.WatchConfirmed
import fr.acinq.eclair.blockchain.WatchEventConfirmed
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.eclair.payment.relay.Origin
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.toByteVector32
import fr.acinq.eclair.wire.*
import org.kodein.log.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

object TestsHelper {
    fun reachNormal(channelVersion: ChannelVersion = ChannelVersion.STANDARD, currentHeight: Int = 0, fundingAmount: Satoshi = TestConstants.fundingSatoshis): Pair<Normal, Normal> {
        var alice: ChannelState = WaitForInit(StaticParams(TestConstants.Alice.nodeParams, TestConstants.Bob.keyManager.nodeId), currentTip = Pair(currentHeight, Block.RegtestGenesisBlock.header))
        var bob: ChannelState = WaitForInit(StaticParams(TestConstants.Bob.nodeParams, TestConstants.Alice.keyManager.nodeId), currentTip = Pair(currentHeight, Block.RegtestGenesisBlock.header))
        val channelFlags = 0.toByte()
        var aliceChannelParams = TestConstants.Alice.channelParams
        var bobChannelParams = TestConstants.Bob.channelParams
        if (channelVersion.isSet(ChannelVersion.ZERO_RESERVE_BIT)) {
            aliceChannelParams = aliceChannelParams.copy(channelReserve = Satoshi(0))
        }
        val aliceInit = Init(ByteVector(aliceChannelParams.features.toByteArray()))
        val bobInit = Init(ByteVector(bobChannelParams.features.toByteArray()))
        var ra = alice.process(
            InitFunder(
                ByteVector32.Zeroes,
                fundingAmount,
                TestConstants.pushMsat,
                TestConstants.feeratePerKw,
                TestConstants.feeratePerKw,
                aliceChannelParams,
                bobInit,
                channelFlags,
                channelVersion
            )
        )
        alice = ra.first
        assertTrue { alice is WaitForAcceptChannel }
        var rb = bob.process(InitFundee(ByteVector32.Zeroes, bobChannelParams, aliceInit))
        bob = rb.first
        assertTrue { bob is WaitForOpenChannel }

        val open = findOutgoingMessage<OpenChannel>(ra.second)
        rb = bob.process(MessageReceived(open))
        bob = rb.first
        val accept = findOutgoingMessage<AcceptChannel>(rb.second)
        ra = alice.process(MessageReceived(accept))
        alice = ra.first
        val makeFundingTx = run {
            val candidates = ra.second.filterIsInstance<MakeFundingTx>()
            if (candidates.isEmpty()) throw IllegalArgumentException("cannot find MakeFundingTx")
            candidates.first()
        }
        val fundingTx = Transaction(
            version = 2,
            txIn = listOf(),
            txOut = listOf(TxOut(makeFundingTx.amount, makeFundingTx.pubkeyScript)),
            lockTime = 0
        )
        ra = alice.process(MakeFundingTxResponse(fundingTx, 0, Satoshi((100))))
        alice = ra.first
        val created = findOutgoingMessage<FundingCreated>(ra.second)
        rb = bob.process(MessageReceived(created))
        bob = rb.first
        val signedBob = findOutgoingMessage<FundingSigned>(rb.second)
        ra = alice.process(MessageReceived(signedBob))
        alice = ra.first
        val watchConfirmed = run {
            val candidates = ra.second.filterIsInstance<SendWatch>().map { it.watch }.filterIsInstance<WatchConfirmed>()
            if (candidates.isEmpty()) throw IllegalArgumentException("cannot find WatchConfirmed")
            candidates.first()
        }

        ra = alice.process(WatchReceived(WatchEventConfirmed(watchConfirmed.channelId, watchConfirmed.event, currentHeight + 144, 1, fundingTx)))
        alice = ra.first
        val fundingLockedAlice = findOutgoingMessage<FundingLocked>(ra.second)

        rb = bob.process(WatchReceived(WatchEventConfirmed(watchConfirmed.channelId, watchConfirmed.event, currentHeight + 144, 1, fundingTx)))
        bob = rb.first
        val fundingLockedBob = findOutgoingMessage<FundingLocked>(rb.second)

        ra = alice.process(MessageReceived(fundingLockedBob))
        alice = ra.first

        rb = bob.process(MessageReceived(fundingLockedAlice))
        bob = rb.first

        return Pair(alice as Normal, bob as Normal)
    }

    inline fun <reified T : LightningMessage> findOutgoingMessage(input: List<ChannelAction>): T {
        val candidates = input.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<T>()
        if (candidates.isEmpty()) throw IllegalArgumentException("cannot find ${T::class}")
        return candidates.first()
    }

    fun makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: Long, paymentPreimage: ByteVector32 = Eclair.randomBytes32(), id: UUID = UUID.randomUUID()): Pair<ByteVector32, CMD_ADD_HTLC> {
        val paymentHash: ByteVector32 = Crypto.sha256(paymentPreimage).toByteVector32()
        val expiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
        val dummyKey = PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")).publicKey()
        val dummyUpdate = ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId(144, 0, 0), 0, 0, 0, CltvExpiryDelta(1), 0.msat, 0.msat, 0, null)
        val cmd = OutgoingPacket.buildCommand(id, paymentHash, listOf(ChannelHop(dummyKey, destination, dummyUpdate)), FinalLegacyPayload(amount, expiry)).first.copy(commit = false)
        return Pair(paymentPreimage, cmd)
    }

    fun addHtlc(amount: MilliSatoshi, sender: ChannelState, receiver: ChannelState): Pair<NodePair, Pair<ByteVector32, UpdateAddHtlc>> {
        val currentBlockHeight = sender.currentBlockHeight.toLong()
        val (paymentPreimage, cmd) = makeCmdAdd(amount, sender.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (sr, htlc) = addHtlc(cmd, sender, receiver)
        return sr to (paymentPreimage to htlc)
    }

    private fun addHtlc(cmdAdd: CMD_ADD_HTLC, sender: ChannelState, receiver: ChannelState): Pair<NodePair, UpdateAddHtlc> {
        val (s, sa) = sender.process(ExecuteCommand(cmdAdd))
        assertTrue(sa.isNotEmpty())
        assertTrue(sa.hasMessage<UpdateAddHtlc>())
        val htlc: UpdateAddHtlc = sa.messages().msg()

        val (r, ra) = receiver.process(MessageReceived(htlc))
        assertTrue(r is HasCommitments)
        assertTrue(r.commitments.remoteChanges.proposed.contains(htlc))

        return NodePair(s, r) to htlc
    }

    fun fulfillHtlc(id: Long, paymentPreimage: ByteVector32, sender: ChannelState, receiver: ChannelState): NodePair {
        val (s, sa) = sender.process(ExecuteCommand(CMD_FULFILL_HTLC(id, paymentPreimage)))
        assertTrue(sa.isNotEmpty())
        assertTrue(sa.hasMessage<UpdateFulfillHtlc>())
        val fulfillHtlc : UpdateFulfillHtlc = sa.messages().msg()

        val (r, ra) = receiver.process(MessageReceived(fulfillHtlc))
        assertTrue(r is HasCommitments)
        assertTrue(r.commitments.remoteChanges.proposed.contains(fulfillHtlc))

        return NodePair(s, r)
    }

    fun crossSign(s: ChannelState, r: ChannelState): NodePair {
        var sender = s ; var receiver = r
        assertTrue(sender is HasCommitments)
        assertTrue(receiver is HasCommitments)

        val sCommitIndex = sender.commitments.localCommit.index
        val rCommitIndex = receiver.commitments.localCommit.index
        val rHasChanges = receiver.commitments.localHasChanges()

        val (s0, sa0) = sender.process(ExecuteCommand(CMD_SIGN))
        sender = s0
        assertTrue(sa0.isNotEmpty())
        assertTrue(sa0.hasMessage<CommitSig>())

        val (r0, ra0) = receiver.process(MessageReceived(sa0.messages().msg<CommitSig>()))
        receiver = r0
        assertTrue(ra0.isNotEmpty())
        assertTrue(ra0.hasMessage<RevokeAndAck>())
        assertTrue(ra0.hasCommand<CMD_SIGN>())

        val (s1, sa1) = sender.process(MessageReceived(ra0.messages().msg<RevokeAndAck>()))
        sender = s1

        val (r1, ra1) = receiver.process(ExecuteCommand(ra0.commands().cmd<CMD_SIGN>()))
        receiver = r1
        assertTrue(ra1.isNotEmpty())
        assertTrue(ra1.hasMessage<CommitSig>())

        val (s2, sa2) = sender.process(MessageReceived(ra1.messages().msg<CommitSig>()))
        sender = s2
        assertTrue(sa2.isNotEmpty())
        assertTrue(sa2.hasMessage<RevokeAndAck>())

        val (r2, ra2) = receiver.process(MessageReceived(sa2.messages().msg<RevokeAndAck>()))
        receiver = r2

        if (rHasChanges) {
            assertTrue(sa2.hasCommand<CMD_SIGN>())
            val (s3, sa3) = sender.process(ExecuteCommand(sa2.commands().cmd<CMD_SIGN>()))
            sender = s3
            assertTrue(sa3.isNotEmpty())
            assertTrue(sa3.hasMessage<CommitSig>())

            val (r3, ra3) = receiver.process(MessageReceived(sa3.messages().msg<CommitSig>()))
            receiver = r3
            assertTrue(ra3.isNotEmpty())
            assertTrue(ra3.hasMessage<RevokeAndAck>())

            val (s4, sa4) = sender.process(MessageReceived(ra3.messages().msg<RevokeAndAck>()))
            sender = s4

            sender as HasCommitments ; receiver as HasCommitments
            assertEquals(sCommitIndex + 1, sender.commitments.localCommit.index)
            assertEquals(sCommitIndex + 2, sender.commitments.remoteCommit.index)
            assertEquals(rCommitIndex + 2, receiver.commitments.localCommit.index)
            assertEquals(rCommitIndex + 1, receiver.commitments.remoteCommit.index)
        } else {
            sender as HasCommitments ; receiver as HasCommitments
            assertEquals(sCommitIndex + 1, sender.commitments.localCommit.index)
            assertEquals(sCommitIndex + 1, sender.commitments.remoteCommit.index)
            assertEquals(rCommitIndex + 1, receiver.commitments.localCommit.index)
            assertEquals(rCommitIndex + 1, receiver.commitments.remoteCommit.index)
        }

        return NodePair(sender, receiver)
    }

    /*
    * sender -> receiver couple can be:
    *   - alice -> bob
    *   - bob -> alice
    */
    fun makePayment(payment: MilliSatoshi = 42000000.msat, sender: ChannelState, receiver: ChannelState, logger: Logger): Pair<ChannelState, ChannelState> {
        val fee = 1720000.msat // fee due to the additional htlc output

        assertTrue(sender is HasCommitments)
        assertTrue(receiver is HasCommitments)

        val ac0 = sender.commitments
        val bc0 = receiver.commitments

        val a = ac0.availableBalanceForSend() // initial balance alice
        val b = bc0.availableBalanceForSend() // initial balance bob

        assertTrue(ac0.availableBalanceForSend() > payment) // alice can afford the payment
        assertEquals(ac0.availableBalanceForSend(), a)
        assertEquals(ac0.availableBalanceForReceive(), b)
        assertEquals(bc0.availableBalanceForSend(), b)
        assertEquals(bc0.availableBalanceForReceive(), a)

        val currentBlockHeight = 144L
        val (payment_preimage, cmdAdd) = makeCmdAdd(payment, receiver.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (ac1, add) = ac0.sendAdd(cmdAdd, Origin.Local(UUID.randomUUID()), currentBlockHeight).get()
        assertEquals(ac1.availableBalanceForSend(), a - payment - fee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
        assertEquals(ac1.availableBalanceForReceive(), b)

        val bc1 = bc0.receiveAdd(add).get()
        assertEquals(bc1.availableBalanceForSend(), b)
        assertEquals(bc1.availableBalanceForReceive(), a - payment - fee)

        val (ac2, commit1) = ac1.sendCommit(sender.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac2.availableBalanceForSend(), a - payment - fee)
        assertEquals(ac2.availableBalanceForReceive(), b)

        val (bc2, revocation1) = bc1.receiveCommit(commit1, receiver.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(bc2.availableBalanceForSend(), b)
        assertEquals(bc2.availableBalanceForReceive(), a - payment - fee)

        val ac3 = ac2.receiveRevocation(revocation1).get().first
        assertEquals(ac3.availableBalanceForSend(), a - payment - fee)
        assertEquals(ac3.availableBalanceForReceive(), b)

        val (bc3, commit2) = bc2.sendCommit(receiver.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(bc3.availableBalanceForSend(), b)
        assertEquals(bc3.availableBalanceForReceive(), a - payment - fee)

        val (ac4, revocation2) = ac3.receiveCommit(commit2, sender.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac4.availableBalanceForSend(), a - payment - fee)
        assertEquals(ac4.availableBalanceForReceive(), b)

        val bc4 = bc3.receiveRevocation(revocation2).get().first
        assertEquals(bc4.availableBalanceForSend(), b)
        assertEquals(bc4.availableBalanceForReceive(), a - payment - fee)

        val cmdFulfill = CMD_FULFILL_HTLC(0, payment_preimage)
        val (bc5, fulfill) = bc4.sendFulfill(cmdFulfill).get()
        assertEquals(bc5.availableBalanceForSend(), b + payment) // as soon as we have the fulfill, the balance increases
        assertEquals(bc5.availableBalanceForReceive(), a - payment - fee)

        val ac5 = ac4.receiveFulfill(fulfill).get().first
        assertEquals(ac5.availableBalanceForSend(), a - payment - fee)
        assertEquals(ac5.availableBalanceForReceive(), b + payment)

        val (bc6, commit3) = bc5.sendCommit(receiver.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(bc6.availableBalanceForSend(), b + payment)
        assertEquals(bc6.availableBalanceForReceive(), a - payment - fee)

        val (ac6, revocation3) = ac5.receiveCommit(commit3, sender.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac6.availableBalanceForSend(), a - payment)
        assertEquals(ac6.availableBalanceForReceive(), b + payment)

        val bc7 = bc6.receiveRevocation(revocation3).get().first
        assertEquals(bc7.availableBalanceForSend(), b + payment)
        assertEquals(bc7.availableBalanceForReceive(), a - payment)

        val (ac7, commit4) = ac6.sendCommit(sender.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(ac7.availableBalanceForSend(), a - payment)
        assertEquals(ac7.availableBalanceForReceive(), b + payment)

        val (bc8, revocation4) = bc7.receiveCommit(commit4, receiver.staticParams.nodeParams.keyManager, logger).get()
        assertEquals(bc8.availableBalanceForSend(), b + payment)
        assertEquals(bc8.availableBalanceForReceive(), a - payment)

        val ac8 = ac7.receiveRevocation(revocation4).get().first
        assertEquals(ac8.availableBalanceForSend(), a - payment)
        assertEquals(ac8.availableBalanceForReceive(), b + payment)

        return sender.updateCommitments(ac8) as ChannelState to receiver.updateCommitments(bc8) as ChannelState
    }
}
