package fr.acinq.lightning.channel

import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.tests.utils.LightningTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelFeaturesTestsCommon : LightningTestSuite() {

    @Test
    fun `channel type uses mandatory features`() {
        // @formatter:off
        assertTrue(ChannelType.SupportedChannelType.Standard.features.isEmpty())
        assertEquals(ChannelType.SupportedChannelType.StaticRemoteKey.toFeatures(), Features(Feature.StaticRemoteKey to FeatureSupport.Mandatory))
        assertEquals(ChannelType.SupportedChannelType.AnchorOutputs.toFeatures(), Features(Feature.StaticRemoteKey to FeatureSupport.Mandatory, Feature.AnchorOutputs to FeatureSupport.Mandatory))
        assertEquals(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve.toFeatures(), Features(Feature.StaticRemoteKey to FeatureSupport.Mandatory, Feature.AnchorOutputs to FeatureSupport.Mandatory, Feature.ZeroReserveChannels to FeatureSupport.Mandatory))
        // @formatter:on
    }

    @Test
    fun `extract channel type from channel features`() {
        // @formatter:off
        assertEquals(ChannelType.SupportedChannelType.Standard, ChannelFeatures(setOf()).channelType)
        assertEquals(ChannelType.SupportedChannelType.Standard, ChannelFeatures(setOf(Feature.ZeroReserveChannels)).channelType)
        assertEquals(ChannelType.SupportedChannelType.StaticRemoteKey, ChannelFeatures(setOf(Feature.StaticRemoteKey)).channelType)
        assertEquals(ChannelType.SupportedChannelType.StaticRemoteKey, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.ZeroReserveChannels)).channelType)
        assertEquals(ChannelType.SupportedChannelType.AnchorOutputs, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs)).channelType)
        assertEquals(ChannelType.SupportedChannelType.AnchorOutputs, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs)).channelType)
        assertEquals(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs, Feature.ZeroReserveChannels)).channelType)
        assertEquals(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve, ChannelFeatures(setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs, Feature.Wumbo, Feature.ZeroReserveChannels)).channelType)
        // @formatter:on
    }

    @Test
    fun `extract channel type from features`() {
        // @formatter:off
        assertEquals(ChannelType.SupportedChannelType.Standard, ChannelType.fromFeatures(Features.empty))
        assertEquals(ChannelType.SupportedChannelType.StaticRemoteKey, ChannelType.fromFeatures(Features(Feature.StaticRemoteKey to FeatureSupport.Mandatory)))
        assertEquals(ChannelType.SupportedChannelType.AnchorOutputs, ChannelType.fromFeatures(Features(Feature.StaticRemoteKey to FeatureSupport.Mandatory, Feature.AnchorOutputs to FeatureSupport.Mandatory)))
        assertEquals(ChannelType.SupportedChannelType.AnchorOutputsZeroReserve, ChannelType.fromFeatures(Features(Feature.StaticRemoteKey to FeatureSupport.Mandatory, Feature.AnchorOutputs to FeatureSupport.Mandatory, Feature.ZeroReserveChannels to FeatureSupport.Mandatory)))
        // @formatter:on
        // Bolt 2 mandates that features match exactly.
        listOf(
            Features(Feature.ZeroReserveChannels to FeatureSupport.Optional),
            Features(Feature.ZeroReserveChannels to FeatureSupport.Mandatory),
            Features(Feature.StaticRemoteKey to FeatureSupport.Optional),
            Features(Feature.StaticRemoteKey to FeatureSupport.Mandatory, Feature.PaymentSecret to FeatureSupport.Mandatory),
            Features(Feature.StaticRemoteKey to FeatureSupport.Optional, Feature.AnchorOutputs to FeatureSupport.Optional),
            Features(Feature.StaticRemoteKey to FeatureSupport.Optional, Feature.AnchorOutputs to FeatureSupport.Mandatory),
            Features(Feature.StaticRemoteKey to FeatureSupport.Mandatory, Feature.AnchorOutputs to FeatureSupport.Optional),
            Features(Feature.StaticRemoteKey to FeatureSupport.Mandatory, Feature.ZeroReserveChannels to FeatureSupport.Mandatory),
            Features(Feature.StaticRemoteKey to FeatureSupport.Mandatory, Feature.AnchorOutputs to FeatureSupport.Mandatory, Feature.Wumbo to FeatureSupport.Mandatory),
            Features(Feature.StaticRemoteKey to FeatureSupport.Mandatory, Feature.AnchorOutputs to FeatureSupport.Mandatory, Feature.Wumbo to FeatureSupport.Mandatory, Feature.ZeroReserveChannels to FeatureSupport.Mandatory),
        ).forEach { features ->
            assertEquals(ChannelType.UnsupportedChannelType(features), ChannelType.fromFeatures(features))
        }
    }

}