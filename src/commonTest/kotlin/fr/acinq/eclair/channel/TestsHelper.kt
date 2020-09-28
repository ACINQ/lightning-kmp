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
import kotlin.test.fail

data class NodePair(val sender: ChannelState, val receiver: ChannelState)

// LN Message
inline fun <reified T : LightningMessage> List<ChannelAction>.findOutgoingMessage(): T =
    filterIsInstance<SendMessage>().map { it.message }.firstOrNull { it is T } as T? ?: fail("cannot find LightningMessage ${T::class}.")
internal inline fun <reified T> List<ChannelAction>.hasMessage() = any { it is SendMessage && it.message is T }

// Commands
inline fun <reified T : Command> List<ChannelAction>.findProcessCommand(): T =
    filterIsInstance<ProcessCommand>().map { it.command }.firstOrNull { it is T } as T? ?: fail("cannot find ProcessCommand ${T::class}.")
internal inline fun <reified T> List<ChannelAction>.hasCommand() = any { it is ProcessCommand && it.command is T }

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

        val open = ra.second.findOutgoingMessage<OpenChannel>()
        rb = bob.process(MessageReceived(open))
        bob = rb.first
        val accept = rb.second.findOutgoingMessage<AcceptChannel>()
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
        val created = ra.second.findOutgoingMessage<FundingCreated>()
        rb = bob.process(MessageReceived(created))
        bob = rb.first
        val signedBob = rb.second.findOutgoingMessage<FundingSigned>()
        ra = alice.process(MessageReceived(signedBob))
        alice = ra.first
        val watchConfirmed = run {
            val candidates = ra.second.filterIsInstance<SendWatch>().map { it.watch }.filterIsInstance<WatchConfirmed>()
            if (candidates.isEmpty()) throw IllegalArgumentException("cannot find WatchConfirmed")
            candidates.first()
        }

        ra = alice.process(WatchReceived(WatchEventConfirmed(watchConfirmed.channelId, watchConfirmed.event, currentHeight + 144, 1, fundingTx)))
        alice = ra.first
        val fundingLockedAlice = ra.second.findOutgoingMessage<FundingLocked>()

        rb = bob.process(WatchReceived(WatchEventConfirmed(watchConfirmed.channelId, watchConfirmed.event, currentHeight + 144, 1, fundingTx)))
        bob = rb.first
        val fundingLockedBob = rb.second.findOutgoingMessage<FundingLocked>()

        ra = alice.process(MessageReceived(fundingLockedBob))
        alice = ra.first

        rb = bob.process(MessageReceived(fundingLockedAlice))
        bob = rb.first

        return Pair(alice as Normal, bob as Normal)
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
        val htlc = sa.findOutgoingMessage<UpdateAddHtlc>()

        val (r, ra) = receiver.process(MessageReceived(htlc))
        assertTrue(r is HasCommitments)
        assertTrue(r.commitments.remoteChanges.proposed.contains(htlc))

        return NodePair(s, r) to htlc
    }

    fun fulfillHtlc(id: Long, paymentPreimage: ByteVector32, sender: ChannelState, receiver: ChannelState): NodePair {
        val (s, sa) = sender.process(ExecuteCommand(CMD_FULFILL_HTLC(id, paymentPreimage)))
        val fulfillHtlc = sa.findOutgoingMessage<UpdateFulfillHtlc>()

        val (r, ra) = receiver.process(MessageReceived(fulfillHtlc))
        assertTrue(r is HasCommitments)
        assertTrue(r.commitments.remoteChanges.proposed.contains(fulfillHtlc))

        return NodePair(s, r)
    }

    fun crossSign(sender: ChannelState, receiver: ChannelState): NodePair {
        assertTrue(sender is HasCommitments)
        assertTrue(receiver is HasCommitments)

        val sCommitIndex = sender.commitments.localCommit.index
        val rCommitIndex = receiver.commitments.localCommit.index
        val rHasChanges = receiver.commitments.localHasChanges()

        val (sender0, sActions0) = sender.process(ExecuteCommand(CMD_SIGN))
        val commitSig0 = sActions0.findOutgoingMessage<CommitSig>()

        val (receiver0, rActions0) = receiver.process(MessageReceived(commitSig0))
        val revokeAndAck0 = rActions0.findOutgoingMessage<RevokeAndAck>()
        val commandSign0 = rActions0.findProcessCommand<CMD_SIGN>()

        val (sender1, _) = sender0.process(MessageReceived(revokeAndAck0))
        val (receiver1, rActions1) = receiver0.process(ExecuteCommand(commandSign0))
        val commitSig1 = rActions1.findOutgoingMessage<CommitSig>()

        val (sender2, sActions2) = sender1.process(MessageReceived(commitSig1))
        val revokeAndAck1 = sActions2.findOutgoingMessage<RevokeAndAck>()
        val (receiver2, _) = receiver1.process(MessageReceived(revokeAndAck1))

        if (rHasChanges) {
            val commandSign1 = sActions2.findProcessCommand<CMD_SIGN>()
            val (sender3, sActions3) = sender2.process(ExecuteCommand(commandSign1))
            val commitSig2 = sActions3.findOutgoingMessage<CommitSig>()

            val (receiver3, rActions3) = receiver2.process(MessageReceived(commitSig2))
            val revokeAndAck2 = rActions3.findOutgoingMessage<RevokeAndAck>()
            val (sender4, _) = sender3.process(MessageReceived(revokeAndAck2))

            sender4 as HasCommitments ; receiver3 as HasCommitments
            assertEquals(sCommitIndex + 1, sender4.commitments.localCommit.index)
            assertEquals(sCommitIndex + 2, sender4.commitments.remoteCommit.index)
            assertEquals(rCommitIndex + 2, receiver3.commitments.localCommit.index)
            assertEquals(rCommitIndex + 1, receiver3.commitments.remoteCommit.index)

            return NodePair(sender4, receiver3)
        } else {
            sender2 as HasCommitments ; receiver2 as HasCommitments
            assertEquals(sCommitIndex + 1, sender2.commitments.localCommit.index)
            assertEquals(sCommitIndex + 1, sender2.commitments.remoteCommit.index)
            assertEquals(rCommitIndex + 1, receiver2.commitments.localCommit.index)
            assertEquals(rCommitIndex + 1, receiver2.commitments.remoteCommit.index)

            return NodePair(sender2, receiver2)
        }
    }
}
