package fr.acinq.eklair.crypto

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPublicKey
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.eklair.Features
import fr.acinq.eklair.ShortChannelId
import fr.acinq.eklair.channel.ChannelVersion
import fr.acinq.eklair.channel.LocalParams
import fr.acinq.eklair.transactions.Transactions.TransactionWithInputInfo
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

interface KeyManager {
    val nodeKey: DeterministicWallet.ExtendedPrivateKey

    val nodeId: PublicKey

    fun fundingPublicKey(keyPath: KeyPath): ExtendedPublicKey

    fun revocationPoint(channelKeyPath: KeyPath): ExtendedPublicKey

    fun paymentPoint(channelKeyPath: KeyPath): ExtendedPublicKey

    fun delayedPaymentPoint(channelKeyPath: KeyPath): ExtendedPublicKey

    fun htlcPoint(channelKeyPath: KeyPath): ExtendedPublicKey

    fun commitmentSecret(channelKeyPath: KeyPath, index: Long): PrivateKey

    fun commitmentPoint(channelKeyPath: KeyPath, index: Long): PublicKey

    fun channelKeyPath(localParams: LocalParams, channelVersion: ChannelVersion): KeyPath =
        if (channelVersion.isSet(ChannelVersion.USE_PUBKEY_KEYPATH_BIT)) {
            // deterministic mode: use the funding pubkey to compute the channel key path
            channelKeyPath(fundingPublicKey(localParams.fundingKeyPath))
        } else {
            // legacy mode:  we reuse the funding key path as our channel key path
            localParams.fundingKeyPath
        }

    /**
     *
     * @param isFunder true if we're funding this channel
     * @return a partial key path for a new funding public key. This key path will be extended:
     *         - with a specific "chain" prefix
     *         - with a specific "funding pubkey" suffix
     */
    fun newFundingKeyPath(isFunder: Boolean) : KeyPath

    /**
     *
     * @param tx        input transaction
     * @param publicKey extended public key
     * @return a signature generated with the private key that matches the input
     *         extended public key
     */
    fun sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey): ByteVector64

    /**
     * This method is used to spend funds send to htlc keys/delayed keys
     *
     * @param tx          input transaction
     * @param publicKey   extended public key
     * @param remotePoint remote point
     * @return a signature generated with a private key generated from the input keys's matching
     *         private key and the remote point.
     */
    fun sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey, remotePoint: PublicKey): ByteVector64

    /**
     * Ths method is used to spend revoked transactions, with the corresponding revocation key
     *
     * @param tx           input transaction
     * @param publicKey    extended public key
     * @param remoteSecret remote secret
     * @return a signature generated with a private key generated from the input keys's matching
     *         private key and the remote secret.
     */
    fun sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey, remoteSecret: PrivateKey): ByteVector64

    /**
     * Sign a channel announcement message
     *
     * @param fundingKeyPath BIP32 path of the funding public key
     * @param chainHash chain hash
     * @param shortChannelId short channel id
     * @param remoteNodeId remote node id
     * @param remoteFundingKey remote funding pubkey
     * @param features channel features
     * @return a (nodeSig, bitcoinSig) pair. nodeSig is the signature of the channel announcement with our node's
     *         private key, bitcoinSig is the signature of the channel announcement with our funding private key
     */
    fun signChannelAnnouncement(fundingKeyPath: KeyPath, chainHash: ByteVector32, shortChannelId: ShortChannelId, remoteNodeId: PublicKey, remoteFundingKey: PublicKey, features: Features): Pair<ByteVector64, ByteVector64>

    companion object {
        /**
         * Create a BIP32 path from a public key. This path will be used to derive channel keys.
         * Having channel keys derived from the funding public keys makes it very easy to retrieve your funds when've you've lost your data:
         * - connect to your peer and use DLP to get them to publish their remote commit tx
         * - retrieve the commit tx from the bitcoin network, extract your funding pubkey from its witness data
         * - recompute your channel keys and spend your output
         *
         * @param fundingPubKey funding public key
         * @return a BIP32 path
         */
        @OptIn(ExperimentalUnsignedTypes::class)
        fun channelKeyPath(fundingPubKey: PublicKey) : KeyPath {
            val buffer = sha256(fundingPubKey.value)

            val path = sequence {
                var i = 0
                while (true) {
                    yield(Pack.int32BE(buffer, i).toUInt().toLong())
                    i += 4
                }
            }
            return KeyPath(path.take(8).toList())
        }

        fun channelKeyPath(fundingPubKey: DeterministicWallet.ExtendedPublicKey) : KeyPath = channelKeyPath(fundingPubKey.publicKey)

        val serializationModule = SerializersModule {
            polymorphic(KeyManager::class) {
                subclass(LocalKeyManager.serializer())
            }
        }
    }
}
