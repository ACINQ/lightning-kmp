package fr.acinq.lightning.transactions

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.musig2.Musig2
import fr.acinq.bitcoin.musig2.PublicNonce
import fr.acinq.bitcoin.musig2.SecretNonce
import fr.acinq.bitcoin.musig2.SessionCtx
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.wire.TxAddInputTlv
import org.kodein.log.newLogger

class SwapInProtocol(val userPublicKey: PublicKey, val serverPublicKey: PublicKey, val refundDelay: Int) {

     constructor(swapInParams: TxAddInputTlv.SwapInParams) : this(swapInParams.userKey, swapInParams.serverKey, swapInParams.refundDelay)

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

class SwapInProtocolMusig2(val userPublicKey: PublicKey, val serverPublicKey: PublicKey, val refundDelay: Int) {
    constructor(swapInParams: TxAddInputTlv.SwapInParams) : this(swapInParams.userKey, swapInParams.serverKey, swapInParams.refundDelay)

    // the redeem script is just the refund script. it is generated from this policy: and_v(v:pk(user),older(refundDelay))
    val redeemScript = listOf(OP_PUSHDATA(userPublicKey.xOnly()), OP_CHECKSIGVERIFY, OP_PUSHDATA(Script.encodeNumber(refundDelay)), OP_CHECKSEQUENCEVERIFY)
    private val scriptTree = ScriptTree.Leaf(ScriptLeaf(0, Script.write(redeemScript).byteVector(), Script.TAPROOT_LEAF_TAPSCRIPT))
    private val merkleRoot = ScriptTree.hash(scriptTree)
    private val internalPubKey = Musig2.keyAgg(listOf(userPublicKey, serverPublicKey)).Q.xOnly()
    private val commonPubKeyAndParity = internalPubKey.outputKey(Crypto.TaprootTweak.ScriptTweak(merkleRoot))
    val commonPubKey = commonPubKeyAndParity.first
    private val parity = commonPubKeyAndParity.second
    val pubkeyScript: List<ScriptElt> = Script.pay2tr(commonPubKey)
    private val executionData = Script.ExecutionData(annex = null, tapleafHash = merkleRoot)
    private val controlBlock = byteArrayOf((Script.TAPROOT_LEAF_TAPSCRIPT + (if (parity) 1 else 0)).toByte()) + internalPubKey.value.toByteArray()

    fun isMine(txOut: TxOut): Boolean = txOut.publicKeyScript.contentEquals(Script.write(pubkeyScript))

    fun address(chain: NodeParams.Chain): String = Bitcoin.addressFromPublicKeyScript(chain.chainHash, pubkeyScript).result!!

    fun witness(commonSig: ByteVector64): ScriptWitness = ScriptWitness(listOf(commonSig))

    fun witnessRefund(userSig: ByteVector64): ScriptWitness = ScriptWitness.empty.push(userSig).push(redeemScript).push(controlBlock)

    fun signSwapInputUser(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>, userPrivateKey: PrivateKey, userNonce: SecretNonce, serverNonce: PublicNonce): ByteVector32 {
        require(userPrivateKey.publicKey() == userPublicKey)
        val txHash = Transaction.hashForSigningSchnorr(fundingTx, index, parentTxOuts, SigHash.SIGHASH_DEFAULT, SigVersion.SIGVERSION_TAPROOT)
        val commonNonce = PublicNonce.aggregate(listOf(userNonce.publicNonce(), serverNonce))
        val ctx = SessionCtx(
            commonNonce,
            listOf(userPrivateKey.publicKey(), serverPublicKey),
            listOf(Pair(internalPubKey.tweak(Crypto.TaprootTweak.ScriptTweak(merkleRoot)), true)),
            txHash
        )
        return ctx.sign(userNonce, userPrivateKey)
    }

    fun signSwapInputRefund(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>, userPrivateKey: PrivateKey): ByteVector64 {
        val txHash = Transaction.hashForSigningSchnorr(fundingTx, index, parentTxOuts, SigHash.SIGHASH_DEFAULT, SigVersion.SIGVERSION_TAPSCRIPT, executionData)
        return Crypto.signSchnorr(txHash, userPrivateKey, Crypto.SchnorrTweak.NoTweak)
    }

    fun signSwapInputServer(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>, userNonce: PublicNonce, serverPrivateKey: PrivateKey, serverNonce: SecretNonce): ByteVector32 {
        val txHash = Transaction.hashForSigningSchnorr(fundingTx, index, parentTxOuts, SigHash.SIGHASH_DEFAULT, SigVersion.SIGVERSION_TAPROOT)
        val commonNonce = PublicNonce.aggregate(listOf(userNonce, serverNonce.publicNonce()))
        val ctx = SessionCtx(
            commonNonce,
            listOf(userPublicKey, serverPrivateKey.publicKey()),
            listOf(Pair(internalPubKey.tweak(Crypto.TaprootTweak.ScriptTweak(merkleRoot)), true)),
            txHash
        )
        return ctx.sign(serverNonce, serverPrivateKey)
    }

    fun signingCtx(fundingTx: Transaction, index: Int, parentTxOuts: List<TxOut>, commonNonce: PublicNonce): SessionCtx {
        val txHash = Transaction.hashForSigningSchnorr(fundingTx, index, parentTxOuts, SigHash.SIGHASH_DEFAULT, SigVersion.SIGVERSION_TAPROOT)
        return SessionCtx(
            commonNonce,
            listOf(userPublicKey, serverPublicKey),
            listOf(Pair(internalPubKey.tweak(Crypto.TaprootTweak.ScriptTweak(merkleRoot)), true)),
            txHash
        )
    }
}