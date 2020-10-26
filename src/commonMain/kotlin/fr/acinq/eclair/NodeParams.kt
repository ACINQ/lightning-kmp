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
    val revocationTimeout: Long,
    val authTimeout: Long,
    val initTimeout: Long,
    val pingInterval: Long,
    val pingTimeout: Long,
    val pingDisconnect: Boolean,
    val autoReconnect: Boolean,
    val initialRandomReconnectDelay: Long,
    val maxReconnectInterval: Long,
    @Serializable(with = ByteVector32KSerializer::class) val chainHash: ByteVector32,
    val channelFlags: Byte,
    val paymentRequestExpiry: Long,
    val multiPartPaymentExpiry: Long,
    @Serializable(with = SatoshiKSerializer::class) val minFundingSatoshis: Satoshi,
    @Serializable(with = SatoshiKSerializer::class) val maxFundingSatoshis: Satoshi,
    val maxPaymentAttempts: Int,
    val trampolineNode: Triple<@Serializable(with = PublicKeyKSerializer::class) PublicKey, String, Int>?,
    val enableTrampolinePayment: Boolean,
) {
    val nodePrivateKey get() = keyManager.nodeKey.privateKey
    val nodeId get() = keyManager.nodeId
}
