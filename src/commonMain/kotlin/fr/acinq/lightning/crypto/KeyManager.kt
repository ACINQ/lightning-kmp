package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.DeterministicWallet.hardened
import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.musig2.PublicNonce
import fr.acinq.bitcoin.musig2.SecretNonce
import fr.acinq.lightning.DefaultSwapInParams
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.transactions.SwapInProtocol
import fr.acinq.lightning.transactions.SwapInProtocolMusig2
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
        val refundDelay: Int = DefaultSwapInParams.RefundDelay
    ) {
        private val userExtendedPrivateKey: DeterministicWallet.ExtendedPrivateKey = DeterministicWallet.derivePrivateKey(master, swapInUserKeyPath(chain))
        private val userRefundExtendedPrivateKey: DeterministicWallet.ExtendedPrivateKey = DeterministicWallet.derivePrivateKey(master, swapInUserRefundKeyPath(chain))
        private val swapExtendedPublicKey = DeterministicWallet.publicKey(DeterministicWallet.derivePrivateKey(master, swapInLocalServerKeyPath(chain)))
        private val xpub = DeterministicWallet.encode(swapExtendedPublicKey, DeterministicWallet.tpub)

        val userPrivateKey: PrivateKey = userExtendedPrivateKey.privateKey
        val userPublicKey: PublicKey = userPrivateKey.publicKey()

        val userRefundPrivateKey: PrivateKey = userRefundExtendedPrivateKey.privateKey
        val userRefundPublicKey: PublicKey = userPrivateKey.publicKey()

        private val localServerExtendedPrivateKey: DeterministicWallet.ExtendedPrivateKey = DeterministicWallet.derivePrivateKey(master, swapInLocalServerKeyPath(chain))
        fun localServerPrivateKey(remoteNodeId: PublicKey): PrivateKey = DeterministicWallet.derivePrivateKey(localServerExtendedPrivateKey, perUserPath(remoteNodeId)).privateKey

        val swapInProtocol = SwapInProtocol(userPublicKey, remoteServerPublicKey, refundDelay)
        val swapInProtocolMusig2 = SwapInProtocolMusig2(userPublicKey, remoteServerPublicKey, userRefundPublicKey, refundDelay)

        /**
         * The output script descriptor matching our swap-in addresses.
         * That descriptor can be imported in bitcoind to recover funds after the refund delay.
         */
        val descriptor = run {
            // Since child public keys cannot be derived from a master xpub when hardened derivation is used,
            // we need to provide the fingerprint of the master xpub and the hardened derivation path.
            // This lets wallets that have access to the master xpriv derive the corresponding private and public keys.
            val masterFingerprint = ByteVector(Crypto.hash160(DeterministicWallet.publicKey(master).publickeybytes).take(4).toByteArray())
            val encodedChildKey = DeterministicWallet.encode(DeterministicWallet.publicKey(userExtendedPrivateKey), testnet = chain != NodeParams.Chain.Mainnet)
            val userKey = "[${masterFingerprint.toHex()}/${encodedSwapInUserKeyPath(chain)}]$encodedChildKey"
            "wsh(and_v(v:pk($userKey),or_d(pk(${remoteServerPublicKey.toHex()}),older($refundDelay))))"
        }

        fun signSwapInputUser(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>): ByteVector64 {
            return swapInProtocol.signSwapInputUser(fundingTx, index, parentTxOuts[fundingTx.txIn[index].outPoint.index.toInt()] , userPrivateKey)
        }

        fun signSwapInputUserMusig2(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>, userNonce: SecretNonce, serverNonce: PublicNonce): ByteVector32 {
            return swapInProtocolMusig2.signSwapInputUser(fundingTx, index, parentTxOuts, userPrivateKey, userNonce, serverNonce)
        }

        /**
         * Create a recovery transaction that spends a swap-in transaction after the refund delay has passed
         * @param swapInTx swap-in transaction
         * @param address address to send funds to
         * @param feeRate fee rate for the refund transaction
         * @return a signed transaction that spends our swap-in transaction. It cannot be published until `swapInTx` has enough confirmations
         */
        fun createRecoveryTransaction(swapInTx: Transaction, address: String, feeRate: FeeratePerKw): Transaction? {
            val utxos = swapInTx.txOut.filter { it.publicKeyScript.contentEquals(Script.write(swapInProtocol.pubkeyScript)) || it.publicKeyScript.contentEquals(Script.write(swapInProtocolMusig2.pubkeyScript))}
            return if (utxos.isEmpty()) {
                null
            } else {
                val pubKeyScript = Bitcoin.addressToPublicKeyScript(chain.chainHash, address).result
                pubKeyScript?.let { script ->
                    val ourOutput = TxOut(utxos.map { it.amount }.sum(), script)
                    val unsignedTx = Transaction(
                        version = 2,
                        txIn = utxos.map { TxIn(OutPoint(swapInTx, swapInTx.txOut.indexOf(it).toLong()), sequence = refundDelay.toLong()) },
                        txOut = listOf(ourOutput),
                        lockTime = 0
                    )

                    fun sign(tx: Transaction, index: Int, utxo: TxOut): Transaction {
                        return if (swapInProtocol.isMine(utxo)) {
                            val sig = swapInProtocol.signSwapInputUser(tx, index, utxo, userPrivateKey)
                            tx.updateWitness(index, swapInProtocol.witnessRefund(sig))
                        } else {
                            val sig = swapInProtocolMusig2.signSwapInputRefund(tx, index, utxos, userPrivateKey)
                            tx.updateWitness(index, swapInProtocolMusig2.witnessRefund(sig))
                        }
                    }

                    val fees = run {
                        val recoveryTx = utxos.foldIndexed(unsignedTx) { index, tx, utxo ->
                            sign(tx, index, utxo)
                        }
                        Transactions.weight2fee(feeRate, recoveryTx.weight())
                    }
                    val unsignedTx1 = unsignedTx.copy(txOut = listOf(ourOutput.copy(amount = ourOutput.amount - fees)))
                    val recoveryTx = utxos.foldIndexed(unsignedTx1) { index, tx, utxo ->
                        sign(tx, index, utxo)
                    }
                    // this tx is signed but cannot be published until swapInTx has `refundDelay` confirmations
                    recoveryTx
                }
            }
        }

        companion object {
            private fun swapInKeyBasePath(chain: NodeParams.Chain) = when (chain) {
                NodeParams.Chain.Regtest, NodeParams.Chain.Testnet -> KeyPath.empty / hardened(51) / hardened(0)
                NodeParams.Chain.Mainnet -> KeyPath.empty / hardened(52) / hardened(0)
            }

            fun swapInUserKeyPath(chain: NodeParams.Chain) = swapInKeyBasePath(chain) / hardened(0)

            fun swapInUserRefundKeyPath(chain: NodeParams.Chain) = swapInKeyBasePath(chain) / hardened(0) / 0L

            fun swapInLocalServerKeyPath(chain: NodeParams.Chain) = swapInKeyBasePath(chain) / hardened(1)

            fun encodedSwapInUserKeyPath(chain: NodeParams.Chain) = when (chain) {
                NodeParams.Chain.Regtest, NodeParams.Chain.Testnet -> "51h/0h/0h"
                NodeParams.Chain.Mainnet -> "52h/0h/0h"
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
