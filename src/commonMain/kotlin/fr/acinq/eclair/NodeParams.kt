package fr.acinq.eclair

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.blockchain.fee.OnChainFeeConf
import fr.acinq.eclair.crypto.KeyManager
import fr.acinq.eclair.io.ByteVector32KSerializer
import fr.acinq.eclair.io.PublicKeyKSerializer
import fr.acinq.eclair.io.SatoshiKSerializer
import kotlinx.serialization.Serializable

@Serializable
data class NodeUri(@Serializable(with = PublicKeyKSerializer::class) val id: PublicKey, val host: String, val port: Int)

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class NodeParams(
    val keyManager: KeyManager,
    val alias: String,
    val features: Features,
    @Serializable(with = SatoshiKSerializer::class) val dustLimit: Satoshi,
    val onChainFeeConf: OnChainFeeConf,
    val maxHtlcValueInFlightMsat: Long,
    val maxAcceptedHtlcs: Int,
    val expiryDeltaBlocks: CltvExpiryDelta,
    val fulfillSafetyBeforeTimeoutBlocks: CltvExpiryDelta,
    val htlcMinimum: MilliSatoshi,
    val toRemoteDelayBlocks: CltvExpiryDelta,
    val maxToLocalDelayBlocks: CltvExpiryDelta,
    val minDepthBlocks: Int,
    val feeBase: MilliSatoshi,
    val feeProportionalMillionth: Int,
    val reserveToFundingRatio: Double,
    val maxReserveToFundingRatio: Double,
    val revocationTimeoutSeconds: Long,
    val authTimeoutSeconds: Long,
    val initTimeoutSeconds: Long,
    val pingIntervalSeconds: Long,
    val pingTimeoutSeconds: Long,
    val pingDisconnect: Boolean,
    val autoReconnect: Boolean,
    val initialRandomReconnectDelaySeconds: Long,
    val maxReconnectIntervalSeconds: Long,
    @Serializable(with = ByteVector32KSerializer::class) val chainHash: ByteVector32,
    val channelFlags: Byte,
    val paymentRequestExpirySeconds: Long,
    val multiPartPaymentExpirySeconds: Long,
    @Serializable(with = SatoshiKSerializer::class) val minFundingSatoshis: Satoshi,
    @Serializable(with = SatoshiKSerializer::class) val maxFundingSatoshis: Satoshi,
    val maxPaymentAttempts: Int,
    val trampolineNode: NodeUri,
    val enableTrampolinePayment: Boolean,
) {
    val nodePrivateKey get() = keyManager.nodeKey.privateKey
    val nodeId get() = keyManager.nodeId
}
