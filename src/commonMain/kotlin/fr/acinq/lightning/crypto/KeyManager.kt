package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPublicKey
import fr.acinq.lightning.channel.ChannelConfig
import fr.acinq.lightning.channel.ChannelKeys
import fr.acinq.lightning.channel.LocalParams
import fr.acinq.lightning.channel.RecoveredChannelKeys
import fr.acinq.lightning.transactions.Transactions.TransactionWithInputInfo

interface KeyManager {
    /** The node key that the same seed would have produced on the legacy eclair-based Phoenix implementation on Android. Useful to automate the migration. */
    val legacyNodeKey: DeterministicWallet.ExtendedPrivateKey
    val nodeKey: DeterministicWallet.ExtendedPrivateKey
    val nodeId: PublicKey

    fun bip84PrivateKey(account: Long, addressIndex: Long): PrivateKey

    fun bip84Address(account: Long, addressIndex: Long): String

    fun bip84Xpub(account: Long): String

    fun closingPubkeyScript(fundingPubKey: PublicKey): Pair<PublicKey, ByteArray>

    fun fundingPublicKey(keyPath: KeyPath): ExtendedPublicKey

    fun revocationPoint(channelKeyPath: KeyPath): ExtendedPublicKey

    fun paymentPoint(channelKeyPath: KeyPath): ExtendedPublicKey

    fun delayedPaymentPoint(channelKeyPath: KeyPath): ExtendedPublicKey

    fun htlcPoint(channelKeyPath: KeyPath): ExtendedPublicKey

    fun commitmentSecret(channelKeyPath: KeyPath, index: Long): PrivateKey

    fun commitmentPoint(channelKeyPath: KeyPath, index: Long): PublicKey

    fun commitmentSecret(shaSeed: ByteVector32, index: Long): PrivateKey

    fun commitmentPoint(shaSeed: ByteVector32, index: Long): PublicKey

    fun channelKeyPath(fundingKeyPath: KeyPath, channelConfig: ChannelConfig): KeyPath

    fun channelKeyPath(localParams: LocalParams, channelConfig: ChannelConfig): KeyPath

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
     * generate channel-specific keys and secrets (note that we cannot re-compute the channel's funding private key)
     * @params fundingPubKey funding public key
     * @return channel keys and secrets
     */
    fun recoverChannelKeys(fundingPubKey: PublicKey): RecoveredChannelKeys

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

}
