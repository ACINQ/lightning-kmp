package fr.acinq.lightning.tests

import fr.acinq.bitcoin.*
import fr.acinq.lightning.*
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.blockchain.fee.FeerateTolerance
import fr.acinq.lightning.blockchain.fee.OnChainFeeConf
import fr.acinq.lightning.channel.LocalParams
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.io.PeerChannels
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector32
import fr.acinq.lightning.wire.OnionRoutingPacket
import fr.acinq.secp256k1.Hex
import org.kodein.log.LoggerFactory

object TestConstants {
    const val defaultBlockHeight = 400_000
    val aliceFundingAmount = 850_000.sat
    val bobFundingAmount = 150_000.sat
    val alicePushAmount = 50_000_000.msat
    val bobPushAmount = 0.msat
    val feeratePerKw = FeeratePerKw(5000.sat) // 20 sat/byte
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
        private val entropy = Hex.decode("0101010101010101010101010101010101010101010101010101010101010101")
        private val mnemonics = MnemonicCode.toMnemonics(entropy)
        private val seed = MnemonicCode.toSeed(mnemonics, "").toByteVector32()

        val keyManager = LocalKeyManager(seed, NodeParams.Chain.Regtest)
        val walletParams = WalletParams(NodeUri(Bob.keyManager.nodeKeys.nodeKey.publicKey, "bob.com", 9735), trampolineFees, InvoiceDefaultRoutingFees(1_000.msat, 100, CltvExpiryDelta(144)))
        val nodeParams = NodeParams(
            chain = NodeParams.Chain.Regtest,
            loggerFactory = LoggerFactory.default,
            keyManager = keyManager,
        ).copy(
            alias = "alice",
            features = Features(
                Feature.OptionDataLossProtect to FeatureSupport.Optional,
                Feature.VariableLengthOnion to FeatureSupport.Mandatory,
                Feature.PaymentSecret to FeatureSupport.Mandatory,
                Feature.BasicMultiPartPayment to FeatureSupport.Optional,
                Feature.Wumbo to FeatureSupport.Optional,
                Feature.StaticRemoteKey to FeatureSupport.Mandatory,
                Feature.AnchorOutputs to FeatureSupport.Mandatory,
                Feature.DualFunding to FeatureSupport.Mandatory,
                Feature.ChannelType to FeatureSupport.Mandatory,
                Feature.PaymentMetadata to FeatureSupport.Optional,
                Feature.ExperimentalTrampolinePayment to FeatureSupport.Optional,
                Feature.WakeUpNotificationProvider to FeatureSupport.Optional,
                Feature.PayToOpenProvider to FeatureSupport.Optional,
                Feature.ChannelBackupProvider to FeatureSupport.Optional,
            ),
            dustLimit = 1_100.sat,
            maxRemoteDustLimit = 1_500.sat,
            onChainFeeConf = OnChainFeeConf(
                closeOnOfflineMismatch = true,
                updateFeeMinDiffRatio = 0.1,
                feerateTolerance = FeerateTolerance(ratioLow = 0.5, ratioHigh = 5.0)
            ),
            maxHtlcValueInFlightMsat = 1_500_000_000L,
            maxAcceptedHtlcs = 100,
            htlcMinimum = 0.msat,
            toRemoteDelayBlocks = CltvExpiryDelta(144),
            maxToLocalDelayBlocks = CltvExpiryDelta(2048),
            feeBase = 100.msat,
            feeProportionalMillionth = 10,
            paymentRecipientExpiryParams = RecipientCltvExpiryParams(CltvExpiryDelta(0), CltvExpiryDelta(0)),
        )

        private val closingPubkeyScript = keyManager.finalOnChainWallet.pubkeyScript(0)

        fun channelParams(finalScriptPubKey: ByteVector = closingPubkeyScript): LocalParams = PeerChannels.makeChannelParams(
            nodeParams,
            finalScriptPubKey,
            isInitiator = true,
            nodeParams.maxHtlcValueInFlightMsat.msat,
        )
    }

    object Bob {
        private val entropy = Hex.decode("0202020202020202020202020202020202020202020202020202020202020202")
        val mnemonics = MnemonicCode.toMnemonics(entropy)
        private val seed = MnemonicCode.toSeed(mnemonics, "").toByteVector32()
        val keyManager = LocalKeyManager(seed, NodeParams.Chain.Regtest)
        val walletParams = WalletParams(NodeUri(Alice.keyManager.nodeKeys.nodeKey.publicKey, "alice.com", 9735), trampolineFees, InvoiceDefaultRoutingFees(1_000.msat, 100, CltvExpiryDelta(144)))
        val nodeParams = NodeParams(
            chain = NodeParams.Chain.Regtest,
            loggerFactory = LoggerFactory.default,
            keyManager = keyManager,
        ).copy(
            alias = "bob",
            dustLimit = 1_000.sat,
            maxRemoteDustLimit = 1_500.sat,
            onChainFeeConf = OnChainFeeConf(
                closeOnOfflineMismatch = true,
                updateFeeMinDiffRatio = 0.1,
                feerateTolerance = FeerateTolerance(ratioLow = 0.5, ratioHigh = 5.0)
            ),
            maxHtlcValueInFlightMsat = 1_500_000_000L,
            maxAcceptedHtlcs = 100,
            toRemoteDelayBlocks = CltvExpiryDelta(144),
            maxToLocalDelayBlocks = CltvExpiryDelta(1024),
            feeBase = 10.msat,
            feeProportionalMillionth = 10,
            paymentRecipientExpiryParams = RecipientCltvExpiryParams(CltvExpiryDelta(0), CltvExpiryDelta(0)),
        )

        private val closingPubkeyScript = Alice.keyManager.finalOnChainWallet.pubkeyScript(0)

        fun channelParams(finalScriptPubKey: ByteVector = closingPubkeyScript): LocalParams = PeerChannels.makeChannelParams(
            nodeParams,
            finalScriptPubKey,
            isInitiator = false,
            nodeParams.maxHtlcValueInFlightMsat.msat,
        )
    }

}
