package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.crypto.musig2.Musig2
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.transactions.Transactions

object NonceGenerator {

    // When using single-funding, we don't have access to the funding tx and remote funding key when creating our first
    // verification nonce, so we use placeholder values instead. Note that this is fixed with dual-funding.
    val dummyFundingTxId: TxId = TxId(ByteVector32.Zeroes)
    val dummyRemoteFundingPubKey: PublicKey = PrivateKey(ByteVector32.One).publicKey()

    /**
     * @return a deterministic nonce used to sign our local commit tx: its public part is sent to our peer.
     */
    fun verificationNonce(fundingTxId: TxId, fundingPrivKey: PrivateKey, remoteFundingPubKey: PublicKey, commitIndex: Long): Transactions.LocalNonce {
        val nonces = Musig2.generateNonceWithCounter(commitIndex, fundingPrivKey, listOf(fundingPrivKey.publicKey(), remoteFundingPubKey), null, fundingTxId.value)
        println("verificationNonce((fundingTxId= $fundingTxId localKey = ${fundingPrivKey.publicKey()} remoteKey = $remoteFundingPubKey commitIndex = $commitIndex)) = ${nonces.second}")
        return Transactions.LocalNonce(nonces.first, nonces.second)
    }

    /**
     * @return a random nonce used to sign our peer's commit tx.
     */
    fun signingNonce(localFundingPubKey: PublicKey, remoteFundingPubKey: PublicKey, fundingTxId: TxId): Transactions.LocalNonce {
        val sessionId = randomBytes32()
        val nonces = Musig2.generateNonce(sessionId,  null, localFundingPubKey, listOf(localFundingPubKey, remoteFundingPubKey), null, fundingTxId.value)
        return Transactions.LocalNonce(nonces.first, nonces.second)
    }
}