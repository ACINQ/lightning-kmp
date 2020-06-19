package fr.acinq.eklair.channel

import fr.acinq.bitcoin.*
import fr.acinq.eklair.TestConstants
import fr.acinq.eklair.blockchain.WatchConfirmed
import fr.acinq.eklair.blockchain.WatchEventConfirmed
import fr.acinq.eklair.payment.relay.Origin
import fr.acinq.eklair.transactions.CommitmentSpecTestsCommon
import fr.acinq.eklair.utils.Try
import fr.acinq.eklair.utils.UUID
import fr.acinq.eklair.utils.msat
import fr.acinq.eklair.wire.*
import org.kodein.log.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommitmentsTests {
    val logger = LoggerFactory.default.newLogger(CommitmentSpecTestsCommon::class)

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
        // we need to take the additional HTLC fee into account because balances are above the trim threshold.
        assertEquals(ac0.availableBalanceForSend(),  a - htlcOutputFee)
        assertEquals(bc0.availableBalanceForReceive(), a - htlcOutputFee)

        val currentBlockHeight = 144L
        val cmdAdd = TestsHelper.makeCmdAdd(a - htlcOutputFee - 1000.msat, bob.staticParams.nodeParams.nodeId, currentBlockHeight).second
        val (ac1, add) = (ac0.sendAdd(cmdAdd, Origin.Local(UUID.randomUUID()), currentBlockHeight) as Try.Success<Pair<Commitments, UpdateAddHtlc>>).result
        val bc1 = (bc0.receiveAdd(add) as Try.Success<Commitments>).result
        val (_, commit1) = (ac1.sendCommit(alice.staticParams.nodeParams.keyManager, logger) as Try.Success<Pair<Commitments, CommitSig>>).result
        val (bc2, _) = (bc1.receiveCommit(commit1, bob.staticParams.nodeParams.keyManager, logger) as Try.Success<Pair<Commitments, RevokeAndAck>>).result
        assertEquals(ac1.availableBalanceForSend() , 1000.msat)
        assertEquals(bc2.availableBalanceForReceive() , 1000.msat)
    }

    @Test
    fun `correct values for availableForSend - availableForReceive (success case)`() {
        val (alice, bob) = reachNormal()

        val fee = 1720000.msat // fee due to the additional htlc output
        val funderFeeReserve = fee * 2 // extra reserve to handle future fee increase
        val a = (772760000.msat) - fee - funderFeeReserve // initial balance alice
        val b = 190000000.msat // initial balance bob
        val p = 42000000.msat // a->b payment

        val ac0 = alice.commitments
        val bc0 = bob.commitments

        assertTrue(ac0.availableBalanceForSend() > p) // alice can afford the payment
        assertEquals(ac0.availableBalanceForSend(), a)
        assertEquals(ac0.availableBalanceForReceive(), b)
        assertEquals(bc0.availableBalanceForSend(), b)
        assertEquals(bc0.availableBalanceForReceive(), a)

        val currentBlockHeight = 144L
        val (payment_preimage, cmdAdd) = TestsHelper.makeCmdAdd(p, bob.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (ac1, add) = (ac0.sendAdd(cmdAdd, Origin.Local(UUID.randomUUID()), currentBlockHeight) as Try.Success<Pair<Commitments, UpdateAddHtlc>>).result
        assertEquals(ac1.availableBalanceForSend(), a - p - fee) // as soon as htlc is sent, alice sees its balance decrease (more than the payment amount because of the commitment fees)
        assertEquals(ac1.availableBalanceForReceive(), b)

        val bc1 = (bc0.receiveAdd(add) as Try.Success<Commitments>).result
        assertEquals(bc1.availableBalanceForSend(), b)
        assertEquals(bc1.availableBalanceForReceive(), a - p - fee)

        val (ac2, commit1) = (ac1.sendCommit(alice.staticParams.nodeParams.keyManager, logger) as Try.Success<Pair<Commitments, CommitSig>>).result
        assertEquals(ac2.availableBalanceForSend(), a - p - fee)
        assertEquals(ac2.availableBalanceForReceive(), b)

        val (bc2, revocation1) = (bc1.receiveCommit(commit1, bob.staticParams.nodeParams.keyManager, logger) as Try.Success<Pair<Commitments, RevokeAndAck>>).result
        assertEquals(bc2.availableBalanceForSend(), b)
        assertEquals(bc2.availableBalanceForReceive(), a - p - fee)


        val ac3 = (ac2.receiveRevocation(revocation1) as Try.Success<Commitments>).result
        assertEquals(ac3.availableBalanceForSend(), a - p - fee)
        assertEquals(ac3.availableBalanceForReceive(), b)

        val (bc3, commit2) = (bc2.sendCommit(bob.staticParams.nodeParams.keyManager, logger) as Try.Success<Pair<Commitments, CommitSig>>).result
        assertEquals(bc3.availableBalanceForSend(), b)
        assertEquals(bc3.availableBalanceForReceive(), a - p - fee)

        val (ac4, revocation2) = (ac3.receiveCommit(commit2, alice.staticParams.nodeParams.keyManager, logger) as Try.Success<Pair<Commitments, RevokeAndAck>>).result
        assertEquals(ac4.availableBalanceForSend(), a - p - fee)
        assertEquals(ac4.availableBalanceForReceive(), b)

        val bc4 = (bc3.receiveRevocation(revocation2) as Try.Success<Commitments>).result
        assertEquals(bc4.availableBalanceForSend(), b)
        assertEquals(bc4.availableBalanceForReceive(), a - p - fee)

        val cmdFulfill = CMD_FULFILL_HTLC(0, payment_preimage)
        val (bc5, fulfill) = (bc4.sendFulfill(cmdFulfill) as Try.Success<Pair<Commitments, UpdateFulfillHtlc>>).result
        assertEquals(bc5.availableBalanceForSend(), b + p) // as soon as we have the fulfill, the balance increases
        assertEquals(bc5.availableBalanceForReceive(), a - p - fee)

        val ac5 = (ac4.receiveFulfill(fulfill) as Try.Success<Triple<Commitments, Origin, UpdateAddHtlc>>).result.first
        assertEquals(ac5.availableBalanceForSend(), a - p - fee)
        assertEquals(ac5.availableBalanceForReceive(), b + p)

        val (bc6, commit3) = (bc5.sendCommit(bob.staticParams.nodeParams.keyManager, logger) as Try.Success<Pair<Commitments, CommitSig>>).result
        assertEquals(bc6.availableBalanceForSend(), b + p)
        assertEquals(bc6.availableBalanceForReceive(), a - p - fee)

        val (ac6, revocation3) = (ac5.receiveCommit(commit3, alice.staticParams.nodeParams.keyManager, logger) as Try.Success<Pair<Commitments, RevokeAndAck>>).result
        assertEquals(ac6.availableBalanceForSend(), a - p)
        assertEquals(ac6.availableBalanceForReceive(), b + p)

        val bc7 = (bc6.receiveRevocation(revocation3) as Try.Success<Commitments>).result
        assertEquals(bc7.availableBalanceForSend(), b + p)
        assertEquals(bc7.availableBalanceForReceive(), a - p)

        val (ac7, commit4) = (ac6.sendCommit(alice.staticParams.nodeParams.keyManager, logger) as Try.Success<Pair<Commitments, CommitSig>>).result
        assertEquals(ac7.availableBalanceForSend(), a - p)
        assertEquals(ac7.availableBalanceForReceive(), b + p)

        val (bc8, revocation4) = (bc7.receiveCommit(commit4, bob.staticParams.nodeParams.keyManager, logger) as Try.Success<Pair<Commitments, RevokeAndAck>>).result
        assertEquals(bc8.availableBalanceForSend(), b + p)
        assertEquals(bc8.availableBalanceForReceive(), a - p)

        val ac8 = (ac7.receiveRevocation(revocation4) as Try.Success<Commitments>).result
        assertEquals(ac8.availableBalanceForSend(), a - p)
        assertEquals(ac8.availableBalanceForReceive(), b + p)
    }

}