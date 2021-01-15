package fr.acinq.eclair.tests

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Script
import fr.acinq.eclair.*
import fr.acinq.eclair.Eclair.randomKey
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.fee.FeerateTolerance
import fr.acinq.eclair.blockchain.fee.OnChainFeeConf
import fr.acinq.eclair.channel.LocalParams
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.io.PeerChannels
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.sat
import fr.acinq.eclair.wire.OnionRoutingPacket

@OptIn(ExperimentalUnsignedTypes::class)
object TestConstants {
    const val defaultBlockHeight = 400_000
    val fundingAmount = 1_000_000.sat
    val pushMsat = 200_000_000.msat
    val feeratePerKw = FeeratePerKw(10_000.sat)
    val emptyOnionPacket = OnionRoutingPacket(0, ByteVector(ByteArray(33)), ByteVector(ByteArray(OnionRoutingPacket.PaymentPacketLength)), ByteVector32.Zeroes)

    val trampolineFees = listOf(
        TrampolineFees(0.sat, 0, CltvExpiryDelta(576)),
        TrampolineFees(1.sat, 100, CltvExpiryDelta(576)),
        TrampolineFees(3.sat, 100, CltvExpiryDelta(576)),
        TrampolineFees(5.sat, 500, CltvExpiryDelta(576)),
        TrampolineFees(5.sat, 1000, CltvExpiryDelta(576)),
        TrampolineFees(5.sat, 1200, CltvExpiryDelta(576))
    )

    object Alice {
        private val seed = ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")
        val keyManager = LocalKeyManager(seed, Block.RegtestGenesisBlock.hash)
        val walletParams = WalletParams(NodeUri(randomKey().publicKey(), "alice.com", 9735), trampolineFees)
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
                    ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional),
                    ActivatedFeature(Feature.Wumbo, FeatureSupport.Optional),
                    ActivatedFeature(Feature.StaticRemoteKey, FeatureSupport.Mandatory),
                    ActivatedFeature(Feature.AnchorOutputs, FeatureSupport.Mandatory),
                    ActivatedFeature(Feature.TrampolinePayment, FeatureSupport.Optional)
                )
            ),
            dustLimit = 1_100.sat,
            onChainFeeConf = OnChainFeeConf(
                closeOnOfflineMismatch = true,
                updateFeeMinDiffRatio = 0.1,
                feerateTolerance = FeerateTolerance(ratioLow = 0.5, ratioHigh = 5.0)
            ),
            maxHtlcValueInFlightMsat = 150_000_000L,
            maxAcceptedHtlcs = 100,
            expiryDeltaBlocks = CltvExpiryDelta(144),
            fulfillSafetyBeforeTimeoutBlocks = CltvExpiryDelta(6),
            checkHtlcTimeoutAfterStartupDelaySeconds = 15,
            htlcMinimum = 0.msat,
            minDepthBlocks = 3,
            toRemoteDelayBlocks = CltvExpiryDelta(144),
            maxToLocalDelayBlocks = CltvExpiryDelta(2048),
            feeBase = 100.msat,
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
            minFundingSatoshis = 1_000.sat,
            maxFundingSatoshis = 25_000_000.sat,
            maxPaymentAttempts = 5,
            enableTrampolinePayment = true
        )

        val channelParams: LocalParams = PeerChannels.makeChannelParams(
            nodeParams,
            ByteVector(Script.write(Script.pay2wpkh(randomKey().publicKey()))),
            isFunder = true,
            fundingAmount
        ).copy(channelReserve = 10_000.sat) // Bob will need to keep that much satoshis as direct payment
    }

    object Bob {
        private val seed = ByteVector32("0202020202020202020202020202020202020202020202020202020202020202")
        val keyManager = LocalKeyManager(seed, Block.RegtestGenesisBlock.hash)
        val walletParams = WalletParams(NodeUri(randomKey().publicKey(), "bob.com", 9735), trampolineFees)
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
                    ActivatedFeature(Feature.BasicMultiPartPayment, FeatureSupport.Optional),
                    ActivatedFeature(Feature.Wumbo, FeatureSupport.Optional),
                    ActivatedFeature(Feature.StaticRemoteKey, FeatureSupport.Mandatory),
                    ActivatedFeature(Feature.AnchorOutputs, FeatureSupport.Mandatory),
                    ActivatedFeature(Feature.TrampolinePayment, FeatureSupport.Optional)
                )
            ),
            dustLimit = 1_000.sat,
            onChainFeeConf = OnChainFeeConf(
                closeOnOfflineMismatch = true,
                updateFeeMinDiffRatio = 0.1,
                feerateTolerance = FeerateTolerance(ratioLow = 0.5, ratioHigh = 5.0)
            ),
            maxHtlcValueInFlightMsat = 1_500_000_000L,
            maxAcceptedHtlcs = 100,
            expiryDeltaBlocks = CltvExpiryDelta(144),
            fulfillSafetyBeforeTimeoutBlocks = CltvExpiryDelta(6),
            checkHtlcTimeoutAfterStartupDelaySeconds = 15,
            htlcMinimum = 1_000.msat,
            minDepthBlocks = 3,
            toRemoteDelayBlocks = CltvExpiryDelta(144),
            maxToLocalDelayBlocks = CltvExpiryDelta(1024),
            feeBase = 10.msat,
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
            minFundingSatoshis = 1_000.sat,
            maxFundingSatoshis = 25_000_000.sat,
            maxPaymentAttempts = 5,
            enableTrampolinePayment = true
        )

        val channelParams: LocalParams = PeerChannels.makeChannelParams(
            nodeParams,
            ByteVector(Script.write(Script.pay2wpkh(randomKey().publicKey()))),
            isFunder = false,
            fundingAmount
        ).copy(channelReserve = 20_000.sat) // Alice will need to keep that much satoshis as direct payment
    }

}