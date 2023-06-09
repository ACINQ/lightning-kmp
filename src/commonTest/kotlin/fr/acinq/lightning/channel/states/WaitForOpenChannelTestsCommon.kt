package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.*
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WaitForOpenChannelTestsCommon : LightningTestSuite() {

    @Test
    fun `recv OpenChannel -- without wumbo`() {
        val (_, bob, open) = TestsHelper.init(aliceFeatures = TestConstants.Alice.nodeParams.features.remove(Feature.Wumbo))
        assertEquals(open.pushAmount, TestConstants.alicePushAmount)
        assertEquals(open.tlvStream.get(), ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.AnchorOutputs))
        val (bob1, actions) = bob.process(ChannelCommand.MessageReceived(open))
        assertIs<LNChannel<WaitForFundingCreated>>(bob1)
        assertEquals(3, actions.size)
        assertTrue(bob1.state.channelConfig.hasOption(ChannelConfigOption.FundingPubKeyBasedChannelKeyPath))
        assertEquals(bob1.state.channelFeatures, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs, Feature.DualFunding)))
        actions.hasOutgoingMessage<AcceptDualFundedChannel>()
        actions.has<ChannelAction.ChannelId.IdAssigned>()
        assertEquals(ChannelEvents.Creating(bob1.state), actions.find<ChannelAction.EmitEvent>().event)
    }

    @Test
    fun `recv OpenChannel -- wumbo`() {
        val (_, bob, open) = TestsHelper.init(aliceFundingAmount = 20_000_000.sat)
        assertEquals(open.pushAmount, TestConstants.alicePushAmount)
        val (bob1, actions) = bob.process(ChannelCommand.MessageReceived(open))
        assertIs<LNChannel<WaitForFundingCreated>>(bob1)
        assertEquals(3, actions.size)
        assertEquals(bob1.state.channelFeatures, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs, Feature.DualFunding)))
        actions.hasOutgoingMessage<AcceptDualFundedChannel>()
        actions.has<ChannelAction.ChannelId.IdAssigned>()
        assertEquals(ChannelEvents.Creating(bob1.state), actions.find<ChannelAction.EmitEvent>().event)
    }

    @Test
    fun `recv OpenChannel -- zero conf -- zero reserve`() {
        val (_, bob, open) = TestsHelper.init(channelType = ChannelType.SupportedChannelType.AnchorOutputsZeroReserve, zeroConf = true)
        val (bob1, actions) = bob.process(ChannelCommand.MessageReceived(open))
        assertIs<LNChannel<WaitForFundingCreated>>(bob1)
        assertEquals(3, actions.size)
        assertTrue(bob1.state.channelConfig.hasOption(ChannelConfigOption.FundingPubKeyBasedChannelKeyPath))
        assertEquals(bob1.state.channelFeatures, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs, Feature.ZeroReserveChannels, Feature.DualFunding)))
        val accept = actions.hasOutgoingMessage<AcceptDualFundedChannel>()
        assertEquals(0, accept.minimumDepth)
        actions.has<ChannelAction.ChannelId.IdAssigned>()
        assertEquals(ChannelEvents.Creating(bob1.state), actions.find<ChannelAction.EmitEvent>().event)
    }

    @Test
    fun `recv OpenChannel -- missing channel type`() {
        val (_, bob, open) = TestsHelper.init()
        val open1 = open.copy(tlvStream = TlvStream.empty())
        val (bob1, actions) = bob.process(ChannelCommand.MessageReceived(open1))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, MissingChannelType(open.temporaryChannelId).message))
        assertIs<LNChannel<Aborted>>(bob1)
    }

    @Test
    fun `recv OpenChannel -- invalid channel type`() {
        val (_, bob, open) = TestsHelper.init()
        val unsupportedChannelType = ChannelType.UnsupportedChannelType(Features(Feature.StaticRemoteKey to FeatureSupport.Mandatory))
        val open1 = open.copy(tlvStream = TlvStream(ChannelTlv.ChannelTypeTlv(unsupportedChannelType)))
        val (bob1, actions) = bob.process(ChannelCommand.MessageReceived(open1))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, InvalidChannelType(open.temporaryChannelId, ChannelType.SupportedChannelType.AnchorOutputsZeroReserve, unsupportedChannelType).message))
        assertIs<LNChannel<Aborted>>(bob1)
    }

    @Test
    fun `recv OpenChannel -- invalid chain`() {
        val (_, bob, open) = TestsHelper.init()
        val open1 = open.copy(chainHash = Block.LivenetGenesisBlock.hash)
        val (bob1, actions) = bob.process(ChannelCommand.MessageReceived(open1))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, InvalidChainHash(open.temporaryChannelId, bob.staticParams.nodeParams.chainHash, Block.LivenetGenesisBlock.hash).message))
        assertIs<LNChannel<Aborted>>(bob1)
    }

    @Test
    fun `recv OpenChannel -- invalid max accepted htlcs`() {
        val (_, bob, open) = TestsHelper.init()
        val open1 = open.copy(maxAcceptedHtlcs = Channel.MAX_ACCEPTED_HTLCS + 1)
        val (bob1, actions) = bob.process(ChannelCommand.MessageReceived(open1))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, InvalidMaxAcceptedHtlcs(open.temporaryChannelId, Channel.MAX_ACCEPTED_HTLCS + 1, Channel.MAX_ACCEPTED_HTLCS).message))
        assertIs<LNChannel<Aborted>>(bob1)
    }

    @Test
    fun `recv OpenChannel -- invalid push_amount`() {
        val (_, bob, open) = TestsHelper.init(aliceFundingAmount = TestConstants.aliceFundingAmount, alicePushAmount = TestConstants.aliceFundingAmount.toMilliSatoshi() + 1.msat)
        assertEquals(open.pushAmount, TestConstants.aliceFundingAmount.toMilliSatoshi() + 1.msat)
        val (bob1, actions) = bob.process(ChannelCommand.MessageReceived(open))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, InvalidPushAmount(open.temporaryChannelId, open.fundingAmount.toMilliSatoshi() + 1.msat, open.fundingAmount.toMilliSatoshi()).message))
        assertIs<LNChannel<Aborted>>(bob1)
    }

    @Test
    fun `recv OpenChannel -- to_self_delay too high`() {
        val (_, bob, open) = TestsHelper.init()
        val invalidToSelfDelay = bob.staticParams.nodeParams.maxToLocalDelayBlocks + CltvExpiryDelta(10)
        val open1 = open.copy(toSelfDelay = invalidToSelfDelay)
        val (bob1, actions) = bob.process(ChannelCommand.MessageReceived(open1))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, ToSelfDelayTooHigh(open.temporaryChannelId, invalidToSelfDelay, bob.staticParams.nodeParams.maxToLocalDelayBlocks).message))
        assertIs<LNChannel<Aborted>>(bob1)
    }

    @Test
    fun `recv OpenChannel -- zero-reserve below dust`() {
        val (_, bob, open) = TestsHelper.init(bobFeatures = TestConstants.Bob.nodeParams.features.add(Feature.ZeroReserveChannels to FeatureSupport.Optional))
        val (bob1, actions) = bob.process(ChannelCommand.MessageReceived(open))
        assertIs<LNChannel<WaitForFundingCreated>>(bob1)
        actions.hasOutgoingMessage<AcceptDualFundedChannel>()
    }

    @Test
    fun `recv OpenChannel -- dust limit too high`() {
        val (_, bob, open) = TestsHelper.init()
        val dustLimitTooHigh = 2000.sat
        val open1 = open.copy(dustLimit = dustLimitTooHigh)
        val (bob1, actions) = bob.process(ChannelCommand.MessageReceived(open1))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, DustLimitTooLarge(open.temporaryChannelId, dustLimitTooHigh, bob.staticParams.nodeParams.maxRemoteDustLimit).message))
        assertIs<LNChannel<Aborted>>(bob1)
    }

    @Test
    fun `recv Error`() {
        val (_, bob, _) = TestsHelper.init()
        val (bob1, actions) = bob.process(ChannelCommand.MessageReceived(Error(ByteVector32.Zeroes, "oops")))
        assertIs<LNChannel<Aborted>>(bob1)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `recv Disconnected`() {
        val (_, bob, _) = TestsHelper.init()
        val (bob1, actions) = bob.process(ChannelCommand.Disconnected)
        assertIs<LNChannel<WaitForOpenChannel>>(bob1)
        assertTrue(actions.isEmpty())
    }

}
