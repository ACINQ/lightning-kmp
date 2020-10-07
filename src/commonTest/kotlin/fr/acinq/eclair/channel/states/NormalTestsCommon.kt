package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.*
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.TestConstants.Bob
import fr.acinq.eclair.blockchain.*
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.channel.TestsHelper.addHtlc
import fr.acinq.eclair.channel.TestsHelper.crossSign
import fr.acinq.eclair.channel.TestsHelper.fulfillHtlc
import fr.acinq.eclair.channel.TestsHelper.reachNormal
import fr.acinq.eclair.channel.TestsHelper.signAndRevack
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.weight2fee
import fr.acinq.eclair.transactions.incomings
import fr.acinq.eclair.transactions.outgoings
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import kotlin.test.*

@OptIn(ExperimentalUnsignedTypes::class)
class NormalTestsCommon {

    private val currentBlockHeight = 144L
    private val CMD_ADD_HTLC: () -> CMD_ADD_HTLC = {
        CMD_ADD_HTLC(
            50_000_000.msat,
            randomBytes32(),
            CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight),
            TestConstants.emptyOnionPacket,
            UUID.randomUUID()
        )
    }

    @Test
    fun `recv CMD_ADD_HTLC (empty origin)`() {
        val (alice0, bob0) = reachNormal()
        val h = randomBytes32()
        val add = CMD_ADD_HTLC().copy(paymentHash = h)

        val (alice1, actions) = alice0.process(ExecuteCommand(add))
        assertTrue((alice1 as Normal).commitments.availableBalanceForSend() < alice0.commitments.availableBalanceForSend())

        val htlc = actions.findOutgoingMessage<UpdateAddHtlc>()
        assertEquals(0, htlc.id)
        assertEquals(h, htlc.paymentHash)

//        assertEquals(alice0.copy(commitments = alice0.commitments.copy(
//            localNextHtlcId = 1,
//            localChanges = alice0.commitments.localChanges.copy(proposed = listOf(htlc)),
////            originChannels = mapOf(0L, add.origin)
//        )), alice1)
    }

    @Test
    fun `recv CMD_ADD_HTLC (incrementing ids)`() {
        val (alice0, bob0) = reachNormal()
        val h = randomBytes32()

        var alice = alice0
        for (i in 0 until 10) {
            val add = CMD_ADD_HTLC().copy(paymentHash = h)

            val (tempAlice, actions) = alice.process(ExecuteCommand(add))
            alice = tempAlice as Normal

            val htlc = actions.findOutgoingMessage<UpdateAddHtlc>()
            assertEquals(i.toLong(), htlc.id)
            assertEquals(h, htlc.paymentHash)
        }
    }

    @Test
    fun `recv CMD_ADD_HTLC (expiry too small)`() {
        val (alice0, bob0) = reachNormal()
        // It's usually dangerous for Bob to accept HTLCs that are expiring soon. However it's not Alice's decision to reject
        // them when she's asked to relay; she should forward those HTLCs to Bob, and Bob will choose whether to fail them
        // or fulfill them (Bob could be #reckless and fulfill HTLCs with a very low expiry delta).
        val expiryTooSmall = CltvExpiry(currentBlockHeight + 3)

        val add = CMD_ADD_HTLC().copy(cltvExpiry = expiryTooSmall)
        val (alice1, actions1) = alice0.process(ExecuteCommand(add))
        actions1.hasError<ExpiryTooSmall>()
        assertEquals(alice0, alice1)
    }

    @Test
    fun `recv CMD_ADD_HTLC (expiry too big)`() {
        val (alice0, bob0) = reachNormal()

        val expiryTooBig = (Channel.MAX_CLTV_EXPIRY_DELTA + 1).toCltvExpiry(currentBlockHeight)
        val add = CMD_ADD_HTLC().copy(cltvExpiry = expiryTooBig)

        val (alice1, actions1) = alice0.process(ExecuteCommand(add))
        val actualError = actions1.findError<ExpiryTooBig>()

        val expectedError = ExpiryTooBig(alice0.channelId,
            maximum = Channel.MAX_CLTV_EXPIRY_DELTA.toCltvExpiry(currentBlockHeight),
            actual = expiryTooBig,
            blockCount = currentBlockHeight)

        assertEquals(expectedError, actualError)
        assertEquals(alice0, alice1)
    }

    @Test
    fun `recv CMD_ADD_HTLC (value too small)`() {
        val (alice0, bob0) = reachNormal()

        val add = CMD_ADD_HTLC().copy(amount = 50.msat)
        val (alice1, actions) = alice0.process(ExecuteCommand(add))
        val actualError = actions.findError<HtlcValueTooSmall>()

        val expectedError = HtlcValueTooSmall(alice0.channelId, 1000.msat, 50.msat)
        assertEquals(expectedError, actualError)
        assertEquals(alice0, alice1)
    }

    @Test
    fun `recv CMD_ADD_HTLC (0 msat)`() {
        val (alice0, bob0) = reachNormal()
        // Alice has a minimum set to 0 msat (which should be invalid, but may mislead Bob into relaying 0-value HTLCs which is forbidden by the spec).
        assertEquals(0.msat, alice0.commitments.localParams.htlcMinimum)
        val add = CMD_ADD_HTLC().copy(amount = 0.msat)
        val (bob1, actions) = bob0.process(ExecuteCommand(add))
        val actualError = actions.findError<HtlcValueTooSmall>()
        val expectedError = HtlcValueTooSmall(bob0.channelId, 1.msat, 0.msat)
        assertEquals(expectedError, actualError)
        assertEquals(bob0, bob1)
    }

    @Ignore
    fun `recv CMD_ADD_HTLC (increasing balance but still below reserve)`() {
        TODO("tag no_push_msat need to be applied here")
    }

    @Test
    fun `recv CMD_ADD_HTLC (insufficient funds)`() {
        val (alice0, bob0) = reachNormal()
        val add = CMD_ADD_HTLC().copy(amount = Int.MAX_VALUE.msat)
        val (alice1, actions) = alice0.process(ExecuteCommand(add))
        val actualError = actions.findError<InsufficientFunds>()
        val expectError = InsufficientFunds(alice0.channelId,
            amount = Int.MAX_VALUE.msat,
            missing = 1_379_883.sat,
            reserve = 20_000.sat,
            fees = 8960.sat)
        assertEquals(expectError, actualError)
        assertEquals(alice0, alice1)
    }

    @Ignore
    fun `recv CMD_ADD_HTLC (insufficient funds) (anchor outputs)`() {
        TODO("tag anchor outputs -> ChannelVersion.ANCHOR_OUTPUTS not available")
    }

    @Test
    fun `recv CMD_ADD_HTLC (insufficient funds, missing 1 msat)`() {
        val (_, bob0) = reachNormal()
        val add = CMD_ADD_HTLC().copy(amount = bob0.commitments.availableBalanceForSend() + 1.sat.toMilliSatoshi())
        val (bob1, actions) = bob0.process(ExecuteCommand(add))
        val actualError = actions.findError<InsufficientFunds>()
        val expectedError = InsufficientFunds(bob0.channelId, amount = add.amount, missing = 1.sat, reserve = 10000.sat, fees = 0.sat)
        assertEquals(expectedError, actualError)
        assertEquals(bob0, bob1)
    }

    @Test
    fun `recv CMD_ADD_HTLC (HTLC dips into remote funder fee reserve)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, bob1) = addHtlc(767_600_000.msat, alice0, bob0).first
        val (alice2, bob2) = crossSign(alice1, bob1)
        assertEquals(0.msat, (alice2 as HasCommitments).commitments.availableBalanceForSend())

        // actual test begins
        // at this point alice has the minimal amount to sustain a channel
        // alice maintains an extra reserve to accommodate for a few more HTLCs, so the first two HTLCs should be allowed
        val (bob3, actionsBob3) = bob2.process(ExecuteCommand(CMD_ADD_HTLC().copy(amount = 12_000_000.msat)))
        actionsBob3.hasMessage<UpdateAddHtlc>()
        val (bob4, actionsBob4) = bob3.process(ExecuteCommand(CMD_ADD_HTLC().copy(amount = 12_500_000.msat)))
        actionsBob4.hasMessage<UpdateAddHtlc>()
        // but this one will dip alice below her reserve: we must wait for the two previous HTLCs to settle before sending any more
        val (_, actionsBob5) = bob4.process(ExecuteCommand(CMD_ADD_HTLC().copy(amount = 11_000_000.msat)))
        actionsBob5.hasError<RemoteCannotAffordFeesForNewHtlc>()
    }

    @Test
    fun `recv CMD_ADD_HTLC (insufficient funds with pending htlcs and 0 balance)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, actionsAlice1) = alice0.process(ExecuteCommand(CMD_ADD_HTLC().copy(amount = 50_000_000.msat)))
        actionsAlice1.hasMessage<UpdateAddHtlc>()
        val (alice2, actionsAlice2) = alice1.process(ExecuteCommand(CMD_ADD_HTLC().copy(amount = 20_000_000.msat)))
        actionsAlice2.hasMessage<UpdateAddHtlc>()
        val (alice3, actionsAlice3) = alice2.process(ExecuteCommand(CMD_ADD_HTLC().copy(amount = 64_160_000.msat)))
        actionsAlice3.hasMessage<UpdateAddHtlc>()
        val (_, actionsAlice4) = alice3.process(ExecuteCommand(CMD_ADD_HTLC().copy(amount = 1_000_000.msat)))
        val actualError = actionsAlice4.findError<InsufficientFunds>()
        val expectedError = InsufficientFunds(alice0.channelId, amount = 1_000_000.msat, missing = 1000.sat, reserve = 20_000.sat, fees = 12_400.sat)
        assertEquals(expectedError, actualError)
    }

    @Test
    fun `recv CMD_ADD_HTLC (insufficient funds with pending htlcs 2-2)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, actionsAlice1) = alice0.process(ExecuteCommand(CMD_ADD_HTLC().copy(amount = 300_000_000.msat)))
        actionsAlice1.hasMessage<UpdateAddHtlc>()
        val (alice2, actionsAlice2) = alice1.process(ExecuteCommand(CMD_ADD_HTLC().copy(amount = 300_000_000.msat)))
        actionsAlice2.hasMessage<UpdateAddHtlc>()
        val (_, actionsAlice3) = alice2.process(ExecuteCommand(CMD_ADD_HTLC().copy(amount = 500_000_000.msat)))
        val actualError = actionsAlice3.findError<InsufficientFunds>()
        val expectError = InsufficientFunds(alice0.channelId, amount = 500_000_000.msat, missing = 335_840.sat, reserve = 20_000.sat, fees = 12_400.sat)
        assertEquals(expectError, actualError)
    }

    @Test
    fun `recv CMD_ADD_HTLC (over max inflight htlc value)`() {
        val (alice0, bob0) = reachNormal()
        val (bob1, actions) = bob0.process(ExecuteCommand(CMD_ADD_HTLC().copy(amount = 151_000_000.msat)))
        val actualError = actions.findError<HtlcValueTooHighInFlight>()
        val expectedError = HtlcValueTooHighInFlight(bob0.channelId, maximum = 150_000_000UL, actual = 151_000_000.msat)
        assertEquals(expectedError, actualError)
    }

    @Test
    fun `recv CMD_ADD_HTLC (over max inflight htlc value with duplicate amounts)`() {
        val (_, bob0) = reachNormal()
        val (bob1, actionsBob1) = bob0.process(ExecuteCommand(CMD_ADD_HTLC().copy(amount = 75_500_000.msat)))
        actionsBob1.hasMessage<UpdateAddHtlc>()
        val (_, actionsBob2) = bob1.process(ExecuteCommand(CMD_ADD_HTLC().copy(amount = 75_500_000.msat)))
        val actualError = actionsBob2.findError<HtlcValueTooHighInFlight>()
        val expectedError = HtlcValueTooHighInFlight(bob0.channelId, maximum = 150_000_000UL, actual = 151_000_000.msat)
        assertEquals(expectedError, actualError)
    }

    @Test
    fun `recv CMD_ADD_HTLC (over max accepted htlcs)`() {
        val (alice0, bob0) = reachNormal()

        // Bob accepts a maximum of 30 htlcs
        val alice1 = kotlin.run {
            var alice = alice0
            for (i in 0 until 100) { // TODO 30 ?
                val (tempAlice, actions) = alice.process(ExecuteCommand(CMD_ADD_HTLC().copy(amount = 1_000_000.msat)))
                println(actions)
                actions.hasMessage<UpdateAddHtlc>()
                alice = tempAlice as Normal
            }
            alice
        }

        val (_, actions) = alice1.process(ExecuteCommand(CMD_ADD_HTLC().copy(amount = 1_000_000.msat)))
        val actualError = actions.findError<TooManyAcceptedHtlcs>()
        val expectedError = TooManyAcceptedHtlcs(alice0.channelId, maximum = 100)
        assertEquals(expectedError, actualError)
    }

    @Test
    fun `recv CMD_ADD_HTLC (over capacity)`() {
        val (alice0, bob0) = reachNormal()

        val (alice1, actionsAlice1) = alice0.process(ExecuteCommand(CMD_ADD_HTLC().copy(amount = TestConstants.fundingSatoshis.toMilliSatoshi() * 2 / 3)))
        actionsAlice1.hasMessage<UpdateAddHtlc>()
        val (alice2, actionsAlice2) = alice1.process(ExecuteCommand(CMD_SIGN))
        actionsAlice2.hasMessage<CommitSig>()
        // this is over channel-capacity
        val failAdd = CMD_ADD_HTLC().copy(amount = TestConstants.fundingSatoshis.toMilliSatoshi() * 2 / 3)
        val (_, actionsAlice3) = alice2.process(ExecuteCommand(failAdd))
        val actualError = actionsAlice3.findError<InsufficientFunds>()
        val expectedError = InsufficientFunds(alice0.channelId, failAdd.amount, 567_453.sat, 20_000.sat, 10_680.sat)
        assertEquals(expectedError, actualError)
    }

    @Test
    fun `recv CMD_ADD_HTLC (channel feerate mismatch)`() {
        TODO("not implemented yet!")
    }

    @Test
    fun `recv CMD_ADD_HTLC (after having sent Shutdown)`() {
        TODO("not implemented yet!")
    }

    @Test
    fun `recv CMD_ADD_HTLC (after having received Shutdown)`() {
        TODO("not implemented yet!")
    }

    @Test
    fun `recv CMD_SIGN`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, _) = addHtlc(50_000_000.msat, payer = alice0, payee = bob0).first
        val (alice2, actions) = alice1.process(ExecuteCommand(CMD_SIGN))
        val commitSig = actions.findOutgoingMessage<CommitSig>()
        assertEquals(1, commitSig.htlcSignatures.size)
        assertTrue { (alice2 as HasCommitments).commitments.remoteNextCommitInfo.isLeft }
    }

    @Test
    fun `recv CMD_SIGN (two identical htlcs in each direction)`() {
        val (alice0, bob0) = reachNormal()
        val currentBlockHeight = alice0.currentBlockHeight.toLong()
        val add = CMD_ADD_HTLC(10_000_000.msat,
            randomBytes32(),
            CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight),
            TestConstants.emptyOnionPacket,
            UUID.randomUUID())

        val (alice1, actionsAlice1) = alice0.process(ExecuteCommand(add))
        val htlc1 = actionsAlice1.findOutgoingMessage<UpdateAddHtlc>()
        val (bob1, _) = bob0.process(MessageReceived(htlc1))
        val (alice2, actionsAlice2) = alice1.process(ExecuteCommand(add))
        val htlc2 = actionsAlice2.findOutgoingMessage<UpdateAddHtlc>()
        val (bob2, _) = bob1.process(MessageReceived(htlc2))

        val (alice3, bob3) = crossSign(alice2, bob2)

        val (bob4, actionsBob4) = bob3.process(ExecuteCommand(add))
        val htlc3 = actionsBob4.findOutgoingMessage<UpdateAddHtlc>()
        val (alice4, _) = alice3.process(MessageReceived(htlc3))
        val (bob5, actionsBob5) = bob4.process(ExecuteCommand(add))
        val htlc4 = actionsBob5.findOutgoingMessage<UpdateAddHtlc>()
        alice4.process(MessageReceived(htlc4))

        val (_, actionsBob6) = bob5.process(ExecuteCommand(CMD_SIGN))
        val commitSig = actionsBob6.findOutgoingMessage<CommitSig>()
        assertEquals(4, commitSig.htlcSignatures.size)
    }

    @Ignore
    fun `recv CMD_SIGN (check htlc info are persisted)`() {
        val (alice0, bob0) = reachNormal()
        // for the test to be really useful we have constraint on parameters
        assertTrue { alice0.staticParams.nodeParams.dustLimit > bob0.staticParams.nodeParams.dustLimit }
        // we're gonna exchange two htlcs in each direction, the goal is to have bob's commitment have 4 htlcs, and alice's
        // commitment only have 3. We will then check that alice indeed persisted 4 htlcs, and bob only 3.
        val aliceMinReceive =
            Alice.nodeParams.dustLimit + weight2fee(TestConstants.feeratePerKw, Transactions.htlcSuccessWeight)
        val aliceMinOffer =
            Alice.nodeParams.dustLimit + weight2fee(TestConstants.feeratePerKw, Transactions.htlcTimeoutWeight)
        val bobMinReceive =
            Bob.nodeParams.dustLimit + weight2fee(TestConstants.feeratePerKw, Transactions.htlcSuccessWeight)
        val bobMinOffer =
            Bob.nodeParams.dustLimit + weight2fee(TestConstants.feeratePerKw, Transactions.htlcTimeoutWeight)
        val a2b_1 = bobMinReceive + 10.sat // will be in alice and bob tx
        val a2b_2 = bobMinReceive + 20.sat // will be in alice and bob tx
        val b2a_1 = aliceMinReceive + 10.sat // will be in alice and bob tx
        val b2a_2 = bobMinOffer + 10.sat // will be only be in bob tx
        assertTrue { a2b_1 > aliceMinOffer && a2b_1 > bobMinReceive }
        assertTrue { a2b_2 > aliceMinOffer && a2b_2 > bobMinReceive }
        assertTrue { b2a_1 > aliceMinReceive && b2a_1 > bobMinOffer }
        assertTrue { b2a_2 < aliceMinReceive && b2a_2 > bobMinOffer }

        val (alice1, bob1) = addHtlc(a2b_1.toMilliSatoshi(), alice0, bob0).first
        val (alice2, bob2) = addHtlc(a2b_2.toMilliSatoshi(), alice1, bob1).first
        val (alice3, bob3) = addHtlc(b2a_1.toMilliSatoshi(), bob2, alice2).first
        val (alice4, bob4) = addHtlc(b2a_2.toMilliSatoshi(), bob3, alice3).first

        val (alice5, bob5) = crossSign(alice4, bob4)
        // depending on who starts signing first, there will be one or two commitments because both sides have changes
        alice5 as HasCommitments; bob5 as HasCommitments
        assertEquals(1, alice5.commitments.localCommit.index)
        assertEquals(2, bob5.commitments.localCommit.index)

        /* TODO test DB channels ?
                assert(alice.underlyingActor.nodeParams.db.channels.listHtlcInfos(alice.stateData.asInstanceOf[DATA_NORMAL].channelId, 0).size == 0)
                assert(alice.underlyingActor.nodeParams.db.channels.listHtlcInfos(alice.stateData.asInstanceOf[DATA_NORMAL].channelId, 1).size == 2)
                assert(alice.underlyingActor.nodeParams.db.channels.listHtlcInfos(alice.stateData.asInstanceOf[DATA_NORMAL].channelId, 2).size == 4)
                assert(bob.underlyingActor.nodeParams.db.channels.listHtlcInfos(bob.stateData.asInstanceOf[DATA_NORMAL].channelId, 0).size == 0)
                assert(bob.underlyingActor.nodeParams.db.channels.listHtlcInfos(bob.stateData.asInstanceOf[DATA_NORMAL].channelId, 1).size == 3)
         */
    }

    @Test
    fun `recv CMD_SIGN (htlcs with same pubkeyScript but different amounts)`() {
        val (alice0, bob0) = reachNormal()
        val add = CMD_ADD_HTLC(10_000_000.msat,
            randomBytes32(),
            CltvExpiryDelta(144).toCltvExpiry(alice0.currentBlockHeight.toLong()),
            TestConstants.emptyOnionPacket,
            UUID.randomUUID())
        val epsilons = listOf(3, 1, 5, 7, 6) // unordered on purpose
        val htlcCount = epsilons.size

        val (alice1, bob1) = kotlin.run {
            var (alice1, bob1) = alice0 to bob0
            for (i in epsilons) {
                val (stateA, actionsA) = alice1.process(ExecuteCommand(add.copy(amount = add.amount + (i * 1000).msat)))
                alice1 = stateA as Normal
                val updateAddHtlc = actionsA.findOutgoingMessage<UpdateAddHtlc>()
                val (stateB, _) = bob1.process(MessageReceived(updateAddHtlc))
                bob1 = stateB as Normal
            }
            alice1 to bob1
        }

        val (_, actionsAlice2) = alice1.process(ExecuteCommand(CMD_SIGN))
        val commitSig = actionsAlice2.findOutgoingMessage<CommitSig>()
        assertEquals(htlcCount, commitSig.htlcSignatures.size)
        val (bob2, _) = bob1.process(MessageReceived(commitSig))
        bob2 as HasCommitments
        val htlcTxs = bob2.commitments.localCommit.publishableTxs.htlcTxsAndSigs
        assertEquals(htlcCount, htlcTxs.size)
        val amounts = htlcTxs.map { it.txinfo.tx.txOut.first().amount.sat }
        assertEquals(amounts, amounts.sorted())
    }

    @Test
    fun `recv CMD_SIGN (no changes)`() {
        val (alice0, _) = reachNormal()
        val (_, actions) = alice0.process(ExecuteCommand(CMD_SIGN))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `recv CMD_SIGN (while waiting for RevokeAndAck (no pending changes)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, bob1) = addHtlc(50000000.msat, alice0, bob0).first
        assertTrue((alice1 as HasCommitments).commitments.remoteNextCommitInfo.isRight)
        val (alice2, actionsAlice2) = alice1.process(ExecuteCommand(CMD_SIGN))
        actionsAlice2.hasMessage<CommitSig>()

        (alice2 as HasCommitments)
        assertNotNull(alice2.commitments.remoteNextCommitInfo.left)
        val waitForRevocation = alice2.commitments.remoteNextCommitInfo.left
        assertEquals(false, waitForRevocation?.reSignAsap)

        val (alice3, _) = alice2.process(ExecuteCommand(CMD_SIGN))
        assertEquals(Either.Left(waitForRevocation), (alice3 as HasCommitments).commitments.remoteNextCommitInfo)
    }

    @Test
    fun `recv CMD_SIGN (while waiting for RevokeAndAck (with pending changes)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, bob1) = addHtlc(50_000_000.msat, alice0, bob0).first
        assertTrue((alice1 as HasCommitments).commitments.remoteNextCommitInfo.isRight)
        val (alice2, actionsAlice2) = alice1.process(ExecuteCommand(CMD_SIGN))
        actionsAlice2.hasMessage<CommitSig>()

        (alice2 as HasCommitments)
        assertNotNull(alice2.commitments.remoteNextCommitInfo.left)
        val waitForRevocation = alice2.commitments.remoteNextCommitInfo.left
        assertNotNull(waitForRevocation)
        assertFalse(waitForRevocation.reSignAsap)

        val (alice3, _) = addHtlc(50_000_000.msat, alice2, bob1).first
        val (alice4, _) = alice3.process(ExecuteCommand(CMD_SIGN))
        (alice4 as HasCommitments)
        assertEquals(Either.Left(waitForRevocation.copy(reSignAsap = true)), alice4.commitments.remoteNextCommitInfo)
    }

    @Ignore
    fun `recv CMD_SIGN (going above reserve)`() {
        val (alice0, bob0) = reachNormal()
        // TODO channel starts with all funds on alice's side, so channel will be initially disabled on bob's side
//                assertEquals(false, Announcements.isEnabled(bob0.channelUpdate.channelFlags))
    }

    @Ignore
    fun `recv CMD_SIGN (after CMD_UPDATE_FEE)`() {
        TODO("not implemented yet!")
    }

    @Test
    fun `recv CMD_SIGN (channel backup, zero-reserve channel, fundee)`() {
        val currentBlockHeight = 500L
        val (alice, bob) = reachNormal(ChannelVersion.STANDARD or ChannelVersion.ZERO_RESERVE)
        val (_, cmdAdd) = TestsHelper.makeCmdAdd(50000000.msat,
            alice.staticParams.nodeParams.nodeId,
            currentBlockHeight)
        val (bob1, actions) = bob.process(ExecuteCommand(cmdAdd))
        val add = actions.findOutgoingMessage<UpdateAddHtlc>()
        val (alice1, _) = alice.process(MessageReceived(add))
        assertTrue { (alice1 as Normal).commitments.remoteChanges.proposed.contains(add) }
        val (bob2, actions2) = bob1.process(ExecuteCommand(CMD_SIGN))
        val commitSig = actions2.findOutgoingMessage<CommitSig>()
        val blob = Helpers.encrypt(bob.staticParams.nodeParams.nodePrivateKey.value, bob2 as Normal)
        assertEquals(blob.toByteVector(), commitSig.channelData)
    }

    @Test
    fun `recv CommitSig (one htlc received)`() {
        val (alice0, bob0) = reachNormal()
        val (nodes0, _, htlc) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, initialState) = nodes0

        val (_, bob2) = signAndRevack(alice1, initialState)
        val (bob3, _) = bob2.process(ExecuteCommand(CMD_SIGN))

        bob3 as HasCommitments; initialState as HasCommitments
        assertTrue(bob3.commitments.localCommit.spec.htlcs.incomings().any { it.id == htlc.id })
        assertEquals(1, bob3.commitments.localCommit.publishableTxs.htlcTxsAndSigs.size)
        assertEquals(initialState.commitments.localCommit.spec.toLocal, bob3.commitments.localCommit.spec.toLocal)
        assertEquals(0, bob3.commitments.remoteChanges.acked.size)
        assertEquals(1, bob3.commitments.remoteChanges.signed.size)
    }

    @Test
    fun `recv CommitSig (one htlc sent)`() {
        val (alice0, bob0) = reachNormal()
        val (nodes0, _, htlc) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, initialState) = nodes0

        val (alice2, bob2) = signAndRevack(alice1, initialState)
        val (bob3, actionsBob3) = bob2.process(ExecuteCommand(CMD_SIGN))
        val commitSig = actionsBob3.findOutgoingMessage<CommitSig>()
        val (alice3, _) = alice2.process(MessageReceived(commitSig))

        alice3 as HasCommitments; bob3 as HasCommitments; initialState as HasCommitments
        assertTrue(alice3.commitments.localCommit.spec.htlcs.outgoings().any { it.id == htlc.id })
        assertEquals(1, alice3.commitments.localCommit.publishableTxs.htlcTxsAndSigs.size)
        assertEquals(1, alice3.commitments.localCommit.publishableTxs.htlcTxsAndSigs.size)
        assertEquals(initialState.commitments.localCommit.spec.toLocal, bob3.commitments.localCommit.spec.toLocal)
    }

    @Test
    fun `recv CommitSig (multiple htlcs in both directions)`() {
        val (alice0, bob0) = reachNormal()
        val (nodes1, r1, htlc1) = addHtlc(50_000_000.msat, alice0, bob0) // a->b (regular)
        val (alice1, bob1) = nodes1
        val (nodes2, r2, htlc2) = addHtlc(8_000_000.msat, alice1, bob1) //  a->b (regular)
        val (alice2, bob2) = nodes2
        val (nodes3, r3, htlc3) = addHtlc(300_000.msat, bob2, alice2) //   b->a (dust)
        val (bob3, alice3) = nodes3
        val (nodes4, r4, htlc4) = addHtlc(1_000_000.msat, alice3, bob3) //  a->b (regular)
        val (alice4, bob4) = nodes4
        val (nodes5, r5, htlc5) = addHtlc(50_000_000.msat, bob4, alice4) // b->a (regular)
        val (bob5, alice5) = nodes5
        val (nodes6, r6, htlc6) = addHtlc(500_000.msat, alice5, bob5) //   a->b (dust)
        val (alice6, bob6) = nodes6
        val (nodes7, r7, htlc7) = addHtlc(4_000_000.msat, bob6, alice6) //  b->a (regular)
        val (bob7, alice7) = nodes7

        val (alice8, bob8) = signAndRevack(alice7, bob7)
        val (_, actionsBob9) = bob8.process(ExecuteCommand(CMD_SIGN))
        val commitSig = actionsBob9.findOutgoingMessage<CommitSig>()
        val (alice9, _) = alice8.process(MessageReceived(commitSig))

        alice9 as HasCommitments
        assertEquals(1, alice9.commitments.localCommit.index)
        assertEquals(3, alice9.commitments.localCommit.publishableTxs.htlcTxsAndSigs.size)
    }

    @Ignore
    fun `recv CommitSig (only fee update)`() {
        TODO("not implemented yet!")
    }

    @Test
    fun `recv CommitSig (two htlcs received with same r)`() {
        val (alice0, bob0) = reachNormal()

        val r = randomBytes32()
        val h = Crypto.sha256(r).toByteVector32()
        val currentBlockHeight = alice0.currentBlockHeight.toLong()

        val (alice1, actionsAlice1) = alice0.process(ExecuteCommand(
            CMD_ADD_HTLC(50_000_000.msat,
                h,
                CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight),
                TestConstants.emptyOnionPacket,
                UUID.randomUUID())
        ))
        val htlc1 = actionsAlice1.findOutgoingMessage<UpdateAddHtlc>()
        val (bob1, _) = bob0.process(MessageReceived(htlc1))

        val (alice2, actionsAlice2) = alice1.process(ExecuteCommand(
            CMD_ADD_HTLC(50_000_000.msat,
                h,
                CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight),
                TestConstants.emptyOnionPacket,
                UUID.randomUUID())
        ))
        val htlc2 = actionsAlice2.findOutgoingMessage<UpdateAddHtlc>()
        val (bob2, _) = bob1.process(MessageReceived(htlc2))

        assertEquals(listOf(htlc1, htlc2), (bob2 as HasCommitments).commitments.remoteChanges.proposed)
        val initialState = bob2

        val (_, bob3) = crossSign(alice2, bob2)

        bob3 as HasCommitments
        assertTrue(bob3.commitments.localCommit.spec.htlcs.incomings().any { it.id == htlc1.id })
        assertEquals(2, bob3.commitments.localCommit.publishableTxs.htlcTxsAndSigs.size)
        assertEquals(initialState.commitments.localCommit.spec.toLocal, bob3.commitments.localCommit.spec.toLocal)
        assertEquals(2, bob3.commitments.localCommit.publishableTxs.commitTx.tx.txOut.count { it.amount == 50_000.sat })
    }

    @Test
    fun `recv CommitSig (no changes)`() {
        val (alice0, bob0) = reachNormal()
        val tx = bob0.commitments.localCommit.publishableTxs.commitTx.tx

        // signature is invalid but it doesn't matter
        val sig = CommitSig(ByteVector32.Zeroes, ByteVector64.Zeroes, emptyList())
        val (bob1, actionsBob1) = bob0.process(MessageReceived(sig))
        /** TODO handle invalid sig!!
        sender.send(bob, CommitSig(ByteVector32.Zeroes, ByteVector64.Zeroes, Nil))
        val error = bob2alice.expectMsgType[Error]
        assert(new String(error.data.toArray).startsWith("cannot sign when there are no changes"))
        awaitCond(bob.stateName == CLOSING)
        // channel should be advertised as down
        assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
        bob2blockchain.expectMsg(PublishAsap(tx))
        bob2blockchain.expectMsgType[PublishAsap]
        bob2blockchain.expectMsgType[WatchConfirmed]
         */
    }

    @Test
    fun `recv CommitSig (invalid signature)`() {
        val (alice0, bob0) = reachNormal()
        val tx = bob0.commitments.localCommit.publishableTxs.commitTx.tx

        // signature is invalid but it doesn't matter
        val sig = CommitSig(ByteVector32.Zeroes, ByteVector64.Zeroes, emptyList())
        val (bob1, actionsBob1) = bob0.process(MessageReceived(sig))
        val error = actionsBob1.findError<InvalidCommitmentSignature>()
        error.message.let {
            assertNotNull(it)
            assertTrue(it.toLowerCase().startsWith("invalid commitment signature"))
        }
        /* TODO handle invalid sig!!
            assertTrue(bob1 is Closing)
            actionsBob1.hasMessage<PublishTx>()
            actionsBob1.hasMessage<WatchConfirmed>()
         */
    }

    @Test
    fun `recv CommitSig (bad htlc sig count)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, bob1) = addHtlc(50_000_000.msat, alice0, bob0).first
        val tx = (bob1 as HasCommitments).commitments.localCommit.publishableTxs.commitTx.tx

        val (alice2, actionsAlice2) = alice1.process(ExecuteCommand(CMD_SIGN))
        val commitSig = actionsAlice2.findOutgoingMessage<CommitSig>()
        val badCommitSig = commitSig.copy(htlcSignatures = commitSig.htlcSignatures + commitSig.htlcSignatures)

        val (bob2, actionsBob2) = bob1.process(MessageReceived(badCommitSig))
        /*  TODO handle invalid sig!!
                val error = bob2alice.expectMsgType[Error]
                assert(new String(error.data.toArray) === HtlcSigCountMismatch(channelId(bob), expected = 1, actual = 2).getMessage)
                bob2blockchain.expectMsg(PublishAsap(tx))
                bob2blockchain.expectMsgType[PublishAsap]
                bob2blockchain.expectMsgType[WatchConfirmed]
         */
    }

    @Test
    fun `recv CommitSig (invalid htlc sig)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, bob1) = addHtlc(50_000_000.msat, alice0, bob0).first
        val tx = (bob1 as HasCommitments).commitments.localCommit.publishableTxs.commitTx.tx

        val (alice2, actionsAlice2) = alice1.process(ExecuteCommand(CMD_SIGN))
        val commitSig = actionsAlice2.findOutgoingMessage<CommitSig>()
        val badCommitSig = commitSig.copy(htlcSignatures = listOf(commitSig.signature))
        val (bob2, actionsBob2) = bob1.process(MessageReceived(badCommitSig))
        /*  TODO handle invalid sig!!
                val error = bob2alice.expectMsgType[Error]
                assert(new String(error.data.toArray).startsWith("invalid htlc signature"))
                bob2blockchain.expectMsg(PublishAsap(tx))
                bob2blockchain.expectMsgType[PublishAsap]
                bob2blockchain.expectMsgType[WatchConfirmed]
         */
    }

    @Test
    fun `recv RevokeAndAck (channel backup, zero-reserve channel, fundee)`() {
        val currentBlockHeight = 500L
        val (alice, bob) = reachNormal(ChannelVersion.STANDARD or ChannelVersion.ZERO_RESERVE)
        val (_, cmdAdd) = TestsHelper.makeCmdAdd(50000000.msat,
            alice.staticParams.nodeParams.nodeId,
            currentBlockHeight)
        val (bob1, actions) = bob.process(ExecuteCommand(cmdAdd))
        val add = actions.findOutgoingMessage<UpdateAddHtlc>()
        val (alice1, _) = alice.process(MessageReceived(add))
        assertTrue { (alice1 as Normal).commitments.remoteChanges.proposed.contains(add) }
        val (bob2, actions2) = bob1.process(ExecuteCommand(CMD_SIGN))
        val commitSig = actions2.findOutgoingMessage<CommitSig>()
        val (alice2, actions3) = alice1.process(MessageReceived(commitSig))
        val revack = actions3.findOutgoingMessage<RevokeAndAck>()
        val (bob3, _) = bob2.process(MessageReceived(revack))
        val (_, actions4) = alice2.process(ExecuteCommand(CMD_SIGN))
        val commitSig1 = actions4.findOutgoingMessage<CommitSig>()
        val (bob4, actions5) = bob3.process(MessageReceived(commitSig1))
        val revack1 = actions5.findOutgoingMessage<RevokeAndAck>()
        val blob = Helpers.encrypt(bob4.staticParams.nodeParams.nodePrivateKey.value, bob4 as Normal)
        assertEquals(blob.toByteVector(), revack1.channelData)
    }

    @Test
    fun `recv RevokeAndAck (one htlc sent)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, bob1) = addHtlc(50_000_000.msat, alice0, bob0).first

        val (alice2, actionsAlice2) = alice1.process(ExecuteCommand(CMD_SIGN))
        val commitSig = actionsAlice2.findOutgoingMessage<CommitSig>()
        val (_, actionsBob2) = bob1.process(MessageReceived(commitSig))
        val revokeAndAck = actionsBob2.findOutgoingMessage<RevokeAndAck>()

        assertTrue((alice2 as HasCommitments).commitments.remoteNextCommitInfo.isLeft)
        val (alice3, _) = alice2.process(MessageReceived(revokeAndAck))
        assertTrue((alice3 as HasCommitments).commitments.remoteNextCommitInfo.isRight)
        assertEquals(1, (alice3 as HasCommitments).commitments.localChanges.acked.size)
    }

    @Test
    fun `recv RevokeAndAck (one htlc received)`() {
        val (alice0, bob0) = reachNormal()
        val (nodes, _, htlc) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, bob1) = nodes

        val (alice2, actionsAlice2) = alice1.process(ExecuteCommand(CMD_SIGN))
        val commitSig0 = actionsAlice2.findOutgoingMessage<CommitSig>()

        val (bob2, actionsBob2) = bob1.process(MessageReceived(commitSig0))
        val revokeAndAck0 = actionsBob2.findOutgoingMessage<RevokeAndAck>()
        val cmd = actionsBob2.findProcessCommand<CMD_SIGN>()
        val (bob3, actionsBob3) = bob2.process(ExecuteCommand(cmd))
        val (alice3, _) = alice2.process(MessageReceived(revokeAndAck0))
        assertTrue((alice3 as HasCommitments).commitments.remoteNextCommitInfo.isRight)
        val commitSig1 = actionsBob3.findOutgoingMessage<CommitSig>()
        val (_, actionsAlice4) = alice3.process(MessageReceived(commitSig1))
        val revokeAndAck1 = actionsAlice4.findOutgoingMessage<RevokeAndAck>()
        val (bob4, _) = bob3.process(MessageReceived(revokeAndAck1))
        assertTrue((bob4 as HasCommitments).commitments.remoteNextCommitInfo.isRight)
    }

    @Test
    fun `recv RevokeAndAck (multiple htlcs in both directions)`() {
        val (alice0, bob0) = reachNormal()
        val (nodes1, r1, htlc1) = addHtlc(50_000_000.msat, alice0, bob0) // a->b (regular)
        val (alice1, bob1) = nodes1
        val (nodes2, r2, htlc2) = addHtlc(8_000_000.msat, alice1, bob1) //  a->b (regular)
        val (alice2, bob2) = nodes2
        val (nodes3, r3, htlc3) = addHtlc(300_000.msat, bob2, alice2) //   b->a (dust)
        val (bob3, alice3) = nodes3
        val (nodes4, r4, htlc4) = addHtlc(1_000_000.msat, alice3, bob3) //  a->b (regular)
        val (alice4, bob4) = nodes4
        val (nodes5, r5, htlc5) = addHtlc(50_000_000.msat, bob4, alice4) // b->a (regular)
        val (bob5, alice5) = nodes5
        val (nodes6, r6, htlc6) = addHtlc(500_000.msat, alice5, bob5) //   a->b (dust)
        val (alice6, bob6) = nodes6
        val (nodes7, r7, htlc7) = addHtlc(4_000_000.msat, bob6, alice6) //  b->a (regular)
        val (bob7, alice7) = nodes7

        val (alice8, actionsAlice8) = alice7.process(ExecuteCommand(CMD_SIGN))
        val commitSig0 = actionsAlice8.findOutgoingMessage<CommitSig>()
        val (bob8, actionsBob8) = bob7.process(MessageReceived(commitSig0))
        val revokeAndAck0 = actionsBob8.findOutgoingMessage<RevokeAndAck>()
        val cmd = actionsBob8.findProcessCommand<CMD_SIGN>()
        val (bob9, actionsBob9) = bob8.process(ExecuteCommand(cmd))
        val (alice9, _) = alice8.process(MessageReceived(revokeAndAck0))
        assertTrue((alice9 as HasCommitments).commitments.remoteNextCommitInfo.isRight)

        val commitSig1 = actionsBob9.findOutgoingMessage<CommitSig>()
        val (_, actionsAlice10) = alice9.process(MessageReceived(commitSig1))
        val revokeAndAck1 = actionsAlice10.findOutgoingMessage<RevokeAndAck>()
        val (bob10, _) = bob9.process(MessageReceived(revokeAndAck1))
        bob10 as HasCommitments

        assertTrue(bob10.commitments.remoteNextCommitInfo.isRight)
        assertEquals(1, bob10.commitments.remoteCommit.index)
        assertEquals(7, bob10.commitments.remoteCommit.spec.htlcs.size)
    }

    @Test
    fun `recv RevokeAndAck (with reSignAsap=true)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, bob1) = addHtlc(50_000_000.msat, alice0, bob0).first
        assertTrue((alice1 as HasCommitments).commitments.remoteNextCommitInfo.isRight)

        val (alice2, actionsAlice2) = alice1.process(ExecuteCommand(CMD_SIGN))
        val commitSig0 = actionsAlice2.findOutgoingMessage<CommitSig>()
        val (bob2, actionsBob2) = bob1.process(MessageReceived(commitSig0))

        val (alice3, _) = addHtlc(50_000_000.msat, alice2, bob2).first
        val (alice4, _) = alice3.process(ExecuteCommand(CMD_SIGN))
        assertTrue((alice4 as HasCommitments).commitments.remoteNextCommitInfo.left?.reSignAsap == true)

        val revokeAndAck0 = actionsBob2.findOutgoingMessage<RevokeAndAck>()
        val (alice5, actionsAlice5) = alice4.process(MessageReceived(revokeAndAck0))
        val cmd = actionsAlice5.findProcessCommand<CMD_SIGN>()
        val (_, actionsAlice6) = alice5.process(ExecuteCommand(cmd))
        actionsAlice6.hasMessage<CommitSig>()
    }

    @Test
    fun `recv RevokeAndAck (invalid preimage)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, bob1) = addHtlc(50_000_000.msat, alice0, bob0).first
        val tx = (alice1 as HasCommitments).commitments.localCommit.publishableTxs.commitTx.tx

        val (alice2, actionsAlice2) = alice1.process(ExecuteCommand(CMD_SIGN))
        val commitSig0 = actionsAlice2.findOutgoingMessage<CommitSig>()
        val (bob2, actionsBob2) = bob1.process(MessageReceived(commitSig0))
        actionsBob2.hasMessage<RevokeAndAck>()
        val (alice3, actionsAlice3) = alice2.process(MessageReceived(RevokeAndAck(ByteVector32.Zeroes,
            PrivateKey(randomBytes32()),
            PrivateKey(randomBytes32()).publicKey())))
        val error = actionsAlice3.findError<InvalidRevocation>()
        error.message.let { assertNotNull(it); assertTrue(it.toLowerCase().startsWith("invalid revocation")) }
        /** TODO handle invalid revokeAndAck!
        assertTrue(alice3 is Closing)
        // channel should be advertised as down
        assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
        alice2blockchain.expectMsg(PublishAsap(tx))
        alice2blockchain.expectMsgType[PublishAsap]
        alice2blockchain.expectMsgType[WatchConfirmed]
         */
    }

    @Test
    fun `recv RevokeAndAck (unexpectedly)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, _) = addHtlc(50_000_000.msat, alice0, bob0).first
        val tx = (alice1 as HasCommitments).commitments.localCommit.publishableTxs.commitTx.tx
        assertTrue((alice1 as HasCommitments).commitments.remoteNextCommitInfo.isRight)

        val (alice2, actionsAlice2) = alice1.process(MessageReceived(RevokeAndAck(ByteVector32.Zeroes,
            PrivateKey(randomBytes32()),
            PrivateKey(randomBytes32()).publicKey())))
        val error = actionsAlice2.findError<UnexpectedRevocation>()
        error.message.let {
            assertNotNull(it); assertTrue(it.toLowerCase().startsWith("received unexpected revokeandack message"))
        }
        /** TODO handle invalid revokeAndAck!
        assertTrue(alice2 is Closing)
        // channel should be advertised as down
        assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
        alice2blockchain.expectMsg(PublishAsap(tx))
        alice2blockchain.expectMsgType[PublishAsap]
        alice2blockchain.expectMsgType[WatchConfirmed]
         */
    }

    @Ignore
    fun `recv RevokeAndAck (forward UpdateFailHtlc)`() {
        TODO("?")
    }

    @Ignore
    fun `recv RevokeAndAck (forward UpdateFailMalformedHtlc)`() {
        TODO("?")
    }

    @Ignore
    fun `recv RevokeAndAck (one htlc sent, static_remotekey)`() {
        TODO("?")
    }

    @Ignore
    fun `recv RevocationTimeout`() {
        TODO("?")
    }

    @Test
    fun `recv CMD_FULFILL_HTLC`() {
        val (alice0, bob0) = reachNormal()
        val (nodes, r, htlc) = addHtlc(50_000_000.msat, alice0, bob0)
        val (_, bob1) = crossSign(nodes.first, nodes.second)
        val initialState = bob1 as Normal

        val (bob2, actionsBob2) = bob1.process(ExecuteCommand(CMD_FULFILL_HTLC(htlc.id, r)))
        val fulfillHtlc = actionsBob2.findOutgoingMessage<UpdateFulfillHtlc>()
        assertEquals(initialState.copy(commitments = initialState.commitments.copy(
            localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed + fulfillHtlc)
        )), bob2)
    }

    @Test
    fun `recv CMD_FULFILL_HTLC (unknown htlc id)`() {
        val (alice0, bob0) = reachNormal()

        val r = randomBytes32()
        val initialState = bob0
        val cmd = CMD_FULFILL_HTLC(42, r)

        assertFailsWith<NullPointerException> {
            // TODO expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
            bob0.process(ExecuteCommand(cmd))
        }
    }

    @Test
    fun `recv CMD_FULFILL_HTLC (invalid preimage)`() {
        val (alice0, bob0) = reachNormal()
        val (nodes, r, htlc) = addHtlc(50_000_000.msat, alice0, bob0)
        val (_, bob1) = crossSign(nodes.first, nodes.second)
        val initialState = bob1 as Normal

        val cmd = CMD_FULFILL_HTLC(htlc.id, ByteVector32.Zeroes)
        val (bob2, actionsBob2) = bob1.process(ExecuteCommand(cmd))
        actionsBob2.hasError<InvalidHtlcPreimage>()

        assertEquals(initialState, bob2)
    }

    @Test
    fun `recv CMD_FULFILL_HTLC (acknowledge in case of failure)`() {
        val (_, bob0) = reachNormal()

        val r = randomBytes32()
        val initialState = bob0
        val cmd = CMD_FULFILL_HTLC(42, r)

        assertFailsWith<NullPointerException> {
            // TODO expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
            bob0.process(ExecuteCommand(cmd))
        }
        // TODO ? awaitCond(bob.underlyingActor.nodeParams.db.pendingRelay.listPendingRelay(initialState.channelId).isEmpty)
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT (their commit with htlc)`() {
        val (alice0, bob0) = reachNormal()

        val (nodes0, _, _) = addHtlc(250_000_000.msat, payer = alice0, payee = bob0)
        val (alice1, bob1) = nodes0
        val (nodes1, preimage_alice2bob_2, _) = addHtlc(100_000_000.msat, payer = alice1, payee = bob1)
        val (alice2, bob2) = nodes1
        val (nodes2, _, _) = addHtlc(10_000.msat, payer = alice2, payee = bob2)
        val (alice3, bob3) = nodes2
        val (nodes3, preimage_bob2alice_1, _) = addHtlc(50_000_000.msat, payer = bob3, payee = alice3)
        val (bob4, alice4) = nodes3
        val (nodes4, _, _) = addHtlc(55_000_000.msat, payer = bob4, payee = alice4)
        val (bob5, alice5) = nodes4

        val (alice6, bob6) = crossSign(alice5, bob5)
        val (alice7, bob7) = fulfillHtlc(1, preimage_alice2bob_2, payer = alice6, payee = bob6)
        val (bob8, alice8) = fulfillHtlc(0, preimage_bob2alice_1, payer = bob7, payee = alice7)

        // at this point here is the situation from alice pov and what she should do when bob publishes his commit tx:
        // balances :
        //    alice's balance : 449 999 990                             => nothing to do
        //    bob's balance   :  95 000 000                             => nothing to do
        // htlcs :
        //    alice -> bob    : 250 000 000 (bob does not have the preimage)   => wait for the timeout and spend
        //    alice -> bob    : 100 000 000 (bob has the preimage)             => if bob does not use the preimage, wait for the timeout and spend
        //    alice -> bob    :          10 (dust)                             => won't appear in the commitment tx
        //    bob -> alice    :  50 000 000 (alice has the preimage)           => spend immediately using the preimage
        //    bob -> alice    :  55 000 000 (alice does not have the preimage) => nothing to do, bob will get his money back after the timeout

        alice8 as HasCommitments; bob8 as HasCommitments
        val bobCommitTx = bob8.commitments.localCommit.publishableTxs.commitTx.tx
        assertEquals(6, bobCommitTx.txOut.size) // 2 main outputs and 4 pending htlcs

        val (aliceClosing, actions) = alice8.process(WatchReceived(WatchEventSpent(alice8.channelId,
            BITCOIN_FUNDING_SPENT,
            bobCommitTx)))

        assertTrue(aliceClosing is Closing)
        assertTrue(actions.isNotEmpty())
        // in response to that, alice publishes its claim txes
        val claimTxes = actions.filterIsInstance<PublishTx>().map { it.tx }
        assertEquals(4, claimTxes.size)
        val claimMain = claimTxes.first()
        // in addition to its main output, alice can only claim 3 out of 4 htlcs,
        // she can't do anything regarding the htlc sent by bob for which she does not have the preimage
        val amountClaimed = claimTxes.map { claimHtlcTx ->
            assertEquals(1, claimHtlcTx.txIn.size)
            assertEquals(1, claimHtlcTx.txOut.size)
            Transaction.correctlySpends(claimHtlcTx, listOf(bobCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            claimHtlcTx.txOut[0].amount
        }.sum()
        // at best we have a little less than 450 000 + 250 000 + 100 000 + 50 000 = 850 000 (because fees)
        assertEquals(815220.sat, amountClaimed) // TODO formerly 814880.sat ?

        assertEquals(BITCOIN_TX_CONFIRMED(bobCommitTx), actions.watches<WatchConfirmed>()[0].event)
        assertEquals(BITCOIN_TX_CONFIRMED(claimMain), actions.watches<WatchConfirmed>()[1].event)
        assertEquals(3, actions.watches<WatchSpent>().count { it.event is BITCOIN_OUTPUT_SPENT })

        assertEquals(1, aliceClosing.remoteCommitPublished?.claimHtlcSuccessTxs?.size)
        assertEquals(2, aliceClosing.remoteCommitPublished?.claimHtlcTimeoutTxs?.size)

        // assert the feerate of the claim main is what we expect
        aliceClosing.staticParams.nodeParams.onChainFeeConf.run {
            val feeTargets = aliceClosing.staticParams.nodeParams.onChainFeeConf.feeTargets
            val expectedFeeRate = feeEstimator.getFeeratePerKw(feeTargets.claimMainBlockTarget)
            val expectedFee = Transactions.weight2fee(expectedFeeRate, Transactions.claimP2WPKHOutputWeight)
            val claimFee = claimMain.txIn.map {
                bobCommitTx.txOut[it.outPoint.index.toInt()].amount
            }.sum() - claimMain.txOut.map { it.amount }.sum()
            assertEquals(expectedFee, claimFee)
        }
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT (their *next* commit with htlc)`() {
        val (alice0, bob0) = reachNormal()

        val (nodes0, _, _) = addHtlc(250_000_000.msat, payer = alice0, payee = bob0)
        val (alice1, bob1) = nodes0
        val (nodes1, preimage_alice2bob_2, _) = addHtlc(100_000_000.msat, payer = alice1, payee = bob1)
        val (alice2, bob2) = nodes1
        val (nodes2, _, _) = addHtlc(10_000.msat, payer = alice2, payee = bob2)
        val (alice3, bob3) = nodes2
        val (nodes3, preimage_bob2alice_1, _) = addHtlc(50_000_000.msat, payer = bob3, payee = alice3)
        val (bob4, alice4) = nodes3
        val (nodes4, _, _) = addHtlc(55_000_000.msat, payer = bob4, payee = alice4)
        val (bob5, alice5) = nodes4

        val (alice6, bob6) = crossSign(alice5, bob5)
        val (alice7, bob7) = fulfillHtlc(1, preimage_alice2bob_2, payer = alice6, payee = bob6)
        val (bob8, alice8) = fulfillHtlc(0, preimage_bob2alice_1, payer = bob7, payee = alice7)

        // alice sign but we intercept bob's revocation
        val (alice9, aActions8) = alice8.process(ExecuteCommand(CMD_SIGN))
        val commitSig0 = aActions8.findOutgoingMessage<CommitSig>()
        val (bob9, _) = bob8.process(MessageReceived(commitSig0))

        // as far as alice knows, bob currently has two valid unrevoked commitment transactions

        // at this point here is the situation from bob's pov with the latest sig received from alice,
        // and what alice should do when bob publishes his commit tx:
        // balances :
        //    alice's balance : 499 999 990                             => nothing to do
        //    bob's balance   :  95 000 000                             => nothing to do
        // htlcs :
        //    alice -> bob    : 250 000 000 (bob does not have the preimage)   => wait for the timeout and spend
        //    alice -> bob    : 100 000 000 (bob has the preimage)             => if bob does not use the preimage, wait for the timeout and spend
        //    alice -> bob    :          10 (dust)                             => won't appear in the commitment tx
        //    bob -> alice    :  55 000 000 (alice does not have the preimage) => nothing to do, bob will get his money back after the timeout

        alice9 as HasCommitments; bob9 as HasCommitments
        // bob publishes his current commit tx
        val bobCommitTx = bob9.commitments.localCommit.publishableTxs.commitTx.tx
        assertEquals(5, bobCommitTx.txOut.size) // 2 main outputs and 3 pending htlcs

        val (aliceClosing, actions) = alice9.process(WatchReceived(WatchEventSpent(alice9.channelId,
            BITCOIN_FUNDING_SPENT,
            bobCommitTx)))

        assertTrue(aliceClosing is Closing)
        assertTrue(actions.isNotEmpty())

        // in response to that, alice publishes its claim txes
        val claimTxes = actions.filterIsInstance<PublishTx>().map { it.tx }
        assertEquals(3, claimTxes.size)
        val claimMain = claimTxes.first()
        // in addition to its main output, alice can only claim 2 out of 3 htlcs,
        // she can't do anything regarding the htlc sent by bob for which she does not have the preimage
        val amountClaimed = claimTxes.map { claimHtlcTx ->
            assertEquals(1, claimHtlcTx.txIn.size)
            assertEquals(1, claimHtlcTx.txOut.size)
            Transaction.correctlySpends(claimHtlcTx, listOf(bobCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            claimHtlcTx.txOut[0].amount
        }.sum()
        // at best we have a little less than 500 000 + 250 000 + 100 000 = 850 000 (because fees)
        assertEquals(822330.sat, amountClaimed) // TODO formerly 822310.sat ?

        assertEquals(BITCOIN_TX_CONFIRMED(bobCommitTx), actions.watches<WatchConfirmed>()[0].event)
        assertEquals(BITCOIN_TX_CONFIRMED(claimMain), actions.watches<WatchConfirmed>()[1].event)
        assertEquals(2, actions.watches<WatchSpent>().count { it.event is BITCOIN_OUTPUT_SPENT })

        assertEquals(0, aliceClosing.remoteCommitPublished?.claimHtlcSuccessTxs?.size)
        assertEquals(2, aliceClosing.remoteCommitPublished?.claimHtlcTimeoutTxs?.size)

        // assert the feerate of the claim main is what we expect
        aliceClosing.staticParams.nodeParams.onChainFeeConf.run {
            val feeTargets = aliceClosing.staticParams.nodeParams.onChainFeeConf.feeTargets
            val expectedFeeRate = feeEstimator.getFeeratePerKw(feeTargets.claimMainBlockTarget)
            val expectedFee = Transactions.weight2fee(expectedFeeRate, Transactions.claimP2WPKHOutputWeight)
            val claimFee = claimMain.txIn.map {
                bobCommitTx.txOut[it.outPoint.index.toInt()].amount
            }.sum() - claimMain.txOut.map { it.amount }.sum()
            assertEquals(expectedFee, claimFee)
        }
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT (revoked commit)`() {
        var (alice, bob) = reachNormal()
        // initially we have :
        // alice = 800 000
        //   bob = 200 000
        fun send(): Transaction {
            // alice sends 8 000 sat
            addHtlc(10_000_000.msat, payer = alice, payee = bob)
                .first.run { alice = first as Normal; bob = second as Normal }
            crossSign(alice, bob)
                .run { alice = first as Normal; bob = second as Normal }

            return bob.commitments.localCommit.publishableTxs.commitTx.tx
        }

        val txes = (0 until 10).map { send() }
        // bob now has 10 spendable tx, 9 of them being revoked

        // let's say that bob published this tx
        val revokedTx = txes[3]
        // channel state for this revoked tx is as follows:
        // alice = 760 000
        //   bob = 200 000
        //  a->b =  10 000
        //  a->b =  10 000
        //  a->b =  10 000
        //  a->b =  10 000
        // 2 main outputs + 4 htlc
        assertEquals(6, revokedTx.txOut.size)

        val (aliceClosing, actions) = alice.process(WatchReceived(WatchEventSpent(alice.channelId,
            BITCOIN_FUNDING_SPENT,
            revokedTx)))

        assertTrue(aliceClosing is Closing)
        assertEquals(1, aliceClosing.revokedCommitPublished.size)
        assertTrue(actions.isNotEmpty())
        actions.hasMessage<Error>()

        val claimTxes = actions.filterIsInstance<PublishTx>().map { it.tx }
        val mainTx = claimTxes[0]
        val mainPenaltyTx = claimTxes[1]
        // TODO business code is disabled for now
        //      val htlcPenaltyTxs = claimTxes.drop(2)
        //      assertEquals(2, htlcPenaltyTxs.size)
        //      // let's make sure that htlc-penalty txs each spend a different output
        //      assertEquals(htlcPenaltyTxs.map { it.txIn.first().outPoint.index }.toSet().size, htlcPenaltyTxs.size)

        assertEquals(BITCOIN_TX_CONFIRMED(revokedTx), actions.watches<WatchConfirmed>()[0].event)
        assertEquals(BITCOIN_TX_CONFIRMED(mainTx), actions.watches<WatchConfirmed>()[1].event)
        assertTrue(actions.watches<WatchSpent>().all { it.event is BITCOIN_OUTPUT_SPENT })
        // TODO business code is disabled for now
        //        assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT) // main-penalty
        //        htlcPenaltyTxs.foreach(htlcPenaltyTx => assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT))

        Transaction.correctlySpends(mainTx, listOf(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        Transaction.correctlySpends(mainPenaltyTx, listOf(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        // TODO business code is disabled for now
//            htlcPenaltyTxs.forEach {
//                Transaction.correctlySpends(it, listOf(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
//            }

        // two main outputs are 760 000 and 200 000
        assertEquals(741500.sat, mainTx.txOut[0].amount)
        assertEquals(195160.sat, mainPenaltyTx.txOut[0].amount)
        // TODO business code is disabled for now
        //        assertEquals(4540.sat, htlcPenaltyTxs[0].txOut[0].amount)
        //        assertEquals(4540.sat, htlcPenaltyTxs[1].txOut[0].amount)
        //        assertEquals(4540.sat, htlcPenaltyTxs[2].txOut[0].amount)
        //        assertEquals(4540.sat, htlcPenaltyTxs[3].txOut[0].amount)
    }

    @Test
    fun `recv BITCOIN_FUNDING_SPENT (revoked commit with identical htlcs)`() {
        val (alice0, bob0) = reachNormal()
        // initially we have :
        // alice = 800 000
        //   bob = 200 000
        val (_, cmdAddHtlc) = TestsHelper.makeCmdAdd(
            10_000_000.msat,
            bob0.staticParams.nodeParams.nodeId,
            alice0.currentBlockHeight.toLong()
        )

        val (alice1, bob1) = addHtlc(cmdAdd = cmdAddHtlc, payer = alice0, payee = bob0)
        val (alice2, bob2) = addHtlc(cmdAdd = cmdAddHtlc, payer = alice1, payee = bob1)

        val (alice3, bob3) = crossSign(alice2, bob2)

        // bob will publish this tx after it is revoked
        val revokedTx = (bob3 as HasCommitments).commitments.localCommit.publishableTxs.commitTx.tx

        val (alice4, bob4) = addHtlc(amount = 10000000.msat, payer = alice3, payee = bob3).first
        val (alice5, bob5) = crossSign(alice4, bob4)

        // channel state for this revoked tx is as follows:
        // alice = 780 000
        //   bob = 200 000
        //  a->b =  10 000
        //  a->b =  10 000
        assertEquals(4, revokedTx.txOut.size)

        val (aliceClosing, actions) = alice5.process(WatchReceived(WatchEventSpent((alice5 as HasCommitments).channelId,
            BITCOIN_FUNDING_SPENT,
            revokedTx)))

        assertTrue(aliceClosing is Closing)
        assertTrue(actions.isNotEmpty())
        actions.hasMessage<Error>()

        val claimTxes = actions.filterIsInstance<PublishTx>().map { it.tx }
        val mainTx = claimTxes[0]
        val mainPenaltyTx = claimTxes[1]
        // TODO business code is disabled for now
        //      val htlcPenaltyTxs = claimTxes.drop(2)
        //      assertEquals(2, htlcPenaltyTxs.size)
        //      // let's make sure that htlc-penalty txs each spend a different output
        //      assertEquals(htlcPenaltyTxs.map { it.txIn.first().outPoint.index }.toSet().size, htlcPenaltyTxs.size)

        assertEquals(BITCOIN_TX_CONFIRMED(revokedTx), actions.watches<WatchConfirmed>()[0].event)
        assertEquals(BITCOIN_TX_CONFIRMED(mainTx), actions.watches<WatchConfirmed>()[1].event)
        assertTrue(actions.watches<WatchSpent>().all { it.event is BITCOIN_OUTPUT_SPENT })
        // TODO business code is disabled for now
        //        assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT) // main-penalty
        //        htlcPenaltyTxs.foreach(htlcPenaltyTx => assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT))

        Transaction.correctlySpends(mainTx, listOf(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        Transaction.correctlySpends(mainPenaltyTx, listOf(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        // TODO business code is disabled for now
//            htlcPenaltyTxs.forEach {
//                Transaction.correctlySpends(it, listOf(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
//            }
    }
}
