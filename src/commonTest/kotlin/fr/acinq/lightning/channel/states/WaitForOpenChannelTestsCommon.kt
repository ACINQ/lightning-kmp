package fr.acinq.lightning.channel.states

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.OnChainFeerates
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.channel.TestsHelper.createFunding
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.utils.toMilliSatoshi
import fr.acinq.lightning.wire.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WaitForOpenChannelTestsCommon : LightningTestSuite() {

    @Test
    fun `reject other channel types than anchor outputs`() {
        val alice = WaitForInit(
            StaticParams(TestConstants.Alice.nodeParams, TestConstants.Bob.keyManager.nodeId),
            Pair(TestConstants.defaultBlockHeight, Block.RegtestGenesisBlock.header),
            OnChainFeerates(TestConstants.feeratePerKw, TestConstants.feeratePerKw, TestConstants.feeratePerKw)
        )
        val bobInit = Init(TestConstants.Bob.nodeParams.features.toByteArray().toByteVector())
        val unsupportedChannelTypes = listOf(ChannelType.SupportedChannelType.Standard, ChannelType.SupportedChannelType.StaticRemoteKey)
        unsupportedChannelTypes.forEach { channelType ->
            val (alice1, actions1) = alice.process(
                ChannelEvent.InitInitiator(
                    createFunding(TestConstants.aliceFundingAmount, 50.sat),
                    0.msat,
                    FeeratePerKw.CommitmentFeerate,
                    TestConstants.feeratePerKw,
                    TestConstants.Alice.channelParams(),
                    bobInit,
                    0,
                    ChannelConfig.standard,
                    channelType,
                    null
                )
            )
            assertIs<Aborted>(alice1)
            assertTrue(actions1.isEmpty())
        }
    }

    @Test
    fun `recv OpenChannel -- without wumbo`() {
        val (_, bob, open) = TestsHelper.init(aliceFeatures = TestConstants.Alice.nodeParams.features.remove(Feature.Wumbo))
        assertEquals(open.pushAmount, TestConstants.pushAmount)
        assertEquals(open.tlvStream.get(), ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.AnchorOutputs))
        val (bob1, actions) = bob.process(ChannelEvent.MessageReceived(open))
        assertIs<WaitForFundingCreated>(bob1)
        assertTrue(bob1.channelConfig.hasOption(ChannelConfigOption.FundingPubKeyBasedChannelKeyPath))
        assertEquals(bob1.channelFeatures, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs)))
        actions.hasOutgoingMessage<AcceptDualFundedChannel>()
    }

    @Test
    fun `recv OpenChannel -- wumbo`() {
        val (_, bob, open) = TestsHelper.init(aliceFundingAmount = 20_000_000.sat)
        assertEquals(open.pushAmount, TestConstants.pushAmount)
        val (bob1, actions) = bob.process(ChannelEvent.MessageReceived(open))
        assertIs<WaitForFundingCreated>(bob1)
        assertEquals(bob1.channelFeatures, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs, Feature.Wumbo)))
        actions.hasOutgoingMessage<AcceptDualFundedChannel>()
    }

    @Test
    fun `recv OpenChannel -- zero conf -- zero reserve`() {
        val (_, bob, open) = TestsHelper.init(channelType = ChannelType.SupportedChannelType.AnchorOutputsZeroConfZeroReserve)
        val (bob1, actions) = bob.process(ChannelEvent.MessageReceived(open))
        assertIs<WaitForFundingCreated>(bob1)
        assertTrue(bob1.channelConfig.hasOption(ChannelConfigOption.FundingPubKeyBasedChannelKeyPath))
        assertEquals(bob1.channelFeatures, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs, Feature.Wumbo, Feature.ZeroConfChannels, Feature.ZeroReserveChannels)))
        val accept = actions.hasOutgoingMessage<AcceptDualFundedChannel>()
        assertEquals(0, accept.minimumDepth)
    }

    @Test
    fun `recv OpenChannel -- missing channel type`() {
        val (_, bob, open) = TestsHelper.init()
        val open1 = open.copy(tlvStream = TlvStream(listOf()))
        val (bob1, actions) = bob.process(ChannelEvent.MessageReceived(open1))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, MissingChannelType(open.temporaryChannelId).message))
        assertIs<Aborted>(bob1)
    }

    @Test
    fun `recv OpenChannel -- invalid channel type`() {
        val (_, bob, open) = TestsHelper.init()
        val open1 = open.copy(tlvStream = TlvStream(listOf(ChannelTlv.ChannelTypeTlv(ChannelType.SupportedChannelType.StaticRemoteKey))))
        val (bob1, actions) = bob.process(ChannelEvent.MessageReceived(open1))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, InvalidChannelType(open.temporaryChannelId, ChannelType.SupportedChannelType.AnchorOutputsZeroConfZeroReserve, ChannelType.SupportedChannelType.StaticRemoteKey).message))
        assertIs<Aborted>(bob1)
    }

    @Test
    fun `recv OpenChannel -- invalid chain`() {
        val (_, bob, open) = TestsHelper.init()
        val open1 = open.copy(chainHash = Block.LivenetGenesisBlock.hash)
        val (bob1, actions) = bob.process(ChannelEvent.MessageReceived(open1))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, InvalidChainHash(open.temporaryChannelId, bob.staticParams.nodeParams.chainHash, Block.LivenetGenesisBlock.hash).message))
        assertIs<Aborted>(bob1)
    }

    @Test
    fun `recv OpenChannel -- funding too low`() {
        val (_, bob, open) = TestsHelper.init(aliceFundingAmount = 100.sat, bobFundingAmount = 0.sat, pushAmount = 0.msat)
        val (bob1, actions) = bob.process(ChannelEvent.MessageReceived(open))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, InvalidFundingAmount(open.temporaryChannelId, 100.sat, bob.staticParams.nodeParams.minFundingSatoshis, bob.staticParams.nodeParams.maxFundingSatoshis).message))
        assertIs<Aborted>(bob1)
    }

    @Test
    fun `recv OpenChannel -- funding too high`() {
        val (_, bob, open) = TestsHelper.init(aliceFundingAmount = 30_000_000.sat)
        val (bob1, actions) = bob.process(ChannelEvent.MessageReceived(open))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, InvalidFundingAmount(open.temporaryChannelId, 30_000_000.sat, bob.staticParams.nodeParams.minFundingSatoshis, bob.staticParams.nodeParams.maxFundingSatoshis).message))
        assertIs<Aborted>(bob1)
    }

    @Test
    fun `recv OpenChannel -- invalid max accepted htlcs`() {
        val (_, bob, open) = TestsHelper.init()
        val open1 = open.copy(maxAcceptedHtlcs = Channel.MAX_ACCEPTED_HTLCS + 1)
        val (bob1, actions) = bob.process(ChannelEvent.MessageReceived(open1))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, InvalidMaxAcceptedHtlcs(open.temporaryChannelId, Channel.MAX_ACCEPTED_HTLCS + 1, Channel.MAX_ACCEPTED_HTLCS).message))
        assertIs<Aborted>(bob1)
    }

    @Test
    fun `recv OpenChannel -- invalid push_amount`() {
        val (_, bob, open) = TestsHelper.init(aliceFundingAmount = TestConstants.aliceFundingAmount, pushAmount = TestConstants.aliceFundingAmount.toMilliSatoshi() + 1.msat)
        assertEquals(open.pushAmount, TestConstants.aliceFundingAmount.toMilliSatoshi() + 1.msat)
        val (bob1, actions) = bob.process(ChannelEvent.MessageReceived(open))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, InvalidPushAmount(open.temporaryChannelId, open.fundingAmount.toMilliSatoshi() + 1.msat, open.fundingAmount.toMilliSatoshi()).message))
        assertIs<Aborted>(bob1)
    }

    @Test
    fun `recv OpenChannel -- to_self_delay too high`() {
        val (_, bob, open) = TestsHelper.init()
        val invalidToSelfDelay = bob.staticParams.nodeParams.maxToLocalDelayBlocks + CltvExpiryDelta(10)
        val open1 = open.copy(toSelfDelay = invalidToSelfDelay)
        val (bob1, actions) = bob.process(ChannelEvent.MessageReceived(open1))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, ToSelfDelayTooHigh(open.temporaryChannelId, invalidToSelfDelay, bob.staticParams.nodeParams.maxToLocalDelayBlocks).message))
        assertIs<Aborted>(bob1)
    }

    @Test
    fun `recv OpenChannel -- zero-reserve below dust`() {
        val (_, bob, open) = TestsHelper.init(bobFeatures = TestConstants.Bob.nodeParams.features.add(Feature.ZeroReserveChannels to FeatureSupport.Optional))
        val (bob1, actions) = bob.process(ChannelEvent.MessageReceived(open))
        assertIs<WaitForFundingCreated>(bob1)
        actions.hasOutgoingMessage<AcceptDualFundedChannel>()
    }

    @Test
    fun `recv OpenChannel -- dust limit too high`() {
        val (_, bob, open) = TestsHelper.init()
        val dustLimitTooHigh = 2000.sat
        val open1 = open.copy(dustLimit = dustLimitTooHigh)
        val (bob1, actions) = bob.process(ChannelEvent.MessageReceived(open1))
        val error = actions.findOutgoingMessage<Error>()
        assertEquals(error, Error(open.temporaryChannelId, DustLimitTooLarge(open.temporaryChannelId, dustLimitTooHigh, bob.staticParams.nodeParams.maxRemoteDustLimit).message))
        assertIs<Aborted>(bob1)
    }

    @Test
    fun `recv Error`() {
        val (_, bob, _) = TestsHelper.init()
        val (bob1, actions) = bob.process(ChannelEvent.MessageReceived(Error(ByteVector32.Zeroes, "oops")))
        assertIs<Aborted>(bob1)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `recv CMD_CLOSE`() {
        val (_, bob, _) = TestsHelper.init()
        val (bob1, actions) = bob.process(ChannelEvent.ExecuteCommand(CMD_CLOSE(null, null)))
        assertIs<Aborted>(bob1)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `recv Disconnected`() {
        val (_, bob, _) = TestsHelper.init()
        val (bob1, actions) = bob.process(ChannelEvent.Disconnected)
        assertIs<WaitForOpenChannel>(bob1)
        assertTrue(actions.isEmpty())
    }

}
