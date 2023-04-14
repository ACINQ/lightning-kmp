package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.DeterministicWallet.derivePrivateKey
import fr.acinq.bitcoin.DeterministicWallet.hardened
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.lightning.Lightning.secureRandom
import fr.acinq.lightning.NodeParams.Chain
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.crypto.Generators.derive
import fr.acinq.lightning.crypto.Generators.deriveRevocation
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.transactions.Transactions

data class LocalKeyManager(val seed: ByteVector, val chain: Chain) : KeyManager {

    private val master = DeterministicWallet.generate(seed)

    override val nodeKeys: KeyManager.NodeKeys = KeyManager.NodeKeys(
        legacyNodeKey = @Suppress("DEPRECATION") derivePrivateKey(master, eclairNodeKeyBasePath(chain)),
        nodeKey = derivePrivateKey(master, nodeKeyBasePath(chain)),
    )

    private val channelKeyBasePath: KeyPath = channelKeyBasePath(chain)
    private val bip84BasePath: KeyPath = bip84BasePath(chain)

    override fun toString(): String = "LocalKeyManager(seed=<redacted>,chain=$chain)"

    private fun privateKey(keyPath: KeyPath): PrivateKey = derivePrivateKey(master, keyPath).privateKey

    override fun bip84PrivateKey(account: Long, addressIndex: Long): PrivateKey {
        val path = bip84BasePath I hardened(account) I 0 I addressIndex
        return privateKey(path)
    }

    override fun bip84Address(account: Long, addressIndex: Long): String {
        return Bitcoin.computeP2WpkhAddress(bip84PrivateKey(account, addressIndex).publicKey(), chain.chainHash)
    }

    override fun bip84Xpub(account: Long): String {
        return DeterministicWallet.encode(
            input = DeterministicWallet.publicKey(derivePrivateKey(master, bip84BasePath I hardened(account))),
            prefix = when (chain) {
                Chain.Testnet, Chain.Regtest -> DeterministicWallet.vpub
                Chain.Mainnet -> DeterministicWallet.zpub
            }
        )
    }

    override fun closingPubkeyScript(fundingPubKey: PublicKey): Pair<PublicKey, ByteArray> {
        val priv = bip84PrivateKey(Peer.finalWalletAccount, 0)
        val pub = priv.publicKey()
        val script = Script.pay2wpkh(pub)
        return Pair(pub, Script.write(script))
    }

    override fun newFundingKeyPath(isInitiator: Boolean): KeyPath {
        val last = hardened(if (isInitiator) 1 else 0)
        fun next() = secureRandom.nextInt().toLong() and 0xFFFFFFFF
        return KeyPath.empty I next() I next() I next() I next() I next() I next() I next() I next() I last
    }

    override fun channelKeys(fundingKeyPath: KeyPath): KeyManager.ChannelKeys {
        // deterministic mode: use the funding pubkey to compute the channel key path
        val fundingPrivateKey = privateKey(channelKeyBasePath I fundingKeyPath I hardened(0))
        // we use the recovery process even in the normal case, which guarantees it works when we need it
        val recoveredChannelKeys = recoverChannelKeys(fundingPrivateKey.publicKey())
        return KeyManager.ChannelKeys(
            fundingKeyPath,
            fundingPrivateKey = fundingPrivateKey,
            paymentKey = recoveredChannelKeys.paymentKey,
            delayedPaymentKey = recoveredChannelKeys.delayedPaymentKey,
            htlcKey = recoveredChannelKeys.htlcKey,
            revocationKey = recoveredChannelKeys.revocationKey,
            shaSeed = recoveredChannelKeys.shaSeed
        )
    }

    /**
     * generate channel-specific keys and secrets (note that we cannot re-compute the channel's funding private key)
     * @params fundingPubKey funding public key
     * @return channel keys and secrets
     */
    fun recoverChannelKeys(fundingPubKey: PublicKey): RecoveredChannelKeys {
        val channelKeyPrefix = channelKeyBasePath I channelKeyPath(fundingPubKey)
        return RecoveredChannelKeys(
            fundingPubKey,
            paymentKey = privateKey(channelKeyPrefix I hardened(2)),
            delayedPaymentKey = privateKey(channelKeyPrefix I hardened(3)),
            htlcKey = privateKey(channelKeyPrefix I hardened(4)),
            revocationKey = privateKey(channelKeyPrefix I hardened(1)),
            shaSeed = privateKey(channelKeyPrefix I hardened(5)).value.concat(1).sha256()
        )
    }

    /**
     * Channel keys recovered from the channel's funding public key (note that we cannot recover the funding private key).
     * These keys can be used to spend our outputs from a commit tx that has been published to the blockchain, without any other information than
     * the node's seed ("backup less backup")
     */
    data class RecoveredChannelKeys(
        val fundingPubKey: PublicKey,
        val paymentKey: PrivateKey,
        val delayedPaymentKey: PrivateKey,
        val htlcKey: PrivateKey,
        val revocationKey: PrivateKey,
        val shaSeed: ByteVector32
    ) {
        val htlcBasepoint: PublicKey = htlcKey.publicKey()
        val paymentBasepoint: PublicKey = paymentKey.publicKey()
        val delayedPaymentBasepoint: PublicKey = delayedPaymentKey.publicKey()
        val revocationBasepoint: PublicKey = revocationKey.publicKey()
    }

    /**
     * WARNING: If you change the paths below, keys will change (including your node id) even if the seed remains the same!!!
     * Note that the node path and the above channel path are on different branches so even if the
     * node key is compromised there is no way to retrieve the wallet keys
     */
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
        fun channelKeyPath(fundingPubKey: PublicKey): KeyPath {
            val buffer = fundingPubKey.value.sha256().toByteArray()

            val path = sequence {
                var i = 0
                while (true) {
                    yield(Pack.int32BE(buffer, i).toUInt().toLong())
                    i += 4
                }
            }
            return KeyPath(path.take(8).toList())
        }

        fun channelKeyBasePath(chain: Chain) = when (chain) {
            Chain.Regtest, Chain.Testnet -> KeyPath.empty I hardened(48) I hardened(1)
            Chain.Mainnet -> KeyPath.empty I hardened(50) I hardened(1)
        }

        /** Path for node keys generated by eclair-core */
        @Deprecated("used for backward-compat with eclair-core", replaceWith = ReplaceWith("nodeKeyBasePath(chain)"))
        fun eclairNodeKeyBasePath(chain: Chain) = when (chain) {
            Chain.Regtest, Chain.Testnet -> KeyPath.empty I hardened(46) I hardened(0)
            Chain.Mainnet -> KeyPath.empty I hardened(47) I hardened(0)
        }

        fun nodeKeyBasePath(chain: Chain) = when (chain) {
            Chain.Regtest, Chain.Testnet -> KeyPath.empty I hardened(48) I hardened(0)
            Chain.Mainnet -> KeyPath.empty I hardened(50) I hardened(0)
        }

        fun bip84BasePath(chain: Chain) = when (chain) {
            Chain.Regtest, Chain.Testnet -> KeyPath.empty I hardened(84) I hardened(1)
            Chain.Mainnet -> KeyPath.empty I hardened(84) I hardened(0)
        }
    }
}

infix fun KeyPath.I(index: Long): KeyPath = this.append(index)

infix fun KeyPath.I(other: KeyPath): KeyPath = this.append(other)
