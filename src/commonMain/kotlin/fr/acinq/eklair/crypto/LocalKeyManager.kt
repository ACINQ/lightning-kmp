package fr.acinq.eklair.crypto

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.DeterministicWallet.derivePrivateKey
import fr.acinq.bitcoin.DeterministicWallet.hardened
import fr.acinq.eklair.Eclair.secureRandom
import fr.acinq.eklair.Features
import fr.acinq.eklair.ShortChannelId
import fr.acinq.eklair.io.ByteVector32KSerializer
import fr.acinq.eklair.io.ByteVectorKSerializer
import fr.acinq.eklair.transactions.Transactions
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class LocalKeyManager(@Serializable(with = ByteVectorKSerializer::class) val seed: ByteVector, @Serializable(with = ByteVector32KSerializer::class) val chainHash: ByteVector32) : KeyManager {

    @Transient
    private val master = DeterministicWallet.generate(seed)

    @Transient
    override val nodeKey: DeterministicWallet.ExtendedPrivateKey = derivePrivateKey(master, nodeKeyBasePath(chainHash))

    override val nodeId: PublicKey get() = nodeKey.publicKey

    private fun internalKeyPath(channelKeyPath: List<Long>, index: Long): List<Long> = channelKeyBasePath(chainHash) + channelKeyPath + index

    private fun internalKeyPath(channelKeyPath: KeyPath, index: Long): List<Long> = internalKeyPath(channelKeyPath.path, index)

    private fun privateKey(keyPath: KeyPath): DeterministicWallet.ExtendedPrivateKey = derivePrivateKey(master, keyPath)

    private fun privateKey(keyPath: List<Long>): DeterministicWallet.ExtendedPrivateKey = derivePrivateKey(master, keyPath)

    private fun publicKey(keyPath: KeyPath): DeterministicWallet.ExtendedPublicKey = DeterministicWallet.publicKey(privateKey(keyPath))

    private fun publicKey(keyPath: List<Long>): DeterministicWallet.ExtendedPublicKey = DeterministicWallet.publicKey(privateKey(keyPath))

    private fun fundingPrivateKey(channelKeyPath: KeyPath) = privateKey(internalKeyPath(channelKeyPath, hardened(0)))

    private fun revocationSecret(channelKeyPath: KeyPath) = privateKey(internalKeyPath(channelKeyPath, hardened(1)))

    private fun paymentSecret(channelKeyPath: KeyPath) = privateKey(internalKeyPath(channelKeyPath, hardened(2)))

    private fun delayedPaymentSecret(channelKeyPath: KeyPath) = privateKey(internalKeyPath(channelKeyPath, hardened(3)))

    private fun htlcSecret(channelKeyPath: KeyPath) = privateKey(internalKeyPath(channelKeyPath, hardened(4)))

    private fun shaSeed(channelKeyPath: KeyPath) = ByteVector32(Crypto.sha256(privateKey(internalKeyPath(channelKeyPath, hardened(5))).privateKey.value.concat(1.toByte())))

    private fun shaSeed(channelKeyPath: List<Long>) = ByteVector32(Crypto.sha256(privateKey(internalKeyPath(channelKeyPath, hardened(5))).privateKey.value.concat(1.toByte())))

    override fun newFundingKeyPath(isFunder: Boolean): KeyPath {
        val last = DeterministicWallet.hardened(if (isFunder) 1 else 0)
        fun next() = secureRandom.nextInt().toLong() and 0xFFFFFFFF
        return KeyPath(listOf(next(), next(), next(), next(), next(), next(), next(), next(), last))
    }

    override fun fundingPublicKey(keyPath: KeyPath) = publicKey(internalKeyPath(keyPath, hardened(0)))

    override fun revocationPoint(channelKeyPath: KeyPath) = publicKey(internalKeyPath(channelKeyPath, hardened(1)))

    override fun paymentPoint(channelKeyPath: KeyPath) = publicKey(internalKeyPath(channelKeyPath, hardened(2)))

    override fun delayedPaymentPoint(channelKeyPath: KeyPath) = publicKey(internalKeyPath(channelKeyPath, hardened(3)))

    override fun htlcPoint(channelKeyPath: KeyPath) = publicKey(internalKeyPath(channelKeyPath, hardened(4)))

    override fun commitmentSecret(channelKeyPath: KeyPath, index: Long) = Generators.perCommitSecret(shaSeed(channelKeyPath), index)

    override fun commitmentPoint(channelKeyPath: KeyPath, index: Long) = Generators.perCommitPoint(shaSeed(channelKeyPath), index)

    override fun sign(tx: Transactions.TransactionWithInputInfo, publicKey: DeterministicWallet.ExtendedPublicKey): ByteVector64 {
        val privateKey = privateKey(publicKey.path)
        return Transactions.sign(tx, privateKey.privateKey)
    }

    override fun sign(tx: Transactions.TransactionWithInputInfo, publicKey: DeterministicWallet.ExtendedPublicKey, remotePoint: PublicKey): ByteVector64 {
        val privateKey = privateKey(publicKey.path)
        val currentKey = Generators.derivePrivKey(privateKey.privateKey, remotePoint)
        return Transactions.sign(tx, currentKey)
    }

    override fun sign(tx: Transactions.TransactionWithInputInfo, publicKey: DeterministicWallet.ExtendedPublicKey, remoteSecret: PrivateKey): ByteVector64 {
        val privateKey = privateKey(publicKey.path)
        val currentKey = Generators.revocationPrivKey(privateKey.privateKey, remoteSecret)
        return Transactions.sign(tx, currentKey)
    }

    override fun signChannelAnnouncement(fundingKeyPath: KeyPath, chainHash: ByteVector32, shortChannelId: ShortChannelId, remoteNodeId: PublicKey, remoteFundingKey: PublicKey, features: Features): Pair<ByteVector64, ByteVector64> {
        TODO("Not yet implemented")
    }

    companion object {
        fun channelKeyBasePath(chainHash: ByteVector32) = when(chainHash) {
            Block.RegtestGenesisBlock.hash,  Block.TestnetGenesisBlock.hash -> listOf(DeterministicWallet.hardened(46), DeterministicWallet.hardened(1))
            Block.LivenetGenesisBlock.hash -> listOf(DeterministicWallet.hardened(47), DeterministicWallet.hardened(1))
            else -> throw IllegalArgumentException("unknown chain hash $chainHash")
        }


        // WARNING: if you change this path, you will change your node id even if the seed remains the same!!!
        // Note that the node path and the above channel path are on different branches so even if the
        // node key is compromised there is no way to retrieve the wallet keys
        fun nodeKeyBasePath(chainHash: ByteVector32) = when(chainHash) {
            Block.RegtestGenesisBlock.hash, Block.TestnetGenesisBlock.hash -> listOf(DeterministicWallet.hardened(46), DeterministicWallet.hardened(0))
            Block.LivenetGenesisBlock.hash -> listOf(DeterministicWallet.hardened(47), DeterministicWallet.hardened(0))
            else -> throw IllegalArgumentException("unknown chain hash $chainHash")
        }
    }
}
