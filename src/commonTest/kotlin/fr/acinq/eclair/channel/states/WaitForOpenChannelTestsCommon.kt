package fr.acinq.eclair.channel.states

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.channel.*
import fr.acinq.eclair.utils.sat
import fr.acinq.eclair.wire.ChannelTlv
import fr.acinq.eclair.wire.Error
import fr.acinq.eclair.wire.TlvStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaitForOpenChannelTestsCommon {
    @Test
    fun `recv OpenChannel`() {
        val (_, b, open) = TestsHelper.init(ChannelVersion.STANDARD, 0, 1000000.sat)
        val bob = b as ChannelState
        assertEquals(TlvStream(listOf(ChannelTlv.UpfrontShutdownScript(ByteVector.empty))), open.tlvStream)
        val (bob1, _) = bob.process(MessageReceived(open))
        assertTrue { bob1 is WaitForFundingCreated }
    }

    @Test
    fun `recv OpenChannel (invalid chain)`() {
        val (_, b, open) = TestsHelper.init(ChannelVersion.STANDARD, 0, 1000000.sat)
        val bob = b as ChannelState
        val open1 = open.copy(chainHash = Block.LivenetGenesisBlock.hash)
        val (bob1, actions) = bob.process(MessageReceived(open1))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, InvalidChainHash(open.temporaryChannelId, bob.staticParams.nodeParams.chainHash, Block.LivenetGenesisBlock.hash).message))
        assertTrue { bob1 is Aborted }
    }

    @Test
    fun `recv OpenChannel (funding too low)`() {
        val (a, b, open) = TestsHelper.init(ChannelVersion.STANDARD, 0, 100.sat)
        val alice = a as ChannelState
        val bob = b as ChannelState
        val (bob1, actions) = bob.process(MessageReceived(open))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, InvalidFundingAmount(open.temporaryChannelId, 100.sat, alice.staticParams.nodeParams.minFundingSatoshis, bob.staticParams.nodeParams.maxFundingSatoshis).message))
        assertTrue { bob1 is Aborted }
    }

    @Test
    fun `recv OpenChannel (reserve below dust)`() {
        val (alice, bob, open) = TestsHelper.init(ChannelVersion.STANDARD, 0, 1000000.sat)
        val reserveTooSmall = open.dustLimitSatoshis - 1.sat
        val open1 = open.copy(channelReserveSatoshis = reserveTooSmall)
        val (bob1, actions) = bob.process(MessageReceived(open1))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, DustLimitTooLarge(open.temporaryChannelId, open.dustLimitSatoshis, reserveTooSmall).message))
        assertTrue { bob1 is Aborted }
    }

    @Test
    fun `recv OpenChannel (reserve below dust, zero-reserve channel)`() {
        val (alice, bob, open) = TestsHelper.init(ChannelVersion.STANDARD or ChannelVersion.ZERO_RESERVE, 0, 1000000.sat)
        assertEquals(Satoshi(0), open.channelReserveSatoshis)
        val (bob1, actions) = bob.process(MessageReceived(open))
        assertTrue { bob1 is WaitForFundingCreated }
    }

    @Test
    fun `recv Error`() {
        val (_, bob, _) = TestsHelper.init(ChannelVersion.STANDARD, 0, 100.sat)
        val (bob1, _) = bob.process(MessageReceived(Error(ByteVector32.Zeroes, "oops")))
        assertTrue { bob1 is Aborted }
    }

    @Test
    fun `recv CMD_CLOSE`() {
        val (_, bob, _) = TestsHelper.init(ChannelVersion.STANDARD, 0, 100.sat)
        val (bob1, _) = bob.process(ExecuteCommand(CMD_CLOSE(null)))
        assertTrue { bob1 is Aborted }
    }
}
