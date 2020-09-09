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
    fun reachNormal(currentHeight: Int = 0, fundingAmount: Satoshi = TestConstants.fundingSatoshis): Pair<Normal, Normal> {
        var alice: ChannelState = WaitForInit(StaticParams(TestConstants.Alice.nodeParams, TestConstants.Bob.keyManager.nodeId), currentTip = Pair(currentHeight, Block.RegtestGenesisBlock.header))
        var bob: ChannelState = WaitForInit(StaticParams(TestConstants.Bob.nodeParams, TestConstants.Alice.keyManager.nodeId), currentTip = Pair(currentHeight, Block.RegtestGenesisBlock.header))
        val channelFlags = 0.toByte()
        val channelVersion = ChannelVersion.STANDARD
        val aliceInit = Init(ByteVector(TestConstants.Alice.channelParams.features.toByteArray()))
        val bobInit = Init(ByteVector(TestConstants.Bob.channelParams.features.toByteArray()))
        var ra = alice.process(
            InitFunder(
                ByteVector32.Zeroes,
                fundingAmount,
                TestConstants.pushMsat,
                TestConstants.feeratePerKw,
                TestConstants.feeratePerKw,
                TestConstants.Alice.channelParams,
                bobInit,
                channelFlags,
                channelVersion
            )
        )
        alice = ra.first
        assertTrue { alice is WaitForAcceptChannel }
        var rb = bob.process(InitFundee(ByteVector32.Zeroes, TestConstants.Bob.channelParams, aliceInit))
        bob = rb.first
        assertTrue { bob is WaitForOpenChannel }

        val open = ra.second.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<OpenChannel>().first()
        rb = bob.process(MessageReceived(open))
        bob = rb.first
        val accept = rb.second.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<AcceptChannel>().first()
        ra = alice.process(MessageReceived(accept))
        alice = ra.first
        val makeFundingTx = ra.second.filterIsInstance<MakeFundingTx>().first()
        val fundingTx = Transaction(
            version = 2,
            txIn = listOf(),
            txOut = listOf(TxOut(makeFundingTx.amount, makeFundingTx.pubkeyScript)),
            lockTime = 0
        )
        ra = alice.process(MakeFundingTxResponse(fundingTx, 0, Satoshi((100))))
        alice = ra.first
        val created = ra.second.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<FundingCreated>().first()
        rb = bob.process(MessageReceived(created))
        bob = rb.first
        val signedBob = rb.second.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<FundingSigned>().first()
        ra = alice.process(MessageReceived(signedBob))
        alice = ra.first
        val watchConfirmed = ra.second.filterIsInstance<SendWatch>().map { it.watch }.filterIsInstance<WatchConfirmed>().first()

        ra = alice.process(WatchReceived(WatchEventConfirmed(watchConfirmed.channelId, watchConfirmed.event, currentHeight + 144, 1, fundingTx)))
        alice = ra.first
        val fundingLockedAlice = ra.second.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<FundingLocked>().first()

        rb = bob.process(WatchReceived(WatchEventConfirmed(watchConfirmed.channelId, watchConfirmed.event, currentHeight + 144, 1, fundingTx)))
        bob = rb.first
        val fundingLockedBob = rb.second.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<FundingLocked>().first()

        ra = alice.process(MessageReceived(fundingLockedBob))
        alice = ra.first

        rb = bob.process(MessageReceived(fundingLockedAlice))
        bob = rb.first

        return Pair(alice as Normal, bob as Normal)
    }

    fun makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: Long, paymentPreimage: ByteVector32 = Eclair.randomBytes32(), upstream: Upstream = Upstream.Local(UUID.randomUUID())): Pair<ByteVector32, CMD_ADD_HTLC> {
        val paymentHash: ByteVector32 = Crypto.sha256(paymentPreimage).toByteVector32()
        val expiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
        val dummyKey = PrivateKey(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")).publicKey()
        val dummyUpdate = ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId(144,0,0), 0, 0, 0, CltvExpiryDelta(1), 0.msat, 0.msat, 0, null)
        val cmd = OutgoingPacket.buildCommand(upstream, paymentHash, listOf(ChannelHop(dummyKey, destination, dummyUpdate)), FinalLegacyPayload(amount, expiry)).first.copy(commit = false)
        return Pair(paymentPreimage, cmd)
    }
}