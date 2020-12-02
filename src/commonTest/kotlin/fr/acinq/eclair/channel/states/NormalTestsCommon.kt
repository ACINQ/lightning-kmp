package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.*
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.Feature
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.TestConstants.Bob
import fr.acinq.eclair.blockchain.*
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.fee.OnChainFeerates
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.channel.TestsHelper.addHtlc
import fr.acinq.eclair.channel.TestsHelper.crossSign
import fr.acinq.eclair.channel.TestsHelper.fulfillHtlc
import fr.acinq.eclair.channel.TestsHelper.makeCmdAdd
import fr.acinq.eclair.channel.TestsHelper.reachNormal
import fr.acinq.eclair.channel.TestsHelper.signAndRevack
import fr.acinq.eclair.crypto.sphinx.Sphinx
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.transactions.Transactions.weight2fee
import fr.acinq.eclair.transactions.incomings
import fr.acinq.eclair.transactions.outgoings
import fr.acinq.eclair.utils.*
import fr.acinq.eclair.wire.*
import kotlin.test.*

@ExperimentalUnsignedTypes
class NormalTestsCommon : EclairTestSuite() {

    private val currentBlockHeight = 144L
    private val defaultAdd = CMD_ADD_HTLC(
        50_000_000.msat,
        randomBytes32(),
        CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight),
        TestConstants.emptyOnionPacket,
        UUID.randomUUID()
    )

    @Test
    fun `recv CMD_ADD_HTLC (empty origin)`() {
        val (alice0, _) = reachNormal()
        val h = randomBytes32()
        val add = defaultAdd.copy(paymentHash = h)

        val (alice1, actions) = alice0.process(ChannelEvent.ExecuteCommand(add))
        assertTrue((alice1 as Normal).commitments.availableBalanceForSend() < alice0.commitments.availableBalanceForSend())

        val htlc = actions.findOutgoingMessage<UpdateAddHtlc>()
        assertEquals(0, htlc.id)
        assertEquals(h, htlc.paymentHash)

        assertEquals(
            alice0.copy(
                commitments = alice0.commitments.copy(
                    localNextHtlcId = 1,
                    localChanges = alice0.commitments.localChanges.copy(proposed = listOf(htlc)),
                    payments = mapOf(0L to add.paymentId)
                )
            ), alice1
        )
    }

    @Test
    fun `recv CMD_ADD_HTLC (incrementing ids)`() {
        val (alice0, _) = reachNormal()
        val h = randomBytes32()

        var alice = alice0
        for (i in 0 until 10) {
            val add = defaultAdd.copy(paymentHash = h)

            val (tempAlice, actions) = alice.process(ChannelEvent.ExecuteCommand(add))
            alice = tempAlice as Normal

            val htlc = actions.findOutgoingMessage<UpdateAddHtlc>()
            assertEquals(i.toLong(), htlc.id)
            assertEquals(h, htlc.paymentHash)
        }
    }

    @Test
    fun `recv CMD_ADD_HTLC (expiry too small)`() {
        val (alice0, _) = reachNormal()
        // It's usually dangerous for Bob to accept HTLCs that are expiring soon. However it's not Alice's decision to reject
        // them when she's asked to relay; she should forward those HTLCs to Bob, and Bob will choose whether to fail them
        // or fulfill them (Bob could be #reckless and fulfill HTLCs with a very low expiry delta).
        val currentBlockHeight = alice0.currentBlockHeight.toLong()
        val expiryTooSmall = CltvExpiry(currentBlockHeight + 3)

        val add = defaultAdd.copy(cltvExpiry = expiryTooSmall)
        val (_, actions1) = alice0.process(ChannelEvent.ExecuteCommand(add))
        actions1.hasOutgoingMessage<UpdateAddHtlc>()
    }

    @Test
    fun `recv CMD_ADD_HTLC (expiry too big)`() {
        val (alice0, _) = reachNormal()

        val currentBlockHeight = alice0.currentBlockHeight.toLong()
        val expiryTooBig = (Channel.MAX_CLTV_EXPIRY_DELTA + 1).toCltvExpiry(currentBlockHeight)
        val add = defaultAdd.copy(cltvExpiry = expiryTooBig)

        val (alice1, actions1) = alice0.process(ChannelEvent.ExecuteCommand(add))
        val actualError = actions1.findCommandError<ExpiryTooBig>()

        val expectedError = ExpiryTooBig(
            alice0.channelId,
            maximum = Channel.MAX_CLTV_EXPIRY_DELTA.toCltvExpiry(currentBlockHeight),
            actual = expiryTooBig,
            blockCount = currentBlockHeight
        )

        assertEquals(expectedError, actualError)
        assertEquals(alice0, alice1)
    }

    @Test
    fun `recv CMD_ADD_HTLC (value too small)`() {
        val (alice0, _) = reachNormal()

        val add = defaultAdd.copy(amount = 50.msat)
        val (alice1, actions) = alice0.process(ChannelEvent.ExecuteCommand(add))
        val actualError = actions.findCommandError<HtlcValueTooSmall>()

        val expectedError = HtlcValueTooSmall(alice0.channelId, 1000.msat, 50.msat)
        assertEquals(expectedError, actualError)
        assertEquals(alice0, alice1)
    }

    @Test
    fun `recv CMD_ADD_HTLC (0 msat)`() {
        val (alice0, bob0) = reachNormal()
        // Alice has a minimum set to 0 msat (which should be invalid, but may mislead Bob into relaying 0-value HTLCs which is forbidden by the spec).
        assertEquals(0.msat, alice0.commitments.localParams.htlcMinimum)
        val add = defaultAdd.copy(amount = 0.msat)
        val (bob1, actions) = bob0.process(ChannelEvent.ExecuteCommand(add))
        val actualError = actions.findCommandError<HtlcValueTooSmall>()
        val expectedError = HtlcValueTooSmall(bob0.channelId, 1.msat, 0.msat)
        assertEquals(expectedError, actualError)
        assertEquals(bob0, bob1)
    }

    @Ignore
    fun `recv CMD_ADD_HTLC (increasing balance but still below reserve)`() {
        TODO("later")
    }

    @Test
    fun `recv CMD_ADD_HTLC (insufficient funds)`() {
        val (alice0, _) = reachNormal()
        val add = defaultAdd.copy(amount = Int.MAX_VALUE.msat)
        val (alice1, actions) = alice0.process(ChannelEvent.ExecuteCommand(add))
        val actualError = actions.findCommandError<InsufficientFunds>()
        val expectError = InsufficientFunds(
            alice0.channelId,
            amount = Int.MAX_VALUE.msat,
            missing = 1_382_823.sat,
            reserve = 20_000.sat,
            fees = 7_140.sat
        )
        assertEquals(expectError, actualError)
        assertEquals(alice0, alice1)
    }

    @Test
    fun `recv CMD_ADD_HTLC (insufficient funds, missing 1 msat)`() {
        val (_, bob0) = reachNormal()
        val add = defaultAdd.copy(amount = bob0.commitments.availableBalanceForSend() + 1.sat.toMilliSatoshi())
        val (bob1, actions) = bob0.process(ChannelEvent.ExecuteCommand(add))
        val actualError = actions.findCommandError<InsufficientFunds>()
        val expectedError = InsufficientFunds(bob0.channelId, amount = add.amount, missing = 1.sat, reserve = 10000.sat, fees = 0.sat)
        assertEquals(expectedError, actualError)
        assertEquals(bob0, bob1)
    }

    @Test
    fun `recv CMD_ADD_HTLC (HTLC dips into remote funder fee reserve)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, bob1) = addHtlc(764_660_000.msat, alice0, bob0).first
        val (alice2, bob2) = crossSign(alice1, bob1)
        assertEquals(0.msat, (alice2 as ChannelStateWithCommitments).commitments.availableBalanceForSend())

        tailrec fun loop(bob: ChannelState, count: Int): ChannelState = if (count == 0) bob else {
            val (newbob, actions1) = bob.process(ChannelEvent.ExecuteCommand(defaultAdd.copy(amount = 16_000_000.msat)))
            actions1.hasOutgoingMessage<UpdateAddHtlc>()
            loop(newbob, count - 1)
        }

        // actual test begins
        // at this point alice has the minimal amount to sustain a channel
        // alice maintains an extra reserve to accommodate for a few more HTLCs, so the first two HTLCs should be allowed
        val bob3 = loop(bob2, 9)
        // 2640 000 2640 000 msat
        // but this one will dip alice below her reserve: we must wait for the previous HTLCs to settle before sending any more
        val (_, actionsBob4) = bob3.process(ChannelEvent.ExecuteCommand(defaultAdd.copy(amount = 12_500_000.msat)))
        actionsBob4.findCommandError<RemoteCannotAffordFeesForNewHtlc>()
    }

    @Test
    fun `recv CMD_ADD_HTLC (insufficient funds with pending htlcs and 0 balance)`() {
        val (alice0, _) = reachNormal()
        val (alice1, actionsAlice1) = alice0.process(ChannelEvent.ExecuteCommand(defaultAdd.copy(amount = 500_000_000.msat)))
        actionsAlice1.hasOutgoingMessage<UpdateAddHtlc>()
        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.ExecuteCommand(defaultAdd.copy(amount = 200_000_000.msat)))
        actionsAlice2.hasOutgoingMessage<UpdateAddHtlc>()
        val (alice3, actionsAlice3) = alice2.process(ChannelEvent.ExecuteCommand(defaultAdd.copy(amount = 61_120_000.msat)))
        actionsAlice3.hasOutgoingMessage<UpdateAddHtlc>()
        val (_, actionsAlice4) = alice3.process(ChannelEvent.ExecuteCommand(defaultAdd.copy(amount = 1_000_000.msat)))
        val actualError = actionsAlice4.findCommandError<InsufficientFunds>()
        val expectedError = InsufficientFunds(alice0.channelId, amount = 1_000_000.msat, missing = 900.sat, reserve = 20_000.sat, fees = 8_860.sat)
        assertEquals(expectedError, actualError)
    }

    @Test
    fun `recv CMD_ADD_HTLC (insufficient funds with pending htlcs 2-2)`() {
        val (alice0, _) = reachNormal()
        val (alice1, actionsAlice1) = alice0.process(ChannelEvent.ExecuteCommand(defaultAdd.copy(amount = 300_000_000.msat)))
        actionsAlice1.hasOutgoingMessage<UpdateAddHtlc>()
        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.ExecuteCommand(defaultAdd.copy(amount = 300_000_000.msat)))
        actionsAlice2.hasOutgoingMessage<UpdateAddHtlc>()
        val (_, actionsAlice3) = alice2.process(ChannelEvent.ExecuteCommand(defaultAdd.copy(amount = 500_000_000.msat)))
        val actualError = actionsAlice3.findCommandError<InsufficientFunds>()
        val expectError = InsufficientFunds(alice0.channelId, amount = 500_000_000.msat, missing = 338_780.sat, reserve = 20_000.sat, fees = 8_860.sat)
        assertEquals(expectError, actualError)
    }

    @Test
    fun `recv CMD_ADD_HTLC (over max inflight htlc value)`() {
        val (_, bob0) = reachNormal()
        val (_, actions) = bob0.process(ChannelEvent.ExecuteCommand(defaultAdd.copy(amount = 151_000_000.msat)))
        val actualError = actions.findCommandError<HtlcValueTooHighInFlight>()
        val expectedError = HtlcValueTooHighInFlight(bob0.channelId, maximum = 150_000_000UL, actual = 151_000_000.msat)
        assertEquals(expectedError, actualError)
    }

    @Test
    fun `recv CMD_ADD_HTLC (over max inflight htlc value with duplicate amounts)`() {
        val (_, bob0) = reachNormal()
        val (bob1, actionsBob1) = bob0.process(ChannelEvent.ExecuteCommand(defaultAdd.copy(amount = 75_500_000.msat)))
        actionsBob1.hasOutgoingMessage<UpdateAddHtlc>()
        val (_, actionsBob2) = bob1.process(ChannelEvent.ExecuteCommand(defaultAdd.copy(amount = 75_500_000.msat)))
        val actualError = actionsBob2.findCommandError<HtlcValueTooHighInFlight>()
        val expectedError = HtlcValueTooHighInFlight(bob0.channelId, maximum = 150_000_000UL, actual = 151_000_000.msat)
        assertEquals(expectedError, actualError)
    }

    @Test
    fun `recv CMD_ADD_HTLC (over max accepted htlcs)`() {
        val (alice0, _) = reachNormal()

        // Bob accepts a maximum of 100 htlcs
        val alice1 = kotlin.run {
            var alice = alice0
            for (i in 0 until 100) {
                val (tempAlice, actions) = alice.process(ChannelEvent.ExecuteCommand(defaultAdd.copy(amount = 1_000_000.msat)))
                actions.hasOutgoingMessage<UpdateAddHtlc>()
                alice = tempAlice as Normal
            }
            alice
        }

        val (_, actions) = alice1.process(ChannelEvent.ExecuteCommand(defaultAdd.copy(amount = 1_000_000.msat)))
        val actualError = actions.findCommandError<TooManyAcceptedHtlcs>()
        val expectedError = TooManyAcceptedHtlcs(alice0.channelId, maximum = 100)
        assertEquals(expectedError, actualError)
    }

    @Test
    fun `recv CMD_ADD_HTLC (over capacity)`() {
        val (alice0, _) = reachNormal()
        val (alice1, actionsAlice1) = alice0.process(ChannelEvent.ExecuteCommand(defaultAdd.copy(amount = TestConstants.fundingSatoshis.toMilliSatoshi() * 2 / 3)))
        actionsAlice1.hasOutgoingMessage<UpdateAddHtlc>()
        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        actionsAlice2.hasOutgoingMessage<CommitSig>()
        // this is over channel-capacity
        val failAdd = defaultAdd.copy(amount = TestConstants.fundingSatoshis.toMilliSatoshi() * 2 / 3)
        val (_, actionsAlice3) = alice2.process(ChannelEvent.ExecuteCommand(failAdd))
        val actualError = actionsAlice3.findCommandError<InsufficientFunds>()
        val expectedError = InsufficientFunds(alice0.channelId, failAdd.amount, 570_393.sat, 20_000.sat, 8_000.sat)
        assertEquals(expectedError, actualError)
    }

    @Test
    fun `recv CMD_ADD_HTLC (after having sent Shutdown)`() {
        val (alice0, _) = reachNormal()
        val (alice1, actionsAlice1) = alice0.process(ChannelEvent.ExecuteCommand(CMD_CLOSE(null)))
        actionsAlice1.findOutgoingMessage<Shutdown>()
        assertTrue { alice1 is Normal && alice1.localShutdown != null && alice1.remoteShutdown == null }

        val (_, cmdAdd) = makeCmdAdd(500000000.msat, alice0.staticParams.nodeParams.nodeId, currentBlockHeight, randomBytes32())
        val (_, actionsAlice2) = alice1.process(ChannelEvent.ExecuteCommand(cmdAdd))
        assertNotNull(actionsAlice2.findCommandError<NoMoreHtlcsClosingInProgress>())
    }

    @Test
    fun `recv CMD_ADD_HTLC (after having received Shutdown)`() {
        val (alice0, bob0) = reachNormal()

        // let's make alice send an htlc
        val (_, cmdAdd1) = makeCmdAdd(500000000.msat, alice0.staticParams.nodeParams.nodeId, currentBlockHeight, randomBytes32())
        val (alice1, actionsAlice1) = alice0.process(ChannelEvent.ExecuteCommand(cmdAdd1))
        actionsAlice1.findOutgoingMessage<UpdateAddHtlc>()

        // at the same time bob initiates a closing
        val (_, actionsBob1) = bob0.process(ChannelEvent.ExecuteCommand(CMD_CLOSE(null)))
        val shutdown = actionsBob1.findOutgoingMessage<Shutdown>()

        val (alice2, _) = alice1.process(ChannelEvent.MessageReceived(shutdown))
        val (_, cmdAdd2) = makeCmdAdd(100000000.msat, alice0.staticParams.nodeParams.nodeId, currentBlockHeight, randomBytes32())
        val (_, actionsAlice3) = alice2.process(ChannelEvent.ExecuteCommand(cmdAdd2))
        assertNotNull(actionsAlice3.findCommandError<NoMoreHtlcsClosingInProgress>())
    }

    @Test
    fun `recv CMD_SIGN`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, _) = addHtlc(50_000_000.msat, payer = alice0, payee = bob0).first
        val (alice2, actions) = alice1.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig = actions.findOutgoingMessage<CommitSig>()
        assertEquals(1, commitSig.htlcSignatures.size)
        assertTrue { (alice2 as ChannelStateWithCommitments).commitments.remoteNextCommitInfo.isLeft }
    }

    @Test
    fun `recv CMD_SIGN (two identical htlcs in each direction)`() {
        val (alice0, bob0) = reachNormal()
        val currentBlockHeight = alice0.currentBlockHeight.toLong()
        val add = CMD_ADD_HTLC(
            10_000_000.msat,
            randomBytes32(),
            CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight),
            TestConstants.emptyOnionPacket,
            UUID.randomUUID()
        )

        val (alice1, actionsAlice1) = alice0.process(ChannelEvent.ExecuteCommand(add))
        val htlc1 = actionsAlice1.findOutgoingMessage<UpdateAddHtlc>()
        val (bob1, _) = bob0.process(ChannelEvent.MessageReceived(htlc1))
        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.ExecuteCommand(add))
        val htlc2 = actionsAlice2.findOutgoingMessage<UpdateAddHtlc>()
        val (bob2, _) = bob1.process(ChannelEvent.MessageReceived(htlc2))

        val (alice3, bob3) = crossSign(alice2, bob2)

        val (bob4, actionsBob4) = bob3.process(ChannelEvent.ExecuteCommand(add))
        val htlc3 = actionsBob4.findOutgoingMessage<UpdateAddHtlc>()
        val (alice4, _) = alice3.process(ChannelEvent.MessageReceived(htlc3))
        val (bob5, actionsBob5) = bob4.process(ChannelEvent.ExecuteCommand(add))
        val htlc4 = actionsBob5.findOutgoingMessage<UpdateAddHtlc>()
        alice4.process(ChannelEvent.MessageReceived(htlc4))

        val (_, actionsBob6) = bob5.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig = actionsBob6.findOutgoingMessage<CommitSig>()
        assertEquals(4, commitSig.htlcSignatures.size)
    }

    @Test
    fun `recv CMD_SIGN (check htlc info are persisted)`() {
        val (alice0, bob0) = reachNormal()
        // for the test to be really useful we have constraint on parameters
        assertTrue { alice0.staticParams.nodeParams.dustLimit > bob0.staticParams.nodeParams.dustLimit }
        // we're gonna exchange two htlcs in each direction, the goal is to have bob's commitment have 4 htlcs, and alice's
        // commitment only have 3. We will then check that alice indeed persisted 4 htlcs, and bob only 3.
        val aliceMinReceive = Alice.nodeParams.dustLimit + weight2fee(FeeratePerKw.CommitmentFeerate, Commitments.HTLC_SUCCESS_WEIGHT)
        val aliceMinOffer = Alice.nodeParams.dustLimit + weight2fee(FeeratePerKw.CommitmentFeerate, Commitments.HTLC_TIMEOUT_WEIGHT)
        val bobMinReceive = Bob.nodeParams.dustLimit + weight2fee(FeeratePerKw.CommitmentFeerate, Commitments.HTLC_SUCCESS_WEIGHT)
        val bobMinOffer = Bob.nodeParams.dustLimit + weight2fee(FeeratePerKw.CommitmentFeerate, Commitments.HTLC_TIMEOUT_WEIGHT)
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
        val (bob3, alice3) = addHtlc(b2a_1.toMilliSatoshi(), bob2, alice2).first
        val (bob4, alice4) = addHtlc(b2a_2.toMilliSatoshi(), bob3, alice3).first

        val (alice5, aActions5) = alice4.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig0 = aActions5.findOutgoingMessage<CommitSig>()

        val (bob5, bActions5) = bob4.process(ChannelEvent.MessageReceived(commitSig0))
        val revokeAndAck0 = bActions5.findOutgoingMessage<RevokeAndAck>()
        val commandSign0 = bActions5.findCommand<CMD_SIGN>()

        val (alice6, _) = alice5.process(ChannelEvent.MessageReceived(revokeAndAck0))
        val (bob6, bActions6) = bob5.process(ChannelEvent.ExecuteCommand(commandSign0))
        val commitSig1 = bActions6.findOutgoingMessage<CommitSig>()

        val (alice7, aActions7) = alice6.process(ChannelEvent.MessageReceived(commitSig1))
        val revokeAndAck1 = aActions7.findOutgoingMessage<RevokeAndAck>()
        val (bob7, _) = bob6.process(ChannelEvent.MessageReceived(revokeAndAck1))

        val commandSign1 = aActions7.findCommand<CMD_SIGN>()
        val (alice8, aActions8) = alice7.process(ChannelEvent.ExecuteCommand(commandSign1))
        val commitSig2 = aActions8.findOutgoingMessage<CommitSig>()

        val (_, bActions8) = bob7.process(ChannelEvent.MessageReceived(commitSig2))
        val revokeAndAck2 = bActions8.findOutgoingMessage<RevokeAndAck>()
        val (_, _) = alice8.process(ChannelEvent.MessageReceived(revokeAndAck2))

        val aliceHtlcInfos = buildList {
            addAll(aActions5.filterIsInstance<ChannelAction.Storage.StoreHtlcInfos>())
            addAll(aActions8.filterIsInstance<ChannelAction.Storage.StoreHtlcInfos>())
        }.map { it.htlcs }.flatten()

        val bobHtlcInfos = bActions6.filterIsInstance<ChannelAction.Storage.StoreHtlcInfos>().map { it.htlcs }.flatten()

        assertEquals(0, aliceHtlcInfos.count { it.channelId == alice0.channelId && it.commitmentNumber == 0L })
        assertEquals(2, aliceHtlcInfos.count { it.channelId == alice0.channelId && it.commitmentNumber == 1L })
        assertEquals(4, aliceHtlcInfos.count { it.channelId == alice0.channelId && it.commitmentNumber == 2L })
        assertEquals(0, bobHtlcInfos.count { it.channelId == bob0.channelId && it.commitmentNumber == 0L })
        assertEquals(3, bobHtlcInfos.count { it.channelId == bob0.channelId && it.commitmentNumber == 1L })
    }

    @Test
    fun `recv CMD_SIGN (htlcs with same pubkeyScript but different amounts)`() {
        val (alice0, bob0) = reachNormal()
        val add = CMD_ADD_HTLC(
            10_000_000.msat,
            randomBytes32(),
            CltvExpiryDelta(144).toCltvExpiry(alice0.currentBlockHeight.toLong()),
            TestConstants.emptyOnionPacket,
            UUID.randomUUID()
        )
        val epsilons = listOf(3, 1, 5, 7, 6) // unordered on purpose
        val htlcCount = epsilons.size

        val (alice1, bob1) = kotlin.run {
            var (alice1, bob1) = alice0 to bob0
            for (i in epsilons) {
                val (stateA, actionsA) = alice1.process(ChannelEvent.ExecuteCommand(add.copy(amount = add.amount + (i * 1000).msat)))
                alice1 = stateA as Normal
                val updateAddHtlc = actionsA.findOutgoingMessage<UpdateAddHtlc>()
                val (stateB, _) = bob1.process(ChannelEvent.MessageReceived(updateAddHtlc))
                bob1 = stateB as Normal
            }
            alice1 to bob1
        }

        val (_, actionsAlice2) = alice1.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig = actionsAlice2.findOutgoingMessage<CommitSig>()
        assertEquals(htlcCount, commitSig.htlcSignatures.size)
        val (bob2, _) = bob1.process(ChannelEvent.MessageReceived(commitSig))
        bob2 as ChannelStateWithCommitments
        val htlcTxs = bob2.commitments.localCommit.publishableTxs.htlcTxsAndSigs
        assertEquals(htlcCount, htlcTxs.size)
        val amounts = htlcTxs.map { it.txinfo.tx.txOut.first().amount.sat }
        assertEquals(amounts, amounts.sorted())
    }

    @Test
    fun `recv CMD_SIGN (no changes)`() {
        val (alice0, _) = reachNormal()
        val (_, actions) = alice0.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        assertTrue(actions.isEmpty())
    }

    @Ignore
    fun `recv CMD_SIGN (while waiting for RevokeAndAck (no pending changes)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, _) = addHtlc(50_000_000.msat, alice0, bob0).first
        assertTrue((alice1 as ChannelStateWithCommitments).commitments.remoteNextCommitInfo.isRight)
        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        actionsAlice2.hasOutgoingMessage<CommitSig>()

        (alice2 as ChannelStateWithCommitments)
        assertNotNull(alice2.commitments.remoteNextCommitInfo.left)
        val waitForRevocation = alice2.commitments.remoteNextCommitInfo.left!!
        assertEquals(false, waitForRevocation.reSignAsap) // TODO

        val (alice3, _) = alice2.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        assertEquals<Either<WaitingForRevocation, PublicKey>>(Either.Left(waitForRevocation), (alice3 as ChannelStateWithCommitments).commitments.remoteNextCommitInfo)
    }

    @Ignore
    fun `recv CMD_SIGN (while waiting for RevokeAndAck (with pending changes)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, bob1) = addHtlc(50_000_000.msat, alice0, bob0).first
        assertTrue((alice1 as ChannelStateWithCommitments).commitments.remoteNextCommitInfo.isRight)
        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        actionsAlice2.hasOutgoingMessage<CommitSig>()

        (alice2 as ChannelStateWithCommitments)
        assertNotNull(alice2.commitments.remoteNextCommitInfo.left)
        val waitForRevocation = alice2.commitments.remoteNextCommitInfo.left
        assertNotNull(waitForRevocation)
        assertFalse(waitForRevocation.reSignAsap) // TODO

        val (alice3, _) = addHtlc(50_000_000.msat, alice2, bob1).first
        val (alice4, _) = alice3.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        (alice4 as ChannelStateWithCommitments)
        assertEquals(Either.Left(waitForRevocation.copy(reSignAsap = true)), alice4.commitments.remoteNextCommitInfo)
    }

    @Ignore
    fun `recv CMD_SIGN (going above reserve)`() {
        TODO("later")
    }

    @Test
    fun `recv CMD_SIGN (after CMD_UPDATE_FEE)`() {
        val (alice, _) = reachNormal()
        val (alice1, actions1) = alice.process(ChannelEvent.ExecuteCommand(CMD_UPDATE_FEE(FeeratePerKw.CommitmentFeerate + FeeratePerKw(1_000.sat))))
        actions1.hasOutgoingMessage<UpdateFee>()
        val (_, actions2) = alice1.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        actions2.hasOutgoingMessage<CommitSig>()
    }

    @Test
    fun `recv CMD_SIGN (channel backup, zero-reserve channel, fundee)`() {
        val currentBlockHeight = 500L
        val (alice, bob) = reachNormal(ChannelVersion.STANDARD or ChannelVersion.ZERO_RESERVE)
        val (_, cmdAdd) = makeCmdAdd(
            50000000.msat,
            alice.staticParams.nodeParams.nodeId,
            currentBlockHeight
        )
        val (bob1, actions) = bob.process(ChannelEvent.ExecuteCommand(cmdAdd))
        val add = actions.findOutgoingMessage<UpdateAddHtlc>()
        val (alice1, _) = alice.process(ChannelEvent.MessageReceived(add))
        assertTrue { (alice1 as Normal).commitments.remoteChanges.proposed.contains(add) }
        val (bob2, actions2) = bob1.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
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
        val (bob3, _) = bob2.process(ChannelEvent.ExecuteCommand(CMD_SIGN))

        bob3 as ChannelStateWithCommitments; initialState as ChannelStateWithCommitments
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
        val (bob3, actionsBob3) = bob2.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig = actionsBob3.findOutgoingMessage<CommitSig>()
        val (alice3, _) = alice2.process(ChannelEvent.MessageReceived(commitSig))

        alice3 as ChannelStateWithCommitments; bob3 as ChannelStateWithCommitments; initialState as ChannelStateWithCommitments
        assertTrue(alice3.commitments.localCommit.spec.htlcs.outgoings().any { it.id == htlc.id })
        assertEquals(1, alice3.commitments.localCommit.publishableTxs.htlcTxsAndSigs.size)
        assertEquals(1, alice3.commitments.localCommit.publishableTxs.htlcTxsAndSigs.size)
        assertEquals(initialState.commitments.localCommit.spec.toLocal, bob3.commitments.localCommit.spec.toLocal)
    }

    @Test
    fun `recv CommitSig (multiple htlcs in both directions)`() {
        val (alice0, bob0) = reachNormal()
        val (nodes1, _, _) = addHtlc(50_000_000.msat, alice0, bob0) // a->b (regular)
        val (alice1, bob1) = nodes1
        val (nodes2, _, _) = addHtlc(8_000_000.msat, alice1, bob1) //  a->b (regular)
        val (alice2, bob2) = nodes2
        val (nodes3, _, _) = addHtlc(300_000.msat, bob2, alice2) //   b->a (dust)
        val (bob3, alice3) = nodes3
        val (nodes4, _, _) = addHtlc(1_000_000.msat, alice3, bob3) //  a->b (regular)
        val (alice4, bob4) = nodes4
        val (nodes5, _, _) = addHtlc(50_000_000.msat, bob4, alice4) // b->a (regular)
        val (bob5, alice5) = nodes5
        val (nodes6, _, _) = addHtlc(500_000.msat, alice5, bob5) //   a->b (dust)
        val (alice6, bob6) = nodes6
        val (nodes7, _, _) = addHtlc(4_000_000.msat, bob6, alice6) //  b->a (regular)
        val (bob7, alice7) = nodes7

        val (alice8, bob8) = signAndRevack(alice7, bob7)
        val (_, actionsBob9) = bob8.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig = actionsBob9.findOutgoingMessage<CommitSig>()
        val (alice9, _) = alice8.process(ChannelEvent.MessageReceived(commitSig))

        alice9 as ChannelStateWithCommitments
        assertEquals(1, alice9.commitments.localCommit.index)
        assertEquals(3, alice9.commitments.localCommit.publishableTxs.htlcTxsAndSigs.size)
    }

    @Test
    fun `recv CommitSig (only fee update)`() {
        val (alice0, bob0) = reachNormal()
        println(bob0)
        val (alice1, actions1) = alice0.process(ChannelEvent.ExecuteCommand(CMD_UPDATE_FEE(FeeratePerKw.CommitmentFeerate + FeeratePerKw(1_000.sat), false)))
        val updateFee = actions1.findOutgoingMessage<UpdateFee>()
        assertEquals(FeeratePerKw.CommitmentFeerate + FeeratePerKw(1_000.sat), updateFee.feeratePerKw)
        val (bob1, _) = bob0.process(ChannelEvent.MessageReceived(updateFee))
        val (alice2, actions2) = alice1.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig = actions2.findOutgoingMessage<CommitSig>()
        val (_, actions3) = bob1.process(ChannelEvent.MessageReceived(commitSig))
        val revokeAndAck = actions3.findOutgoingMessage<RevokeAndAck>()
        val (alice3, _) = alice2.process(ChannelEvent.MessageReceived(revokeAndAck))
        assertTrue { alice3 is Normal }
    }

    @Test
    fun `recv CommitSig (two htlcs received with same r)`() {
        val (alice0, bob0) = reachNormal()

        val r = randomBytes32()
        val h = Crypto.sha256(r).toByteVector32()
        val currentBlockHeight = alice0.currentBlockHeight.toLong()

        val (alice1, actionsAlice1) = alice0.process(
            ChannelEvent.ExecuteCommand(
                CMD_ADD_HTLC(
                    50_000_000.msat,
                    h,
                    CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight),
                    TestConstants.emptyOnionPacket,
                    UUID.randomUUID()
                )
            )
        )
        val htlc1 = actionsAlice1.findOutgoingMessage<UpdateAddHtlc>()
        val (bob1, _) = bob0.process(ChannelEvent.MessageReceived(htlc1))

        val (alice2, actionsAlice2) = alice1.process(
            ChannelEvent.ExecuteCommand(
                CMD_ADD_HTLC(
                    50_000_000.msat,
                    h,
                    CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight),
                    TestConstants.emptyOnionPacket,
                    UUID.randomUUID()
                )
            )
        )
        val htlc2 = actionsAlice2.findOutgoingMessage<UpdateAddHtlc>()
        val (bob2, _) = bob1.process(ChannelEvent.MessageReceived(htlc2))
        assertEquals(listOf(htlc1, htlc2), (bob2 as ChannelStateWithCommitments).commitments.remoteChanges.proposed)

        val (_, bob3) = crossSign(alice2, bob2)
        bob3 as ChannelStateWithCommitments
        assertTrue(bob3.commitments.localCommit.spec.htlcs.incomings().any { it.id == htlc1.id })
        assertEquals(2, bob3.commitments.localCommit.publishableTxs.htlcTxsAndSigs.size)
        assertEquals(bob2.commitments.localCommit.spec.toLocal, bob3.commitments.localCommit.spec.toLocal)
        assertEquals(2, bob3.commitments.localCommit.publishableTxs.commitTx.tx.txOut.count { it.amount == 50_000.sat })
    }

    @Test
    fun `recv CommitSig (no changes)`() {
        val (_, bob0) = reachNormal()
        val tx = bob0.commitments.localCommit.publishableTxs.commitTx.tx

        // signature is invalid but it doesn't matter
        val sig = CommitSig(ByteVector32.Zeroes, ByteVector64.Zeroes, emptyList())
        val (bob1, actionsBob1) = bob0.process(ChannelEvent.MessageReceived(sig))

        assertTrue(bob1 is Closing)
        actionsBob1.hasOutgoingMessage<Error>()

        assertEquals(2, actionsBob1.filterIsInstance<ChannelAction.Blockchain.PublishTx>().count())
        assertEquals(tx, actionsBob1.filterIsInstance<ChannelAction.Blockchain.PublishTx>().first().tx)
        assertEquals(tx.txid, actionsBob1.filterIsInstance<ChannelAction.Blockchain.PublishTx>().last().tx.txIn.first().outPoint.txid)

        assertEquals(2, actionsBob1.findWatches<WatchConfirmed>().count())
        actionsBob1.findWatches<WatchConfirmed>().first().run {
            val e = event as? BITCOIN_TX_CONFIRMED
            assertNotNull(e)
            assertEquals(tx, e.tx)
        }
        actionsBob1.findWatches<WatchConfirmed>().last().run {
            val e = event as? BITCOIN_TX_CONFIRMED
            assertNotNull(e)
            assertEquals(tx.txid, actionsBob1.filterIsInstance<ChannelAction.Blockchain.PublishTx>().last().tx.txIn.first().outPoint.txid)
        }
    }

    @Test
    fun `recv CommitSig (invalid signature)`() {
        val (_, bob0) = reachNormal()
        val tx = bob0.commitments.localCommit.publishableTxs.commitTx.tx

        // signature is invalid but it doesn't matter
        val sig = CommitSig(ByteVector32.Zeroes, ByteVector64.Zeroes, emptyList())
        val (bob1, actionsBob1) = bob0.process(ChannelEvent.MessageReceived(sig))

        assertTrue(bob1 is Closing)
        actionsBob1.hasOutgoingMessage<Error>()

        assertEquals(2, actionsBob1.filterIsInstance<ChannelAction.Blockchain.PublishTx>().count())
        assertEquals(tx, actionsBob1.filterIsInstance<ChannelAction.Blockchain.PublishTx>().first().tx)
        assertEquals(tx.txid, actionsBob1.filterIsInstance<ChannelAction.Blockchain.PublishTx>().last().tx.txIn.first().outPoint.txid)

        assertEquals(2, actionsBob1.findWatches<WatchConfirmed>().count())
        actionsBob1.findWatches<WatchConfirmed>().first().run {
            val e = event as? BITCOIN_TX_CONFIRMED
            assertNotNull(e)
            assertEquals(tx, e.tx)
        }
        actionsBob1.findWatches<WatchConfirmed>().last().run {
            val e = event as? BITCOIN_TX_CONFIRMED
            assertNotNull(e)
            assertEquals(tx.txid, actionsBob1.filterIsInstance<ChannelAction.Blockchain.PublishTx>().last().tx.txIn.first().outPoint.txid)
        }
    }

    @Test
    fun `recv CommitSig (bad htlc sig count)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, bob1) = addHtlc(50_000_000.msat, alice0, bob0).first
        val tx = (bob1 as ChannelStateWithCommitments).commitments.localCommit.publishableTxs.commitTx.tx

        val (_, actionsAlice2) = alice1.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig = actionsAlice2.findOutgoingMessage<CommitSig>()
        val badCommitSig = commitSig.copy(htlcSignatures = commitSig.htlcSignatures + commitSig.htlcSignatures)

        val (bob2, actionsBob2) = bob1.process(ChannelEvent.MessageReceived(badCommitSig))

        assertTrue(bob2 is Closing)
        actionsBob2.hasOutgoingMessage<Error>()

        assertEquals(2, actionsBob2.filterIsInstance<ChannelAction.Blockchain.PublishTx>().count())
        assertEquals(tx, actionsBob2.filterIsInstance<ChannelAction.Blockchain.PublishTx>().first().tx)
        assertEquals(tx.txid, actionsBob2.filterIsInstance<ChannelAction.Blockchain.PublishTx>().last().tx.txIn.first().outPoint.txid)

        assertEquals(2, actionsBob2.findWatches<WatchConfirmed>().count())
        actionsBob2.findWatches<WatchConfirmed>().first().run {
            val e = event as? BITCOIN_TX_CONFIRMED
            assertNotNull(e)
            assertEquals(tx, e.tx)
        }
        actionsBob2.findWatches<WatchConfirmed>().last().run {
            val e = event as? BITCOIN_TX_CONFIRMED
            assertNotNull(e)
            assertEquals(tx.txid, actionsBob2.filterIsInstance<ChannelAction.Blockchain.PublishTx>().last().tx.txIn.first().outPoint.txid)
        }
    }

    @Test
    fun `recv CommitSig (invalid htlc sig)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, bob1) = addHtlc(50_000_000.msat, alice0, bob0).first
        val tx = (bob1 as ChannelStateWithCommitments).commitments.localCommit.publishableTxs.commitTx.tx

        val (_, actionsAlice2) = alice1.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig = actionsAlice2.findOutgoingMessage<CommitSig>()
        val badCommitSig = commitSig.copy(htlcSignatures = listOf(commitSig.signature))
        val (bob2, actionsBob2) = bob1.process(ChannelEvent.MessageReceived(badCommitSig))

        assertTrue(bob2 is Closing)
        actionsBob2.hasOutgoingMessage<Error>()

        assertEquals(2, actionsBob2.filterIsInstance<ChannelAction.Blockchain.PublishTx>().count())
        assertEquals(tx, actionsBob2.filterIsInstance<ChannelAction.Blockchain.PublishTx>().first().tx)
        assertEquals(tx.txid, actionsBob2.filterIsInstance<ChannelAction.Blockchain.PublishTx>().last().tx.txIn.first().outPoint.txid)

        assertEquals(2, actionsBob2.findWatches<WatchConfirmed>().count())
        actionsBob2.findWatches<WatchConfirmed>().first().run {
            val e = event as? BITCOIN_TX_CONFIRMED
            assertNotNull(e)
            assertEquals(tx, e.tx)
        }
        actionsBob2.findWatches<WatchConfirmed>().last().run {
            val e = event as? BITCOIN_TX_CONFIRMED
            assertNotNull(e)
            assertEquals(tx.txid, actionsBob2.filterIsInstance<ChannelAction.Blockchain.PublishTx>().last().tx.txIn.first().outPoint.txid)
        }
    }

    @Test
    fun `recv RevokeAndAck (channel backup, zero-reserve channel, fundee)`() {
        val currentBlockHeight = 500L
        val (alice, bob) = reachNormal(ChannelVersion.STANDARD or ChannelVersion.ZERO_RESERVE)
        val (_, cmdAdd) = makeCmdAdd(
            50000000.msat,
            alice.staticParams.nodeParams.nodeId,
            currentBlockHeight
        )
        val (bob1, actions) = bob.process(ChannelEvent.ExecuteCommand(cmdAdd))
        val add = actions.findOutgoingMessage<UpdateAddHtlc>()
        val (alice1, _) = alice.process(ChannelEvent.MessageReceived(add))
        assertTrue { (alice1 as Normal).commitments.remoteChanges.proposed.contains(add) }
        val (bob2, actions2) = bob1.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig = actions2.findOutgoingMessage<CommitSig>()
        val (alice2, actions3) = alice1.process(ChannelEvent.MessageReceived(commitSig))
        val revack = actions3.findOutgoingMessage<RevokeAndAck>()
        val (bob3, _) = bob2.process(ChannelEvent.MessageReceived(revack))
        val (_, actions4) = alice2.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig1 = actions4.findOutgoingMessage<CommitSig>()
        val (bob4, actions5) = bob3.process(ChannelEvent.MessageReceived(commitSig1))
        val revack1 = actions5.findOutgoingMessage<RevokeAndAck>()
        val blob = Helpers.encrypt(bob4.staticParams.nodeParams.nodePrivateKey.value, bob4 as Normal)
        assertEquals(blob.toByteVector(), revack1.channelData)
    }

    @Test
    fun `recv RevokeAndAck (one htlc sent)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, bob1) = addHtlc(50_000_000.msat, alice0, bob0).first

        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig = actionsAlice2.findOutgoingMessage<CommitSig>()
        val (_, actionsBob2) = bob1.process(ChannelEvent.MessageReceived(commitSig))
        val revokeAndAck = actionsBob2.findOutgoingMessage<RevokeAndAck>()

        assertTrue((alice2 as ChannelStateWithCommitments).commitments.remoteNextCommitInfo.isLeft)
        val (alice3, _) = alice2.process(ChannelEvent.MessageReceived(revokeAndAck))
        assertTrue((alice3 as ChannelStateWithCommitments).commitments.remoteNextCommitInfo.isRight)
        assertEquals(1, alice3.commitments.localChanges.acked.size)
    }

    @Test
    fun `recv RevokeAndAck (one htlc received)`() {
        val (alice0, bob0) = reachNormal()
        val (nodes, _, _) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, bob1) = nodes

        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig0 = actionsAlice2.findOutgoingMessage<CommitSig>()

        val (bob2, actionsBob2) = bob1.process(ChannelEvent.MessageReceived(commitSig0))
        val revokeAndAck0 = actionsBob2.findOutgoingMessage<RevokeAndAck>()
        val cmd = actionsBob2.findCommand<CMD_SIGN>()
        val (bob3, actionsBob3) = bob2.process(ChannelEvent.ExecuteCommand(cmd))
        val (alice3, _) = alice2.process(ChannelEvent.MessageReceived(revokeAndAck0))
        assertTrue((alice3 as ChannelStateWithCommitments).commitments.remoteNextCommitInfo.isRight)
        val commitSig1 = actionsBob3.findOutgoingMessage<CommitSig>()
        val (_, actionsAlice4) = alice3.process(ChannelEvent.MessageReceived(commitSig1))
        val revokeAndAck1 = actionsAlice4.findOutgoingMessage<RevokeAndAck>()
        val (bob4, _) = bob3.process(ChannelEvent.MessageReceived(revokeAndAck1))
        assertTrue((bob4 as ChannelStateWithCommitments).commitments.remoteNextCommitInfo.isRight)
    }

    @Test
    fun `recv RevokeAndAck (multiple htlcs in both directions)`() {
        val (alice0, bob0) = reachNormal()
        val (nodes1, _, _) = addHtlc(50_000_000.msat, alice0, bob0) // a->b (regular)
        val (alice1, bob1) = nodes1
        val (nodes2, _, _) = addHtlc(8_000_000.msat, alice1, bob1) //  a->b (regular)
        val (alice2, bob2) = nodes2
        val (nodes3, _, _) = addHtlc(300_000.msat, bob2, alice2) //   b->a (dust)
        val (bob3, alice3) = nodes3
        val (nodes4, _, _) = addHtlc(1_000_000.msat, alice3, bob3) //  a->b (regular)
        val (alice4, bob4) = nodes4
        val (nodes5, _, _) = addHtlc(50_000_000.msat, bob4, alice4) // b->a (regular)
        val (bob5, alice5) = nodes5
        val (nodes6, _, _) = addHtlc(500_000.msat, alice5, bob5) //   a->b (dust)
        val (alice6, bob6) = nodes6
        val (nodes7, _, _) = addHtlc(4_000_000.msat, bob6, alice6) //  b->a (regular)
        val (bob7, alice7) = nodes7

        val (alice8, actionsAlice8) = alice7.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig0 = actionsAlice8.findOutgoingMessage<CommitSig>()
        val (bob8, actionsBob8) = bob7.process(ChannelEvent.MessageReceived(commitSig0))
        val revokeAndAck0 = actionsBob8.findOutgoingMessage<RevokeAndAck>()
        val cmd = actionsBob8.findCommand<CMD_SIGN>()
        val (bob9, actionsBob9) = bob8.process(ChannelEvent.ExecuteCommand(cmd))
        val (alice9, _) = alice8.process(ChannelEvent.MessageReceived(revokeAndAck0))
        assertTrue((alice9 as ChannelStateWithCommitments).commitments.remoteNextCommitInfo.isRight)

        val commitSig1 = actionsBob9.findOutgoingMessage<CommitSig>()
        val (_, actionsAlice10) = alice9.process(ChannelEvent.MessageReceived(commitSig1))
        val revokeAndAck1 = actionsAlice10.findOutgoingMessage<RevokeAndAck>()
        val (bob10, _) = bob9.process(ChannelEvent.MessageReceived(revokeAndAck1))
        bob10 as ChannelStateWithCommitments

        assertTrue(bob10.commitments.remoteNextCommitInfo.isRight)
        assertEquals(1, bob10.commitments.remoteCommit.index)
        assertEquals(7, bob10.commitments.remoteCommit.spec.htlcs.size)
    }

    @Test
    fun `recv RevokeAndAck (with reSignAsap=true)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, bob1) = addHtlc(50_000_000.msat, alice0, bob0).first
        assertTrue((alice1 as ChannelStateWithCommitments).commitments.remoteNextCommitInfo.isRight)

        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig0 = actionsAlice2.findOutgoingMessage<CommitSig>()
        val (bob2, actionsBob2) = bob1.process(ChannelEvent.MessageReceived(commitSig0))

        val (alice3, _) = addHtlc(50_000_000.msat, alice2, bob2).first
        val (alice4, _) = alice3.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        assertTrue((alice4 as ChannelStateWithCommitments).commitments.remoteNextCommitInfo.left?.reSignAsap == true)

        val revokeAndAck0 = actionsBob2.findOutgoingMessage<RevokeAndAck>()
        val (alice5, actionsAlice5) = alice4.process(ChannelEvent.MessageReceived(revokeAndAck0))
        val cmd = actionsAlice5.findCommand<CMD_SIGN>()
        val (_, actionsAlice6) = alice5.process(ChannelEvent.ExecuteCommand(cmd))
        actionsAlice6.hasOutgoingMessage<CommitSig>()
    }

    @Test
    fun `recv RevokeAndAck (invalid preimage)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, bob1) = addHtlc(50_000_000.msat, alice0, bob0).first
        val tx = (alice1 as ChannelStateWithCommitments).commitments.localCommit.publishableTxs.commitTx.tx

        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig0 = actionsAlice2.findOutgoingMessage<CommitSig>()
        val (_, actionsBob2) = bob1.process(ChannelEvent.MessageReceived(commitSig0))
        actionsBob2.hasOutgoingMessage<RevokeAndAck>()

        val (alice3, actionsAlice3) = alice2.process(
            ChannelEvent.MessageReceived(
                RevokeAndAck(
                    ByteVector32.Zeroes,
                    PrivateKey(randomBytes32()),
                    PrivateKey(randomBytes32()).publicKey()
                )
            )
        )

        assertTrue(alice3 is Closing)
        actionsAlice3.hasOutgoingMessage<Error>()
        assertEquals(2, actionsAlice3.filterIsInstance<ChannelAction.Blockchain.PublishTx>().count())
        assertEquals(2, actionsAlice3.findWatches<WatchConfirmed>().count())
        assertTrue { actionsAlice3.filterIsInstance<ChannelAction.Blockchain.PublishTx>().any { it.tx == tx } }
    }

    @Test
    fun `recv RevokeAndAck (unexpectedly)`() {
        val (alice0, bob0) = reachNormal()
        val (alice1, _) = addHtlc(50_000_000.msat, alice0, bob0).first
        val tx = (alice1 as ChannelStateWithCommitments).commitments.localCommit.publishableTxs.commitTx.tx
        assertTrue(alice1.commitments.remoteNextCommitInfo.isRight)

        val (alice2, actionsAlice2) = alice1.process(
            ChannelEvent.MessageReceived(
                RevokeAndAck(
                    ByteVector32.Zeroes,
                    PrivateKey(randomBytes32()),
                    PrivateKey(randomBytes32()).publicKey()
                )
            )
        )

        assertTrue(alice2 is Closing)
        actionsAlice2.hasOutgoingMessage<Error>()
        assertEquals(2, actionsAlice2.filterIsInstance<ChannelAction.Blockchain.PublishTx>().count())
        assertEquals(2, actionsAlice2.findWatches<WatchConfirmed>().count())
        assertTrue { actionsAlice2.filterIsInstance<ChannelAction.Blockchain.PublishTx>().any { it.tx == tx } }
    }

    @Ignore
    fun `recv RevokeAndAck (one htlc sent, static_remotekey)`() {
        val (alice0, bob0) = reachNormal()

        assertTrue { alice0.commitments.localParams.features.hasFeature(Feature.StaticRemoteKey) } // TODO FALSE!
        assertTrue { bob0.commitments.localParams.features.hasFeature(Feature.StaticRemoteKey) }
    }

    @Ignore
    fun `recv RevocationTimeout`() {
        TODO("later")
    }

    @Test
    fun `recv CMD_FULFILL_HTLC`() {
        val (alice0, bob0) = reachNormal()
        val (nodes, r, htlc) = addHtlc(50_000_000.msat, alice0, bob0)
        val (_, bob1) = crossSign(nodes.first, nodes.second)
        val initialState = bob1 as Normal

        val (bob2, actionsBob2) = bob1.process(ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(htlc.id, r)))
        val fulfillHtlc = actionsBob2.findOutgoingMessage<UpdateFulfillHtlc>()
        assertEquals(
            initialState.copy(
                commitments = initialState.commitments.copy(
                    localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed + fulfillHtlc)
                )
            ), bob2
        )
    }

    @Test
    fun `recv CMD_FULFILL_HTLC (unknown htlc id)`() {
        val (_, bob0) = reachNormal()

        val cmd = CMD_FULFILL_HTLC(42, randomBytes32())

        val (_, actions) = bob0.process(ChannelEvent.ExecuteCommand(cmd))
        val actualError = actions.findCommandError<UnknownHtlcId>()
        val expectedError = UnknownHtlcId(bob0.channelId, 42)

        assertEquals(expectedError, actualError)
    }

    @Test
    fun `recv CMD_FULFILL_HTLC (invalid preimage)`() {
        val (alice0, bob0) = reachNormal()
        val (nodes, _, htlc) = addHtlc(50_000_000.msat, alice0, bob0)
        val (_, bob1) = crossSign(nodes.first, nodes.second)
        val initialState = bob1 as Normal

        val cmd = CMD_FULFILL_HTLC(htlc.id, ByteVector32.Zeroes)
        val (bob2, actionsBob2) = bob1.process(ChannelEvent.ExecuteCommand(cmd))
        actionsBob2.hasCommandError<InvalidHtlcPreimage>()

        assertEquals(initialState, bob2)
    }

    @Test
    fun `recv UpdateFulfillHtlc`() {
        val (alice0, bob0) = reachNormal()
        val (nodes, r, htlc) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, bob1) = crossSign(nodes.first, nodes.second)
        val (_, actionsBob2) = bob1.process(ChannelEvent.ExecuteCommand(CMD_FULFILL_HTLC(htlc.id, r)))
        val fulfill = actionsBob2.findOutgoingMessage<UpdateFulfillHtlc>()
        val initialState = alice1 as Normal

        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.MessageReceived(fulfill))
        assertEquals(initialState.copy(commitments = initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed + fulfill))), alice2)
        val addSettled = actionsAlice2.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFulfill>().first()
        assertEquals(htlc, addSettled.htlc)
        assertEquals(ChannelAction.HtlcResult.Fulfill.RemoteFulfill(fulfill), addSettled.result)
    }

    @Test
    fun `recv UpdateFulfillHtlc (unknown htlc id)`() {
        val (alice0, _) = reachNormal()
        val commitTx = alice0.commitments.localCommit.publishableTxs.commitTx.tx
        val (alice1, actions1) = alice0.process(ChannelEvent.MessageReceived(UpdateFulfillHtlc(alice0.channelId, 42, randomBytes32())))
        assertTrue(alice1 is Closing)
        assertTrue(actions1.contains(ChannelAction.Storage.StoreState(alice1)))
        assertTrue(actions1.contains(ChannelAction.Blockchain.PublishTx(commitTx)))
        assertTrue(actions1.findWatches<WatchConfirmed>().isNotEmpty())
        assertTrue(actions1.filterIsInstance<ChannelAction.Blockchain.SendWatch>().all { it.watch is WatchConfirmed })
        val error = actions1.findOutgoingMessage<Error>()
        assertEquals(error.toAscii(), UnknownHtlcId(alice0.channelId, 42).message)
    }

    @Test
    fun `recv UpdateFulfillHtlc (sender has not signed htlc)`() {
        val (alice0, bob0) = reachNormal()
        val (nodes, r, htlc) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, actions1) = nodes.first.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        actions1.findOutgoingMessage<CommitSig>()
        val commitTx = (alice1 as Normal).commitments.localCommit.publishableTxs.commitTx.tx
        val (alice2, actions2) = alice1.process(ChannelEvent.MessageReceived(UpdateFulfillHtlc(alice1.channelId, htlc.id, r)))
        assertTrue(alice2 is Closing)
        assertTrue(actions2.contains(ChannelAction.Storage.StoreState(alice2)))
        assertTrue(actions2.contains(ChannelAction.Blockchain.PublishTx(commitTx)))
        assertTrue(actions2.findWatches<WatchConfirmed>().isNotEmpty())
        assertTrue(actions2.filterIsInstance<ChannelAction.Blockchain.SendWatch>().all { it.watch is WatchConfirmed })
        val error = actions2.findOutgoingMessage<Error>()
        assertEquals(error.toAscii(), UnknownHtlcId(alice0.channelId, 0).message)
    }

    @Test
    fun `recv UpdateFulfillHtlc (invalid preimage)`() {
        val (alice0, bob0) = reachNormal()
        val (nodes, _, htlc) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, _) = crossSign(nodes.first, nodes.second)
        val commitTx = (alice1 as Normal).commitments.localCommit.publishableTxs.commitTx.tx
        val (alice2, actions2) = alice1.process(ChannelEvent.MessageReceived(UpdateFulfillHtlc(alice0.channelId, htlc.id, ByteVector32.Zeroes)))
        assertTrue(alice2 is Closing)
        assertTrue(actions2.contains(ChannelAction.Storage.StoreState(alice2)))
        assertTrue(actions2.contains(ChannelAction.Blockchain.PublishTx(commitTx)))
        assertEquals(4, actions2.filterIsInstance<ChannelAction.Blockchain.PublishTx>().size) // commit tx + main delayed + htlc-timeout + htlc delayed
        assertEquals(4, actions2.filterIsInstance<ChannelAction.Blockchain.SendWatch>().size)
        val error = actions2.findOutgoingMessage<Error>()
        assertEquals(error.toAscii(), InvalidHtlcPreimage(alice0.channelId, htlc.id).message)
    }

    @Test
    fun `recv CMD_FAIL_HTLC`() {
        val (alice0, bob0) = reachNormal()
        val (nodes, _, htlc) = addHtlc(50_000_000.msat, alice0, bob0)
        val (_, bob1) = crossSign(nodes.first, nodes.second)
        val initialState = bob1 as Normal

        val cmd = CMD_FAIL_HTLC(htlc.id, CMD_FAIL_HTLC.Reason.Failure(PermanentChannelFailure))
        val (bob2, actions2) = bob1.process(ChannelEvent.ExecuteCommand(cmd))
        val fail = actions2.findOutgoingMessage<UpdateFailHtlc>()
        assertEquals(initialState.copy(commitments = initialState.commitments.copy(localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed + fail))), bob2)
    }

    @Test
    fun `recv CMD_FAIL_HTLC (unknown htlc id)`() {
        val (_, bob0) = reachNormal()
        val cmdFail = CMD_FAIL_HTLC(42, CMD_FAIL_HTLC.Reason.Failure(PermanentChannelFailure))
        val (bob1, actions1) = bob0.process(ChannelEvent.ExecuteCommand(cmdFail))
        assertEquals(actions1, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmdFail, UnknownHtlcId(bob0.channelId, 42))))
        assertEquals(bob0, bob1)
    }

    @Test
    fun `recv CMD_FAIL_HTLC_MALFORMED`() {
        val (alice0, bob0) = reachNormal()
        val (nodes, _, htlc) = addHtlc(50_000_000.msat, alice0, bob0)
        val (_, bob1) = crossSign(nodes.first, nodes.second)
        val initialState = bob1 as Normal

        val cmdFail = CMD_FAIL_MALFORMED_HTLC(htlc.id, Sphinx.hash(htlc.onionRoutingPacket), FailureMessage.BADONION)
        val (bob2, actions2) = bob1.process(ChannelEvent.ExecuteCommand(cmdFail))
        val fail = actions2.findOutgoingMessage<UpdateFailMalformedHtlc>()
        assertEquals(initialState.copy(commitments = initialState.commitments.copy(localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed + fail))), bob2)
    }

    @Test
    fun `recv CMD_FAIL_HTLC_MALFORMED (unknown htlc id)`() {
        val (_, bob0) = reachNormal()
        val cmdFail = CMD_FAIL_MALFORMED_HTLC(42, ByteVector32.Zeroes, FailureMessage.BADONION)
        val (bob1, actions1) = bob0.process(ChannelEvent.ExecuteCommand(cmdFail))
        assertEquals(actions1, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmdFail, UnknownHtlcId(bob0.channelId, 42))))
        assertEquals(bob0, bob1)
    }

    @Test
    fun `recv CMD_FAIL_HTLC_MALFORMED (invalid failure_code)`() {
        val (alice0, bob0) = reachNormal()
        val (nodes, _, htlc) = addHtlc(50_000_000.msat, alice0, bob0)
        val (_, bob1) = crossSign(nodes.first, nodes.second)

        val cmdFail = CMD_FAIL_MALFORMED_HTLC(htlc.id, Sphinx.hash(htlc.onionRoutingPacket), 42)
        val (bob2, actions2) = bob1.process(ChannelEvent.ExecuteCommand(cmdFail))
        assertEquals(actions2, listOf(ChannelAction.ProcessCmdRes.NotExecuted(cmdFail, InvalidFailureCode(bob0.channelId))))
        assertEquals(bob1, bob2)
    }

    @Test
    fun `recv UpdateFailHtlc`() {
        val (alice0, bob0) = reachNormal()
        val (nodes, _, htlc) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, bob1) = crossSign(nodes.first, nodes.second)
        val (_, actionsBob2) = bob1.process(ChannelEvent.ExecuteCommand(CMD_FAIL_HTLC(htlc.id, CMD_FAIL_HTLC.Reason.Failure(PermanentChannelFailure))))
        val fail = actionsBob2.findOutgoingMessage<UpdateFailHtlc>()
        val initialState = alice1 as Normal

        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.MessageReceived(fail))
        assertTrue(alice2 is Normal)
        assertTrue(actionsAlice2.isEmpty())
        assertEquals(initialState.copy(commitments = initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed + fail))), alice2)
    }

    @Test
    fun `recv UpdateFailHtlc (unknown htlc id)`() {
        val (alice0, _) = reachNormal()
        val commitTx = alice0.commitments.localCommit.publishableTxs.commitTx.tx
        val (alice1, actions1) = alice0.process(ChannelEvent.MessageReceived(UpdateFailHtlc(alice0.channelId, 42, ByteVector.empty)))
        assertTrue(alice1 is Closing)
        assertTrue(actions1.contains(ChannelAction.Storage.StoreState(alice1)))
        assertTrue(actions1.contains(ChannelAction.Blockchain.PublishTx(commitTx)))
        assertTrue(actions1.findWatches<WatchConfirmed>().isNotEmpty())
        assertTrue(actions1.filterIsInstance<ChannelAction.Blockchain.SendWatch>().all { it.watch is WatchConfirmed })
        val error = actions1.findOutgoingMessage<Error>()
        assertEquals(error.toAscii(), UnknownHtlcId(alice0.channelId, 42).message)
    }

    @Test
    fun `recv UpdateFailHtlc (sender has not signed htlc)`() {
        val (alice0, bob0) = reachNormal()
        val (nodes, _, htlc) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, actions1) = nodes.first.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        actions1.findOutgoingMessage<CommitSig>()
        val commitTx = (alice1 as Normal).commitments.localCommit.publishableTxs.commitTx.tx
        val (alice2, actions2) = alice1.process(ChannelEvent.MessageReceived(UpdateFailHtlc(alice1.channelId, htlc.id, ByteVector.empty)))
        assertTrue(alice2 is Closing)
        assertTrue(actions2.contains(ChannelAction.Storage.StoreState(alice2)))
        assertTrue(actions2.contains(ChannelAction.Blockchain.PublishTx(commitTx)))
        assertTrue(actions2.findWatches<WatchConfirmed>().isNotEmpty())
        assertTrue(actions2.filterIsInstance<ChannelAction.Blockchain.SendWatch>().all { it.watch is WatchConfirmed })
        val error = actions2.findOutgoingMessage<Error>()
        assertEquals(error.toAscii(), UnknownHtlcId(alice0.channelId, 0).message)
    }

    @Test
    fun `recv UpdateFailMalformedHtlc`() {
        val (alice0, bob0) = reachNormal()
        val (nodes, _, htlc) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, bob1) = crossSign(nodes.first, nodes.second)
        val (_, actionsBob2) = bob1.process(ChannelEvent.ExecuteCommand(CMD_FAIL_MALFORMED_HTLC(htlc.id, Sphinx.hash(htlc.onionRoutingPacket), FailureMessage.BADONION)))
        val fail = actionsBob2.findOutgoingMessage<UpdateFailMalformedHtlc>()
        val initialState = alice1 as Normal

        val (alice2, actionsAlice2) = alice1.process(ChannelEvent.MessageReceived(fail))
        assertTrue(alice2 is Normal)
        assertTrue(actionsAlice2.isEmpty())
        assertEquals(initialState.copy(commitments = initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed + fail))), alice2)
    }

    @Test
    fun `recv UpdateFailMalformedHtlc (unknown htlc id)`() {
        val (alice0, _) = reachNormal()
        val commitTx = alice0.commitments.localCommit.publishableTxs.commitTx.tx
        val (alice1, actions1) = alice0.process(ChannelEvent.MessageReceived(UpdateFailMalformedHtlc(alice0.channelId, 42, ByteVector32.Zeroes, FailureMessage.BADONION)))
        assertTrue(alice1 is Closing)
        assertTrue(actions1.contains(ChannelAction.Storage.StoreState(alice1)))
        assertTrue(actions1.contains(ChannelAction.Blockchain.PublishTx(commitTx)))
        assertTrue(actions1.findWatches<WatchConfirmed>().isNotEmpty())
        assertTrue(actions1.filterIsInstance<ChannelAction.Blockchain.SendWatch>().all { it.watch is WatchConfirmed })
        val error = actions1.findOutgoingMessage<Error>()
        assertEquals(error.toAscii(), UnknownHtlcId(alice0.channelId, 42).message)
    }

    @Test
    fun `recv UpdateFailMalformedHtlc (invalid failure_code)`() {
        val (alice0, bob0) = reachNormal()
        val (nodes, _, htlc) = addHtlc(50_000_000.msat, alice0, bob0)
        val (alice1, _) = crossSign(nodes.first, nodes.second)
        val commitTx = (alice1 as Normal).commitments.localCommit.publishableTxs.commitTx.tx

        val (alice2, actions2) = alice1.process(ChannelEvent.MessageReceived(UpdateFailMalformedHtlc(alice0.channelId, htlc.id, Sphinx.hash(htlc.onionRoutingPacket), 42)))
        assertTrue(alice2 is Closing)
        assertTrue(actions2.contains(ChannelAction.Storage.StoreState(alice2)))
        assertTrue(actions2.contains(ChannelAction.Blockchain.PublishTx(commitTx)))
        assertEquals(4, actions2.filterIsInstance<ChannelAction.Blockchain.PublishTx>().size) // commit tx + main delayed + htlc-timeout + htlc delayed
        assertEquals(4, actions2.filterIsInstance<ChannelAction.Blockchain.SendWatch>().size)
        val error = actions2.findOutgoingMessage<Error>()
        assertEquals(error.toAscii(), InvalidFailureCode(alice0.channelId).message)
    }

    @Test
    fun `recv UpdateFee`() {
        val (_, bob) = reachNormal()
        val fee = UpdateFee(ByteVector32.Zeroes, FeeratePerKw(7_500.sat))
        val (bob1, _) = bob.process(ChannelEvent.MessageReceived(fee))
        bob1 as Normal
        assertEquals(bob.commitments.copy(remoteChanges = bob.commitments.remoteChanges.copy(proposed = bob.commitments.remoteChanges.proposed + fee)), bob1.commitments)
    }

    @Test
    fun `recv UpdateFee (2 in a row)`() {
        val (_, bob) = reachNormal()
        val fee1 = UpdateFee(ByteVector32.Zeroes, FeeratePerKw(7_500.sat))
        val (bob1, _) = bob.process(ChannelEvent.MessageReceived(fee1))
        val fee2 = UpdateFee(ByteVector32.Zeroes, FeeratePerKw(9_000.sat))
        val (bob2, _) = bob1.process(ChannelEvent.MessageReceived(fee2))
        bob2 as Normal
        assertEquals(bob.commitments.copy(remoteChanges = bob.commitments.remoteChanges.copy(proposed = bob.commitments.remoteChanges.proposed + fee2)), bob2.commitments)
    }

    @Test
    fun `recv UpdateFee (sender cannot afford it)`() {
        val (alice, bob) = reachNormal()
        // We put all the balance on Bob's side, so that Alice cannot afford a feerate increase.
        val (nodes, _, _) = addHtlc(alice.commitments.availableBalanceForSend(), alice, bob)
        val (_, bob1) = crossSign(nodes.first, nodes.second)
        val commitTx = (bob1 as Normal).commitments.localCommit.publishableTxs.commitTx.tx

        val fee = UpdateFee(ByteVector32.Zeroes, FeeratePerKw.CommitmentFeerate * 4)
        val (bob2, actions) = bob1.process(ChannelEvent.MessageReceived(fee))
        assertTrue { bob2 is Closing }
        assertTrue { actions.contains(ChannelAction.Blockchain.PublishTx(commitTx)) }
        actions.hasWatch<WatchConfirmed>()
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error.toAscii(), CannotAffordFees(bob.channelId, missing = 11_240.sat, reserve = 20_000.sat, fees = 26_580.sat).message)
    }

    @Test
    fun `recv UpdateFee (remote feerate is too small)`() {
        val (_, bob) = reachNormal()
        assertEquals(FeeratePerKw.CommitmentFeerate, bob.commitments.localCommit.spec.feerate)
        val (bob1, actions) = bob.process(ChannelEvent.MessageReceived(UpdateFee(bob.channelId, FeeratePerKw(252.sat))))
        assertTrue { bob1 is Closing }
        assertTrue { actions.contains(ChannelAction.Blockchain.PublishTx(bob.commitments.localCommit.publishableTxs.commitTx.tx)) }
        actions.hasWatch<WatchConfirmed>()
        val error = actions.findOutgoingMessage<Error>()
        assertTrue { error.toAscii().contains("emote fee rate is too small: remoteFeeratePerKw=252") }
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

        alice8 as ChannelStateWithCommitments; bob8 as ChannelStateWithCommitments
        val bobCommitTx = bob8.commitments.localCommit.publishableTxs.commitTx.tx
        assertEquals(8, bobCommitTx.txOut.size) // 2 main outputs and 4 pending htlcs

        val (aliceClosing, actions) = alice8.process(
            ChannelEvent.WatchReceived(
                WatchEventSpent(
                    alice8.channelId,
                    BITCOIN_FUNDING_SPENT,
                    bobCommitTx
                )
            )
        )

        assertTrue(aliceClosing is Closing)
        assertTrue(actions.isNotEmpty())
        // in response to that, alice publishes its claim txes
        val claimTxes = actions.filterIsInstance<ChannelAction.Blockchain.PublishTx>().map { it.tx }
        assertEquals(3, claimTxes.size)
        // in addition to its main output, alice can only claim 3 out of 4 htlcs,
        // she can't do anything regarding the htlc sent by bob for which she does not have the preimage
        val amountClaimed = claimTxes.map { claimHtlcTx ->
            assertEquals(1, claimHtlcTx.txIn.size)
            assertEquals(1, claimHtlcTx.txOut.size)
            Transaction.correctlySpends(claimHtlcTx, listOf(bobCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            claimHtlcTx.txOut[0].amount
        }.sum()
        // at best we have a little less than 450 000 + 250 000 + 100 000 + 50 000 = 850 000 (because fees)
        assertEquals(383640.sat, amountClaimed) // TODO formerly 814880.sat ?

        assertEquals(BITCOIN_TX_CONFIRMED(bobCommitTx), actions.findWatches<WatchConfirmed>()[0].event)
        assertEquals(3, actions.findWatches<WatchSpent>().count { it.event is BITCOIN_OUTPUT_SPENT })

        assertEquals(1, aliceClosing.remoteCommitPublished?.claimHtlcSuccessTxs?.size)
        assertEquals(2, aliceClosing.remoteCommitPublished?.claimHtlcTimeoutTxs?.size)
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
        val (alice9, aActions8) = alice8.process(ChannelEvent.ExecuteCommand(CMD_SIGN))
        val commitSig0 = aActions8.findOutgoingMessage<CommitSig>()
        val (bob9, _) = bob8.process(ChannelEvent.MessageReceived(commitSig0))

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

        alice9 as ChannelStateWithCommitments; bob9 as ChannelStateWithCommitments
        // bob publishes his current commit tx
        val bobCommitTx = bob9.commitments.localCommit.publishableTxs.commitTx.tx
        assertEquals(7, bobCommitTx.txOut.size) // 2 main outputs and 3 pending htlcs

        val (aliceClosing, actions) = alice9.process(
            ChannelEvent.WatchReceived(
                WatchEventSpent(
                    alice9.channelId,
                    BITCOIN_FUNDING_SPENT,
                    bobCommitTx
                )
            )
        )

        assertTrue(aliceClosing is Closing)
        assertTrue(actions.isNotEmpty())

        // in response to that, alice publishes its claim txes
        val claimTxes = actions.filterIsInstance<ChannelAction.Blockchain.PublishTx>().map { it.tx }
        assertEquals(2, claimTxes.size)
        // alice can only claim 2 out of 3 htlcs,
        // she can't do anything regarding the htlc sent by bob for which she does not have the preimage
        val amountClaimed = claimTxes.map { claimHtlcTx ->
            assertEquals(1, claimHtlcTx.txIn.size)
            assertEquals(1, claimHtlcTx.txOut.size)
            Transaction.correctlySpends(claimHtlcTx, listOf(bobCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            claimHtlcTx.txOut[0].amount
        }.sum()
        // at best we have a little less than 500 000 + 250 000 + 100 000 = 850 000 (because fees)
        assertEquals(339060.sat, amountClaimed) // TODO formerly 822310.sat ?

        assertEquals(BITCOIN_TX_CONFIRMED(bobCommitTx), actions.findWatches<WatchConfirmed>()[0].event)
        assertEquals(2, actions.findWatches<WatchSpent>().count { it.event is BITCOIN_OUTPUT_SPENT })

        assertEquals(0, aliceClosing.nextRemoteCommitPublished?.claimHtlcSuccessTxs?.size)
        assertEquals(2, aliceClosing.nextRemoteCommitPublished?.claimHtlcTimeoutTxs?.size)
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
        // 2 anchor outputs + 2 main outputs + 4 htlc
        assertEquals(8, revokedTx.txOut.size)

        val (aliceClosing, actions) = alice.process(
            ChannelEvent.WatchReceived(
                WatchEventSpent(
                    alice.channelId,
                    BITCOIN_FUNDING_SPENT,
                    revokedTx
                )
            )
        )

        assertTrue(aliceClosing is Closing)
        assertEquals(1, aliceClosing.revokedCommitPublished.size)
        assertTrue(actions.isNotEmpty())
        actions.hasOutgoingMessage<Error>()

        val claimTxes = actions.filterIsInstance<ChannelAction.Blockchain.PublishTx>().map { it.tx }
        // we don't need to claim our output since we use static_remote_key
        val mainPenaltyTx = claimTxes[0]
        // TODO business code is disabled for now
        //      val htlcPenaltyTxs = claimTxes.drop(2)
        //      assertEquals(2, htlcPenaltyTxs.size)
        //      // let's make sure that htlc-penalty txs each spend a different output
        //      assertEquals(htlcPenaltyTxs.map { it.txIn.first().outPoint.index }.toSet().size, htlcPenaltyTxs.size)

        assertEquals(BITCOIN_TX_CONFIRMED(revokedTx), actions.findWatches<WatchConfirmed>()[0].event)
        assertTrue(actions.findWatches<WatchSpent>().all { it.event is BITCOIN_OUTPUT_SPENT })
        // TODO business code is disabled for now
        //        assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT) // main-penalty
        //        htlcPenaltyTxs.foreach(htlcPenaltyTx => assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT))

        Transaction.correctlySpends(mainPenaltyTx, listOf(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        // TODO business code is disabled for now
//            htlcPenaltyTxs.forEach {
//                Transaction.correctlySpends(it, listOf(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
//            }

        // two main outputs are 760 000 and 200 000
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
        val (_, cmdAddHtlc) = makeCmdAdd(
            10_000_000.msat,
            bob0.staticParams.nodeParams.nodeId,
            alice0.currentBlockHeight.toLong()
        )

        val (alice1, bob1, _) = addHtlc(cmdAdd = cmdAddHtlc, payer = alice0, payee = bob0)
        val (alice2, bob2, _) = addHtlc(cmdAdd = cmdAddHtlc, payer = alice1, payee = bob1)

        val (alice3, bob3) = crossSign(alice2, bob2)

        // bob will publish this tx after it is revoked
        val revokedTx = (bob3 as ChannelStateWithCommitments).commitments.localCommit.publishableTxs.commitTx.tx

        val (alice4, bob4) = addHtlc(amount = 10000000.msat, payer = alice3, payee = bob3).first
        val (alice5, _) = crossSign(alice4, bob4)

        // channel state for this revoked tx is as follows:
        // alice = 780 000
        //   bob = 200 000
        //  a->b =  10 000
        //  a->b =  10 000
        assertEquals(6, revokedTx.txOut.size)

        val (aliceClosing, actions) = alice5.process(
            ChannelEvent.WatchReceived(
                WatchEventSpent(
                    (alice5 as ChannelStateWithCommitments).channelId,
                    BITCOIN_FUNDING_SPENT,
                    revokedTx
                )
            )
        )

        assertTrue(aliceClosing is Closing)
        assertTrue(actions.isNotEmpty())
        actions.hasOutgoingMessage<Error>()

        val claimTxes = actions.filterIsInstance<ChannelAction.Blockchain.PublishTx>().map { it.tx }
        val mainPenaltyTx = claimTxes[0]
        // TODO business code is disabled for now
        //      val htlcPenaltyTxs = claimTxes.drop(2)
        //      assertEquals(2, htlcPenaltyTxs.size)
        //      // let's make sure that htlc-penalty txs each spend a different output
        //      assertEquals(htlcPenaltyTxs.map { it.txIn.first().outPoint.index }.toSet().size, htlcPenaltyTxs.size)

        assertEquals(BITCOIN_TX_CONFIRMED(revokedTx), actions.findWatches<WatchConfirmed>()[0].event)
        assertTrue(actions.findWatches<WatchSpent>().all { it.event is BITCOIN_OUTPUT_SPENT })
        // TODO business code is disabled for now
        //        assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT) // main-penalty
        //        htlcPenaltyTxs.foreach(htlcPenaltyTx => assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT))

        Transaction.correctlySpends(mainPenaltyTx, listOf(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        // TODO business code is disabled for now
//            htlcPenaltyTxs.forEach {
//                Transaction.correctlySpends(it, listOf(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
//            }
    }

    @Test
    fun `recv Disconnected`() {
        val (alice0, _) = reachNormal()
        val (alice1, _) = alice0.process(ChannelEvent.Disconnected)
        assertTrue { alice1 is Offline } ; alice1 as Offline // force type inference
        val previousState = alice1.state
        assertTrue { previousState is Normal } ; previousState as Normal // force type inference
        assertEquals(alice0.channelUpdate, previousState.channelUpdate)
    }

    @Test
    fun `recv Disconnected (with htlcs)`() {
        val (alice0, bob0) = reachNormal()
        val (nodes0, _, htlc1) = addHtlc(250_000_000.msat, payer = alice0, payee = bob0)
        val (alice1, bob1) = nodes0
        val (nodes1, _, htlc2) = addHtlc(250_000_000.msat, payer = alice1, payee = bob1)
        val (alice2, _) = nodes1

        val (alice3, actions) = alice2.process(ChannelEvent.Disconnected)
        assertTrue { alice3 is Offline } ; alice3 as Offline // force type inference

        val addSettledFailList = actions.filterIsInstance<ChannelAction.ProcessCmdRes.AddSettledFail>()
        assertEquals(2, addSettledFailList.size)
        assertTrue { addSettledFailList.all { it.result is ChannelAction.HtlcResult.Fail.Disconnected } }
        assertEquals(htlc1.paymentHash, addSettledFailList.first().htlc.paymentHash)
        assertEquals(htlc2.paymentHash, addSettledFailList.last().htlc.paymentHash)
        assertEquals(alice2, alice3.state)
    }
}
