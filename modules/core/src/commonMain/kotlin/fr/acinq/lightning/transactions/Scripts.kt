package fr.acinq.lightning.transactions

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.ScriptEltMapping.code2elt
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.transactions.Scripts.htlcOffered
import fr.acinq.lightning.transactions.Scripts.htlcReceived
import fr.acinq.lightning.transactions.Scripts.toLocalDelayed

/**
 * Created by PM on 02/12/2016.
 */
object Scripts {

    fun der(sig: ByteVector64, sigHash: Int): ByteVector = Crypto.compact2der(sig).concat(sigHash.toByte())

    fun multiSig2of2(pubkey1: PublicKey, pubkey2: PublicKey): List<ScriptElt> = when {
        LexicographicalOrdering.isLessThan(pubkey1.value, pubkey2.value) -> Script.createMultiSigMofN(2, listOf(pubkey1, pubkey2))
        else -> Script.createMultiSigMofN(2, listOf(pubkey2, pubkey1))
    }

    /**
     * @return a script witness that matches the msig 2-of-2 pubkey script for pubkey1 and pubkey2
     */
    fun witness2of2(sig1: ByteVector64, sig2: ByteVector64, pubkey1: PublicKey, pubkey2: PublicKey): ScriptWitness {
        val encodedSig1 = der(sig1, SigHash.SIGHASH_ALL)
        val encodedSig2 = der(sig2, SigHash.SIGHASH_ALL)
        val redeemScript = ByteVector(Script.write(multiSig2of2(pubkey1, pubkey2)))
        return when {
            LexicographicalOrdering.isLessThan(pubkey1.value, pubkey2.value) -> ScriptWitness(listOf(ByteVector.empty, encodedSig1, encodedSig2, redeemScript))
            else -> ScriptWitness(listOf(ByteVector.empty, encodedSig2, encodedSig1, redeemScript))
        }
    }

    /**
     * minimal encoding of a number into a script element:
     * - OP_0 to OP_16 if 0 <= n <= 16
     * - OP_PUSHDATA(encodeNumber(n)) otherwise
     *
     * @param n input number
     * @return a script element that represents n
     */
    fun encodeNumber(n: Long): ScriptElt = when (n) {
        0L -> OP_0
        -1L -> OP_1NEGATE
        in 1..16 -> code2elt.getValue(OP_1.code + n.toInt() - 1)
        else -> OP_PUSHDATA(Script.encodeNumber(n))
    }

    /**
     * This function interprets the locktime for the given transaction, and returns the block height before which this tx cannot be published.
     * By convention in bitcoin, depending of the value of locktime it might be a number of blocks or a number of seconds since epoch.
     * This function does not support the case when the locktime is a number of seconds that is not way in the past.
     * NB: We use this property in lightning to store data in this field.
     *
     * @return the block height before which this tx cannot be published.
     */
    fun cltvTimeout(tx: Transaction): Long = when {
        // locktime is a number of blocks
        tx.lockTime <= Script.LOCKTIME_THRESHOLD -> tx.lockTime
        else -> {
            // locktime is a unix epoch timestamp
            require(tx.lockTime <= 0x20FFFFFF) { "locktime should be lesser than 0x20FFFFFF" }
            // since locktime is very well in the past (0x20FFFFFF is in 1987), it is equivalent to no locktime at all
            0
        }
    }

    /**
     * @return the number of confirmations of the tx parent before which it can be published
     */
    fun csvTimeout(tx: Transaction): Long {
        fun sequenceToBlockHeight(sequence: Long) =
            if ((sequence and TxIn.SEQUENCE_LOCKTIME_DISABLE_FLAG) != 0L) 0L
            else {
                require((sequence and TxIn.SEQUENCE_LOCKTIME_TYPE_FLAG) == 0L) { "CSV timeout must use block heights, not block times" }
                sequence and TxIn.SEQUENCE_LOCKTIME_MASK
            }

        return if (tx.version < 2) 0L else tx.txIn.map { it.sequence }.maxOf { sequenceToBlockHeight(it) }
    }

    fun toAnchor(fundingPubkey: PublicKey): List<ScriptElt> =
        // @formatter:off
        listOf(
            OP_PUSHDATA(fundingPubkey),
            OP_CHECKSIG,
            OP_IFDUP,
            OP_NOTIF,
                OP_16, OP_CHECKSEQUENCEVERIFY,
            OP_ENDIF
        )
        // @formatter:on

    fun toLocalDelayed(revocationPubkey: PublicKey, toSelfDelay: CltvExpiryDelta, localDelayedPaymentPubkey: PublicKey): List<ScriptElt> =
        // @formatter:off
        listOf(
            OP_IF,
                OP_PUSHDATA(revocationPubkey),
            OP_ELSE,
                encodeNumber(toSelfDelay.toLong()), OP_CHECKSEQUENCEVERIFY, OP_DROP,
                OP_PUSHDATA(localDelayedPaymentPubkey),
            OP_ENDIF,
            OP_CHECKSIG
        )
        // @formatter:on

    fun toRemoteDelayed(pub: PublicKey): List<ScriptElt> = listOf(OP_PUSHDATA(pub), OP_CHECKSIGVERIFY, OP_1, OP_CHECKSEQUENCEVERIFY)

    /**
     * This witness script spends a [[toLocalDelayed]] output using a local sig after a delay
     */
    fun witnessToRemoteDelayedAfterDelay(localSig: ByteVector64, toRemoteDelayedScript: ByteVector) =
        ScriptWitness(listOf(der(localSig, SigHash.SIGHASH_ALL), toRemoteDelayedScript))

    /**
     * This witness script spends a [[toLocalDelayed]] output using a local sig after a delay
     */
    fun witnessToLocalDelayedAfterDelay(localSig: ByteVector64, toLocalDelayedScript: ByteVector) =
        ScriptWitness(listOf(der(localSig, SigHash.SIGHASH_ALL), ByteVector.empty, toLocalDelayedScript))

    /**
     * This witness script spends (steals) a [[toLocalDelayed]] output using a revocation key as a punishment
     * for having published a revoked transaction
     */
    fun witnessToLocalDelayedWithRevocationSig(revocationSig: ByteVector64, toLocalScript: ByteVector) =
        ScriptWitness(listOf(der(revocationSig, SigHash.SIGHASH_ALL), ByteVector(byteArrayOf(1)), toLocalScript))

    fun htlcOffered(localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey, revocationPubKey: PublicKey, paymentHash: ByteArray): List<ScriptElt> = listOf(
        // @formatter:off
        // To you with revocation key
        OP_DUP, OP_HASH160, OP_PUSHDATA(revocationPubKey.hash160()), OP_EQUAL,
        OP_IF,
            OP_CHECKSIG,
        OP_ELSE,
            OP_PUSHDATA(remoteHtlcPubkey), OP_SWAP, OP_SIZE, encodeNumber(32), OP_EQUAL,
            OP_NOTIF,
                // To me via HTLC-timeout transaction (timelocked).
                OP_DROP, OP_2, OP_SWAP, OP_PUSHDATA(localHtlcPubkey), OP_2, OP_CHECKMULTISIG,
            OP_ELSE,
                OP_HASH160, OP_PUSHDATA(paymentHash), OP_EQUALVERIFY,
                OP_CHECKSIG,
            OP_ENDIF,
            OP_1, OP_CHECKSEQUENCEVERIFY, OP_DROP,
        OP_ENDIF
        // @formatter:on
    )

    /**
     * This is the witness script of the 2nd-stage HTLC Success transaction (consumes htlcOffered script from commit tx)
     * local signature is created with SIGHASH_ALL flag
     * remote signature is created with SIGHASH_SINGLE || SIGHASH_ANYONECANPAY
     */
    fun witnessHtlcSuccess(localSig: ByteVector64, remoteSig: ByteVector64, paymentPreimage: ByteVector32, htlcOfferedScript: ByteVector) =
        ScriptWitness(listOf(ByteVector.empty, der(remoteSig, SigHash.SIGHASH_SINGLE or SigHash.SIGHASH_ANYONECANPAY), der(localSig, SigHash.SIGHASH_ALL), paymentPreimage, htlcOfferedScript))

    /** Extract payment preimages from a 2nd-stage HTLC Success transaction's witness script. */
    fun extractPreimagesFromHtlcSuccess(tx: Transaction): Set<ByteVector32> {
        return tx.txIn.map { it.witness }.mapNotNull {
            when {
                it.stack.size < 5 -> null
                !it.stack[0].isEmpty() -> null
                it.stack[3].size() != 32 -> null
                else -> ByteVector32(it.stack[3])
            }
        }.toSet()
    }

    /**
     * If remote publishes its commit tx where there was a remote->local htlc, then local uses this script to
     * claim its funds using a payment preimage (consumes htlcOffered script from commit tx)
     */
    fun witnessClaimHtlcSuccessFromCommitTx(localSig: ByteVector64, paymentPreimage: ByteVector32, htlcOffered: ByteVector) =
        ScriptWitness(listOf(der(localSig, SigHash.SIGHASH_ALL), paymentPreimage, htlcOffered))

    /** Extract payment preimages from a claim-htlc transaction. */
    fun extractPreimagesFromClaimHtlcSuccess(tx: Transaction): Set<ByteVector32> {
        return tx.txIn.map { it.witness }.mapNotNull {
            when {
                it.stack.size < 3 -> null
                it.stack[1].size() != 32 -> null
                else -> ByteVector32(it.stack[1])
            }
        }.toSet()
    }

    fun htlcReceived(localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey, revocationPubKey: PublicKey, paymentHash: ByteArray, lockTime: CltvExpiry) = listOf(
        // @formatter:off
        // To you with revocation key
        OP_DUP, OP_HASH160, OP_PUSHDATA(revocationPubKey.hash160()), OP_EQUAL,
        OP_IF,
            OP_CHECKSIG,
        OP_ELSE,
            OP_PUSHDATA(remoteHtlcPubkey), OP_SWAP, OP_SIZE, encodeNumber(32), OP_EQUAL,
            OP_IF,
                // To me via HTLC-success transaction.
                OP_HASH160, OP_PUSHDATA(paymentHash), OP_EQUALVERIFY,
                OP_2, OP_SWAP, OP_PUSHDATA(localHtlcPubkey), OP_2, OP_CHECKMULTISIG,
            OP_ELSE,
                // To you after timeout.
                OP_DROP, encodeNumber(lockTime.toLong()), OP_CHECKLOCKTIMEVERIFY, OP_DROP,
                OP_CHECKSIG,
            OP_ENDIF,
            OP_1, OP_CHECKSEQUENCEVERIFY, OP_DROP,
        OP_ENDIF
        // @formatter:on
    )

    /**
     * This is the witness script of the 2nd-stage HTLC Timeout transaction (consumes htlcOffered script from commit tx)
     * local signature is created with SIGHASH_ALL
     * remote signature is created with SIGHASH_SINGLE || SIGHASH_ANYONECANPAY
     */
    fun witnessHtlcTimeout(localSig: ByteVector64, remoteSig: ByteVector64, htlcOfferedScript: ByteVector) =
        ScriptWitness(listOf(ByteVector.empty, der(remoteSig, SigHash.SIGHASH_SINGLE or SigHash.SIGHASH_ANYONECANPAY), der(localSig, SigHash.SIGHASH_ALL), ByteVector.empty, htlcOfferedScript))

    /**
     * If remote publishes its commit tx where there was a local->remote htlc, then local uses this script to
     * claim its funds after timeout (consumes htlcReceived script from commit tx)
     */
    fun witnessClaimHtlcTimeoutFromCommitTx(localSig: ByteVector64, htlcReceivedScript: ByteVector) =
        ScriptWitness(listOf(der(localSig, SigHash.SIGHASH_ALL), ByteVector.empty, htlcReceivedScript))

    /**
     * This witness script spends (steals) a [[htlcOffered]] or [[htlcReceived]] output using a revocation key as a punishment
     * for having published a revoked transaction
     */
    fun witnessHtlcWithRevocationSig(revocationSig: ByteVector64, revocationPubkey: PublicKey, htlcScript: ByteVector) =
        ScriptWitness(listOf(der(revocationSig, SigHash.SIGHASH_ALL), revocationPubkey.value, htlcScript))

}