package fr.acinq.eclair.channel.states

import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.Eclair.randomBytes32
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.channel.TestsHelper.signAndRevack
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.wire.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShutdownTestsCommon {
    @Test
    fun `recv CMD_ADD_HTLC`() {
        val (_, bob) = init()
        val add = CMD_ADD_HTLC(500000000.msat, r1, cltvExpiry = CltvExpiry(300000), TestConstants.emptyOnionPacket, UUID.randomUUID())
        val (bob1, actions1) = bob.process(ExecuteCommand(add))
        assertTrue { bob1 is ShuttingDown }
        assertTrue { actions1.any { it is HandleCommandFailed && it.error is AddHtlcFailed && (it.error as AddHtlcFailed).t == ChannelUnavailable(bob.channelId) } }
    }

    @Test
    fun `recv CMD_FULFILL_HTLC`() {
        val (_, bob) = init()
        val (bob1, actions1) = bob.process(ExecuteCommand(CMD_FULFILL_HTLC(0, r1)))
        val fulfill = actions1.findOutgoingMessage<UpdateFulfillHtlc>()
        assertTrue { bob1 is ShuttingDown && bob1.commitments.localChanges.proposed.contains(fulfill) }
    }

    @Test
    fun `recv UpdateFulfillHtlc`() {
        val (alice, bob) = init()
        val (_, actions1) = bob.process(ExecuteCommand(CMD_FULFILL_HTLC(0, r1)))
        val fulfill = actions1.findOutgoingMessage<UpdateFulfillHtlc>()
        val (alice1, _) = alice.process(MessageReceived(fulfill))
        assertTrue { alice1 is ShuttingDown && alice1.commitments.remoteChanges.proposed.contains(fulfill) }
    }

    @Test
    fun `recv CMD_FAIL_HTLC`() {
        val (_, bob) = init()
        val (bob1, actions1) = bob.process(ExecuteCommand(CMD_FAIL_HTLC(0, CMD_FAIL_HTLC.Reason.Failure(PermanentChannelFailure))))
        val fail = actions1.findOutgoingMessage<UpdateFailHtlc>()
        assertTrue { bob1 is ShuttingDown && bob1.commitments.localChanges.proposed.contains(fail) }
    }

    @Test
    fun `recv UpdateFailHtlc`() {
        val (alice, bob) = init()
        val (_, actions1) = bob.process(ExecuteCommand(CMD_FAIL_HTLC(0, CMD_FAIL_HTLC.Reason.Failure(PermanentChannelFailure))))
        val fail = actions1.findOutgoingMessage<UpdateFailHtlc>()
        val (alice1, _) = alice.process(MessageReceived(fail))
        assertTrue { alice1 is ShuttingDown && alice1.commitments.remoteChanges.proposed.contains(fail) }
    }

    @Test
    fun `recv CMD_SIGN`() {
        val (alice, bob) = init()
        val (bob1, actions1) = bob.process(ExecuteCommand(CMD_FULFILL_HTLC(0, r1)))
        val fulfill = actions1.findOutgoingMessage<UpdateFulfillHtlc>()
        val (alice1, _) = alice.process(MessageReceived(fulfill))
        val (bob2, alice2) = signAndRevack(bob1, alice1)
        val (alice3, _) = alice2.process(ExecuteCommand(CMD_SIGN))
        assertTrue { alice3 is ShuttingDown && alice3.commitments.remoteNextCommitInfo.isLeft }
    }

    @Test
    fun `recv CMD_SIGN (no changes)`() {
        val (_, bob) = init()
        val (bob1, actions1) = bob.process(ExecuteCommand(CMD_SIGN))
        assertEquals(bob, bob1)
        assertTrue { actions1.isEmpty() }
    }

    companion object {
        val r1 = randomBytes32()
        val r2 = randomBytes32()

        fun init(currentBlockHeight: Long = 0L): Pair<ShuttingDown, ShuttingDown> {
            val (alice, bob) = TestsHelper.reachNormal(ChannelVersion.STANDARD)
            val (_, cmdAdd1) = TestsHelper.makeCmdAdd(300000000.msat, alice.staticParams.nodeParams.nodeId, currentBlockHeight, r1)
            val (alice1, actions) = alice.process(ExecuteCommand(cmdAdd1))
            val htlc1 = actions.findOutgoingMessage<UpdateAddHtlc>()
            val (bob1, _) = bob.process(MessageReceived(htlc1))

            val (_, cmdAdd2) = TestsHelper.makeCmdAdd(200000000.msat, alice.staticParams.nodeParams.nodeId, currentBlockHeight, r2)
            val (alice2, actions3) = alice1.process(ExecuteCommand(cmdAdd2))
            val htlc2 = actions3.findOutgoingMessage<UpdateAddHtlc>()
            val (bob2, _) = bob1.process(MessageReceived(htlc2))

            // Alice signs
            val (alice3, bob3) = signAndRevack(alice2, bob2)

            // Bob signs back
            val (bob4, alice4) = signAndRevack(bob3, alice3)

            // Alice initiates a closing
            val (alice5, actions5) = alice4.process(ExecuteCommand(CMD_CLOSE(null)))
            val shutdown = actions5.findOutgoingMessage<Shutdown>()
            val (bob5, actions6) = bob4.process(MessageReceived(shutdown))
            val shutdown1 = actions6.findOutgoingMessage<Shutdown>()
            val (alice6, _) = alice5.process(MessageReceived(shutdown1))
            assertTrue { alice6 is ShuttingDown }
            assertTrue { bob5 is ShuttingDown }
            return Pair(alice6 as ShuttingDown, bob5 as ShuttingDown)
        }
    }
}
