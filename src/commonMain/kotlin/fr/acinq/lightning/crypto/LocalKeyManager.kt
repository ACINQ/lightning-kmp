package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.bitcoin.DeterministicWallet.derivePrivateKey
import fr.acinq.bitcoin.DeterministicWallet.hardened
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.lightning.Lightning.secureRandom
import fr.acinq.lightning.NodeParams.Chain
import fr.acinq.lightning.channel.*
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.transactions.Transactions

data class LocalKeyManager(val seed: ByteVector, val chain: Chain) : KeyManager {

    private val master = DeterministicWallet.generate(seed)
    @Suppress("DEPRECATION")
    override val legacyNodeKey: DeterministicWallet.ExtendedPrivateKey = derivePrivateKey(master, eclairNodeKeyBasePath(chain))
    override val nodeKey: DeterministicWallet.ExtendedPrivateKey = derivePrivateKey(master, nodeKeyBasePath(chain))
    override val nodeId: PublicKey get() = nodeKey.publicKey

    override fun toString(): String {
        return "LocalKeyManager(seed=<redacted>,chain=$chain)"
    }

    private fun internalKeyPath(channelKeyPath: List<Long>, index: Long): List<Long> = channelKeyBasePath(chain) + channelKeyPath + index

    private fun internalKeyPath(channelKeyPath: KeyPath, index: Long): List<Long> = internalKeyPath(channelKeyPath.path, index)

    fun privateKey(keyPath: KeyPath): DeterministicWallet.ExtendedPrivateKey = derivePrivateKey(master, keyPath)

    fun privateKey(keyPath: List<Long>): DeterministicWallet.ExtendedPrivateKey = derivePrivateKey(master, keyPath)

    private fun publicKey(keyPath: List<Long>): DeterministicWallet.ExtendedPublicKey = DeterministicWallet.publicKey(privateKey(keyPath))

    private fun shaSeed(channelKeyPath: KeyPath) = ByteVector32(Crypto.sha256(privateKey(internalKeyPath(channelKeyPath, hardened(5))).privateKey.value.concat(1.toByte())))

    override fun bip84PrivateKey(account: Long, addressIndex: Long): PrivateKey {
        val path = bip84BasePath(chain) + hardened(account) + 0 + addressIndex
        return derivePrivateKey(master, path).privateKey
    }

    override fun bip84Address(account: Long, addressIndex: Long): String {
        return Bitcoin.computeP2WpkhAddress(bip84PrivateKey(account, addressIndex).publicKey(), chain.chainHash)
    }

    override fun bip84Xpub(account: Long): String {
        return DeterministicWallet.encode(
            input = DeterministicWallet.publicKey(privateKey(bip84BasePath(chain) + hardened(account))),
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
        return KeyPath(listOf(next(), next(), next(), next(), next(), next(), next(), next(), last))
    }

    override fun fundingPublicKey(keyPath: KeyPath) = publicKey(internalKeyPath(keyPath, hardened(0)))

    override fun revocationPoint(channelKeyPath: KeyPath) = publicKey(internalKeyPath(channelKeyPath, hardened(1)))

    internal fun paymentPoint(channelKeyPath: KeyPath) = publicKey(internalKeyPath(channelKeyPath, hardened(2)))

    override fun delayedPaymentPoint(channelKeyPath: KeyPath) = publicKey(internalKeyPath(channelKeyPath, hardened(3)))

    override fun htlcPoint(channelKeyPath: KeyPath) = publicKey(internalKeyPath(channelKeyPath, hardened(4)))

    override fun commitmentSecret(channelKeyPath: KeyPath, index: Long) = commitmentSecret(shaSeed(channelKeyPath), index)

    override fun commitmentPoint(channelKeyPath: KeyPath, index: Long) = commitmentPoint(shaSeed(channelKeyPath), index)

    override fun commitmentSecret(shaSeed: ByteVector32, index: Long): PrivateKey = Generators.perCommitSecret(shaSeed, index)

    override fun commitmentPoint(shaSeed: ByteVector32, index: Long): PublicKey = Generators.perCommitPoint(shaSeed, index)

    override fun channelKeyPath(fundingKeyPath: KeyPath, channelConfig: ChannelConfig): KeyPath = when {
        // deterministic mode: use the funding pubkey to compute the channel key path
        channelConfig.hasOption(ChannelConfigOption.FundingPubKeyBasedChannelKeyPath) -> channelKeyPath(fundingPublicKey(fundingKeyPath))
        // legacy mode:  we reuse the funding key path as our channel key path
        else -> fundingKeyPath
    }

    override fun channelKeyPath(localParams: LocalParams, channelConfig: ChannelConfig): KeyPath = channelKeyPath(localParams.fundingKeyPath, channelConfig)

    override fun channelKeys(fundingKeyPath: KeyPath): ChannelKeys {
        val fundingPubKey = fundingPublicKey(fundingKeyPath)
        val recoveredChannelKeys = recoverChannelKeys(fundingPubKey.publicKey)
        return ChannelKeys(
            fundingKeyPath,
            fundingPrivateKey = privateKey(fundingPubKey.path).privateKey,
            paymentKey = recoveredChannelKeys.paymentKey,
            delayedPaymentKey = recoveredChannelKeys.delayedPaymentKey,
            htlcKey = recoveredChannelKeys.htlcKey,
            revocationKey = recoveredChannelKeys.revocationKey,
            shaSeed = recoveredChannelKeys.shaSeed
        )
    }

    override fun recoverChannelKeys(fundingPubKey: PublicKey): RecoveredChannelKeys {
        val channelKeyPath = channelKeyPath(fundingPubKey)
        return RecoveredChannelKeys(
            fundingPubKey,
            paymentKey = privateKey(paymentPoint(channelKeyPath).path).privateKey,
            delayedPaymentKey = privateKey(delayedPaymentPoint(channelKeyPath).path).privateKey,
            htlcKey = privateKey(htlcPoint(channelKeyPath).path).privateKey,
            revocationKey = privateKey(revocationPoint(channelKeyPath).path).privateKey,
            shaSeed = shaSeed(channelKeyPath)
        )
    }

    override fun sign(tx: Transactions.TransactionWithInputInfo, privateKey: PrivateKey): ByteVector64 {
        return Transactions.sign(tx, privateKey)
    }

    override fun sign(tx: Transactions.TransactionWithInputInfo, privateKey: PrivateKey, remotePoint: PublicKey, sigHash: Int): ByteVector64 {
        val currentKey = Generators.derivePrivKey(privateKey, remotePoint)
        return Transactions.sign(tx, currentKey, sigHash)
    }

    override fun sign(tx: Transactions.TransactionWithInputInfo, privateKey: PrivateKey, remoteSecret: PrivateKey): ByteVector64 {
        val currentKey = Generators.revocationPrivKey(privateKey, remoteSecret)
        return Transactions.sign(tx, currentKey)
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

        fun channelKeyPath(fundingPubKey: DeterministicWallet.ExtendedPublicKey): KeyPath = channelKeyPath(fundingPubKey.publicKey)

        fun channelKeyBasePath(chain: Chain) = when (chain) {
            Chain.Regtest, Chain.Testnet -> listOf(hardened(48), hardened(1))
            Chain.Mainnet -> listOf(hardened(50), hardened(1))
        }

        /** Path for node keys generated by eclair-core */
        @Deprecated("used for backward-compat with eclair-core", replaceWith = ReplaceWith("nodeKeyBasePath(chain)"))
        fun eclairNodeKeyBasePath(chain: Chain) = when (chain) {
            Chain.Regtest, Chain.Testnet -> listOf(hardened(46), hardened(0))
            Chain.Mainnet -> listOf(hardened(47), hardened(0))
        }

        fun nodeKeyBasePath(chain: Chain) = when (chain) {
            Chain.Regtest, Chain.Testnet -> listOf(hardened(48), hardened(0))
            Chain.Mainnet -> listOf(hardened(50), hardened(0))
        }

        fun bip84BasePath(chain: Chain) = when (chain) {
            Chain.Regtest, Chain.Testnet -> listOf(hardened(84), hardened(1))
            Chain.Mainnet -> listOf(hardened(84), hardened(0))
        }
    }
}
