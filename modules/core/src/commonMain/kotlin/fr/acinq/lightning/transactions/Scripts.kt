package fr.acinq.lightning.transactions

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.ScriptEltMapping.code2elt
import fr.acinq.bitcoin.SigHash.SIGHASH_ALL
import fr.acinq.bitcoin.SigHash.SIGHASH_ANYONECANPAY
import fr.acinq.bitcoin.SigHash.SIGHASH_DEFAULT
import fr.acinq.bitcoin.SigHash.SIGHASH_SINGLE
import fr.acinq.bitcoin.crypto.musig2.Musig2
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.crypto.CommitmentPublicKeys
import fr.acinq.lightning.crypto.LocalCommitmentKeys
import fr.acinq.lightning.crypto.RemoteCommitmentKeys
import fr.acinq.lightning.transactions.Scripts.htlcOffered
import fr.acinq.lightning.transactions.Scripts.htlcReceived
import fr.acinq.lightning.transactions.Scripts.toLocalDelayed

/**
 * Created by PM on 02/12/2016.
 */
object Scripts {

    fun der(sig: ByteVector64, sigHash: Int): ByteVector = Crypto.compact2der(sig).concat(sigHash.toByte())

    fun sort(pubkeys: List<PublicKey>): List<PublicKey> = pubkeys.sortedWith { p1, p2 -> LexicographicalOrdering.compare(p1, p2) }

    private fun htlcRemoteSighash(commitmentFormat: Transactions.CommitmentFormat): Int = when (commitmentFormat) {
        is Transactions.CommitmentFormat.AnchorOutputs -> SIGHASH_SINGLE or SIGHASH_ANYONECANPAY
        is Transactions.CommitmentFormat.SimpleTaprootChannels -> SIGHASH_SINGLE or SIGHASH_ANYONECANPAY
    }

    fun multiSig2of2(pubkey1: PublicKey, pubkey2: PublicKey): List<ScriptElt> = when {
        LexicographicalOrdering.isLessThan(pubkey1.value, pubkey2.value) -> Script.createMultiSigMofN(2, listOf(pubkey1, pubkey2))
        else -> Script.createMultiSigMofN(2, listOf(pubkey2, pubkey1))
    }

    /**
     * @return a script witness that matches the msig 2-of-2 pubkey script for pubkey1 and pubkey2
     */
    fun witness2of2(sig1: ByteVector64, sig2: ByteVector64, pubkey1: PublicKey, pubkey2: PublicKey): ScriptWitness {
        val encodedSig1 = der(sig1, SIGHASH_ALL)
        val encodedSig2 = der(sig2, SIGHASH_ALL)
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

    fun toAnchor(anchorKey: PublicKey): List<ScriptElt> = listOf(
        // @formatter:off
        OP_PUSHDATA(anchorKey), OP_CHECKSIG, OP_IFDUP,
        OP_NOTIF,
            OP_16, OP_CHECKSEQUENCEVERIFY,
        OP_ENDIF
        // @formatter:on
    )

    fun toLocalDelayed(keys: CommitmentPublicKeys, toSelfDelay: CltvExpiryDelta): List<ScriptElt> = listOf(
        // @formatter:off
        OP_IF,
            OP_PUSHDATA(keys.revocationPublicKey),
        OP_ELSE,
            encodeNumber(toSelfDelay.toLong()), OP_CHECKSEQUENCEVERIFY, OP_DROP,
            OP_PUSHDATA(keys.localDelayedPaymentPublicKey),
        OP_ENDIF,
        OP_CHECKSIG
        // @formatter:on
    )

    fun toRemoteDelayed(keys: CommitmentPublicKeys): List<ScriptElt> = listOf(OP_PUSHDATA(keys.remotePaymentPublicKey), OP_CHECKSIGVERIFY, OP_1, OP_CHECKSEQUENCEVERIFY)

    /**
     * This witness script spends a [toLocalDelayed] output using a local sig after a delay
     */
    fun witnessToRemoteDelayedAfterDelay(localSig: ByteVector64, toRemoteDelayedScript: ByteVector) =
        ScriptWitness(listOf(der(localSig, SIGHASH_ALL), toRemoteDelayedScript))

    /**
     * This witness script spends a [toLocalDelayed] output using a local sig after a delay
     */
    fun witnessToLocalDelayedAfterDelay(localSig: ByteVector64, toLocalDelayedScript: ByteVector) =
        ScriptWitness(listOf(der(localSig, SIGHASH_ALL), ByteVector.empty, toLocalDelayedScript))

    /**
     * This witness script spends (steals) a [toLocalDelayed] output using a revocation key as a punishment
     * for having published a revoked transaction
     */
    fun witnessToLocalDelayedWithRevocationSig(revocationSig: ByteVector64, toLocalScript: ByteVector) =
        ScriptWitness(listOf(der(revocationSig, SIGHASH_ALL), ByteVector(byteArrayOf(1)), toLocalScript))

    fun htlcOffered(keys: CommitmentPublicKeys, paymentHash: ByteVector32): List<ScriptElt> = listOf(
        // @formatter:off
        // To you with revocation key
        OP_DUP, OP_HASH160, OP_PUSHDATA(keys.revocationPublicKey.hash160()), OP_EQUAL,
        OP_IF,
            OP_CHECKSIG,
        OP_ELSE,
            OP_PUSHDATA(keys.remoteHtlcPublicKey), OP_SWAP, OP_SIZE, encodeNumber(32), OP_EQUAL,
            OP_NOTIF,
                // To me via HTLC-timeout transaction (timelocked).
                OP_DROP, OP_2, OP_SWAP, OP_PUSHDATA(keys.localHtlcPublicKey), OP_2, OP_CHECKMULTISIG,
            OP_ELSE,
                OP_HASH160, OP_PUSHDATA(Crypto.ripemd160(paymentHash)), OP_EQUALVERIFY,
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
    fun witnessHtlcSuccess(localSig: ByteVector64, remoteSig: ByteVector64, preimage: ByteVector32, htlcOfferedScript: ByteVector) =
        ScriptWitness(listOf(ByteVector.empty, der(remoteSig, SIGHASH_SINGLE or SIGHASH_ANYONECANPAY), der(localSig, SIGHASH_ALL), preimage, htlcOfferedScript))

    /** Extract payment preimages from a 2nd-stage HTLC Success transaction's witness script. */
    fun extractPreimagesFromHtlcSuccess(tx: Transaction): Set<ByteVector32> {
        return tx.txIn.map { it.witness }.mapNotNull {
            when {
                it.stack.size != 5 -> null
                // anchor-outputs
                it.stack[0].isEmpty() && it.stack[3].size() == 32 -> ByteVector32(it.stack[3])
                // taproot
                it.stack[2].size() == 32 -> ByteVector32(it.stack[2])
                else -> null
            }
        }.toSet()
    }

    /**
     * If remote publishes its commit tx where there was a remote->local htlc, then local uses this script to
     * claim its funds using a payment preimage (consumes htlcOffered script from commit tx)
     */
    fun witnessClaimHtlcSuccessFromCommitTx(localSig: ByteVector64, preimage: ByteVector32, htlcOffered: ByteVector) =
        ScriptWitness(listOf(der(localSig, SIGHASH_ALL), preimage, htlcOffered))

    /** Extract payment preimages from a claim-htlc transaction. */
    fun extractPreimagesFromClaimHtlcSuccess(tx: Transaction): Set<ByteVector32> {
        return tx.txIn.map { it.witness }.mapNotNull {
            when {
                // anchor-outputs
                it.stack.size == 3 && it.stack[1].size() == 32 -> ByteVector32(it.stack[1])
                // taproot
                it.stack.size == 4 && it.stack[1].size() == 32 -> ByteVector32(it.stack[1])
                else -> null
            }
        }.toSet()
    }

    fun htlcReceived(keys: CommitmentPublicKeys, paymentHash: ByteVector32, lockTime: CltvExpiry) = listOf(
        // @formatter:off
        // To you with revocation key
        OP_DUP, OP_HASH160, OP_PUSHDATA(keys.revocationPublicKey.hash160()), OP_EQUAL,
        OP_IF,
            OP_CHECKSIG,
        OP_ELSE,
            OP_PUSHDATA(keys.remoteHtlcPublicKey), OP_SWAP, OP_SIZE, encodeNumber(32), OP_EQUAL,
            OP_IF,
                // To me via HTLC-success transaction.
                OP_HASH160, OP_PUSHDATA(Crypto.ripemd160(paymentHash)), OP_EQUALVERIFY,
                OP_2, OP_SWAP, OP_PUSHDATA(keys.localHtlcPublicKey), OP_2, OP_CHECKMULTISIG,
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
        ScriptWitness(listOf(ByteVector.empty, der(remoteSig, SIGHASH_SINGLE or SIGHASH_ANYONECANPAY), der(localSig, SIGHASH_ALL), ByteVector.empty, htlcOfferedScript))

    /**
     * If remote publishes its commit tx where there was a local->remote htlc, then local uses this script to
     * claim its funds after timeout (consumes htlcReceived script from commit tx)
     */
    fun witnessClaimHtlcTimeoutFromCommitTx(localSig: ByteVector64, htlcReceivedScript: ByteVector) =
        ScriptWitness(listOf(der(localSig, SIGHASH_ALL), ByteVector.empty, htlcReceivedScript))

    /**
     * This witness script spends (steals) a [[htlcOffered]] or [[htlcReceived]] output using a revocation key as a punishment
     * for having published a revoked transaction
     */
    fun witnessHtlcWithRevocationSig(commitKeys: RemoteCommitmentKeys, revocationSig: ByteVector64, htlcScript: ByteVector) =
        ScriptWitness(listOf(der(revocationSig, SIGHASH_ALL), commitKeys.revocationPublicKey.value, htlcScript))

    /**
     * Specific scripts for taproot channels
     */
    object Taproot {
        /**
         * Taproot signatures are usually 64 bytes, unless a non-default sighash is used, in which case it is appended.
         */
        fun encodeSig(sig: ByteVector64, sighashType: Int = SIGHASH_DEFAULT): ByteVector = when (sighashType) {
            SIGHASH_DEFAULT -> sig
            else -> sig.concat(sighashType.toByte())
        }

        /**
         * Sort and aggregate the public keys of a musig2 session.
         *
         * @return the aggregated public key
         * @see [fr.acinq.bitcoin.crypto.musig2.Musig2.aggregateKeys]
         */
        fun musig2Aggregate(pubkey1: PublicKey, pubkey2: PublicKey): XonlyPublicKey = Musig2.aggregateKeys(sort(listOf(pubkey1, pubkey2)))

        /**
         * "Nothing Up My Sleeve" point, for which there is no known private key.
         */
        val NUMS_POINT: PublicKey = PublicKey(ByteVector.fromHex("02dca094751109d0bd055d03565874e8276dd53e926b44e3bd1bb6bf4bc130a279"))

        // miniscript: older(16)
        private val anchorScript: List<ScriptElt> = listOf(OP_16, OP_CHECKSEQUENCEVERIFY)
        val anchorScriptTree = ScriptTree.Leaf(anchorScript)

        /**
         * Script used for local or remote anchor outputs.
         * The key used matches the key for the matching node's main output.
         */
        fun anchor(anchorKey: PublicKey): List<ScriptElt> = Script.pay2tr(anchorKey.xOnly(), anchorScriptTree)

        /**
         * Script that can be spent with the revocation key and reveals the delayed payment key to allow observers to claim
         * unused anchor outputs.
         *
         * miniscript: this is not miniscript compatible
         *
         * @return a script that will be used to add a "revocation" leaf to a script tree
         */
        private fun toRevocationKey(keys: CommitmentPublicKeys): List<ScriptElt> = listOf(
            OP_PUSHDATA(keys.localDelayedPaymentPublicKey.xOnly()), OP_DROP, OP_PUSHDATA(keys.revocationPublicKey.xOnly()), OP_CHECKSIG
        )

        /**
         * Script that can be spent by the owner of the commitment transaction after a delay.
         *
         * miniscript: and_v(v:pk(delayed_key),older(delay))
         *
         * @return a script that will be used to add a "to local key" leaf to a script tree
         */
        private fun toLocalDelayed(keys: CommitmentPublicKeys, toSelfDelay: CltvExpiryDelta): List<ScriptElt> = listOf(
            OP_PUSHDATA(keys.localDelayedPaymentPublicKey.xOnly()), OP_CHECKSIGVERIFY, encodeNumber(toSelfDelay.toLong()), OP_CHECKSEQUENCEVERIFY
        )

        data class ToLocalScriptTree(val localDelayed: ScriptTree.Leaf, val revocation: ScriptTree.Leaf) {
            val scriptTree: ScriptTree.Branch = ScriptTree.Branch(localDelayed, revocation)
        }

        /**
         * @return a script tree with two leaves (to self with delay, and to revocation key)
         */
        fun toLocalScriptTree(keys: CommitmentPublicKeys, toSelfDelay: CltvExpiryDelta): ToLocalScriptTree {
            return ToLocalScriptTree(
                ScriptTree.Leaf(toLocalDelayed(keys, toSelfDelay)),
                ScriptTree.Leaf(toRevocationKey(keys)),
            )
        }

        /**
         * Script used for the main balance of the owner of the commitment transaction.
         */
        fun toLocal(keys: CommitmentPublicKeys, toSelfDelay: CltvExpiryDelta): List<ScriptElt> {
            return Script.pay2tr(NUMS_POINT.xOnly(), toLocalScriptTree(keys, toSelfDelay).scriptTree)
        }

        /**
         * Script that can be spent by the channel counterparty after a 1-block delay.
         *
         * miniscript: and_v(v:pk(remote_key),older(1))
         *
         * @return a script that will be used to add a "to remote key" leaf to a script tree
         */
        private fun toRemoteDelayed(keys: CommitmentPublicKeys): List<ScriptElt> = listOf(
            OP_PUSHDATA(keys.remotePaymentPublicKey.xOnly()), OP_CHECKSIGVERIFY, OP_1, OP_CHECKSEQUENCEVERIFY
        )

        /**
         * Script tree used for the main balance of the remote node in our commitment transaction.
         * Note that there is no need for a revocation leaf in that case.
         *
         * @return a script tree with a single leaf (to remote key, with a 1-block CSV delay)
         */
        fun toRemoteScriptTree(keys: CommitmentPublicKeys): ScriptTree.Leaf {
            return ScriptTree.Leaf(toRemoteDelayed(keys))
        }

        /**
         * Script used for the main balance of the remote node in our commitment transaction.
         */
        fun toRemote(keys: CommitmentPublicKeys): List<ScriptElt> {
            return Script.pay2tr(NUMS_POINT.xOnly(), toRemoteScriptTree(keys))
        }

        /**
         * Script that can be spent when an offered (outgoing) HTLC times out.
         * It is spent using a pre-signed HTLC transaction signed with both keys.
         *
         * miniscript: and_v(v:pk(local_htlc_key),pk(remote_htlc_key))
         *
         * @return a script used to create a "HTLC timeout" leaf in a script tree
         */
        private fun offeredHtlcTimeout(keys: CommitmentPublicKeys): List<ScriptElt> = listOf(
            OP_PUSHDATA(keys.localHtlcPublicKey.xOnly()), OP_CHECKSIGVERIFY, OP_PUSHDATA(keys.remoteHtlcPublicKey.xOnly()), OP_CHECKSIG
        )

        /**
         * Script that can be spent when an offered (outgoing) HTLC is fulfilled.
         * It is spent using a signature from the receiving node and the preimage, with a 1-block delay.
         *
         * miniscript: and_v(v:hash160(H),and_v(v:pk(remote_htlc_key),older(1)))
         *
         * @return a script used to create a "spend offered HTLC" leaf in a script tree
         */
        private fun offeredHtlcSuccess(keys: CommitmentPublicKeys, paymentHash: ByteVector32): List<ScriptElt> = listOf(
            // @formatter:off
            OP_SIZE, encodeNumber(32), OP_EQUALVERIFY,
            OP_HASH160, OP_PUSHDATA(Crypto.ripemd160(paymentHash)), OP_EQUALVERIFY,
            OP_PUSHDATA(keys.remoteHtlcPublicKey.xOnly()), OP_CHECKSIGVERIFY,
            OP_1, OP_CHECKSEQUENCEVERIFY
            // @formatter:on
        )

        data class OfferedHtlcScriptTree(val timeout: ScriptTree.Leaf, val success: ScriptTree.Leaf) {
            val scriptTree: ScriptTree.Branch = ScriptTree.Branch(timeout, success)

            fun witnessTimeout(commitKeys: LocalCommitmentKeys, localSig: ByteVector64, remoteSig: ByteVector64): ScriptWitness =
                Script.witnessScriptPathPay2tr(
                    commitKeys.revocationPublicKey.xOnly(),
                    timeout,
                    ScriptWitness(listOf(encodeSig(remoteSig, htlcRemoteSighash(Transactions.CommitmentFormat.SimpleTaprootChannels)), localSig)),
                    scriptTree
                )

            fun witnessSuccess(commitKeys: RemoteCommitmentKeys, localSig: ByteVector64, paymentPreimage: ByteVector32): ScriptWitness =
                Script.witnessScriptPathPay2tr(commitKeys.revocationPublicKey.xOnly(), success, ScriptWitness(listOf(localSig, paymentPreimage)), scriptTree)
        }

        /**
         * Script tree used for offered HTLCs.
         */
        fun offeredHtlcScriptTree(keys: CommitmentPublicKeys, paymentHash: ByteVector32): OfferedHtlcScriptTree =
            OfferedHtlcScriptTree(
                ScriptTree.Leaf(offeredHtlcTimeout(keys)),
                ScriptTree.Leaf(offeredHtlcSuccess(keys, paymentHash)),
            )

        /**
         * Script that can be spent when a received (incoming) HTLC times out.
         * It is spent using a signature from the receiving node after an absolute delay and a 1-block relative delay.
         *
         * miniscript: and_v(v:pk(remote_htlc_key),and_v(v:older(1),after(delay)))
         */
        private fun receivedHtlcTimeout(keys: CommitmentPublicKeys, expiry: CltvExpiry): List<ScriptElt> = listOf(
            // @formatter:off
            OP_PUSHDATA(keys.remoteHtlcPublicKey.xOnly()), OP_CHECKSIGVERIFY,
            OP_1, OP_CHECKSEQUENCEVERIFY, OP_VERIFY,
            encodeNumber(expiry.toLong()), OP_CHECKLOCKTIMEVERIFY,
            // @formatter:on
        )

        /**
         * Script that can be spent when a received (incoming) HTLC is fulfilled.
         * It is spent using a pre-signed HTLC transaction signed with both keys and the preimage.
         *
         * miniscript: and_v(v:hash160(H),and_v(v:pk(local_key),pk(remote_key)))
         */
        private fun receivedHtlcSuccess(keys: CommitmentPublicKeys, paymentHash: ByteVector32): List<ScriptElt> = listOf(
            // @formatter:off
            OP_SIZE, encodeNumber(32), OP_EQUALVERIFY,
            OP_HASH160, OP_PUSHDATA(Crypto.ripemd160(paymentHash)), OP_EQUALVERIFY,
            OP_PUSHDATA(keys.localHtlcPublicKey.xOnly()), OP_CHECKSIGVERIFY,
            OP_PUSHDATA(keys.remoteHtlcPublicKey.xOnly()), OP_CHECKSIG,
            // @formatter:on
        )

        data class ReceivedHtlcScriptTree(val timeout: ScriptTree.Leaf, val success: ScriptTree.Leaf) {
            val scriptTree = ScriptTree.Branch(timeout, success)

            fun witnessSuccess(commitKeys: LocalCommitmentKeys, localSig: ByteVector64, remoteSig: ByteVector64, paymentPreimage: ByteVector32): ScriptWitness =
                Script.witnessScriptPathPay2tr(
                    commitKeys.revocationPublicKey.xOnly(),
                    success,
                    ScriptWitness(listOf(encodeSig(remoteSig, htlcRemoteSighash(Transactions.CommitmentFormat.SimpleTaprootChannels)), localSig, paymentPreimage)),
                    scriptTree
                )

            fun witnessTimeout(commitKeys: RemoteCommitmentKeys, localSig: ByteVector64): ScriptWitness =
                Script.witnessScriptPathPay2tr(commitKeys.revocationPublicKey.xOnly(), timeout, ScriptWitness(listOf(localSig)), scriptTree)

        }

        /**
         * Script tree used for received HTLCs.
         */
        fun receivedHtlcScriptTree(keys: CommitmentPublicKeys, paymentHash: ByteVector32, expiry: CltvExpiry): ReceivedHtlcScriptTree =
            ReceivedHtlcScriptTree(
                ScriptTree.Leaf(receivedHtlcTimeout(keys, expiry)),
                ScriptTree.Leaf(receivedHtlcSuccess(keys, paymentHash)),
            )

        /**
         * Script tree used for the output of pre-signed HTLC 2nd-stage transactions.
         */
        fun htlcDelayedScriptTree(keys: CommitmentPublicKeys, toSelfDelay: CltvExpiryDelta): ScriptTree.Leaf =
            ScriptTree.Leaf(toLocalDelayed(keys, toSelfDelay))

    }

}