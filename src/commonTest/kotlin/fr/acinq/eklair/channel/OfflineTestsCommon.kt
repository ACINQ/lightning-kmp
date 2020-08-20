package fr.acinq.eklair.channel

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.eklair.ActivatedFeature
import fr.acinq.eklair.Feature
import fr.acinq.eklair.FeatureSupport
import fr.acinq.eklair.Features
import fr.acinq.eklair.utils.toByteVector
import fr.acinq.eklair.wire.ChannelReestablish
import fr.acinq.eklair.wire.FundingLocked
import fr.acinq.eklair.wire.Init
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineTestsCommon {
    @Test
    fun `handle disconnect - connect events (no messages sent yet)`() {
        val (alice, bob) = TestsHelper.reachNormal()
        val (alice1, _) = alice.process(Disconnected)
        val (bob1, _) = bob.process(Disconnected)
        assertTrue{ alice1 is Offline }
        assertTrue{ bob1 is Offline }

        val features = Features(
            setOf(
                ActivatedFeature(Feature.OptionDataLossProtect, FeatureSupport.Optional),
                ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
                ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Optional),
            )
        )
        val localInit = Init(features.toByteArray().toByteVector())
        val remoteInit = localInit

        val (alice2, actions) = alice1.process(Connected(localInit, remoteInit))
        assertTrue { alice2 is Syncing}
        val channelReestablishA = (actions[0] as SendMessage).message as ChannelReestablish
        val (bob2, actions1) = bob1.process(Connected(remoteInit, localInit))
        assertTrue { bob2 is Syncing}
        val channelReestablishB = (actions1[0] as SendMessage).message as ChannelReestablish

        val bobCommitments = bob.commitments
        val aliceCommitments = alice.commitments

        val bobCurrentPerCommitmentPoint = bob.keyManager.commitmentPoint(
            bob.keyManager.channelKeyPath(bobCommitments.localParams, bobCommitments.channelVersion),
            bobCommitments.localCommit.index)

        val aliceCurrentPerCommitmentPoint = alice.keyManager.commitmentPoint(
            alice.keyManager.channelKeyPath(aliceCommitments.localParams, aliceCommitments.channelVersion),
            aliceCommitments.localCommit.index)

        // a didn't receive any update or sig
        assertEquals(
            ChannelReestablish(alice.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), aliceCurrentPerCommitmentPoint),
            channelReestablishA
        )
        assertEquals(
            ChannelReestablish(bob.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint),
            channelReestablishB
        )

        val (alice3, actions2) = alice2.process(MessageReceived(channelReestablishB))
        assertEquals(alice, alice3)
        assertTrue(actions2.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<FundingLocked>().size == 1)

        val (bob3, actions4) = bob2.process(MessageReceived(channelReestablishA))
        assertEquals(bob, bob3)
        assertTrue(actions4.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<FundingLocked>().size == 1)
    }
}