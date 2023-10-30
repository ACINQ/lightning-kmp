package fr.acinq.lightning.transactions

import fr.acinq.bitcoin.*
import fr.acinq.lightning.NodeParams

class SwapInProtocol(val userPublicKey: PublicKey, val serverPublicKey: PublicKey, val refundDelay: Int) {
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