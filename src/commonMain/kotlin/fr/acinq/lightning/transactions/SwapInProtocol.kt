package fr.acinq.lightning.transactions

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.crypto.musig2.IndividualNonce
import fr.acinq.bitcoin.crypto.musig2.Musig2
import fr.acinq.bitcoin.crypto.musig2.SecretNonce
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.crypto.PrivateKeyDescriptor

/**
 * new swap-in protocol based on musig2 and taproot: (user key + server key) OR (user refund key + delay)
 * for the common case, we use the musig2 aggregate of the user and server keys, spent through the key-spend path
 * for the refund case, we use the refund script, spent through the script-spend path
 * we use a different user key for the refund case: this allows us to generate generic descriptor for all swap-in addresses
 * (see the descriptor() method below)
 */
data class SwapInProtocol(val userPublicKey: PublicKey, val serverPublicKey: PublicKey, val userRefundKey: PublicKey, val refundDelay: Int) {
    // The key path uses musig2 with the user and server keys.
    private val internalPublicKey = Musig2.aggregateKeys(listOf(userPublicKey, serverPublicKey))

    // The script path contains a refund script, generated from this policy: and_v(v:pk(user),older(refundDelay)).
    // It does not depend upon the user's or server's key, just the user's refund key and the refund delay.
    private val refundScript = listOf(OP_PUSHDATA(userRefundKey.xOnly()), OP_CHECKSIGVERIFY, OP_PUSHDATA(Script.encodeNumber(refundDelay)), OP_CHECKSEQUENCEVERIFY)
    private val scriptTree = ScriptTree.Leaf(0, refundScript)
    val pubkeyScript: List<ScriptElt> = Script.pay2tr(internalPublicKey, scriptTree)
    val serializedPubkeyScript = Script.write(pubkeyScript).byteVector()

    fun address(chain: Chain): String = Bitcoin.addressFromPublicKeyScript(chain.chainHash, pubkeyScript).right!!

    fun witness(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>, userNonce: IndividualNonce, serverNonce: IndividualNonce, userPartialSig: ByteVector32, serverPartialSig: ByteVector32): Either<Throwable, ScriptWitness> {
        val publicKeys = listOf(userPublicKey, serverPublicKey)
        val publicNonces = listOf(userNonce, serverNonce)
        val sigs = listOf(userPartialSig, serverPartialSig)
        return Musig2.aggregateTaprootSignatures(sigs, fundingTx, index, parentTxOuts, publicKeys, publicNonces, scriptTree).map { aggregateSig ->
            Script.witnessKeyPathPay2tr(aggregateSig)
        }
    }

    fun witnessRefund(userSig: ByteVector64): ScriptWitness = Script.witnessScriptPathPay2tr(internalPublicKey, scriptTree, ScriptWitness(listOf(userSig)), scriptTree)

    fun signSwapInputUser(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>, userPrivateKey: PrivateKeyDescriptor, privateNonce: SecretNonce, userNonce: IndividualNonce, serverNonce: IndividualNonce): Either<Throwable, ByteVector32> {
        require(userPrivateKey.publicKey() == userPublicKey) { "user private key does not match expected public key: are you using the refund key instead of the user key?" }
        val publicKeys = listOf(userPublicKey, serverPublicKey)
        val publicNonces = listOf(userNonce, serverNonce)
        return Musig2.signTaprootInput(userPrivateKey.instantiate(), fundingTx, index, parentTxOuts, publicKeys, privateNonce, publicNonces, scriptTree)
    }

    fun signSwapInputRefund(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>, userPrivateKey: PrivateKeyDescriptor): ByteVector64 {
        require(userPrivateKey.publicKey() == userRefundKey) { "refund private key does not match expected public key: are you using the user key instead of the refund key?" }
        return userPrivateKey.signInputTaprootScriptPath(fundingTx, index, parentTxOuts, SigHash.SIGHASH_DEFAULT, scriptTree.hash())
    }

    fun signSwapInputServer(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>, serverPrivateKey: PrivateKeyDescriptor, privateNonce: SecretNonce, userNonce: IndividualNonce, serverNonce: IndividualNonce): Either<Throwable, ByteVector32> {
        val publicKeys = listOf(userPublicKey, serverPublicKey)
        val publicNonces = listOf(userNonce, serverNonce)
        return Musig2.signTaprootInput(serverPrivateKey.instantiate(), fundingTx, index, parentTxOuts, publicKeys, privateNonce, publicNonces, scriptTree)
    }

    companion object {
        fun privateDescriptor(chain: Chain, userPublicKey: PublicKey, serverPublicKey: PublicKey, refundDelay: Int, masterRefundKey: DeterministicWallet.ExtendedPrivateKey): String {
            val internalPubKey = Musig2.aggregateKeys(listOf(userPublicKey, serverPublicKey))
            val prefix = when (chain) {
                Chain.Mainnet -> DeterministicWallet.xprv
                else -> DeterministicWallet.tprv
            }
            val xpriv = DeterministicWallet.encode(masterRefundKey, prefix)
            val desc = "tr(${internalPubKey.value},and_v(v:pk($xpriv/*),older($refundDelay)))"
            val checksum = Descriptor.checksum(desc)
            return "$desc#$checksum"
        }

        fun publicDescriptor(chain: Chain, userPublicKey: PublicKey, serverPublicKey: PublicKey, refundDelay: Int, masterRefundKey: DeterministicWallet.ExtendedPublicKey): String {
            val internalPubKey = Musig2.aggregateKeys(listOf(userPublicKey, serverPublicKey))
            val prefix = when (chain) {
                Chain.Mainnet -> DeterministicWallet.xpub
                else -> DeterministicWallet.tpub
            }
            val xpub = DeterministicWallet.encode(masterRefundKey, prefix)
            val desc = "tr(${internalPubKey.value},and_v(v:pk($xpub/*),older($refundDelay)))"
            val checksum = Descriptor.checksum(desc)
            return "$desc#$checksum"
        }
    }
}

/**
 * legacy swap-in protocol, that uses p2wsh and a single "user + server OR user + delay" script
 */
data class SwapInProtocolLegacy(val userPublicKey: PublicKey, val serverPublicKey: PublicKey, val refundDelay: Int) {
    // This script was generated with https://bitcoin.sipa.be/miniscript/ using the following miniscript policy:
    // and(pk(<user_key>),or(99@pk(<server_key>),older(<delayed_refund>)))
    // @formatter:off
    val redeemScript =  listOf(
        OP_PUSHDATA(userPublicKey), OP_CHECKSIGVERIFY, OP_PUSHDATA(serverPublicKey), OP_CHECKSIG, OP_IFDUP,
        OP_NOTIF,
        OP_PUSHDATA(Script.encodeNumber(refundDelay)), OP_CHECKSEQUENCEVERIFY,
        OP_ENDIF
    )
    // @formatter:on

    val pubkeyScript: List<ScriptElt> = Script.pay2wsh(redeemScript)

    fun address(chain: Chain): String = Bitcoin.addressFromPublicKeyScript(chain.chainHash, pubkeyScript).right!!

    fun witness(userSig: ByteVector64, serverSig: ByteVector64): ScriptWitness {
        return ScriptWitness(listOf(Scripts.der(serverSig, SigHash.SIGHASH_ALL), Scripts.der(userSig, SigHash.SIGHASH_ALL), Script.write(redeemScript).byteVector()))
    }

    fun witnessRefund(userSig: ByteVector64): ScriptWitness {
        return ScriptWitness(listOf(ByteVector.empty, Scripts.der(userSig, SigHash.SIGHASH_ALL), Script.write(redeemScript).byteVector()))
    }

    fun signSwapInputUser(fundingTx: Transaction, index: Int, parentTxOut: TxOut, userKey: PrivateKeyDescriptor): ByteVector64 {
        require(userKey.publicKey() == userPublicKey) { "user private key does not match expected public key: are you using the refund key instead of the user key?" }
        return userKey.sign(fundingTx, index, Script.write(redeemScript), parentTxOut.amount)
    }

    fun signSwapInputServer(fundingTx: Transaction, index: Int, parentTxOut: TxOut, serverKey: PrivateKeyDescriptor): ByteVector64 {
        return serverKey.sign(fundingTx, index, Script.write(redeemScript), parentTxOut.amount)
    }

    companion object {
        /**
         * The output script descriptor matching our legacy swap-in addresses.
         * That descriptor can be imported in bitcoind to recover funds after the refund delay.
         *
         * @param chain chain we're on.
         * @param masterPublicKey master public key for the swap-in wallet.
         * @param userExtendedPublicKey user public key, derived from the master private key.
         * @param remoteServerPublicKey server public key
         * @param refundDelay refund delay
         * @return a p2wsh descriptor that can be imported in bitcoin core (from version 24 on) to recover user funds once the funding delay has passed.
         */
        fun descriptor(chain: Chain, masterPublicKey: DeterministicWallet.ExtendedPublicKey, userExtendedPublicKey: DeterministicWallet.ExtendedPublicKey, remoteServerPublicKey: PublicKey, refundDelay: Int): String {
            // Since child public keys cannot be derived from a master xpub when hardened derivation is used,
            // we need to provide the fingerprint of the master xpub and the hardened derivation path.
            // This lets wallets that have access to the master xpriv derive the corresponding private and public keys.
            val masterFingerprint = ByteVector(Crypto.hash160(masterPublicKey.publickeybytes).take(4).toByteArray())
            val encodedChildKey = DeterministicWallet.encode(userExtendedPublicKey, testnet = chain != Chain.Mainnet)
            val userKey = "[${masterFingerprint.toHex()}/${userExtendedPublicKey.path.asString('h').removePrefix("m/")}]$encodedChildKey"
            return "wsh(and_v(v:pk($userKey),or_d(pk(${remoteServerPublicKey.toHex()}),older($refundDelay))))"
        }
    }
}