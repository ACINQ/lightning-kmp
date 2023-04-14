package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.*
import fr.acinq.lightning.transactions.Transactions.TransactionWithInputInfo

interface KeyManager {

    val nodeKeys: NodeKeys

    fun bip84PrivateKey(account: Long, addressIndex: Long): PrivateKey

    fun bip84Address(account: Long, addressIndex: Long): String

    fun bip84Xpub(account: Long): String

    fun closingPubkeyScript(fundingPubKey: PublicKey): Pair<PublicKey, ByteArray>

    /**
     *
     * @param isInitiator true if we are the channel initiator
     * @return a partial key path for a new funding public key. This key path will be extended:
     *         - with a specific "chain" prefix
     *         - with a specific "funding pubkey" suffix
     */
    fun newFundingKeyPath(isInitiator: Boolean): KeyPath

    /**
     * generate channel-specific keys and secrets
     * @params fundingKeyPath funding public key BIP32 path
     * @return channel keys and secrets
     */
    fun channelKeys(fundingKeyPath: KeyPath): ChannelKeys

    /**
     *
     * @param tx        input transaction
     * @param privateKey private key
     * @return a signature generated with the input private key
     */
    fun sign(tx: TransactionWithInputInfo, privateKey: PrivateKey): ByteVector64

    /**
     * This method is used to spend funds send to htlc keys/delayed keys
     *
     * @param tx          input transaction
     * @param privateKey  private key
     * @param remotePoint remote point
     * @return a signature generated with a private key generated from the input private key and the remote point.
     */
    fun sign(tx: TransactionWithInputInfo, privateKey: PrivateKey, remotePoint: PublicKey, sigHash: Int): ByteVector64

    /**
     * Ths method is used to spend revoked transactions, with the corresponding revocation key
     *
     * @param tx           input transaction
     * @param privateKey   private key
     * @param remoteSecret remote secret
     * @return a signature generated with a private key generated from the input private key and the remote secret.
     */
    fun sign(tx: TransactionWithInputInfo, privateKey: PrivateKey, remoteSecret: PrivateKey): ByteVector64

    /**
     * Keys used for the node. They are used to generate the node id, to secure communication with other peers, and
     * to sign network-wide public announcements.
     */
    data class NodeKeys(
        /** The node key that the same seed would have produced on the legacy eclair-based Phoenix implementation on Android. Useful to automate the migration. */
        val legacyNodeKey: DeterministicWallet.ExtendedPrivateKey,
        val nodeKey: DeterministicWallet.ExtendedPrivateKey,
    )

    /**
     * Secrets and keys for a given channel.
     * How these keys are generated depends on the [KeyManager] implementation.
     */
    data class ChannelKeys(
        val fundingKeyPath: KeyPath,
        val fundingPrivateKey: PrivateKey,
        val paymentKey: PrivateKey,
        val delayedPaymentKey: PrivateKey,
        val htlcKey: PrivateKey,
        val revocationKey: PrivateKey,
        val shaSeed: ByteVector32
    ) {
        val fundingPubKey: PublicKey = fundingPrivateKey.publicKey()
        val htlcBasepoint: PublicKey = htlcKey.publicKey()
        val paymentBasepoint: PublicKey = paymentKey.publicKey()
        val delayedPaymentBasepoint: PublicKey = delayedPaymentKey.publicKey()
        val revocationBasepoint: PublicKey = revocationKey.publicKey()
        val temporaryChannelId: ByteVector32 = (ByteVector(ByteArray(33) { 0 }) + revocationBasepoint.value).sha256()
        fun commitmentPoint(index: Long): PublicKey = Generators.perCommitPoint(shaSeed, index)
        fun commitmentSecret(index: Long): PrivateKey = Generators.perCommitSecret(shaSeed, index)
    }

}
