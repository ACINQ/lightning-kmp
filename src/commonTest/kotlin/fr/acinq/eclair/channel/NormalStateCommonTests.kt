package fr.acinq.eclair.channel

import fr.acinq.eclair.ActivatedFeature
import fr.acinq.eclair.Feature
import fr.acinq.eclair.FeatureSupport
import fr.acinq.eclair.Features
import fr.acinq.eclair.channel.TestsHelper.reachNormal
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.toByteVector
import fr.acinq.eclair.wire.CommitSig
import fr.acinq.eclair.wire.UpdateAddHtlc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NormalStateCommonTests {
    @Test
    fun `recv CMD_SIGN (channel backup, zero-reserve channel, fundee)`() {
        val currentBlockHeight = 500L
         val (alice, bob) = reachNormal(ChannelVersion.STANDARD or ChannelVersion.ZERO_RESERVE)
        val (payment_preimage1, cmdAdd1) = TestsHelper.makeCmdAdd(50000000.msat, alice.staticParams.nodeParams.nodeId, currentBlockHeight)
        val (bob1, actions) = bob.process(ExecuteCommand(cmdAdd1))
        val add = actions.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<UpdateAddHtlc>().first()
        val (alice1, actions1) = alice.process(MessageReceived(add))
        assertTrue { (alice1 as Normal).commitments.remoteChanges.proposed.contains(add) }
        val (bob2, actions2) = bob1.process(ExecuteCommand(CMD_SIGN))
        val commitSig = actions2.filterIsInstance<SendMessage>().map { it.message }.filterIsInstance<CommitSig>().first()
        val blob = Helpers.encrypt(bob.staticParams.nodeParams.nodePrivateKey.value, bob2 as Normal)
        assertEquals(blob.toByteVector(), commitSig.channelData)
    }
}