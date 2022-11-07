package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.DeterministicWallet.derivePrivateKey
import fr.acinq.bitcoin.DeterministicWallet.hardened
import fr.acinq.lightning.Lightning.secureRandom
import fr.acinq.lightning.channel.ChannelKeys
import fr.acinq.lightning.channel.RecoveredChannelKeys
import fr.acinq.lightning.transactions.Transactions

data class LocalKeyManager(val seed: ByteVector, val chainHash: ByteVector32) : KeyManager {

    private val master = DeterministicWallet.generate(seed)
    override val legacyNodeKey: DeterministicWallet.ExtendedPrivateKey = derivePrivateKey(master, eclairNodeKeyBasePath(chainHash))
    override val nodeKey: DeterministicWallet.ExtendedPrivateKey = derivePrivateKey(master, nodeKeyBasePath(chainHash))
    override val nodeId: PublicKey get() = nodeKey.publicKey

    override fun toString(): String {
        return "LocalKeyManager(seed=xxx,chainHash=$chainHash)"
    }

    private fun internalKeyPath(channelKeyPath: List<Long>, index: Long): List<Long> = channelKeyBasePath(chainHash) + channelKeyPath + index

    private fun internalKeyPath(channelKeyPath: KeyPath, index: Long): List<Long> = internalKeyPath(channelKeyPath.path, index)

    fun privateKey(keyPath: KeyPath): DeterministicWallet.ExtendedPrivateKey = derivePrivateKey(master, keyPath)

    fun privateKey(keyPath: List<Long>): DeterministicWallet.ExtendedPrivateKey = derivePrivateKey(master, keyPath)

    private fun publicKey(keyPath: KeyPath): DeterministicWallet.ExtendedPublicKey = DeterministicWallet.publicKey(privateKey(keyPath))

    private fun publicKey(keyPath: List<Long>): DeterministicWallet.ExtendedPublicKey = DeterministicWallet.publicKey(privateKey(keyPath))

    private fun fundingPrivateKey(channelKeyPath: KeyPath) = privateKey(internalKeyPath(channelKeyPath, hardened(0)))

    private fun revocationSecret(channelKeyPath: KeyPath) = privateKey(internalKeyPath(channelKeyPath, hardened(1)))

    private fun paymentSecret(channelKeyPath: KeyPath) = privateKey(internalKeyPath(channelKeyPath, hardened(2)))

    private fun delayedPaymentSecret(channelKeyPath: KeyPath) = privateKey(internalKeyPath(channelKeyPath, hardened(3)))

    private fun htlcSecret(channelKeyPath: KeyPath) = privateKey(internalKeyPath(channelKeyPath, hardened(4)))

    private fun shaSeed(channelKeyPath: KeyPath) = ByteVector32(Crypto.sha256(privateKey(internalKeyPath(channelKeyPath, hardened(5))).privateKey.value.concat(1.toByte())))

    private fun shaSeed(channelKeyPath: List<Long>) = ByteVector32(Crypto.sha256(privateKey(internalKeyPath(channelKeyPath, hardened(5))).privateKey.value.concat(1.toByte())))

    override fun bip84PrivateKey(account: Long, addressIndex: Long): PrivateKey {
        val path = bip84BasePath(chainHash) + hardened(account) + 0 + addressIndex
        return derivePrivateKey(master, path).privateKey
    }

    override fun bip84Address(account: Long, addressIndex: Long): String {
        return Bitcoin.computeP2WpkhAddress(bip84PrivateKey(account, addressIndex).publicKey(), chainHash)
    }

    override fun closingPubkeyScript(fundingPubKey: PublicKey): Pair<PublicKey, ByteArray> {
        val priv = bip84PrivateKey(0, 0)
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

    override fun paymentPoint(channelKeyPath: KeyPath) = publicKey(internalKeyPath(channelKeyPath, hardened(2)))

    override fun delayedPaymentPoint(channelKeyPath: KeyPath) = publicKey(internalKeyPath(channelKeyPath, hardened(3)))

    override fun htlcPoint(channelKeyPath: KeyPath) = publicKey(internalKeyPath(channelKeyPath, hardened(4)))

    override fun commitmentSecret(channelKeyPath: KeyPath, index: Long) = commitmentSecret(shaSeed(channelKeyPath), index)

    override fun commitmentPoint(channelKeyPath: KeyPath, index: Long) = commitmentPoint(shaSeed(channelKeyPath), index)

    override fun commitmentSecret(shaSeed: ByteVector32, index: Long): PrivateKey = Generators.perCommitSecret(shaSeed, index)

    override fun commitmentPoint(shaSeed: ByteVector32, index: Long): PublicKey = Generators.perCommitPoint(shaSeed, index)

    override fun channelKeys(fundingKeyPath: KeyPath): ChannelKeys {
        val fundingPubKey = fundingPublicKey(fundingKeyPath)
        val recoveredChannelKeys = recoverChannelKeys(fundingPubKey.publicKey)
        return ChannelKeys(
            fundingKeyPath,
            privateKey(fundingPubKey.path).privateKey,
            recoveredChannelKeys.paymentKey,
            recoveredChannelKeys.delayedPaymentKey,
            recoveredChannelKeys.htlcKey,
            recoveredChannelKeys.revocationKey,
            recoveredChannelKeys.shaSeed
        )
    }

    override fun recoverChannelKeys(fundingPubKey: PublicKey): RecoveredChannelKeys {
        val channelKeyPath = KeyManager.channelKeyPath(fundingPubKey)
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

    companion object {
        fun channelKeyBasePath(chainHash: ByteVector32) = when (chainHash) {
            Block.RegtestGenesisBlock.hash, Block.TestnetGenesisBlock.hash -> listOf(hardened(48), hardened(1))
            Block.LivenetGenesisBlock.hash -> listOf(hardened(50), hardened(1))
            else -> throw IllegalArgumentException("unknown chain hash $chainHash")
        }

        /** Path for node keys generated by eclair-core */
        fun eclairNodeKeyBasePath(chainHash: ByteVector32) = when (chainHash) {
            Block.RegtestGenesisBlock.hash, Block.TestnetGenesisBlock.hash -> listOf(hardened(46), hardened(0))
            Block.LivenetGenesisBlock.hash -> listOf(hardened(47), hardened(0))
            else -> throw IllegalArgumentException("unknown chain hash $chainHash")
        }

        // WARNING: if you change this path, you will change your node id even if the seed remains the same!!!
        // Note that the node path and the above channel path are on different branches so even if the
        // node key is compromised there is no way to retrieve the wallet keys
        fun nodeKeyBasePath(chainHash: ByteVector32) = when (chainHash) {
            Block.RegtestGenesisBlock.hash, Block.TestnetGenesisBlock.hash -> listOf(hardened(48), hardened(0))
            Block.LivenetGenesisBlock.hash -> listOf(hardened(50), hardened(0))
            else -> throw IllegalArgumentException("unknown chain hash $chainHash")
        }

        fun bip84BasePath(chainHash: ByteVector32) = when (chainHash) {
            Block.TestnetGenesisBlock.hash, Block.RegtestGenesisBlock.hash -> listOf(hardened(84), hardened(1))
            Block.LivenetGenesisBlock.hash -> listOf(hardened(84), hardened(0))
            else -> throw IllegalArgumentException("unknown chain hash $chainHash")
        }
    }
}
