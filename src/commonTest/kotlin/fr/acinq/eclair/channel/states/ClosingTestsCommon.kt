package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.channel.TestsHelper.findOutgoingMessage
import fr.acinq.eclair.channel.TestsHelper.reachNormal
import fr.acinq.eclair.utils.UUID
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.wire.ClosingSigned
import fr.acinq.eclair.wire.Shutdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClosingTestsCommon {
    @Test
    fun `start fee negotiation from configured block target`() {
        val (alice, bob) = reachNormal()
        val (alice1, actions) = alice.process(ExecuteCommand(CMD_CLOSE(null)))
        val shutdown = actions.findOutgoingMessage<Shutdown>()
        val (_, actions1) = bob.process(MessageReceived(shutdown))
        val shutdown1 = actions1.findOutgoingMessage<Shutdown>()
        val (alice2, actions2) = alice1.process(MessageReceived(shutdown1))
        val closingSigned = actions2.findOutgoingMessage<ClosingSigned>()
        val expectedProposedFee = Helpers.Closing.firstClosingFee(
            (alice2 as Negotiating).commitments,
            alice2.localShutdown.scriptPubKey.toByteArray(),
            alice2.remoteShutdown.scriptPubKey.toByteArray(),
            alice2.currentOnchainFeerates.mutualCloseFeeratePerKw
        )
        assertEquals(closingSigned.feeSatoshis, expectedProposedFee)
    }

    @Test
    fun `recv CMD_ADD_HTLC`() {
        val (alice, _) = init()
        val (_, actions) = alice.process(ExecuteCommand(CMD_ADD_HTLC(1000000.msat, ByteVector32.Zeroes, CltvExpiryDelta(144).toCltvExpiry(alice.currentBlockHeight.toLong()), TestConstants.emptyOnionPacket, UUID.randomUUID())))
        assertTrue {
            actions.size == 1 &&
                    (actions.first() as HandleCommandFailed).error is AddHtlcFailed &&
                    ((actions.first() as HandleCommandFailed).error as AddHtlcFailed).t == ChannelUnavailable(alice.channelId)
        }
    }

    @Test
    fun `recv CMD_FULFILL_HTLC (unexisting htlc)`() {
        val (alice, _) = init()
        val (_, actions) = alice.process(ExecuteCommand(CMD_FULFILL_HTLC(1, ByteVector32.Zeroes)))
        assertTrue { actions.size == 1 && (actions.first() as HandleCommandFailed).error is UnknownHtlcId }
    }

    companion object {
        fun init(): Pair<Closing, Closing> {
            val (alice, bob, closingSigned) = NegotiatingTestsCommon.init()
            val (alice1, bob1) = NegotiatingTestsCommon.converge(alice, bob, closingSigned)!!
            return Pair(alice1, bob1)
        }
    }
}