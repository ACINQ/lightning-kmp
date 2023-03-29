package fr.acinq.lightning

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.Lightning.nodeFee
import fr.acinq.lightning.blockchain.fee.FeerateTolerance
import fr.acinq.lightning.blockchain.fee.OnChainFeeConf
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.payment.LiquidityPolicy
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toMilliSatoshi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.kodein.log.LoggerFactory

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
 * When sending a payment, if the expiry used for the last node is very close to the current block height,
 * it lets intermediate nodes figure out their position in the route. To protect against this, a random
 * delta between min and max will be added to the current block height, which makes it look like there
 * are more hops after the final node.
 */
data class RecipientCltvExpiryParams(val min: CltvExpiryDelta, val max: CltvExpiryDelta) {
    fun computeFinalExpiry(currentBlockHeight: Int, minFinalExpiryDelta: CltvExpiryDelta): CltvExpiry {
        val randomDelay = when {
            min < max -> Lightning.secureRandom.nextLong(min.toLong(), max.toLong()).toInt()
            else -> max.toInt()
        }
        return (minFinalExpiryDelta + randomDelay).toCltvExpiry(currentBlockHeight.toLong())
    }
}

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
 * @param revocationTimeoutSeconds delay after which we disconnect from our peer if they don't send us a revocation after a new commitment is signed.
 * @param authTimeoutSeconds timeout for the connection authentication phase.
 * @param initTimeoutSeconds timeout for the connection initialization phase.
 * @param pingIntervalSeconds delay between ping messages.
 * @param pingTimeoutSeconds timeout when waiting for a response to our ping.
 * @param pingDisconnect disconnect when a peer doesn't respond to our ping.
 * @param autoReconnect automatically reconnect to our peers.
 * @param initialRandomReconnectDelaySeconds delay before which we reconnect to our peers (will be randomized based on this value).
 * @param maxReconnectIntervalSeconds maximum delay between reconnection attempts.
 * @param chain bitcoin chain we're interested in.
 * @param channelFlags channel flags used to temporarily enable or disable channels.
 * @param paymentRequestExpirySeconds our Bolt 11 invoices will only be valid for this duration.
 * @param multiPartPaymentExpirySeconds number of seconds we will wait to receive all parts of a multi-part payment.
 * @param maxPaymentAttempts maximum number of retries when attempting an outgoing payment.
 * @param paymentRecipientExpiryParams configure the expiry delta used for the final node when sending payments.
 * @param zeroConfPeers list of peers with whom we use zero-conf (note that this is a strong trust assumption).
 * @param enableTrampolinePayment enable trampoline payments.
 */
data class NodeParams(
    val loggerFactory: LoggerFactory,
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
    val revocationTimeoutSeconds: Long,
    val authTimeoutSeconds: Long,
    val initTimeoutSeconds: Long,
    val pingIntervalSeconds: Long,
    val pingTimeoutSeconds: Long,
    val pingDisconnect: Boolean,
    val autoReconnect: Boolean,
    val initialRandomReconnectDelaySeconds: Long,
    val maxReconnectIntervalSeconds: Long,
    val chain: Chain,
    val channelFlags: Byte,
    val paymentRequestExpirySeconds: Long,
    val multiPartPaymentExpirySeconds: Long,
    val maxPaymentAttempts: Int,
    val paymentRecipientExpiryParams: RecipientCltvExpiryParams,
    val zeroConfPeers: Set<PublicKey>,
    val enableTrampolinePayment: Boolean,
    val liquidityPolicy: LiquidityPolicy
) {
    val nodePrivateKey get() = keyManager.nodeKeys.nodeKey.privateKey
    val nodeId get() = keyManager.nodeKeys.nodeKey.publicKey
    val chainHash get() = chain.chainHash

    internal val _nodeEvents = MutableSharedFlow<NodeEvents>()
    val nodeEvents: SharedFlow<NodeEvents> get() = _nodeEvents.asSharedFlow()

    init {
        require(features.hasFeature(Feature.VariableLengthOnion, FeatureSupport.Mandatory)) { "${Feature.VariableLengthOnion.rfcName} should be mandatory" }
        require(features.hasFeature(Feature.PaymentSecret, FeatureSupport.Mandatory)) { "${Feature.PaymentSecret.rfcName} should be mandatory" }
        require(features.hasFeature(Feature.ChannelType, FeatureSupport.Mandatory)) { "${Feature.ChannelType.rfcName} should be mandatory" }
        require(features.hasFeature(Feature.DualFunding, FeatureSupport.Mandatory)) { "${Feature.DualFunding.rfcName} should be mandatory" }
        require(!features.hasFeature(Feature.ZeroConfChannels)) { "${Feature.ZeroConfChannels.rfcName} has been deprecated: use the zeroConfPeers whitelist instead" }
        require(!features.hasFeature(Feature.TrustedSwapInClient)) { "${Feature.TrustedSwapInClient.rfcName} has been deprecated" }
        require(!features.hasFeature(Feature.TrustedSwapInProvider)) { "${Feature.TrustedSwapInProvider.rfcName} has been deprecated" }
        Features.validateFeatureGraph(features)
    }

    /**
     * Library integrators should use this constructor and override values.
     */
    constructor(chain: Chain, loggerFactory: LoggerFactory, keyManager: KeyManager) : this(
        loggerFactory = loggerFactory,
        keyManager = keyManager,
        alias = "lightning-kmp",
        features = Features(
            Feature.OptionDataLossProtect to FeatureSupport.Optional,
            Feature.VariableLengthOnion to FeatureSupport.Mandatory,
            Feature.PaymentSecret to FeatureSupport.Mandatory,
            Feature.BasicMultiPartPayment to FeatureSupport.Optional,
            Feature.Wumbo to FeatureSupport.Optional,
            Feature.StaticRemoteKey to FeatureSupport.Mandatory,
            Feature.AnchorOutputs to FeatureSupport.Optional, // can't set Mandatory because peers prefers AnchorOutputsZeroFeeHtlcTx
            Feature.DualFunding to FeatureSupport.Mandatory,
            Feature.ShutdownAnySegwit to FeatureSupport.Mandatory,
            Feature.ChannelType to FeatureSupport.Mandatory,
            Feature.PaymentMetadata to FeatureSupport.Optional,
            Feature.ExperimentalTrampolinePayment to FeatureSupport.Optional,
            Feature.ZeroReserveChannels to FeatureSupport.Optional,
            Feature.WakeUpNotificationClient to FeatureSupport.Optional,
            Feature.PayToOpenClient to FeatureSupport.Optional,
            Feature.ChannelBackupClient to FeatureSupport.Optional,
            Feature.ExperimentalSplice to FeatureSupport.Optional,
        ),
        dustLimit = 546.sat,
        maxRemoteDustLimit = 600.sat,
        onChainFeeConf = OnChainFeeConf(
            closeOnOfflineMismatch = true,
            updateFeeMinDiffRatio = 0.1,
            feerateTolerance = FeerateTolerance(ratioLow = 0.01, ratioHigh = 100.0)
        ),
        maxHtlcValueInFlightMsat = 20_000_000_000L,
        maxAcceptedHtlcs = 6,
        expiryDeltaBlocks = CltvExpiryDelta(144),
        fulfillSafetyBeforeTimeoutBlocks = CltvExpiryDelta(6),
        checkHtlcTimeoutAfterStartupDelaySeconds = 15,
        htlcMinimum = 1000.msat,
        minDepthBlocks = 3,
        toRemoteDelayBlocks = CltvExpiryDelta(2016),
        maxToLocalDelayBlocks = CltvExpiryDelta(1008),
        feeBase = 1000.msat,
        feeProportionalMillionth = 100,
        revocationTimeoutSeconds = 20,
        authTimeoutSeconds = 10,
        initTimeoutSeconds = 10,
        pingIntervalSeconds = 30,
        pingTimeoutSeconds = 10,
        pingDisconnect = true,
        autoReconnect = false,
        initialRandomReconnectDelaySeconds = 5,
        maxReconnectIntervalSeconds = 3600,
        chain = chain,
        channelFlags = 1,
        paymentRequestExpirySeconds = 3600,
        multiPartPaymentExpirySeconds = 60,
        maxPaymentAttempts = 5,
        enableTrampolinePayment = true,
        zeroConfPeers = emptySet(),
        paymentRecipientExpiryParams = RecipientCltvExpiryParams(CltvExpiryDelta(75), CltvExpiryDelta(200)),
        liquidityPolicy = LiquidityPolicy.Auto(100, 3000.sat)
    )

    sealed class Chain(val name: String, private val genesis: Block) {
        object Regtest : Chain("Regtest", Block.RegtestGenesisBlock)
        object Testnet : Chain("Testnet", Block.TestnetGenesisBlock)
        object Mainnet : Chain("Mainnet", Block.LivenetGenesisBlock)

        fun isMainnet(): Boolean = this is Mainnet
        fun isTestnet(): Boolean = this is Testnet

        val chainHash by lazy { genesis.hash }

        override fun toString(): String = name
    }
}
