package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.*
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toMilliSatoshi
import fr.acinq.lightning.wire.*
import kotlin.test.*

class WaitForAcceptChannelTestsCommon : LightningTestSuite() {

    @Test
    fun `recv AcceptChannel`() {
        val (alice, _, accept) = init()
        val (alice1, actions1) = alice.process(ChannelCommand.MessageReceived(accept))
        assertIs<LNChannel<WaitForFundingCreated>>(alice1)
        assertEquals(3, actions1.size)
        actions1.find<ChannelAction.ChannelId.IdAssigned>()
        assertEquals(ChannelEvents.Creating(alice1.state), actions1.find<ChannelAction.EmitEvent>().event)
        val txAddInput = actions1.findOutgoingMessage<TxAddInput>()
        assertNotEquals(txAddInput.channelId, accept.temporaryChannelId)
        assertEquals(alice1.channelId, txAddInput.channelId)
        assertEquals(alice1.state.channelFeatures, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs)))
    }

    @Test
    fun `recv AcceptChannel -- without non-initiator contribution`() {
        val (alice, _, accept) = init(bobFundingAmount = 0.sat)
        val (alice1, actions1) = alice.process(ChannelCommand.MessageReceived(accept))
        assertIs<LNChannel<WaitForFundingCreated>>(alice1)
        assertEquals(3, actions1.size)
        actions1.find<ChannelAction.ChannelId.IdAssigned>()
        actions1.findOutgoingMessage<TxAddInput>()
        assertEquals(alice1.state.channelFeatures, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs)))
        assertEquals(ChannelEvents.Creating(alice1.state), actions1.find<ChannelAction.EmitEvent>().event)
    }

    @Test
    fun `recv AcceptChannel -- zero conf`() {
        val (alice, _, accept) = init(channelType = ChannelType.SupportedChannelType.AnchorOutputsZeroReserve, zeroConf = true)
        assertEquals(0, accept.minimumDepth)
        val (alice1, actions1) = alice.process(ChannelCommand.MessageReceived(accept))
        assertIs<LNChannel<WaitForFundingCreated>>(alice1)
        assertEquals(3, actions1.size)
        actions1.find<ChannelAction.ChannelId.IdAssigned>()
        assertEquals(ChannelEvents.Creating(alice1.state), actions1.find<ChannelAction.EmitEvent>().event)
        actions1.findOutgoingMessage<TxAddInput>()
        assertEquals(alice1.state.channelFeatures, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs, Feature.ZeroReserveChannels)))
    }

    @Test
    fun `recv AcceptChannel -- missing channel type`() {
        val (alice, _, accept) = init()
        val (alice1, actions1) = alice.process(ChannelCommand.MessageReceived(accept.copy(tlvStream = TlvStream(listOf()))))
        assertIs<LNChannel<Aborted>>(alice1)
        val error = actions1.hasOutgoingMessage<Error>()
        assertEquals(error, Error(accept.temporaryChannelId, MissingChannelType(accept.temporaryChannelId).message))
    }

    @Test
    fun `recv AcceptChannel -- invalid channel type`() {
        val (alice, _, accept) = init()
        val (alice1, actions1) = alice.process(ChannelCommand.MessageReceived(accept.copy(tlvStream = TlvStream(listOf(ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.Standard))))))
        assertIs<LNChannel<Aborted>>(alice1)
        val error = actions1.hasOutgoingMessage<Error>()
        assertEquals(error, Error(accept.temporaryChannelId, InvalidChannelType(accept.temporaryChannelId, ChannelType.SupportedChannelType.AnchorOutputs, ChannelType.SupportedChannelType.Standard).message))
    }

    @Test
    fun `recv AcceptChannel -- funding too high`() {
        val (alice, _, accept) = init(bobFundingAmount = 30_000_000.sat)
        val (alice1, actions) = alice.process(ChannelCommand.MessageReceived(accept))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(accept.temporaryChannelId, InvalidFundingAmount(accept.temporaryChannelId, 30_000_000.sat, alice.staticParams.nodeParams.minFundingSatoshis, alice.staticParams.nodeParams.maxFundingSatoshis).message))
        assertIs<LNChannel<Aborted>>(alice1)
    }

    @Test
    fun `recv AcceptChannel -- invalid max accepted htlcs`() {
        val (alice, _, accept) = init()
        // spec says max = 483
        val invalidMaxAcceptedHtlcs = 484
        val (alice1, actions1) = alice.process(ChannelCommand.MessageReceived(accept.copy(maxAcceptedHtlcs = invalidMaxAcceptedHtlcs)))
        assertIs<LNChannel<Aborted>>(alice1)
        val error = actions1.hasOutgoingMessage<Error>()
        assertEquals(error, Error(accept.temporaryChannelId, InvalidMaxAcceptedHtlcs(accept.temporaryChannelId, invalidMaxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS).message))
    }

    @Test
    fun `recv AcceptChannel -- invalid push_amount`() {
        val (alice, _, accept) = init(bobFundingAmount = TestConstants.bobFundingAmount, bobPushAmount = TestConstants.bobFundingAmount.toMilliSatoshi() + 1.msat)
        assertEquals(accept.pushAmount, TestConstants.bobFundingAmount.toMilliSatoshi() + 1.msat)
        val (alice1, actions) = alice.process(ChannelCommand.MessageReceived(accept))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(accept.temporaryChannelId, InvalidPushAmount(accept.temporaryChannelId, accept.fundingAmount.toMilliSatoshi() + 1.msat, accept.fundingAmount.toMilliSatoshi()).message))
        assertIs<LNChannel<Aborted>>(alice1)
    }

    @Test
    fun `recv AcceptChannel -- dust limit too low`() {
        val (alice, _, accept) = init()
        // we don't want their dust limit to be below 354
        val lowDustLimitSatoshis = 353.sat
        // but we only enforce it on mainnet
        val aliceMainnet = alice.copy(ctx = alice.ctx.copy(staticParams = alice.ctx.staticParams.copy(nodeParams = alice.staticParams.nodeParams.copy(chainHash = Block.LivenetGenesisBlock.hash))))
        val (alice1, actions1) = aliceMainnet.process(ChannelCommand.MessageReceived(accept.copy(dustLimit = lowDustLimitSatoshis)))
        assertIs<LNChannel<Aborted>>(alice1)
        val error = actions1.hasOutgoingMessage<Error>()
        assertEquals(error, Error(accept.temporaryChannelId, DustLimitTooSmall(accept.temporaryChannelId, lowDustLimitSatoshis, Channel.MIN_DUST_LIMIT).message))
    }

    @Test
    fun `recv AcceptChannel -- dust limit too high`() {
        val (alice, _, accept) = init()
        // we don't want their dust limit to be too high
        val highDustLimitSatoshis = 2000.sat
        val (alice1, actions1) = alice.process(ChannelCommand.MessageReceived(accept.copy(dustLimit = highDustLimitSatoshis)))
        assertIs<LNChannel<Aborted>>(alice1)
        val error = actions1.hasOutgoingMessage<Error>()
        assertEquals(error, Error(accept.temporaryChannelId, DustLimitTooLarge(accept.temporaryChannelId, highDustLimitSatoshis, alice.staticParams.nodeParams.maxRemoteDustLimit).message))
    }

    @Test
    fun `recv AcceptChannel -- to_self_delay too high`() {
        val (alice, _, accept) = init()
        val delayTooHigh = CltvExpiryDelta(10_000)
        val (alice1, actions1) = alice.process(ChannelCommand.MessageReceived(accept.copy(toSelfDelay = delayTooHigh)))
        assertIs<LNChannel<Aborted>>(alice1)
        val error = actions1.hasOutgoingMessage<Error>()
        assertEquals(error, Error(accept.temporaryChannelId, ToSelfDelayTooHigh(accept.temporaryChannelId, delayTooHigh, alice.staticParams.nodeParams.maxToLocalDelayBlocks).message))
    }

    @Test
    fun `recv Error`() {
        val (alice, _, _) = init()
        val (alice1, actions1) = alice.process(ChannelCommand.MessageReceived(Error(ByteVector32.Zeroes, "oops")))
        assertIs<LNChannel<Aborted>>(alice1)
        assertTrue(actions1.isEmpty())
    }

    @Test
    fun `recv CMD_CLOSE`() {
        val (alice, _, _) = init()
        val (alice1, actions1) = alice.process(ChannelCommand.ExecuteCommand(CMD_CLOSE(null, null)))
        assertIs<LNChannel<Aborted>>(alice1)
        assertTrue(actions1.isEmpty())
    }

    companion object {
        fun init(
            channelType: ChannelType.SupportedChannelType = ChannelType.SupportedChannelType.AnchorOutputs,
            aliceFeatures: Features = TestConstants.Alice.nodeParams.features,
            bobFeatures: Features = TestConstants.Bob.nodeParams.features,
            currentHeight: Int = TestConstants.defaultBlockHeight,
            aliceFundingAmount: Satoshi = TestConstants.aliceFundingAmount,
            bobFundingAmount: Satoshi = TestConstants.bobFundingAmount,
            alicePushAmount: MilliSatoshi = TestConstants.alicePushAmount,
            bobPushAmount: MilliSatoshi = TestConstants.bobPushAmount,
            zeroConf: Boolean = false,
        ): Triple<LNChannel<WaitForAcceptChannel>, LNChannel<WaitForFundingCreated>, AcceptDualFundedChannel> {
            val (alice, bob, open) = TestsHelper.init(channelType, aliceFeatures, bobFeatures, currentHeight, aliceFundingAmount, bobFundingAmount, alicePushAmount, bobPushAmount, zeroConf)
            assertEquals(open.fundingAmount, aliceFundingAmount)
            assertEquals(open.pushAmount, alicePushAmount)
            assertEquals(open.tlvStream.get(), ChannelTlv.ChannelTypeTlv(channelType))
            val (bob1, actions) = bob.process(ChannelCommand.MessageReceived(open))
            assertIs<LNChannel<WaitForFundingCreated>>(bob1)
            val accept = actions.hasOutgoingMessage<AcceptDualFundedChannel>()
            assertEquals(open.temporaryChannelId, accept.temporaryChannelId)
            assertEquals(accept.fundingAmount, bobFundingAmount)
            assertEquals(accept.pushAmount, bobPushAmount)
            assertEquals(accept.tlvStream.get(), ChannelTlv.ChannelTypeTlv(channelType))
            when (zeroConf) {
                true -> assertEquals(0, accept.minimumDepth)
                false -> assertEquals(3, accept.minimumDepth)
            }
            return Triple(alice, bob1, accept)
        }
    }

}