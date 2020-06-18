package fr.acinq.eklair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eklair.TestConstants
import fr.acinq.eklair.blockchain.WatchConfirmed
import fr.acinq.eklair.blockchain.WatchEventConfirmed
import fr.acinq.eklair.utils.msat
import fr.acinq.eklair.wire.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommitmentsTests {
    fun reachNormal(): Pair<Normal, Normal> {
        var alice: State = WaitForInit(
            StaticParams(
                TestConstants.Alice.nodeParams,
                TestConstants.Bob.keyManager.nodeId
            )
        )
        var bob: State = WaitForInit(
            StaticParams(
                TestConstants.Bob.nodeParams,
                TestConstants.Alice.keyManager.nodeId
            )
        )
        val channelFlags = 0.toByte()
        val channelVersion = ChannelVersion.STANDARD
        val aliceInit = Init(ByteVector(TestConstants.Alice.channelParams.features.toByteArray()))
        val bobInit = Init(ByteVector(TestConstants.Bob.channelParams.features.toByteArray()))
        var ra = alice.process(InitFunder(ByteVector32.Zeroes, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, TestConstants.Alice.channelParams, bobInit, channelFlags, channelVersion))
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
        val fundingTx = Transaction(version = 2, txIn = listOf(), txOut = listOf(TxOut(makeFundingTx.amount, makeFundingTx.pubkeyScript)), lockTime = 0)
        ra = alice.process(MakeFundingTxResponse(fundingTx, 0, Satoshi((100))))
        alice = ra.first
        val created = ra.second.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<FundingCreated>().first()
        rb = bob.process(MessageReceived(created))
        bob = rb.first
        val signedBob = rb.second.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<FundingSigned>().first()
        ra = alice.process(MessageReceived(signedBob))
        alice = ra.first
        val watchConfirmed = ra.second.filterIsInstance<SendWatch>().map { it.watch }.filterIsInstance<WatchConfirmed>().first()

        ra = alice.process(WatchReceived(WatchEventConfirmed(watchConfirmed.event, 144, 1, fundingTx)))
        alice = ra.first
        val fundingLockedAlice = ra.second.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<FundingLocked>().first()

        rb = bob.process(WatchReceived(WatchEventConfirmed(watchConfirmed.event, 144, 1, fundingTx)))
        bob = rb.first
        val fundingLockedBob = rb.second.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<FundingLocked>().first()

        ra = alice.process(MessageReceived(fundingLockedBob))
        alice = ra.first

        rb = bob.process(MessageReceived(fundingLockedAlice))
        bob = rb.first

        return Pair(alice as Normal, bob as Normal)
    }

    @Test
    fun `reach normal state`() {
        val (alice, bob) = reachNormal()
        assertTrue { alice is Normal }
        assertTrue { bob is Normal }
    }

    @Test
    fun `take additional HTLC fee into account`() {
        val (alice, bob) = reachNormal()
        // The fee for a single HTLC is 1720000 msat but the funder keeps an extra reserve to make sure we're able to handle
        // an additional HTLC at twice the feerate (hence the multiplier).
        val htlcOutputFee = (3 * 1720000).msat
        val a = 772760000.msat // initial balance alice
        val ac0 = alice.commitments
        val bc0 = bob.commitments
        val foo = ac0.availableBalanceForSend()
        // we need to take the additional HTLC fee into account because balances are above the trim threshold.
        assertEquals(ac0.availableBalanceForSend(),  a - htlcOutputFee)
        assertEquals(bc0.availableBalanceForReceive(), a - htlcOutputFee)

//        val (_, cmdAdd) = makeCmdAdd(a - htlcOutputFee - 1000.msat, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight)
//        val Success((ac1, add)) = sendAdd(ac0, cmdAdd, Local(UUID.randomUUID, None), currentBlockHeight)
//        val Success(bc1) = receiveAdd(bc0, add)
//        val Success((_, commit1)) = sendCommit(ac1, alice.underlyingActor.nodeParams.keyManager)
//        val Success((bc2, _)) = receiveCommit(bc1, commit1, bob.underlyingActor.nodeParams.keyManager)
        // we don't take into account the additional HTLC fee since Alice's balance is below the trim threshold.
//        assert(ac1.availableBalanceForSend == 1000.msat)
//        assert(bc2.availableBalanceForReceive == 1000.msat)
    }

}