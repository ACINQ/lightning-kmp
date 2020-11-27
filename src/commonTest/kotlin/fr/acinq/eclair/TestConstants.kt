package fr.acinq.eclair

import fr.acinq.bitcoin.*
import fr.acinq.eclair.blockchain.fee.OnChainFeeConf
import fr.acinq.eclair.channel.LocalParams
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.io.PeerChannels
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.sat
import fr.acinq.eclair.wire.OnionRoutingPacket

@OptIn(ExperimentalUnsignedTypes::class)
object TestConstants {
    val defaultBlockHeight = 400000
    val fundingSatoshis = 1000000.sat
    val pushMsat = 200000000.msat
    val feeratePerKw = 10000L
    val emptyOnionPacket = OnionRoutingPacket(0, ByteVector(ByteArray(33)), ByteVector(ByteArray(OnionRoutingPacket.PaymentPacketLength)), ByteVector32.Zeroes)

    object Alice {
        val seed = ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")
        val keyManager = LocalKeyManager(seed, Block.RegtestGenesisBlock.hash)
        val nodeParams = NodeParams(
            keyManager = keyManager,
            alias = "alice",
            features = Features(
                setOf(
                    ActivatedFeature(Feature.InitialRoutingSync, FeatureSupport.Optional),
                    ActivatedFeature(Feature.OptionDataLossProtect, FeatureSupport.Optional),
                    ActivatedFeature(Feature.ChannelRangeQueries, FeatureSupport.Optional),
                    ActivatedFeature(Feature.ChannelRangeQueriesExtended, FeatureSupport.Optional),
                    ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
                    ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Optional),
                    ActivatedFeature(Feature.TrampolinePayment, FeatureSupport.Optional)
                )
            ),
            dustLimit = 1100.sat,
            onChainFeeConf = OnChainFeeConf(
                maxFeerateMismatch = 1.5,
                closeOnOfflineMismatch = true,
                updateFeeMinDiffRatio = 0.1
            ),
            maxHtlcValueInFlightMsat = 150000000L,
            maxAcceptedHtlcs = 100,
            expiryDeltaBlocks = CltvExpiryDelta(144),
            fulfillSafetyBeforeTimeoutBlocks = CltvExpiryDelta(6),
            htlcMinimum = 0.msat,
            minDepthBlocks = 3,
            toRemoteDelayBlocks = CltvExpiryDelta(144),
            maxToLocalDelayBlocks = CltvExpiryDelta(1000),
            feeBase = 546000.msat,
            feeProportionalMillionth = 10,
            reserveToFundingRatio = 0.01, // note: not used (overridden below)
            maxReserveToFundingRatio = 0.05,
            revocationTimeoutSeconds = 20,
            authTimeoutSeconds = 10,
            initTimeoutSeconds = 10,
            pingIntervalSeconds = 30,
            pingTimeoutSeconds = 10,
            pingDisconnect = true,
            autoReconnect = false,
            initialRandomReconnectDelaySeconds = 5,
            maxReconnectIntervalSeconds = 3600,
            chainHash = Block.RegtestGenesisBlock.hash,
            channelFlags = 1,
            paymentRequestExpirySeconds = 3600,
            multiPartPaymentExpirySeconds = 60,
            minFundingSatoshis = 1000.sat,
            maxFundingSatoshis = 16777215.sat,
            maxPaymentAttempts = 5,
            trampolineNode = NodeUri(Eclair.randomKey().publicKey(), "alice.com", 9735),
            enableTrampolinePayment = true
        )

        val channelParams: LocalParams = PeerChannels.makeChannelParams(
            nodeParams,
            ByteVector(Script.write(Script.pay2wpkh(Eclair.randomKey().publicKey()))),
            null,
            true,
            fundingSatoshis
        ).copy(channelReserve = 10000.sat) // Bob will need to keep that much satoshis as direct payment
    }

    object Bob {
        val seed = ByteVector32("0202020202020202020202020202020202020202020202020202020202020202")
        val keyManager = LocalKeyManager(Alice.seed, Block.RegtestGenesisBlock.hash)
        val nodeParams = NodeParams(
            keyManager = keyManager,
            alias = "bob",
            features = Features(
                setOf(
                    ActivatedFeature(Feature.InitialRoutingSync, FeatureSupport.Optional),
                    ActivatedFeature(Feature.OptionDataLossProtect, FeatureSupport.Optional),
                    ActivatedFeature(Feature.ChannelRangeQueries, FeatureSupport.Optional),
                    ActivatedFeature(Feature.ChannelRangeQueriesExtended, FeatureSupport.Optional),
                    ActivatedFeature(Feature.VariableLengthOnion, FeatureSupport.Optional),
                    ActivatedFeature(Feature.PaymentSecret, FeatureSupport.Optional),
                    ActivatedFeature(Feature.TrampolinePayment, FeatureSupport.Optional)
                )
            ),
            dustLimit = 1000.sat,
            onChainFeeConf = OnChainFeeConf(
                maxFeerateMismatch = 1.5,
                closeOnOfflineMismatch = true,
                updateFeeMinDiffRatio = 0.1
            ),
            maxHtlcValueInFlightMsat = Long.MAX_VALUE,
            maxAcceptedHtlcs = 100,
            expiryDeltaBlocks = CltvExpiryDelta(144),
            fulfillSafetyBeforeTimeoutBlocks = CltvExpiryDelta(6),
            htlcMinimum = 1000.msat,
            minDepthBlocks = 3,
            toRemoteDelayBlocks = CltvExpiryDelta(144),
            maxToLocalDelayBlocks = CltvExpiryDelta(1000),
            feeBase = 546000.msat,
            feeProportionalMillionth = 10,
            reserveToFundingRatio = 0.01, // note: not used (overridden below)
            maxReserveToFundingRatio = 0.05,
            revocationTimeoutSeconds = 20,
            authTimeoutSeconds = 10,
            initTimeoutSeconds = 10,
            pingIntervalSeconds = 30,
            pingTimeoutSeconds = 10,
            pingDisconnect = true,
            autoReconnect = false,
            initialRandomReconnectDelaySeconds = 5,
            maxReconnectIntervalSeconds = 3600,
            chainHash = Block.RegtestGenesisBlock.hash,
            channelFlags = 1,
            paymentRequestExpirySeconds = 3600,
            multiPartPaymentExpirySeconds = 60,
            minFundingSatoshis = 1000.sat,
            maxFundingSatoshis = 16777215.sat,
            maxPaymentAttempts = 5,
            trampolineNode = NodeUri(Eclair.randomKey().publicKey(), "bob.com", 9735),
            enableTrampolinePayment = true
        )

        val channelParams: LocalParams = PeerChannels.makeChannelParams(
            nodeParams,
            ByteVector(Script.write(Script.pay2wpkh(Eclair.randomKey().publicKey()))),
            null,
            false,
            fundingSatoshis
        ).copy(channelReserve = 20000.sat) // Alice will need to keep that much satoshis as direct payment
    }
}