package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.ScriptEltMapping.code2elt
import fr.acinq.bitcoin.ScriptEltMapping.elt2code
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.CltvExpiryDelta
import fr.acinq.eclair.channel.CommitmentsFormat
import fr.acinq.eclair.utils.sat

/**
 * Created by PM on 02/12/2016.
 */
object Scripts {

    fun der(sig: ByteVector64, sigHash: Int = SigHash.SIGHASH_ALL): ByteVector = Crypto.compact2der(sig).concat(sigHash.toByte())

    fun multiSig2of2(pubkey1: PublicKey, pubkey2: PublicKey): List<ScriptElt> =
        if (LexicographicalOrdering.isLessThan(pubkey1.value, pubkey2.value)) {
            Script.createMultiSigMofN(2, listOf(pubkey1, pubkey2))
        } else {
            Script.createMultiSigMofN(2, listOf(pubkey2, pubkey1))
        }

    /**
     * @return a script witness that matches the msig 2-of-2 pubkey script for pubkey1 and pubkey2
     */
    fun witness2of2(sig1: ByteVector64, sig2: ByteVector64, pubkey1: PublicKey, pubkey2: PublicKey): ScriptWitness =
        if (LexicographicalOrdering.isLessThan(pubkey1.value, pubkey2.value)) {
            ScriptWitness(listOf(ByteVector.empty, der(sig1), der(sig2), ByteVector(Script.write(multiSig2of2(pubkey1, pubkey2)))))
        } else {
            ScriptWitness(listOf(ByteVector.empty, der(sig2), der(sig1), ByteVector(Script.write(multiSig2of2(pubkey1, pubkey2)))))
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
        in 1..16 -> code2elt.getValue((elt2code.getValue(OP_1) + n - 1).toInt())
        else -> OP_PUSHDATA(Script.encodeNumber(n))
    }

    fun applyFees(amount_us: Satoshi, amount_them: Satoshi, fee: Satoshi): Pair<Satoshi, Satoshi> =
        when {
            amount_us >= fee / 2 && amount_them >= fee / 2 -> Pair((amount_us - fee) / 2, (amount_them - fee) / 2)
            amount_us < fee / 2 -> Pair(0.sat, (amount_them - fee + amount_us).max(0.sat))
            amount_them < fee / 2 -> Pair((amount_us - fee + amount_them).max(0.sat), 0.sat)
            else -> error("impossible")
        }

    /**
     * This function interprets the locktime for the given transaction, and returns the block height before which this tx cannot be published.
     * By convention in bitcoin, depending of the value of locktime it might be a number of blocks or a number of seconds since epoch.
     * This function does not support the case when the locktime is a number of seconds that is not way in the past.
     * NB: We use this property in lightning to store data in this field.
     *
     * @return the block height before which this tx cannot be published.
     */
    fun cltvTimeout(tx: Transaction): Long =
        if (tx.lockTime <= Script.LockTimeThreshold) {
            // locktime is a number of blocks
            tx.lockTime
        } else {
            // locktime is a unix epoch timestamp
            require(tx.lockTime <= 0x20FFFFFF) { "locktime should be lesser than 0x20FFFFFF" }
            // since locktime is very well in the past (0x20FFFFFF is in 1987), it is equivalent to no locktime at all
            0
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

        return if (tx.version < 2) 0L else tx.txIn.map { it.sequence }.map { sequenceToBlockHeight(it) }.maxOrNull()!!
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
    fun witnessToLocalDelayedAfterDelay(localSig: ByteVector64, toLocalDelayedScript: ByteVector) =
        ScriptWitness(listOf(der(localSig), ByteVector.empty, toLocalDelayedScript))

    /**
     * This witness script spends (steals) a [[toLocalDelayed]] output using a revocation key as a punishment
     * for having published a revoked transaction
     */
    fun witnessToLocalDelayedWithRevocationSig(revocationSig: ByteVector64, toLocalScript: ByteVector) =
        ScriptWitness(listOf(der(revocationSig), ByteVector(byteArrayOf(1)), toLocalScript))

    fun htlcOffered(commitmentsFormat: CommitmentsFormat, localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey, revocationPubKey: PublicKey, paymentHash: ByteArray): List<ScriptElt> = when (commitmentsFormat) {
        is CommitmentsFormat.LegacyFormat -> listOf(
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
            OP_ENDIF
            // @formatter:on
        )
        is CommitmentsFormat.AnchorOutputs -> listOf(
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
    }

    /**
     * This is the witness script of the 2nd-stage HTLC Success transaction (consumes htlcOffered script from commit tx)
     */
    fun witnessHtlcSuccess(localSig: ByteVector64, remoteSig: ByteVector64, paymentPreimage: ByteVector32, htlcOfferedScript: ByteVector, sigHash: Int) =
        ScriptWitness(listOf(ByteVector.empty, der(remoteSig, sigHash), der(localSig), paymentPreimage, htlcOfferedScript))

    /** Extract the payment preimage from a 2nd-stage HTLC Success transaction's witness script */
    fun extractPreimageFromHtlcSuccess(): (ScriptWitness) -> ByteVector32? = f@{
        if (it.stack.size < 5 || !it.stack[0].isEmpty()) return@f null
        val paymentPreimage = it.stack[3]
        if (paymentPreimage.size() != 32) return@f null
        ByteVector32(paymentPreimage)
    }

    /**
     * If remote publishes its commit tx where there was a remote->local htlc, then local uses this script to
     * claim its funds using a payment preimage (consumes htlcOffered script from commit tx)
     */
    fun witnessClaimHtlcSuccessFromCommitTx(localSig: ByteVector64, paymentPreimage: ByteVector32, htlcOffered: ByteVector) =
        ScriptWitness(listOf(der(localSig), paymentPreimage, htlcOffered))

    /** Extract the payment preimage from from a fulfilled offered htlc. */
    fun extractPreimageFromClaimHtlcSuccess(): (ScriptWitness) -> ByteVector32? = f@{
        if (it.stack.size < 3) return@f null
        val paymentPreimage = it.stack[1]
        if (paymentPreimage.size() != 32) return@f null
        ByteVector32(paymentPreimage)
    }

    fun htlcReceived(commitmentsFormat: CommitmentsFormat, localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey, revocationPubKey: PublicKey, paymentHash: ByteArray, lockTime: CltvExpiry) = when (commitmentsFormat) {
        is CommitmentsFormat.LegacyFormat -> listOf(
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
            OP_ENDIF
            // @formatter:on
        )
        is CommitmentsFormat.AnchorOutputs -> listOf(
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
    }

    /**
     * This is the witness script of the 2nd-stage HTLC Timeout transaction (consumes htlcOffered script from commit tx)
     */
    fun witnessHtlcTimeout(localSig: ByteVector64, remoteSig: ByteVector64, htlcOfferedScript: ByteVector, sigHash: Int) =
        ScriptWitness(listOf(ByteVector.empty, der(remoteSig, sigHash), der(localSig), ByteVector.empty, htlcOfferedScript))

    /** Extract the payment hash from a 2nd-stage HTLC Timeout transaction's witness script */
    fun extractPaymentHashFromHtlcTimeout(): (ScriptWitness) -> ByteVector? = f@{
        if (it.stack.size < 5 || !it.stack[0].isEmpty() || !it.stack[3].isEmpty()) return@f null
        val htlcOfferedScript = it.stack[4]
        htlcOfferedScript.slice(109, 109 + 20)
    }

    /**
     * If remote publishes its commit tx where there was a local->remote htlc, then local uses this script to
     * claim its funds after timeout (consumes htlcReceived script from commit tx)
     */
    fun witnessClaimHtlcTimeoutFromCommitTx(localSig: ByteVector64, htlcReceivedScript: ByteVector) =
        ScriptWitness(listOf(der(localSig), ByteVector.empty, htlcReceivedScript))

    /** Extract the payment hash from a timed-out received htlc. */
    fun extractPaymentHashFromClaimHtlcTimeout(): (ScriptWitness) -> ByteVector? = f@{
        if (it.stack.size < 3 || !it.stack[1].isEmpty()) return@f null
        val htlcReceivedScript = it.stack[2]
        htlcReceivedScript.slice(69, 69 + 20)
    }

    /**
     * This witness script spends (steals) a [[htlcOffered]] or [[htlcReceived]] output using a revocation key as a punishment
     * for having published a revoked transaction
     */
    fun witnessHtlcWithRevocationSig(revocationSig: ByteVector64, revocationPubkey: PublicKey, htlcScript: ByteVector) =
        ScriptWitness(listOf(der(revocationSig), revocationPubkey.value, htlcScript))

}