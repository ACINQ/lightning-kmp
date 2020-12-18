package fr.acinq.eclair

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.Eclair.nodeFee
import fr.acinq.eclair.blockchain.fee.OnChainFeeConf
import fr.acinq.eclair.crypto.KeyManager
import fr.acinq.eclair.io.ByteVector32KSerializer
import fr.acinq.eclair.io.PublicKeyKSerializer
import fr.acinq.eclair.io.SatoshiKSerializer
import fr.acinq.eclair.utils.toMilliSatoshi
import kotlinx.serialization.Serializable

@Serializable
data class NodeUri(@Serializable(with = PublicKeyKSerializer::class) val id: PublicKey, val host: String, val port: Int)

/**
 * When we send a trampoline payment, we start with a low fee.
 * If that fails, we increase the fee(s) and retry (up to a point).
 * This class encapsulates the fees and expiry to use at a particular attempt.
 */
@Serializable
data class TrampolineFees(@Serializable(with = SatoshiKSerializer::class) val feeBase: Satoshi, val feeProportional: Long, val cltvExpiryDelta: CltvExpiryDelta) {
    fun calculateFees(recipientAmount: MilliSatoshi): MilliSatoshi = nodeFee(feeBase.toMilliSatoshi(), feeProportional, recipientAmount)
}

/**
 * @param trampolineNode address of the trampoline node used for outgoing payments.
 * @param trampolineFees ordered list of trampoline fees to try when making an outgoing payment.
 */
@Serializable
data class WalletParams(val trampolineNode: NodeUri, val trampolineFees: List<TrampolineFees>)

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
    val enableTrampolinePayment: Boolean,
) {
    val nodePrivateKey get() = keyManager.nodeKey.privateKey
    val nodeId get() = keyManager.nodeId

    init {
        Features.validateFeatureGraph(features)
    }
}
