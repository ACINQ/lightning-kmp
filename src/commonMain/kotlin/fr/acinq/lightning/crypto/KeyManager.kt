package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.DeterministicWallet.hardened
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.transactions.Scripts
import fr.acinq.lightning.utils.toByteVector

interface KeyManager {

    val nodeKeys: NodeKeys

    /**
     * Picks a random funding key path for a new channel.
     * @param isInitiator true if we are the channel initiator
     */
    fun newFundingKeyPath(isInitiator: Boolean): KeyPath

    /**
     * Generate channel-specific keys and secrets
     * @params fundingKeyPath funding public key BIP32 path
     * @return channel keys and secrets
     */
    fun channelKeys(fundingKeyPath: KeyPath): ChannelKeys

    val finalOnChainWallet: Bip84OnChainKeys

    val swapInOnChainWallet: SwapInOnChainKeys

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
        val fundingKey: (Long) -> PrivateKey,
        val paymentKey: PrivateKey,
        val delayedPaymentKey: PrivateKey,
        val htlcKey: PrivateKey,
        val revocationKey: PrivateKey,
        val shaSeed: ByteVector32,
    ) {
        fun fundingPubKey(index: Long): PublicKey = fundingKey(index).publicKey()
        val htlcBasepoint: PublicKey = htlcKey.publicKey()
        val paymentBasepoint: PublicKey = paymentKey.publicKey()
        val delayedPaymentBasepoint: PublicKey = delayedPaymentKey.publicKey()
        val revocationBasepoint: PublicKey = revocationKey.publicKey()
        val temporaryChannelId: ByteVector32 = (ByteVector(ByteArray(33) { 0 }) + revocationBasepoint.value).sha256()
        fun commitmentPoint(index: Long): PublicKey = Bolt3Derivation.perCommitPoint(shaSeed, index)
        fun commitmentSecret(index: Long): PrivateKey = Bolt3Derivation.perCommitSecret(shaSeed, index)
    }

    data class Bip84OnChainKeys(
        private val chain: NodeParams.Chain,
        private val master: DeterministicWallet.ExtendedPrivateKey,
        val account: Long
    ) {
        private val xpriv = DeterministicWallet.derivePrivateKey(master, bip84BasePath(chain) / hardened(account))

        val xpub: String = DeterministicWallet.encode(
            input = DeterministicWallet.publicKey(xpriv),
            prefix = when (chain) {
                NodeParams.Chain.Testnet, NodeParams.Chain.Regtest -> DeterministicWallet.vpub
                NodeParams.Chain.Mainnet -> DeterministicWallet.zpub
            }
        )

        fun privateKey(addressIndex: Long): PrivateKey {
            return DeterministicWallet.derivePrivateKey(xpriv, KeyPath.empty / 0 / addressIndex).privateKey
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
            fun bip84BasePath(chain: NodeParams.Chain) = when (chain) {
                NodeParams.Chain.Regtest, NodeParams.Chain.Testnet -> KeyPath.empty / hardened(84) / hardened(1)
                NodeParams.Chain.Mainnet -> KeyPath.empty / hardened(84) / hardened(0)
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
        private val chain: NodeParams.Chain,
        private val master: DeterministicWallet.ExtendedPrivateKey,
        val remoteServerPublicKey: PublicKey,
        val refundDelay: Long = SwapInRefundDelay
    ) {
        val userPrivateKey: PrivateKey = DeterministicWallet.derivePrivateKey(master, swapInKeyBasePath(chain) / hardened(0)).privateKey
        val userPublicKey: PublicKey = userPrivateKey.publicKey()

        val localServerPrivateKey: PrivateKey = DeterministicWallet.derivePrivateKey(master, swapInKeyBasePath(chain) / hardened(1)).privateKey
        val localServerPublicKey: PublicKey = localServerPrivateKey.publicKey()

        val redeemScript: List<ScriptElt> = Scripts.swapIn2of2(userPublicKey, remoteServerPublicKey, refundDelay)
        val pubkeyScript: List<ScriptElt> = Script.pay2wsh(redeemScript)
        val address: String = Bitcoin.addressFromPublicKeyScript(chain.chainHash, pubkeyScript)!!

        companion object {
            /** When doing a swap-in, the user's funds are locked in a 2-of-2: they can claim them unilaterally after that delay. */
            const val SwapInRefundDelay: Long = 144 * 30 * 6 // ~6 months

            fun swapInKeyBasePath(chain: NodeParams.Chain) = when (chain) {
                NodeParams.Chain.Regtest, NodeParams.Chain.Testnet -> KeyPath.empty / hardened(51) / hardened(0)
                NodeParams.Chain.Mainnet -> KeyPath.empty / hardened(52) / hardened(0)
            }
        }
    }

}
