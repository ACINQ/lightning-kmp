package fr.acinq.eclair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eclair.*
import fr.acinq.eclair.blockchain.WatchConfirmed
import fr.acinq.eclair.blockchain.WatchEventConfirmed
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.toByteVector32
import fr.acinq.eclair.wire.*
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
}