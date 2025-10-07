package fr.acinq.lightning.crypto

import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.tests.utils.LightningTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class NonceGeneratorTestsCommon : LightningTestSuite() {

    @Test
    fun `generate deterministic commitment verification nonces`() {
        val fundingTxId1 = TxId(randomBytes32())
        val fundingKey1 = randomKey()
        val remoteFundingKey1 = randomKey().publicKey()
        val fundingTxId2 = TxId(randomBytes32())
        val fundingKey2 = randomKey()
        val remoteFundingKey2 = randomKey().publicKey()
        // The verification nonce changes for each commitment.
        val nonces1 = (0 until 15L).map { commitIndex -> NonceGenerator.verificationNonce(fundingTxId1, fundingKey1, remoteFundingKey1, commitIndex) }
        assertEquals(15, nonces1.toSet().size)
        // We can re-compute verification nonces deterministically.
        (0 until 15L).forEach { i -> assertEquals(nonces1[i.toInt()], NonceGenerator.verificationNonce(fundingTxId1, fundingKey1, remoteFundingKey1, i)) }
        // Nonces for different splices are different.
        val nonces2 = (0 until 15L).map { commitIndex -> NonceGenerator.verificationNonce(fundingTxId2, fundingKey2, remoteFundingKey2, commitIndex) }
        assertEquals(30, (nonces1 + nonces2).toSet().size)
        // Changing any of the parameters changes the nonce value.
        assertFalse(nonces1.contains(NonceGenerator.verificationNonce(fundingTxId2, fundingKey1, remoteFundingKey1, 3)))
        assertFalse(nonces1.contains(NonceGenerator.verificationNonce(fundingTxId1, fundingKey2, remoteFundingKey1, 11)))
        assertFalse(nonces1.contains(NonceGenerator.verificationNonce(fundingTxId1, fundingKey1, remoteFundingKey2, 7)))
    }

    @Test
    fun `generate random signing nonces`() {
        val fundingTxId = TxId(randomBytes32())
        val localFundingKey = randomKey().publicKey()
        val remoteFundingKey = randomKey().publicKey()
        // Signing nonces are random and different every time, even if the parameters are the same.
        val nonce1 = NonceGenerator.signingNonce(localFundingKey, remoteFundingKey, fundingTxId)
        val nonce2 = NonceGenerator.signingNonce(localFundingKey, remoteFundingKey, fundingTxId)
        assertNotEquals(nonce1, nonce2)
        val nonce3 = NonceGenerator.signingNonce(localFundingKey, remoteFundingKey, TxId(randomBytes32()))
        assertNotEquals(nonce3, nonce1)
        assertNotEquals(nonce3, nonce2)
    }

}