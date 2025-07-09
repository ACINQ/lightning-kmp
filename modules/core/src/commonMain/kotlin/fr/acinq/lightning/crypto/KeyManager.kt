package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.DeterministicWallet.hardened
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.crypto.musig2.SecretNonce
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.DefaultSwapInParams
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.ChannelParams
import fr.acinq.lightning.transactions.SwapInProtocol
import fr.acinq.lightning.transactions.SwapInProtocolLegacy
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.sum
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.LightningCodecs

/**
 * Keys used for the node. They are used to generate the node id, to secure communication with other peers, and
 * to sign network-wide public announcements.
 */
data class NodeKeys(
    /** The node key that the same seed would have produced on the legacy eclair-based Phoenix implementation on Android. Useful for recovery for users who didn't upgrade in time. */
    val legacyNodeKey: DeterministicWallet.ExtendedPrivateKey,
    val nodeKey: DeterministicWallet.ExtendedPrivateKey
)

/**
 * Each commitment transaction uses a set of many cryptographic keys for the various spending paths of all its outputs.
 * Some of those keys are static for the channel lifetime, but others change every time we update the commitment
 * transaction.
 *
 * This class can be used indifferently for the local or remote commitment transaction. Beware that when it applies to
 * the remote transaction, the "local" prefix is for their keys and the "remote" prefix is for our keys.
 *
 * See Bolt 3 for more details.
 *
 * @param localDelayedPaymentPublicKey key used for delayed outputs of the transaction owner (main balance and outputs of HTLC transactions).
 * @param remotePaymentPublicKey key used for the main balance of the transaction non-owner (not delayed).
 * @param localHtlcPublicKey key used to sign HTLC transactions by the transaction owner.
 * @param remoteHtlcPublicKey key used to sign HTLC transactions by the transaction non-owner.
 * @param revocationPublicKey key used to revoke this commitment after signing the next one (by revealing the per-commitment secret).
 */
data class CommitmentPublicKeys(
    val localDelayedPaymentPublicKey: PublicKey,
    val remotePaymentPublicKey: PublicKey,
    val localHtlcPublicKey: PublicKey,
    val remoteHtlcPublicKey: PublicKey,
    val revocationPublicKey: PublicKey
)

/**
 * Keys used for our local commitment.
 * WARNING: these private keys must never be stored on disk, in a database, or logged.
 */
data class LocalCommitmentKeys(
    val ourDelayedPaymentKey: PrivateKey,
    val theirPaymentPublicKey: PublicKey,
    val ourPaymentBasePoint: PublicKey,
    val ourHtlcKey: PrivateKey,
    val theirHtlcPublicKey: PublicKey,
    val revocationPublicKey: PublicKey
) {
    val publicKeys: CommitmentPublicKeys = CommitmentPublicKeys(
        localDelayedPaymentPublicKey = ourDelayedPaymentKey.publicKey(),
        remotePaymentPublicKey = theirPaymentPublicKey,
        localHtlcPublicKey = ourHtlcKey.publicKey(),
        remoteHtlcPublicKey = theirHtlcPublicKey,
        revocationPublicKey = revocationPublicKey
    )
}

/**
 * Keys used for the remote commitment.
 * WARNING: these private keys must never be stored on disk, in a database, or logged.
 */
data class RemoteCommitmentKeys(
    val ourPaymentKey: PrivateKey,
    val theirDelayedPaymentPublicKey: PublicKey,
    val ourPaymentBasePoint: PublicKey,
    val ourHtlcKey: PrivateKey,
    val theirHtlcPublicKey: PublicKey,
    val revocationPublicKey: PublicKey
) {
    // Since this is the remote commitment, local is them and remote is us.
    val publicKeys: CommitmentPublicKeys = CommitmentPublicKeys(
        localDelayedPaymentPublicKey = theirDelayedPaymentPublicKey,
        remotePaymentPublicKey = ourPaymentKey.publicKey(),
        localHtlcPublicKey = theirHtlcPublicKey,
        remoteHtlcPublicKey = ourHtlcKey.publicKey(),
        revocationPublicKey = revocationPublicKey
    )
}

/**
 * Keys used for a specific channel instance:
 *  - funding keys (channel funding, splicing and closing)
 *  - commitment "base" keys, which are static for the channel lifetime
 *  - per-commitment keys, which change everytime we create a new commitment transaction:
 *    - derived from the commitment "base" keys
 *    - and tweaked with a per-commitment point
 *
 * WARNING: these private keys must never be stored on disk, in a database, or logged.
 */
data class ChannelKeys(
    private val fundingMasterKey: DeterministicWallet.ExtendedPrivateKey,
    private val commitmentMasterKey: DeterministicWallet.ExtendedPrivateKey
) {
    fun fundingKey(fundingTxIndex: Long): PrivateKey = fundingMasterKey.derivePrivateKey(hardened(fundingTxIndex)).privateKey

    val revocationBaseSecret: PrivateKey = commitmentMasterKey.derivePrivateKey(hardened(1)).privateKey
    val revocationBasePoint: PublicKey = revocationBaseSecret.publicKey()
    val paymentBaseSecret: PrivateKey = commitmentMasterKey.derivePrivateKey(hardened(2)).privateKey
    val paymentBasePoint: PublicKey = paymentBaseSecret.publicKey()
    val delayedPaymentBaseSecret: PrivateKey = commitmentMasterKey.derivePrivateKey(hardened(3)).privateKey
    val delayedPaymentBasePoint: PublicKey = delayedPaymentBaseSecret.publicKey()
    val htlcBaseSecret: PrivateKey = commitmentMasterKey.derivePrivateKey(hardened(4)).privateKey
    val htlcBasePoint: PublicKey = htlcBaseSecret.publicKey()

    // @formatter:off
    // Per-commitment keys are derived using a sha-chain, which provides efficient storage and retrieval mechanisms.
    val shaSeed: ByteVector32 = Crypto.sha256(commitmentMasterKey.derivePrivateKey(hardened(5)).privateKey.value.toByteArray() + ByteArray(1) { 1 }).byteVector32()
    fun commitmentSecret(localCommitmentNumber: Long): PrivateKey = PrivateKey(ShaChain.shaChainFromSeed(shaSeed, 0xFFFFFFFFFFFFL - localCommitmentNumber))
    fun commitmentPoint(localCommitmentNumber: Long): PublicKey = commitmentSecret(localCommitmentNumber).publicKey()
    // @formatter:on

    /** Derive our local delayed payment key for our main output in the local commitment transaction. */
    private fun delayedPaymentKey(commitmentPoint: PublicKey): PrivateKey = derivePerCommitmentKey(delayedPaymentBaseSecret, commitmentPoint)

    /** Derive our HTLC key for our HTLC transactions, in either the local or remote commitment transaction. */
    private fun htlcKey(commitmentPoint: PublicKey): PrivateKey = derivePerCommitmentKey(htlcBaseSecret, commitmentPoint)

    /** With the remote per-commitment secret, we can derive the private key to spend revoked commitments. */
    fun revocationKey(remoteCommitmentSecret: PrivateKey): PrivateKey = revocationKey(revocationBaseSecret, remoteCommitmentSecret)

    fun localCommitmentKeys(channelParams: ChannelParams, localCommitIndex: Long): LocalCommitmentKeys {
        val localPerCommitmentPoint = commitmentPoint(localCommitIndex)
        return LocalCommitmentKeys(
            ourDelayedPaymentKey = delayedPaymentKey(localPerCommitmentPoint),
            theirPaymentPublicKey = channelParams.remoteParams.paymentBasepoint,
            ourPaymentBasePoint = paymentBasePoint,
            ourHtlcKey = htlcKey(localPerCommitmentPoint),
            theirHtlcPublicKey = remotePerCommitmentPublicKey(channelParams.remoteParams.htlcBasepoint, localPerCommitmentPoint),
            revocationPublicKey = revocationPublicKey(channelParams.remoteParams.revocationBasepoint, localPerCommitmentPoint)
        )
    }

    fun remoteCommitmentKeys(channelParams: ChannelParams, remotePerCommitmentPoint: PublicKey): RemoteCommitmentKeys {
        return RemoteCommitmentKeys(
            ourPaymentKey = paymentBaseSecret,
            theirDelayedPaymentPublicKey = remotePerCommitmentPublicKey(channelParams.remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint),
            ourPaymentBasePoint = paymentBasePoint,
            ourHtlcKey = htlcKey(remotePerCommitmentPoint),
            theirHtlcPublicKey = remotePerCommitmentPublicKey(channelParams.remoteParams.htlcBasepoint, remotePerCommitmentPoint),
            revocationPublicKey = revocationPublicKey(revocationBasePoint, remotePerCommitmentPoint)
        )
    }

    companion object {
        /** Derive the local per-commitment key for the base key provided. */
        fun derivePerCommitmentKey(baseSecret: PrivateKey, commitmentPoint: PublicKey): PrivateKey {
            // secretkey = basepoint-secret + SHA256(per-commitment-point || basepoint)
            return baseSecret + PrivateKey(Crypto.sha256(commitmentPoint.value + baseSecret.publicKey().value))
        }

        /** Derive the remote per-commitment key for the base point provided. */
        fun remotePerCommitmentPublicKey(basePoint: PublicKey, commitmentPoint: PublicKey): PublicKey {
            // pubkey = basepoint + SHA256(per-commitment-point || basepoint)*G
            return basePoint + PrivateKey(Crypto.sha256(commitmentPoint.value + basePoint.value)).publicKey()
        }

        /** Derive the revocation private key from our local base revocation key and the remote per-commitment secret. */
        fun revocationKey(baseKey: PrivateKey, remoteCommitmentSecret: PrivateKey): PrivateKey {
            val a = PrivateKey(Crypto.sha256(baseKey.publicKey().value + remoteCommitmentSecret.publicKey().value))
            val b = PrivateKey(Crypto.sha256(remoteCommitmentSecret.publicKey().value + baseKey.publicKey().value))
            return (baseKey * a) + (remoteCommitmentSecret * b)
        }

        /**
         * We create two distinct revocation public keys:
         *   - one for the local commitment using the remote revocation base point and our local per-commitment point
         *   - one for the remote commitment using our revocation base point and the remote per-commitment point
         *
         * The owner of the commitment transaction is providing the per-commitment point, which ensures that they can revoke
         * their previous commitment transactions by revealing the corresponding secret.
         */
        fun revocationPublicKey(revocationBasePoint: PublicKey, commitmentPoint: PublicKey): PublicKey {
            val a = PrivateKey(Crypto.sha256(revocationBasePoint.value + commitmentPoint.value))
            val b = PrivateKey(Crypto.sha256(commitmentPoint.value + revocationBasePoint.value))
            return (revocationBasePoint * a) + (commitmentPoint * b)
        }
    }
}

data class Bip84OnChainKeys(
    private val chain: Chain,
    private val master: DeterministicWallet.ExtendedPrivateKey,
    val account: Long
) {
    private val xpriv = master.derivePrivateKey(bip84BasePath(chain) / hardened(account))
    val xpub: String = xpriv.extendedPublicKey.encode(
        prefix = when (chain) {
            Chain.Testnet4, Chain.Testnet3, Chain.Regtest, Chain.Signet -> DeterministicWallet.vpub
            Chain.Mainnet -> DeterministicWallet.zpub
        }
    )

    fun privateKey(addressIndex: Long): PrivateKey {
        return xpriv.derivePrivateKey(KeyPath.empty / 0 / addressIndex).privateKey
    }

    fun pubkeyScript(addressIndex: Long): ByteVector {
        val priv = privateKey(addressIndex)
        val pub = priv.publicKey()
        val script = Script.pay2wpkh(pub)
        return Script.write(script).toByteVector()
    }

    fun address(addressIndex: Long): String {
        return Bitcoin.computeP2WpkhAddress(privateKey(addressIndex).publicKey(), chain.chainHash)
    }

    companion object {
        fun bip84BasePath(chain: Chain) = when (chain) {
            Chain.Testnet4, Chain.Testnet3, Chain.Regtest, Chain.Signet -> KeyPath.empty / hardened(84) / hardened(1)
            Chain.Mainnet -> KeyPath.empty / hardened(84) / hardened(0)
        }
    }
}

/**
 * We use a specific kind of swap-in where users send funds to a 2-of-2 multisig with a timelock refund.
 * Once confirmed, the swap-in utxos can be spent by one of two paths:
 *  - with a signature from both [userPublicKey] and [remoteServerPublicKey]
 *  - with a signature from [userPublicKey] after the [refundDelay]
 * The keys used are static across swaps to make recovery easier.
 */
data class SwapInOnChainKeys(
    private val chain: Chain,
    private val master: DeterministicWallet.ExtendedPrivateKey,
    val remoteServerPublicKey: PublicKey,
    val refundDelay: Int = DefaultSwapInParams.RefundDelay
) {
    private val userExtendedPrivateKey: DeterministicWallet.ExtendedPrivateKey = master.derivePrivateKey(swapInUserKeyPath(chain))
    private val userRefundExtendedPrivateKey: DeterministicWallet.ExtendedPrivateKey = master.derivePrivateKey(swapInUserRefundKeyPath(chain))

    val userPrivateKey: PrivateKey = userExtendedPrivateKey.privateKey
    val userPublicKey: PublicKey = userPrivateKey.publicKey()

    private val localServerExtendedPrivateKey: DeterministicWallet.ExtendedPrivateKey = master.derivePrivateKey(swapInLocalServerKeyPath(chain))
    fun localServerPrivateKey(remoteNodeId: PublicKey): PrivateKey = localServerExtendedPrivateKey.derivePrivateKey(perUserPath(remoteNodeId)).privateKey

    // legacy p2wsh-based swap-in protocol, with a fixed on-chain address
    val legacySwapInProtocol = SwapInProtocolLegacy(userPublicKey, remoteServerPublicKey, refundDelay)
    val legacyDescriptor = SwapInProtocolLegacy.descriptor(chain, master.extendedPublicKey, userExtendedPrivateKey.extendedPublicKey, remoteServerPublicKey, refundDelay)

    fun signSwapInputUserLegacy(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>): ByteVector64 {
        return legacySwapInProtocol.signSwapInputUser(fundingTx, index, parentTxOuts[fundingTx.txIn[index].outPoint.index.toInt()], userPrivateKey)
    }

    // this is a private descriptor that can be used as-is to recover swap-in funds once the refund delay has passed
    // it is compatible with address rotation as long as refund keys are derived directly from userRefundExtendedPrivateKey
    // README: it includes the user's master refund private key and is not safe to share !!
    val privateDescriptor = SwapInProtocol.privateDescriptor(chain, userPublicKey, remoteServerPublicKey, refundDelay, userRefundExtendedPrivateKey)

    // this is the public version of the above descriptor. It can be used to monitor a user's swap-in transaction
    // README: it cannot be used to derive private keys, but it can be used to derive swap-in addresses
    val publicDescriptor = SwapInProtocol.publicDescriptor(chain, userPublicKey, remoteServerPublicKey, refundDelay, DeterministicWallet.publicKey(userRefundExtendedPrivateKey))

    /**
     * @param addressIndex address index
     * @return the swap-in protocol that matches the input public key script
     */
    fun getSwapInProtocol(addressIndex: Int): SwapInProtocol {
        val userRefundPrivateKey: PrivateKey = userRefundExtendedPrivateKey.derivePrivateKey(addressIndex.toLong()).privateKey
        val userRefundPublicKey: PublicKey = userRefundPrivateKey.publicKey()
        return SwapInProtocol(userPublicKey, remoteServerPublicKey, userRefundPublicKey, refundDelay)
    }

    fun signSwapInputUser(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>, privateNonce: SecretNonce, userNonce: IndividualNonce, serverNonce: IndividualNonce, addressIndex: Int): Either<Throwable, ByteVector32> {
        val swapInProtocol = getSwapInProtocol(addressIndex)
        return swapInProtocol.signSwapInputUser(fundingTx, index, parentTxOuts, userPrivateKey, privateNonce, userNonce, serverNonce)
    }

    data class SwapInUtxo(val txOut: TxOut, val outPoint: OutPoint, val addressIndex: Int?)

    /**
     * Create a recovery transaction that spends swap-in outputs after their refund delay has passed.
     * @param utxos a list of swap-in utxos
     * @param scriptPubKey pubkey script to send funds to
     * @param feerate fee rate for the refund transaction
     * @return a signed transaction that spends our swap-in transaction. It cannot be published until `swapInTx` has enough confirmations
     */
    fun createRecoveryTransaction(utxos: List<SwapInUtxo>, scriptPubKey: ByteVector, feerate: FeeratePerKw): Transaction? {
        return if (utxos.isEmpty()) {
            null
        } else {
            val unsignedTx = Transaction(
                version = 2,
                txIn = utxos.map { TxIn(it.outPoint, sequence = refundDelay.toLong()) },
                txOut = listOf(TxOut(0.sat, scriptPubKey)),
                lockTime = 0
            )

            fun sign(tx: Transaction, inputIndex: Int, utxo: SwapInUtxo): Transaction {
                return when (val addressIndex = utxo.addressIndex) {
                    null -> {
                        val sig = legacySwapInProtocol.signSwapInputUser(tx, inputIndex, utxo.txOut, userPrivateKey)
                        tx.updateWitness(inputIndex, legacySwapInProtocol.witnessRefund(sig))
                    }
                    else -> {
                        val userRefundPrivateKey: PrivateKey = userRefundExtendedPrivateKey.derivePrivateKey(addressIndex.toLong()).privateKey
                        val swapInProtocol = getSwapInProtocol(addressIndex)
                        val sig = swapInProtocol.signSwapInputRefund(tx, inputIndex, utxos.map { it.txOut }, userRefundPrivateKey)
                        tx.updateWitness(inputIndex, swapInProtocol.witnessRefund(sig))
                    }
                }
            }

            val fees = run {
                val recoveryTx = utxos.foldIndexed(unsignedTx) { index, tx, utxo -> sign(tx, index, utxo) }
                Transactions.weight2fee(feerate, recoveryTx.weight())
            }
            val inputAmount = utxos.map { it.txOut.amount }.sum()
            val outputAmount = inputAmount - fees
            val unsignedTx1 = unsignedTx.copy(txOut = listOf(TxOut(outputAmount, scriptPubKey)))
            val signedTx = utxos.foldIndexed(unsignedTx1) { index, tx, utxo -> sign(tx, index, utxo) }
            signedTx
        }
    }

    /**
     * Create a recovery transaction that spends a swap-in transaction after the refund delay has passed
     * @param swapInTx swap-in transaction
     * @param address address to send funds to
     * @param feerate fee rate for the refund transaction
     * @return a signed transaction that spends our swap-in transaction. It cannot be published until `swapInTx` has enough confirmations
     */
    fun createRecoveryTransaction(swapInTx: Transaction, address: String, feerate: FeeratePerKw): Transaction? {
        val swapInProtocols = (0 until 100).map { getSwapInProtocol(it) }
        val utxos = swapInTx.txOut.filter { it.publicKeyScript.contentEquals(Script.write(legacySwapInProtocol.pubkeyScript)) || swapInProtocols.find { p -> p.serializedPubkeyScript == it.publicKeyScript } != null }
        return if (utxos.isEmpty()) {
            null
        } else {
            Bitcoin.addressToPublicKeyScript(chain.chainHash, address).right?.let { script ->
                val swapInUtxos = utxos.map { txOut ->
                    SwapInUtxo(
                        txOut = txOut,
                        outPoint = OutPoint(swapInTx, swapInTx.txOut.indexOf(txOut).toLong()),
                        addressIndex = if (Script.isPay2wsh(txOut.publicKeyScript.toByteArray())) null else swapInProtocols.indexOfFirst { it.serializedPubkeyScript == txOut.publicKeyScript }
                    )
                }
                createRecoveryTransaction(swapInUtxos, ByteVector(Script.write(script)), feerate)
            }
        }
    }

    companion object {
        private fun swapInKeyBasePath(chain: Chain) = when (chain) {
            Chain.Testnet4, Chain.Testnet3, Chain.Regtest, Chain.Signet -> KeyPath.empty / hardened(51) / hardened(0)
            Chain.Mainnet -> KeyPath.empty / hardened(52) / hardened(0)
        }

        fun swapInUserKeyPath(chain: Chain) = swapInKeyBasePath(chain) / hardened(0)

        fun swapInLocalServerKeyPath(chain: Chain) = swapInKeyBasePath(chain) / hardened(1)

        fun swapInUserRefundKeyPath(chain: Chain) = swapInKeyBasePath(chain) / hardened(2) / 0L

        fun encodedSwapInUserKeyPath(chain: Chain) = when (chain) {
            Chain.Testnet4, Chain.Testnet3, Chain.Regtest, Chain.Signet -> "51h/0h/0h"
            Chain.Mainnet -> "52h/0h/0h"
        }

        /** Swap-in servers use a different swap-in key for different users. */
        fun perUserPath(remoteNodeId: PublicKey): KeyPath {
            // We hash the remote node_id and break it into 2-byte values to get non-hardened path indices.
            val h = ByteArrayInput(Crypto.sha256(remoteNodeId.value))
            return KeyPath((0 until 16).map { _ -> LightningCodecs.u16(h).toLong() })
        }
    }
}

interface KeyManager {

    val nodeKeys: NodeKeys
    val finalOnChainWallet: Bip84OnChainKeys
    val swapInOnChainWallet: SwapInOnChainKeys

    /**
     * Create a BIP32 funding key path a new channel.
     * This function must return a unique path every time it is called.
     * This guarantees that unrelated channels use different BIP32 key paths and thus unrelated keys.
     *
     * @param isChannelOpener true if we initiated the channel open: this must be used to derive different key paths.
     */
    fun newFundingKeyPath(isChannelOpener: Boolean): KeyPath

    /**
     * Create channel keys based on a funding key path obtained using [newFundingKeyPath].
     * This function is deterministic: it must always return the same result when called with the same arguments.
     * This allows re-creating the channel keys based on the seed and its main parameters.
     */
    fun channelKeys(fundingKeyPath: KeyPath): ChannelKeys

}
