package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.wire.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineTestsCommon : EclairTestSuite() {
    @Test
    fun `handle disconnect - connect events (no messages sent yet)`() {
        val (alice, bob) = TestsHelper.reachNormal()
        val (alice1, _) = alice.process(Disconnected)
        val (bob1, _) = bob.process(Disconnected)
        assertTrue { alice1 is Offline }
        assertTrue { bob1 is Offline }

        val localInit = Init(ByteVector(TestConstants.Alice.channelParams.features.toByteArray()))
        val remoteInit = Init(ByteVector(TestConstants.Bob.channelParams.features.toByteArray()))

        val (alice2, actions) = alice1.process(Connected(localInit, remoteInit))
        assertTrue { alice2 is Syncing }
        val channelReestablishA = (actions[0] as SendMessage).message as ChannelReestablish
        val (bob2, actions1) = bob1.process(Connected(remoteInit, localInit))
        assertTrue { bob2 is Syncing }
        val channelReestablishB = (actions1[0] as SendMessage).message as ChannelReestablish

        val bobCommitments = bob.commitments
        val aliceCommitments = alice.commitments

        val bobCurrentPerCommitmentPoint = bob.keyManager.commitmentPoint(
            bob.keyManager.channelKeyPath(bobCommitments.localParams, bobCommitments.channelVersion),
            bobCommitments.localCommit.index
        )

        val aliceCurrentPerCommitmentPoint = alice.keyManager.commitmentPoint(
            alice.keyManager.channelKeyPath(aliceCommitments.localParams, aliceCommitments.channelVersion),
            aliceCommitments.localCommit.index
        )

        // a didn't receive any update or sig
        assertEquals(
            ChannelReestablish(alice.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), aliceCurrentPerCommitmentPoint),
            channelReestablishA.copy(channelData = ByteVector.empty)
        )
        assertEquals(
            ChannelReestablish(bob.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint),
            channelReestablishB
        )

        val (alice3, actions2) = alice2.process(MessageReceived(channelReestablishB))
        assertEquals(alice.commitments.localParams, (alice3 as Normal).commitments.localParams)
        assertEquals(alice, alice3)
        assertTrue(actions2.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<FundingLocked>().size == 1)

        val (bob3, actions4) = bob2.process(MessageReceived(channelReestablishA))
        assertEquals(bob, bob3)
        assertTrue(actions4.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<FundingLocked>().size == 1)
    }

    @Test
    fun `re-send update and sig after first commitment`() {
        var (alice, bob) = TestsHelper.reachNormal()
        run {
            val (alice1, actions) = alice.process(ExecuteCommand(CMD_ADD_HTLC(1000000.msat, ByteVector32.Zeroes, CltvExpiryDelta(144).toCltvExpiry(alice.currentBlockHeight.toLong()), TestConstants.emptyOnionPacket, UUID.randomUUID())))
            alice = alice1 as Normal
            val add = actions.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<UpdateAddHtlc>().first()
            val (bob1, _) = bob.process(MessageReceived(add))
            bob = bob1 as Normal
            val (alice2, actions3) = alice.process(ExecuteCommand(CMD_SIGN))
            alice = alice2 as Normal
            assertTrue { actions3.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<CommitSig>().size == 1 }
            // bob doesn't receive the sig
        }

        val (alice1, _) = alice.process(Disconnected)
        val (bob1, _) = bob.process(Disconnected)
        assertTrue { alice1 is Offline }
        assertTrue { bob1 is Offline }

        val localInit = Init(ByteVector(TestConstants.Alice.channelParams.features.toByteArray()))
        val remoteInit = Init(ByteVector(TestConstants.Bob.channelParams.features.toByteArray()))

        val (alice2, actions) = alice1.process(Connected(localInit, remoteInit))
        assertTrue { alice2 is Syncing }
        val channelReestablishA = (actions[0] as SendMessage).message as ChannelReestablish
        val (bob2, actions1) = bob1.process(Connected(remoteInit, localInit))
        assertTrue { bob2 is Syncing }
        val channelReestablishB = (actions1[0] as SendMessage).message as ChannelReestablish

        val bobCommitments = bob.commitments
        val aliceCommitments = alice.commitments

        val bobCurrentPerCommitmentPoint = bob.keyManager.commitmentPoint(
            bob.keyManager.channelKeyPath(bobCommitments.localParams, bobCommitments.channelVersion),
            bobCommitments.localCommit.index
        )

        val aliceCurrentPerCommitmentPoint = alice.keyManager.commitmentPoint(
            alice.keyManager.channelKeyPath(aliceCommitments.localParams, aliceCommitments.channelVersion),
            aliceCommitments.localCommit.index
        )

        // a didn't receive any update or sig
        assertEquals(
            ChannelReestablish(alice.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), aliceCurrentPerCommitmentPoint),
            channelReestablishA
        )
        // b did not receive the sig
        assertEquals(
            ChannelReestablish(bob.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint),
            channelReestablishB
        )

        val (alice3, actions2) = alice2.process(MessageReceived(channelReestablishB))
        alice = alice3 as Normal
        // a sends FundingLocked again
        assertTrue(actions2.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<FundingLocked>().size == 1)
        // a will re-send the update and the sig
        val add = actions2.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<UpdateAddHtlc>().first()
        val sig = actions2.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<CommitSig>().first()

        val (bob3, actions4) = bob2.process(MessageReceived(channelReestablishA))
        bob = bob3 as Normal
        // b sends FundingLocked again
        assertTrue(actions4.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<FundingLocked>().size == 1)
        run {
            val (bob5, _) = bob.process(MessageReceived(add))
            bob = bob5 as Normal
            val (bob6, actions6) = bob.process(MessageReceived(sig))
            bob = bob6 as Normal
            // b sends back a revocation and a sig
            val revB = actions6.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<RevokeAndAck>().first()
            assertTrue { actions6.filterIsInstance<SendToSelf>() == listOf(SendToSelf(CMD_SIGN)) }
            val (bob7, actions7) = bob.process(ExecuteCommand(CMD_SIGN))
            bob = bob7 as Normal
            val sigB = actions7.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<CommitSig>().first()

            val (alice4, _) = alice.process(MessageReceived(revB))
            alice = alice4 as Normal
            val (alice5, actions8) = alice.process(MessageReceived(sigB))
            alice = alice5 as Normal
            val revA = actions8.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<RevokeAndAck>().first()

            val (bob4, _) = bob.process(MessageReceived(revA))
            bob4 as Normal
        }

        assertEquals(1, alice.commitments.localNextHtlcId)
    }
}
