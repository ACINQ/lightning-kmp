package fr.acinq.lightning.transactions

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.crypto.musig2.AggregatedNonce
import fr.acinq.bitcoin.crypto.musig2.KeyAggCache
import fr.acinq.bitcoin.crypto.musig2.SecretNonce
import fr.acinq.bitcoin.crypto.musig2.Session
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.wire.TxAddInputTlv

/**
 * new swap-in protocol based on musig2 and taproot: (user key + server key) OR (user refund key + delay)
 * for the common case, we use the musig2 aggregate of the user and server keys, spent through the key-spend path
 * for the refund case, we use the refund script, spent through the script-spend path
 * we use a different user key for the refund case: this allows us to generate generic descriptor for all swap-in addresses
 * (see the descriptor() method below)
 */
class SwapInProtocol(val userPublicKey: PublicKey, val serverPublicKey: PublicKey, val userRefundKey: PublicKey, val refundDelay: Int) {
    constructor(swapInParams: TxAddInputTlv.SwapInParams) : this(swapInParams.userKey, swapInParams.serverKey, swapInParams.userRefundKey, swapInParams.refundDelay)

    // the redeem script is just the refund script. it is generated from this policy: and_v(v:pk(user),older(refundDelay))
    // it does not depend upon the user's or server's key, just the user's refund key and the refund delay
    val redeemScript = listOf(OP_PUSHDATA(userRefundKey.xOnly()), OP_CHECKSIGVERIFY, OP_PUSHDATA(Script.encodeNumber(refundDelay)), OP_CHECKSEQUENCEVERIFY)
    private val scriptTree = ScriptTree.Leaf(0, redeemScript)
    private val merkleRoot = scriptTree.hash()

    // the internal pubkey is the musig2 aggregation of the user's and server's public keys: it does not depend upon the user's refund's key
    private val internalPubKeyAndCache = KeyAggCache.Companion.add(listOf(userPublicKey, serverPublicKey), null)
    private val internalPubKey = internalPubKeyAndCache.first
    private val cache = internalPubKeyAndCache.second

    // it is tweaked with the script's merkle root to get the pubkey that will be exposed
    private val commonPubKeyAndParity = internalPubKey.outputKey(Crypto.TaprootTweak.ScriptTweak(merkleRoot))
    val commonPubKey = commonPubKeyAndParity.first
    private val parity = commonPubKeyAndParity.second
    val pubkeyScript: List<ScriptElt> = Script.pay2tr(commonPubKey)

    private val controlBlock = byteArrayOf((Script.TAPROOT_LEAF_TAPSCRIPT + (if (parity) 1 else 0)).toByte()) + internalPubKey.value.toByteArray()

    fun isMine(txOut: TxOut): Boolean = txOut.publicKeyScript.contentEquals(Script.write(pubkeyScript))

    fun address(chain: NodeParams.Chain): String = Bitcoin.addressFromPublicKeyScript(chain.chainHash, pubkeyScript).result!!

    fun witness(commonSig: ByteVector64): ScriptWitness = ScriptWitness(listOf(commonSig))

    fun witnessRefund(userSig: ByteVector64): ScriptWitness = ScriptWitness.empty.push(userSig).push(redeemScript).push(controlBlock)

    fun signSwapInputUser(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>, userPrivateKey: PrivateKey, userNonce: SecretNonce, commonNonce: AggregatedNonce): ByteVector32 {
        require(userPrivateKey.publicKey() == userPublicKey)
        val txHash = Transaction.hashForSigningSchnorr(fundingTx, index, parentTxOuts, SigHash.SIGHASH_DEFAULT, SigVersion.SIGVERSION_TAPROOT)
        val cache1 = cache.tweak(internalPubKey.tweak(Crypto.TaprootTweak.ScriptTweak(merkleRoot)), true).first
        val session = Session.build(commonNonce, txHash, cache1)
        return session.sign(userNonce, userPrivateKey, cache1)
    }

    fun signSwapInputRefund(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>, userPrivateKey: PrivateKey): ByteVector64 {
        val txHash = Transaction.hashForSigningSchnorr(fundingTx, index, parentTxOuts, SigHash.SIGHASH_DEFAULT, SigVersion.SIGVERSION_TAPSCRIPT, merkleRoot)
        return Crypto.signSchnorr(txHash, userPrivateKey, Crypto.SchnorrTweak.NoTweak)
    }

    fun signSwapInputServer(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>, commonNonce: AggregatedNonce, serverPrivateKey: PrivateKey, serverNonce: SecretNonce): ByteVector32 {
        val txHash = Transaction.hashForSigningSchnorr(fundingTx, index, parentTxOuts, SigHash.SIGHASH_DEFAULT, SigVersion.SIGVERSION_TAPROOT)
        val cache1 = cache.tweak(internalPubKey.tweak(Crypto.TaprootTweak.ScriptTweak(merkleRoot)), true).first
        val session = Session.build(commonNonce, txHash, cache1)
        return session.sign(serverNonce, serverPrivateKey, cache1)
    }

    fun session(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>, commonNonce: AggregatedNonce): Session {
        val txHash = Transaction.hashForSigningSchnorr(fundingTx, index, parentTxOuts, SigHash.SIGHASH_DEFAULT, SigVersion.SIGVERSION_TAPROOT)
        val cache1 = cache.tweak(internalPubKey.tweak(Crypto.TaprootTweak.ScriptTweak(merkleRoot)), true).first
        return Session.build(commonNonce, txHash, cache1)
    }

    companion object {
        /**
         *
         * @param chain chain we're on
         * @param userPublicKey user public key (used for the mutual path)
         * @param serverPublicKey server public key (used for the mutual path)
         * @param refundDelay refund delay (in blocks)
         * @param masterRefundKey master private key for the refund keys. we assume that there is a single level of derivation to compute the refund keys
         * @return a taproot descriptor that can be imported in bitcoin core (from version 26 on) to recover user funds once the funding delay has passed
         */
        fun descriptor(chain: NodeParams.Chain, userPublicKey: PublicKey, serverPublicKey: PublicKey,  refundDelay: Int, masterRefundKey: DeterministicWallet.ExtendedPrivateKey): String {
            // the internal pubkey is the musig2 aggregation of the user's and server's public keys: it does not depend upon the user's refund's key
            val (internalPubKey, _) = KeyAggCache.Companion.add(listOf(userPublicKey, serverPublicKey), null)
            val prefix = when (chain) {
                NodeParams.Chain.Mainnet -> DeterministicWallet.xprv
                else -> DeterministicWallet.tprv
            }
            val xpriv = DeterministicWallet.encode(masterRefundKey, prefix)
            val path = masterRefundKey.path.toString().replace('\'', 'h').removePrefix("m")
            val desc = "tr(${internalPubKey.value},and_v(v:pk($xpriv$path/*),older($refundDelay)))"
            val checksum = Descriptor.checksum(desc)
            return "$desc#$checksum"
        }

        /**
         *
         * @param chain chain we're on
         * @param userPublicKey user public key (used for the mutual path)
         * @param serverPublicKey server public key (used for the mutual path)
         * @param refundDelay refund delay (in blocks)
         * @param masterRefundKey master public key for the refund keys. we assume that there is a single level of derivation to compute the refund keys
         * @return a taproot descriptor that can be imported in bitcoin core (from version 26 on) to create a watch-only wallet for your swap-in transactions
         */
        fun descriptor(chain: NodeParams.Chain, userPublicKey: PublicKey, serverPublicKey: PublicKey,  refundDelay: Int, masterRefundKey: DeterministicWallet.ExtendedPublicKey): String {
            // the internal pubkey is the musig2 aggregation of the user's and server's public keys: it does not depend upon the user's refund's key
            val (internalPubKey, _) = KeyAggCache.Companion.add(listOf(userPublicKey, serverPublicKey), null)
            val prefix = when (chain) {
                NodeParams.Chain.Mainnet -> DeterministicWallet.xpub
                else -> DeterministicWallet.tpub
            }
            val xpub = DeterministicWallet.encode(masterRefundKey, prefix)
            val path = masterRefundKey.path.toString().replace('\'', 'h').removePrefix("m")
            val desc = "tr(${internalPubKey.value},and_v(v:pk($xpub$path/*),older($refundDelay)))"
            val checksum = Descriptor.checksum(desc)
            return "$desc#$checksum"
        }

    }
}

/**
 * legacy swap-in protocol, that uses p2wsh and a single "user + server OR user + delay" script
 */
class SwapInProtocolLegacy(val userPublicKey: PublicKey, val serverPublicKey: PublicKey, val refundDelay: Int) {

    constructor(swapInParams: TxAddInputTlv.SwapInParamsLegacy) : this(swapInParams.userKey, swapInParams.serverKey, swapInParams.refundDelay)

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

    fun isMine(txOut: TxOut): Boolean = txOut.publicKeyScript.contentEquals(Script.write(pubkeyScript))

    fun address(chain: NodeParams.Chain): String = Bitcoin.addressFromPublicKeyScript(chain.chainHash, pubkeyScript).result!!

    fun witness(userSig: ByteVector64, serverSig: ByteVector64): ScriptWitness {
        return ScriptWitness(listOf(Scripts.der(serverSig, SigHash.SIGHASH_ALL), Scripts.der(userSig, SigHash.SIGHASH_ALL), Script.write(redeemScript).byteVector()))
    }

    fun witnessRefund(userSig: ByteVector64): ScriptWitness {
        return ScriptWitness(listOf(ByteVector.empty, Scripts.der(userSig, SigHash.SIGHASH_ALL), Script.write(redeemScript).byteVector()))
    }

    fun signSwapInputUser(fundingTx: Transaction, index: Int, parentTxOut: TxOut, userKey: PrivateKey): ByteVector64 {
        require(userKey.publicKey() == userPublicKey)
        return Transactions.sign(fundingTx, index, Script.write(redeemScript), parentTxOut.amount, userKey)
    }

    fun signSwapInputServer(fundingTx: Transaction, index: Int, parentTxOut: TxOut, serverKey: PrivateKey): ByteVector64 {
        return Transactions.sign(fundingTx, index, Script.write(redeemScript), parentTxOut.amount, serverKey)
    }
}