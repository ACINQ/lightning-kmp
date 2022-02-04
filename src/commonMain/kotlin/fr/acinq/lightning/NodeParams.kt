package fr.acinq.lightning

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.Lightning.nodeFee
import fr.acinq.lightning.blockchain.fee.OnChainFeeConf
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.utils.toMilliSatoshi

data class NodeUri(val id: PublicKey, val host: String, val port: Int)

/**
 * When we send a trampoline payment, we start with a low fee.
 * If that fails, we increase the fee(s) and retry (up to a point).
 * This class encapsulates the fees and expiry to use at a particular attempt.
 */
data class TrampolineFees(val feeBase: Satoshi, val feeProportional: Long, val cltvExpiryDelta: CltvExpiryDelta) {
    fun calculateFees(recipientAmount: MilliSatoshi): MilliSatoshi = nodeFee(feeBase.toMilliSatoshi(), feeProportional, recipientAmount)
}

/**
 * When we create an invoice, we need to add a routing hint since we only have private channels.
 * This routing hint contains routing fees that should be paid by the sender, set by our peer.
 * When we have a channel, we'll take them from our peer's channel update, but when we don't have any channel we'll use these default values.
 */
data class InvoiceDefaultRoutingFees(val feeBase: MilliSatoshi, val feeProportional: Long, val cltvExpiryDelta: CltvExpiryDelta)

/**
 * @param trampolineNode address of the trampoline node used for outgoing payments.
 * @param trampolineFees ordered list of trampoline fees to try when making an outgoing payment.
 * @param invoiceDefaultRoutingFees default routing fees set in invoices when we don't have any channel.
 */
data class WalletParams(
    val trampolineNode: NodeUri,
    val trampolineFees: List<TrampolineFees>,
    val invoiceDefaultRoutingFees: InvoiceDefaultRoutingFees
)

/**
 * @param keyManager derive private keys and secrets from your seed.
 * @param alias name of the lightning node.
 * @param features features supported by the lightning node.
 * @param dustLimit threshold below which outputs will not be generated in commitment or HTLC transactions (i.e. HTLCs below this amount plus HTLC transaction fees are not enforceable on-chain).
 * @param maxRemoteDustLimit maximum dust limit we let our peer use for his commitment (in theory it should always be 546 sats).
 * @param onChainFeeConf on-chain feerates that will be applied to various transactions.
 * @param maxHtlcValueInFlightMsat cap on the total value of pending HTLCs in a channel: this lets us limit our exposure to HTLCs risk.
 * @param maxAcceptedHtlcs cap on the number of pending HTLCs in a channel: this lets us limit our exposure to HTLCs risk.
 * @param expiryDeltaBlocks cltv-expiry-delta used in our channel_update: since our channels are private and we don't relay payments, this will be basically ignored.
 * @param fulfillSafetyBeforeTimeoutBlocks number of blocks necessary to react to a malicious peer that doesn't acknowledge and sign our HTLC preimages.
 * @param checkHtlcTimeoutAfterStartupDelaySeconds delay in seconds before we check for timed out HTLCs in our channels after a wallet restart.
 * @param htlcMinimum minimum accepted htlc value.
 * @param toRemoteDelayBlocks number of blocks our peer will have to wait before they get their main output back in case they force-close a channel.
 * @param maxToLocalDelayBlocks maximum number of blocks we will have to wait before we get our main output back in case we force-close a channel.
 * @param minDepthBlocks minimum depth of a transaction before we consider it safely confirmed.
 * @param feeBase base fee used in our channel_update: since our channels are private and we don't relay payments, this will be basically ignored.
 * @param feeProportionalMillionth proportional fee used in our channel_update: since our channels are private and we don't relay payments, this will be basically ignored.
 * @param reserveToFundingRatio size of the channel reserve we required from our peer.
 * @param maxReserveToFundingRatio maximum size of the channel reserve our peer can require from us.
 * @param revocationTimeoutSeconds delay after which we disconnect from our peer if they don't send us a revocation after a new commitment is signed.
 * @param authTimeoutSeconds timeout for the connection authentication phase.
 * @param initTimeoutSeconds timeout for the connection initialization phase.
 * @param pingIntervalSeconds delay between ping messages.
 * @param pingTimeoutSeconds timeout when waiting for a response to our ping.
 * @param pingDisconnect disconnect when a peer doesn't respond to our ping.
 * @param autoReconnect automatically reconnect to our peers.
 * @param initialRandomReconnectDelaySeconds delay before which we reconnect to our peers (will be randomized based on this value).
 * @param maxReconnectIntervalSeconds maximum delay between reconnection attempts.
 * @param chainHash bitcoin chain we're interested in (testnet or mainnet).
 * @param channelFlags channel flags used to temporarily enable or disable channels.
 * @param paymentRequestExpirySeconds our Bolt 11 invoices will only be valid for this duration.
 * @param multiPartPaymentExpirySeconds number of seconds we will wait to receive all parts of a multi-part payment.
 * @param minFundingSatoshis minimum channel size.
 * @param maxFundingSatoshis maximum channel size.
 * @param maxPaymentAttempts maximum number of retries when attempting an outgoing payment.
 * @param enableTrampolinePayment enable trampoline payments.
 */
@OptIn(ExperimentalUnsignedTypes::class)
data class NodeParams(
    val keyManager: KeyManager,
    val alias: String,
    val features: Features,
    val dustLimit: Satoshi,
    val maxRemoteDustLimit: Satoshi,
    val onChainFeeConf: OnChainFeeConf,
    val maxHtlcValueInFlightMsat: Long,
    val maxAcceptedHtlcs: Int,
    val expiryDeltaBlocks: CltvExpiryDelta,
    val fulfillSafetyBeforeTimeoutBlocks: CltvExpiryDelta,
    val checkHtlcTimeoutAfterStartupDelaySeconds: Int,
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
    val chainHash: ByteVector32,
    val channelFlags: Byte,
    val paymentRequestExpirySeconds: Long,
    val multiPartPaymentExpirySeconds: Long,
    val minFundingSatoshis: Satoshi,
    val maxFundingSatoshis: Satoshi,
    val maxPaymentAttempts: Int,
    val enableTrampolinePayment: Boolean,
) {
    val nodePrivateKey get() = keyManager.nodeKey.privateKey
    val nodeId get() = keyManager.nodeId

    init {
        require(features.hasFeature(Feature.VariableLengthOnion, FeatureSupport.Mandatory)) { "${Feature.VariableLengthOnion.rfcName} should be mandatory" }
        require(features.hasFeature(Feature.PaymentSecret, FeatureSupport.Mandatory)) { "${Feature.PaymentSecret.rfcName} should be mandatory" }
        require(features.hasFeature(Feature.ChannelType, FeatureSupport.Mandatory)) { "${Feature.ChannelType.rfcName} should be mandatory" }
        Features.validateFeatureGraph(features)
    }
}
