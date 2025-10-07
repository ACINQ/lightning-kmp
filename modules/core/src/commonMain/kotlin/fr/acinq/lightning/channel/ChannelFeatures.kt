package fr.acinq.lightning.channel

import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.secp256k1.Hex

/**
 * Subset of Bolt 9 features used to configure a channel and applicable over the lifetime of that channel.
 * Even if one of these features is later disabled at the connection level, it will still apply to the channel.
 */
data class ChannelFeatures(val features: Set<Feature>) {

    /**
     * Main constructor, to be used when a new channel is created. Features from the channel type are included, but
     * we also add other permanent features from the init messages.
     */
    constructor(channelType: ChannelType, localFeatures: Features, remoteFeatures: Features) : this(
        buildSet {
            addAll(channelType.permanentChannelFeatures)
            addAll(permanentChannelFeatures.filter { Features.canUseFeature(localFeatures, remoteFeatures, it) })
        }
    )

    fun hasFeature(feature: Feature): Boolean = features.contains(feature)

    override fun toString(): String = features.joinToString(",")

    companion object {
        /**
         * In addition to channel types features, the following features will be added to the permanent channel features if they
         * are supported by both peers.
         */
        private val permanentChannelFeatures: Set<Feature> = setOf(Feature.DualFunding)
    }

}

/** A channel type is a specific set of feature bits that represent persistent channel features as defined in Bolt 2. */
sealed class ChannelType {

    abstract val name: String
    abstract val features: Set<Feature>
    abstract val permanentChannelFeatures: Set<Feature>
    abstract val commitmentFormat: Transactions.CommitmentFormat

    override fun toString(): String = name

    sealed class SupportedChannelType : ChannelType() {

        fun toFeatures(): Features = Features(features.associateWith { FeatureSupport.Mandatory })

        object AnchorOutputs : SupportedChannelType() {
            override val name: String get() = "anchor_outputs"
            override val features: Set<Feature> get() = setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs)
            override val permanentChannelFeatures: Set<Feature> get() = setOf()
            override val commitmentFormat: Transactions.CommitmentFormat get() = Transactions.CommitmentFormat.AnchorOutputs
        }

        object AnchorOutputsZeroReserve : SupportedChannelType() {
            override val name: String get() = "anchor_outputs_zero_reserve"
            override val features: Set<Feature> get() = setOf(Feature.StaticRemoteKey, Feature.AnchorOutputs, Feature.ZeroReserveChannels)
            override val permanentChannelFeatures: Set<Feature> get() = setOf(Feature.ZeroReserveChannels)
            override val commitmentFormat: Transactions.CommitmentFormat get() = Transactions.CommitmentFormat.AnchorOutputs
        }

        object SimpleTaprootChannels : SupportedChannelType() {
            override val name: String get() = "simple_taproot_channel"
            override val features: Set<Feature> get() = setOf(Feature.SimpleTaprootChannels, Feature.ZeroReserveChannels)
            override val permanentChannelFeatures: Set<Feature> get() = setOf(Feature.ZeroReserveChannels)
            override val commitmentFormat: Transactions.CommitmentFormat get() = Transactions.CommitmentFormat.SimpleTaprootChannels
        }
    }

    data class UnsupportedChannelType(val featureBits: Features) : ChannelType() {
        override val name: String get() = "0x${Hex.encode(featureBits.toByteArray())}"
        override val features: Set<Feature> get() = featureBits.activated.keys
        override val permanentChannelFeatures: Set<Feature> get() = setOf()
        override val commitmentFormat: Transactions.CommitmentFormat get() = Transactions.CommitmentFormat.AnchorOutputs
    }

    companion object {

        // NB: Bolt 2: features must exactly match in order to identify a channel type.
        fun fromFeatures(features: Features): ChannelType = when (features) {
            // @formatter:off
            Features(Feature.SimpleTaprootChannels to FeatureSupport.Mandatory, Feature.ZeroReserveChannels to FeatureSupport.Mandatory) -> SupportedChannelType.SimpleTaprootChannels
            Features(Feature.StaticRemoteKey to FeatureSupport.Mandatory, Feature.AnchorOutputs to FeatureSupport.Mandatory, Feature.ZeroReserveChannels to FeatureSupport.Mandatory) -> SupportedChannelType.AnchorOutputsZeroReserve
            Features(Feature.StaticRemoteKey to FeatureSupport.Mandatory, Feature.AnchorOutputs to FeatureSupport.Mandatory) -> SupportedChannelType.AnchorOutputs
            else -> UnsupportedChannelType(features)
            // @formatter:on
        }

    }

}