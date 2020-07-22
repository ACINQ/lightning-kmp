package fr.acinq.eklair

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eklair.blockchain.fee.OnChainFeeConf
import fr.acinq.eklair.crypto.KeyManager
import fr.acinq.eklair.db.Databases

@OptIn(ExperimentalUnsignedTypes::class)
data class NodeParams(
    val keyManager: KeyManager,
    val alias: String,
    val features: Features,
    val dustLimit: Satoshi,
    val onChainFeeConf: OnChainFeeConf,
    val maxHtlcValueInFlightMsat: ULong,
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
    val db: Databases,
    val revocationTimeout: Long,
    val authTimeout: Long,
    val initTimeout: Long,
    val pingInterval: Long,
    val pingTimeout: Long,
    val pingDisconnect: Boolean,
    val autoReconnect: Boolean,
    val initialRandomReconnectDelay: Long,
    val maxReconnectInterval: Long,
    val chainHash: ByteVector32,
    val channelFlags: Byte,
    val paymentRequestExpiry: Long,
    val multiPartPaymentExpiry: Long,
    val minFundingSatoshis: Satoshi,
    val maxFundingSatoshis: Satoshi,
    val maxPaymentAttempts: Int,
    val enableTrampolinePayment: Boolean,
) {
    val privateKey = keyManager.nodeKey.privateKey
    val nodeId = keyManager.nodeId
}
