package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.DeterministicWallet.hardened
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.crypto.musig2.SecretNonce
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.DefaultSwapInParams
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.transactions.SwapInProtocol
import fr.acinq.lightning.transactions.SwapInProtocolLegacy
import fr.acinq.lightning.transactions.Transactions
import fr.acinq.lightning.utils.sum
import fr.acinq.lightning.utils.toByteVector
import fr.acinq.lightning.wire.LightningCodecs

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
        val fundingKey: (Long) -> PrivateKeyDescriptor,
        val paymentKey: PrivateKeyDescriptor,
        val delayedPaymentKey: PrivateKeyDescriptor,
        val htlcKey: PrivateKeyDescriptor,
        val revocationKey: PrivateKeyDescriptor,
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
        private val chain: Chain,
        private val master: DeterministicWallet.ExtendedPrivateKey,
        val account: Long
    ) {
        private val xpriv = DeterministicWallet.derivePrivateKey(master, bip84BasePath(chain) / hardened(account))

        val xpub: String = DeterministicWallet.encode(
            input = DeterministicWallet.publicKey(xpriv),
            prefix = when (chain) {
                Chain.Testnet, Chain.Regtest, Chain.Signet -> DeterministicWallet.vpub
                Chain.Mainnet -> DeterministicWallet.zpub
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
            fun bip84BasePath(chain: Chain) = when (chain) {
                Chain.Regtest, Chain.Testnet, Chain.Signet -> KeyPath.empty / hardened(84) / hardened(1)
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
        private val master: ExtendedPrivateKeyDescriptor,
        val remoteServerPublicKey: PublicKey,
        val refundDelay: Int = DefaultSwapInParams.RefundDelay
    ) {
        private val userExtendedPrivateKey: ExtendedPrivateKeyDescriptor =
            LocalKeyManager.DerivedExtendedPrivateKeyDescriptor(master, swapInUserKeyPath(chain))
        private val userRefundExtendedPrivateKey: ExtendedPrivateKeyDescriptor = LocalKeyManager.DerivedExtendedPrivateKeyDescriptor(master, swapInUserRefundKeyPath(chain))

        val userPrivateKey: PrivateKeyDescriptor = LocalKeyManager.FromExtendedPrivateKeyDescriptor(userExtendedPrivateKey)

        val userPublicKey: PublicKey = userPrivateKey.publicKey()

        private val localServerExtendedPrivateKey: ExtendedPrivateKeyDescriptor = LocalKeyManager.DerivedExtendedPrivateKeyDescriptor(master, swapInLocalServerKeyPath(chain))
        fun localServerPrivateKey(remoteNodeId: PublicKey): PrivateKeyDescriptor = LocalKeyManager.FromExtendedPrivateKeyDescriptor(localServerExtendedPrivateKey, perUserPath(remoteNodeId))

        // legacy p2wsh-based swap-in protocol, with a fixed on-chain address
        val legacySwapInProtocol = SwapInProtocolLegacy(userPublicKey, remoteServerPublicKey, refundDelay)
        val legacyDescriptor = SwapInProtocolLegacy.descriptor(chain, master.publicKey(), userExtendedPrivateKey.publicKey(), remoteServerPublicKey, refundDelay)

        fun signSwapInputUserLegacy(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>): ByteVector64 {
            return legacySwapInProtocol.signSwapInputUser(fundingTx, index, parentTxOuts[fundingTx.txIn[index].outPoint.index.toInt()], userPrivateKey)
        }

        // this is a private descriptor that can be used as-is to recover swap-in funds once the refund delay has passed
        // it is compatible with address rotation as long as refund keys are derived directly from userRefundExtendedPrivateKey
        // README: it includes the user's master refund private key and is not safe to share !!
        val privateDescriptor = SwapInProtocol.privateDescriptor(chain, userPublicKey, remoteServerPublicKey, refundDelay, userRefundExtendedPrivateKey.instantiate())

        // this is the public version of the above descriptor. It can be used to monitor a user's swap-in transaction
        // README: it cannot be used to derive private keys, but it can be used to derive swap-in addresses
        val publicDescriptor = SwapInProtocol.publicDescriptor(chain, userPublicKey, remoteServerPublicKey, refundDelay, DeterministicWallet.publicKey(userRefundExtendedPrivateKey.instantiate()))

        /**
         * @param addressIndex address index
         * @return the swap-in protocol that matches the input public key script
         */
        fun getSwapInProtocol(addressIndex: Int): SwapInProtocol {
            val userRefundPublicKey: PublicKey = userRefundExtendedPrivateKey.derivePrivateKey(addressIndex.toLong()).publicKey()
            return SwapInProtocol(userPublicKey, remoteServerPublicKey, userRefundPublicKey, refundDelay)
        }

        fun signSwapInputUser(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>, privateNonce: SecretNonce, userNonce: IndividualNonce, serverNonce: IndividualNonce, addressIndex: Int): Either<Throwable, ByteVector32> {
            val swapInProtocol = getSwapInProtocol(addressIndex)
            return swapInProtocol.signSwapInputUser(fundingTx, index, parentTxOuts, userPrivateKey, privateNonce, userNonce, serverNonce)
        }

        /**
         * Create a recovery transaction that spends a swap-in transaction after the refund delay has passed
         * @param swapInTx swap-in transaction
         * @param address address to send funds to
         * @param feeRate fee rate for the refund transaction
         * @return a signed transaction that spends our swap-in transaction. It cannot be published until `swapInTx` has enough confirmations
         */
        fun createRecoveryTransaction(swapInTx: Transaction, address: String, feeRate: FeeratePerKw): Transaction? {
            val swapInProtocols = (0 until 100).map { getSwapInProtocol(it) }
            val utxos = swapInTx.txOut.filter { it.publicKeyScript.contentEquals(Script.write(legacySwapInProtocol.pubkeyScript)) || swapInProtocols.find { p -> p.serializedPubkeyScript == it.publicKeyScript } != null }
            return if (utxos.isEmpty()) {
                null
            } else {
                Bitcoin.addressToPublicKeyScript(chain.chainHash, address).right?.let { script ->
                    val ourOutput = TxOut(utxos.map { it.amount }.sum(), script)
                    val unsignedTx = Transaction(
                        version = 2,
                        txIn = utxos.map { TxIn(OutPoint(swapInTx, swapInTx.txOut.indexOf(it).toLong()), sequence = refundDelay.toLong()) },
                        txOut = listOf(ourOutput),
                        lockTime = 0
                    )

                    fun sign(tx: Transaction, index: Int, utxo: TxOut): Transaction {
                        return if (Script.isPay2wsh(utxo.publicKeyScript.toByteArray())) {
                            val sig = legacySwapInProtocol.signSwapInputUser(tx, index, utxo, userPrivateKey)
                            tx.updateWitness(index, legacySwapInProtocol.witnessRefund(sig))
                        } else {
                            val i = swapInProtocols.indexOfFirst { it.serializedPubkeyScript == utxo.publicKeyScript }
                            val userRefundPrivateKey: PrivateKeyDescriptor = userRefundExtendedPrivateKey.derivePrivateKey(i.toLong())
                            val swapInProtocol = swapInProtocols[i]
                            val sig = swapInProtocol.signSwapInputRefund(tx, index, utxos, userRefundPrivateKey)
                            tx.updateWitness(index, swapInProtocol.witnessRefund(sig))
                        }
                    }

                    val fees = run {
                        val recoveryTx = utxos.foldIndexed(unsignedTx) { index, tx, utxo -> sign(tx, index, utxo) }
                        Transactions.weight2fee(feeRate, recoveryTx.weight())
                    }
                    val unsignedTx1 = unsignedTx.copy(txOut = listOf(ourOutput.copy(amount = ourOutput.amount - fees)))
                    val recoveryTx = utxos.foldIndexed(unsignedTx1) { index, tx, utxo -> sign(tx, index, utxo) }
                    // this tx is signed but cannot be published until swapInTx has `refundDelay` confirmations
                    recoveryTx
                }
            }
        }

        companion object {
            private fun swapInKeyBasePath(chain: Chain) = when (chain) {
                Chain.Regtest, Chain.Testnet, Chain.Signet -> KeyPath.empty / hardened(51) / hardened(0)
                Chain.Mainnet -> KeyPath.empty / hardened(52) / hardened(0)
            }

            fun swapInUserKeyPath(chain: Chain) = swapInKeyBasePath(chain) / hardened(0)

            fun swapInLocalServerKeyPath(chain: Chain) = swapInKeyBasePath(chain) / hardened(1)

            fun swapInUserRefundKeyPath(chain: Chain) = swapInKeyBasePath(chain) / hardened(2) / 0L

            fun encodedSwapInUserKeyPath(chain: Chain) = when (chain) {
                Chain.Regtest, Chain.Testnet, Chain.Signet -> "51h/0h/0h"
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

}
