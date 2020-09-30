package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eclair.*
import fr.acinq.eclair.blockchain.Watch
import fr.acinq.eclair.blockchain.WatchConfirmed
import fr.acinq.eclair.blockchain.WatchEventConfirmed
import fr.acinq.eclair.blockchain.fee.OnchainFeerates
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.toByteVector32
import fr.acinq.eclair.wire.*
import kotlin.test.assertTrue

object TestsHelper {
    fun init(channelVersion: ChannelVersion = ChannelVersion.STANDARD, currentHeight: Int = 0, fundingAmount: Satoshi = TestConstants.fundingSatoshis): Triple<WaitForAcceptChannel, WaitForOpenChannel, OpenChannel> {
        var alice: ChannelState = WaitForInit(StaticParams(TestConstants.Alice.nodeParams, TestConstants.Bob.keyManager.nodeId), currentTip = Pair(currentHeight, Block.RegtestGenesisBlock.header), currentOnchainFeerates = OnchainFeerates(1500, 1500, 1500, 1500))
        var bob: ChannelState = WaitForInit(StaticParams(TestConstants.Bob.nodeParams, TestConstants.Alice.keyManager.nodeId), currentTip = Pair(currentHeight, Block.RegtestGenesisBlock.header), currentOnchainFeerates = OnchainFeerates(1500, 1500, 1500, 1500))
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
        return Triple(alice as WaitForAcceptChannel, bob as WaitForOpenChannel, open)
    }

    fun reachNormal(channelVersion: ChannelVersion = ChannelVersion.STANDARD, currentHeight: Int = 0, fundingAmount: Satoshi = TestConstants.fundingSatoshis): Pair<Normal, Normal> {
        val (a, b, open) = init(channelVersion, currentHeight, fundingAmount)
        var alice = a as ChannelState
        var bob = b as ChannelState
        var rb = bob.process(MessageReceived(open))
        bob = rb.first
        val accept = rb.second.findOutgoingMessage<AcceptChannel>()
        var ra = alice.process(MessageReceived(accept))
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

    fun signAndRevack(alice: ChannelState, bob: ChannelState): Pair<ChannelState, ChannelState> {
        val (alice1, actions1) = alice.process(ExecuteCommand(CMD_SIGN))
        val commitSig = actions1.findOutgoingMessage<CommitSig>()
        val (bob1, actions2) = bob.process(MessageReceived(commitSig))
        val revack = actions2.findOutgoingMessage<RevokeAndAck>()
        val (alice2, _) = alice1.process(MessageReceived(revack))
        return Pair(alice2, bob1)
    }

    inline fun <reified T : LightningMessage> List<ChannelAction>.hasOutgoingMessage(): T? {
        val candidates = this.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<T>()
        return candidates.firstOrNull()
    }

    inline fun <reified T : LightningMessage> List<ChannelAction>.findOutgoingMessage(): T {
        return hasOutgoingMessage() ?: throw IllegalArgumentException("cannot find ${T::class}")
    }

    inline fun <reified T : Watch> List<ChannelAction>.findOutgoingWatch(): T {
        val candidates = this.filterIsInstance<SendWatch>().map { it.watch }.filterIsInstance<T>()
        if (candidates.isEmpty()) throw IllegalArgumentException("cannot find ${T::class}")
        return candidates.first()
    }

    fun makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: Long, paymentPreimage: ByteVector32 = Eclair.randomBytes32(), paymentId: UUID = UUID.randomUUID()): Pair<ByteVector32, CMD_ADD_HTLC> {
        val paymentHash: ByteVector32 = Crypto.sha256(paymentPreimage).toByteVector32()
        val expiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
        val dummyKey = PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")).publicKey()
        val dummyUpdate = ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId(144, 0, 0), 0, 0, 0, CltvExpiryDelta(1), 0.msat, 0.msat, 0, null)
        val cmd = OutgoingPacket.buildCommand(paymentId, paymentHash, listOf(ChannelHop(dummyKey, destination, dummyUpdate)), FinalLegacyPayload(amount, expiry)).first.copy(commit = false)
        return Pair(paymentPreimage, cmd)
    }
}