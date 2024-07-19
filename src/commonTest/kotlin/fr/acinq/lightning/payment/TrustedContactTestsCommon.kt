package fr.acinq.lightning.payment

import fr.acinq.bitcoin.Block
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.OfferTypes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TrustedContactTestsCommon : LightningTestSuite() {

    @Test
    fun `identify payments coming from trusted contacts`() {
        val alice = TestConstants.Alice.nodeParams.defaultOffer(trampolineNodeId = randomKey().publicKey())
        val bob = TestConstants.Alice.nodeParams.defaultOffer(trampolineNodeId = randomKey().publicKey())
        val carol = run {
            val priv = randomKey()
            val offer = OfferTypes.Offer.createNonBlindedOffer(null, null, priv.publicKey(), Features.empty, Block.RegtestGenesisBlock.hash)
            OfferTypes.OfferAndKey(offer, priv)
        }

        // Alice has Bob and Carol in her contacts list.
        val aliceContacts = listOf(bob, carol).map { TrustedContact.create(alice, it.offer) }
        // Bob's only contact is Alice.
        val bobContacts = listOf(alice).map { TrustedContact.create(bob, it.offer) }
        // Carol's only contact is Bob.
        val carolContacts = listOf(bob).map { TrustedContact.create(carol, it.offer) }

        // Alice pays Bob: they are both in each other's contact list.
        val alicePayerKeyForBob = aliceContacts.first().deterministicPayerKey(alice)
        val aliceInvoiceRequestForBob = OfferTypes.InvoiceRequest(bob.offer, 1105.msat, 1, Features.empty, alicePayerKeyForBob, null, Block.RegtestGenesisBlock.hash)
        assertTrue(bobContacts.first().isPayer(aliceInvoiceRequestForBob))
        assertTrue(bobContacts.first().isPayer(alicePayerKeyForBob.publicKey()))

        // Alice pays Carol: but Carol's only contact is Bob, so she cannot identify the payer.
        val alicePayerKeyForCarol = aliceContacts.last().deterministicPayerKey(alice)
        assertNotEquals(alicePayerKeyForBob, alicePayerKeyForCarol)
        val aliceInvoiceRequestForCarol = OfferTypes.InvoiceRequest(carol.offer, 1729.msat, 1, Features.empty, alicePayerKeyForCarol, null, Block.RegtestGenesisBlock.hash)
        carolContacts.forEach { assertFalse(it.isPayer(aliceInvoiceRequestForCarol)) }
        carolContacts.forEach { assertFalse(it.isPayer(alicePayerKeyForCarol.publicKey())) }

        // Alice pays Bob, with a different payer_key: Bob cannot identify the payer.
        val aliceRandomPayerKey = randomKey()
        val alicePrivateInvoiceRequestForBob = OfferTypes.InvoiceRequest(bob.offer, 2465.msat, 1, Features.empty, aliceRandomPayerKey, null, Block.RegtestGenesisBlock.hash)
        bobContacts.forEach { assertFalse(it.isPayer(alicePrivateInvoiceRequestForBob)) }
        bobContacts.forEach { assertFalse(it.isPayer(aliceRandomPayerKey.publicKey())) }
    }

}