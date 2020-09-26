package fr.acinq.eclair.channel

import fr.acinq.eclair.channel.TestsHelper.findOutgoingMessage
import fr.acinq.eclair.channel.TestsHelper.reachNormal
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.toByteVector
import fr.acinq.eclair.wire.CommitSig
import fr.acinq.eclair.wire.RevokeAndAck
import fr.acinq.eclair.wire.UpdateAddHtlc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NormalStateCommonTests : EclairTestSuite() {
    @Test
    fun `recv CMD_SIGN (channel backup, zero-reserve channel, fundee)`() {
        val currentBlockHeight = 500L
        val (alice, bob) = reachNormal(ChannelVersion.STANDARD or ChannelVersion.ZERO_RESERVE)
        val (_, cmdAdd) = TestsHelper.makeCmdAdd(50000000.msat, alice.staticParams.nodeParams.nodeId, currentBlockHeight)
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
    fun `recv RevokeAndAck (channel backup, zero-reserve channel, fundee)`() {
        val currentBlockHeight = 500L
        val (alice, bob) = reachNormal(ChannelVersion.STANDARD or ChannelVersion.ZERO_RESERVE)
        val (_, cmdAdd) = TestsHelper.makeCmdAdd(50000000.msat, alice.staticParams.nodeParams.nodeId, currentBlockHeight)
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
}