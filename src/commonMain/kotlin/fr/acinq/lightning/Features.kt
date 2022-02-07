package fr.acinq.lightning

import fr.acinq.bitcoin.ByteVector
import fr.acinq.lightning.utils.BitField
import fr.acinq.lightning.utils.leftPaddedCopyOf
import fr.acinq.lightning.utils.or
import kotlinx.serialization.Serializable

enum class FeatureSupport {
    Mandatory {
        override fun toString() = "mandatory"
    },
    Optional {
        override fun toString() = "optional"
    }
}

@Serializable
sealed class Feature {

    abstract val rfcName: String
    abstract val mandatory: Int
    val optional: Int get() = mandatory + 1

    fun supportBit(support: FeatureSupport): Int = when (support) {
        FeatureSupport.Mandatory -> mandatory
        FeatureSupport.Optional -> optional
    }

    override fun toString() = rfcName

    @Serializable
    object OptionDataLossProtect : Feature() {
        override val rfcName get() = "option_data_loss_protect"
        override val mandatory get() = 0
    }

    @Serializable
    object InitialRoutingSync : Feature() {
        override val rfcName get() = "initial_routing_sync"

        // reserved but not used as per lightningnetwork/lightning-rfc/pull/178
        override val mandatory get() = 2
    }

    @Serializable
    object ChannelRangeQueries : Feature() {
        override val rfcName get() = "gossip_queries"
        override val mandatory get() = 6
    }

    @Serializable
    object VariableLengthOnion : Feature() {
        override val rfcName get() = "var_onion_optin"
        override val mandatory get() = 8
    }

    @Serializable
    object ChannelRangeQueriesExtended : Feature() {
        override val rfcName get() = "gossip_queries_ex"
        override val mandatory get() = 10
    }

    @Serializable
    object StaticRemoteKey : Feature() {
        override val rfcName get() = "option_static_remotekey"
        override val mandatory get() = 12
    }

    @Serializable
    object PaymentSecret : Feature() {
        override val rfcName get() = "payment_secret"
        override val mandatory get() = 14
    }

    @Serializable
    object BasicMultiPartPayment : Feature() {
        override val rfcName get() = "basic_mpp"
        override val mandatory get() = 16
    }

    @Serializable
    object Wumbo : Feature() {
        override val rfcName get() = "option_support_large_channel"
        override val mandatory get() = 18
    }

    @Serializable
    object AnchorOutputs : Feature() {
        override val rfcName get() = "option_anchor_outputs"
        override val mandatory get() = 20
    }

    @Serializable
    object ShutdownAnySegwit : Feature() {
        override val rfcName get() = "option_shutdown_anysegwit"
        override val mandatory get() = 26
    }

    @Serializable
    object ChannelType : Feature() {
        override val rfcName get() = "option_channel_type"
        override val mandatory get() = 44
    }

    // The following features have not been standardised, hence the high feature bits to avoid conflicts.

    @Serializable
    object TrampolinePayment : Feature() {
        override val rfcName get() = "trampoline_payment"
        override val mandatory get() = 50
    }

    /** This feature bit should be activated when a node accepts having their channel reserve set to 0. */
    @Serializable
    object ZeroReserveChannels : Feature() {
        override val rfcName get() = "zero_reserve_channels"
        override val mandatory get() = 128
    }

    /** This feature bit should be activated when a node accepts unconfirmed channels (will set min_depth to 0 in accept_channel). */
    @Serializable
    object ZeroConfChannels : Feature() {
        override val rfcName get() = "zero_conf_channels"
        override val mandatory get() = 130
    }

    /** This feature bit should be activated when a mobile node supports waking up via push notifications. */
    @Serializable
    object WakeUpNotificationClient : Feature() {
        override val rfcName get() = "wake_up_notification_client"
        override val mandatory get() = 132
    }

    /** This feature bit should be activated when a node supports waking up their peers via push notifications. */
    @Serializable
    object WakeUpNotificationProvider : Feature() {
        override val rfcName get() = "wake_up_notification_provider"
        override val mandatory get() = 134
    }

    /** This feature bit should be activated when a node accepts on-the-fly channel creation. */
    @Serializable
    object PayToOpenClient : Feature() {
        override val rfcName get() = "pay_to_open_client"
        override val mandatory get() = 136
    }

    /** This feature bit should be activated when a node supports opening channels on-the-fly when liquidity is missing to receive a payment. */
    @Serializable
    object PayToOpenProvider : Feature() {
        override val rfcName get() = "pay_to_open_provider"
        override val mandatory get() = 138
    }

    /** This feature bit should be activated when a node accepts channel creation via trusted swaps-in. */
    @Serializable
    object TrustedSwapInClient : Feature() {
        override val rfcName get() = "trusted_swap_in_client"
        override val mandatory get() = 140
    }

    /** This feature bit should be activated when a node supports opening channels in exchange for on-chain funds (swap-in). */
    @Serializable
    object TrustedSwapInProvider : Feature() {
        override val rfcName get() = "trusted_swap_in_provider"
        override val mandatory get() = 142
    }

    /** This feature bit should be activated when a node wants to send channel backups to their peers. */
    @Serializable
    object ChannelBackupClient : Feature() {
        override val rfcName get() = "channel_backup_client"
        override val mandatory get() = 144
    }

    /** This feature bit should be activated when a node stores channel backups for their peers. */
    @Serializable
    object ChannelBackupProvider : Feature() {
        override val rfcName get() = "channel_backup_provider"
        override val mandatory get() = 146
    }
}

@Serializable
data class UnknownFeature(val bitIndex: Int)

@Serializable
data class Features(val activated: Map<Feature, FeatureSupport>, val unknown: Set<UnknownFeature> = emptySet()) {

    fun hasFeature(feature: Feature, support: FeatureSupport? = null): Boolean =
        if (support != null) activated[feature] == support
        else activated.containsKey(feature)

    /** NB: this method is not reflexive, see [[Features.areCompatible]] if you want symmetric validation. */
    fun areSupported(remoteFeatures: Features): Boolean {
        // we allow unknown odd features (it's ok to be odd)
        val unknownFeaturesOk = remoteFeatures.unknown.all { it.bitIndex % 2 == 1 }
        // we verify that we activated every mandatory feature they require
        val knownFeaturesOk = remoteFeatures.activated.all { (feature, support) ->
            when (support) {
                FeatureSupport.Optional -> true
                FeatureSupport.Mandatory -> hasFeature(feature)
            }
        }
        return unknownFeaturesOk && knownFeaturesOk
    }

    fun toByteArray(): ByteArray {
        val activatedFeatureBytes = activated.map { (feature, support) -> feature.supportBit(support) }.toHashSet().indicesToByteArray()
        val unknownFeatureBytes = unknown.map { it.bitIndex }.toHashSet().indicesToByteArray()
        val maxSize = activatedFeatureBytes.size.coerceAtLeast(unknownFeatureBytes.size)
        return activatedFeatureBytes.leftPaddedCopyOf(maxSize) or unknownFeatureBytes.leftPaddedCopyOf(maxSize)
    }

    private fun Set<Int>.indicesToByteArray(): ByteArray {
        if (isEmpty()) return ByteArray(0)
        val buf = BitField.forAtMost(maxOrNull()!! + 1)
        forEach { buf.setRight(it) }
        return buf.bytes
    }

    companion object {
        val empty = Features(mapOf())

        private val knownFeatures: Set<Feature> = setOf(
            Feature.OptionDataLossProtect,
            Feature.InitialRoutingSync,
            Feature.ChannelRangeQueries,
            Feature.VariableLengthOnion,
            Feature.ChannelRangeQueriesExtended,
            Feature.StaticRemoteKey,
            Feature.PaymentSecret,
            Feature.BasicMultiPartPayment,
            Feature.Wumbo,
            Feature.AnchorOutputs,
            Feature.ShutdownAnySegwit,
            Feature.ChannelType,
            Feature.TrampolinePayment,
            Feature.ZeroReserveChannels,
            Feature.ZeroConfChannels,
            Feature.WakeUpNotificationClient,
            Feature.WakeUpNotificationProvider,
            Feature.PayToOpenClient,
            Feature.PayToOpenProvider,
            Feature.TrustedSwapInClient,
            Feature.TrustedSwapInProvider,
            Feature.ChannelBackupClient,
            Feature.ChannelBackupProvider,
        )

        operator fun invoke(bytes: ByteVector): Features = invoke(bytes.toByteArray())

        operator fun invoke(bytes: ByteArray): Features = invoke(BitField.from(bytes))

        operator fun invoke(bits: BitField): Features {
            val all = bits.asRightSequence().withIndex()
                .filter { it.value }
                .map { (idx, _) ->
                    knownFeatures.find { it.optional == idx }?.let { Pair(it, FeatureSupport.Optional) }
                        ?: knownFeatures.find { it.mandatory == idx }?.let { Pair(it, FeatureSupport.Mandatory) }
                        ?: UnknownFeature(idx)
                }
                .toList()

            return Features(
                activated = all.filterIsInstance<Pair<Feature, FeatureSupport>>().toMap(),
                unknown = all.filterIsInstance<UnknownFeature>().toSet()
            )
        }

        operator fun invoke(vararg pairs: Pair<Feature, FeatureSupport>): Features = Features(mapOf(*pairs))

        // Features may depend on other features, as specified in Bolt 9.
        private val featuresDependency: Map<Feature, List<Feature>> = mapOf(
            Feature.ChannelRangeQueriesExtended to listOf(Feature.ChannelRangeQueries),
            Feature.PaymentSecret to listOf(Feature.VariableLengthOnion),
            Feature.BasicMultiPartPayment to listOf(Feature.PaymentSecret),
            Feature.AnchorOutputs to listOf(Feature.StaticRemoteKey),
            Feature.TrampolinePayment to listOf(Feature.PaymentSecret)
        )

        class FeatureException(message: String) : IllegalArgumentException(message)

        fun validateFeatureGraph(features: Features): FeatureException? {
            featuresDependency.forEach { (feature, dependencies) ->
                if (features.hasFeature(feature)) {
                    val missing = dependencies.filter { !features.hasFeature(it) }
                    if (missing.isNotEmpty()) {
                        return FeatureException("$feature is set but is missing a dependency (${missing.joinToString(" and ")})")
                    }
                }
            }
            return null
        }

        fun areCompatible(ours: Features, theirs: Features): Boolean = ours.areSupported(theirs) && theirs.areSupported(ours)

        /** returns true if both have at least optional support */
        fun canUseFeature(localFeatures: Features, remoteFeatures: Features, feature: Feature): Boolean =
            localFeatures.hasFeature(feature) && remoteFeatures.hasFeature(feature)
    }
}
