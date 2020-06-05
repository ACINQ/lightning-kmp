package fr.acinq.eklair.transactions

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Script
import fr.acinq.eklair.CltvExpiry
import fr.acinq.eklair.CltvExpiryDelta
import fr.acinq.eklair.Eclair.randomBytes32
import fr.acinq.eklair.TestConstants
import fr.acinq.eklair.utils.msat
import fr.acinq.eklair.utils.sat
import fr.acinq.eklair.wire.UpdateAddHtlc
import kotlinx.serialization.InternalSerializationApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@InternalSerializationApi
class TransactionsTestsCommon {

    val localFundingPriv = PrivateKey(randomBytes32())
    val remoteFundingPriv = PrivateKey(randomBytes32())
    val localRevocationPriv = PrivateKey(randomBytes32())
    val localPaymentPriv = PrivateKey(randomBytes32())
    val localDelayedPaymentPriv = PrivateKey(randomBytes32())
    val remotePaymentPriv = PrivateKey(randomBytes32())
    val localHtlcPriv = PrivateKey(randomBytes32())
    val remoteHtlcPriv = PrivateKey(randomBytes32())
    val finalPubKeyScript = Script.write(Script.pay2wpkh(PrivateKey(randomBytes32()).publicKey()))
//    val commitInput = Funding.makeFundingInputInfo(randomBytes32, 0, Btc(1), localFundingPriv.publicKey, remoteFundingPriv.publicKey)
    val toLocalDelay = CltvExpiryDelta(144)
    val localDustLimit = 546.sat
    val feeratePerKw = 22_000

    @Test
    fun `encode and decode sequence and locktime (one example)`() {
        val txnumber = 0x11F71FB268DL

        val (sequence, locktime) = Transactions.encodeTxNumber(txnumber)
        assertEquals(0x80011F71L, sequence)
        assertEquals(0x20FB268DL, locktime)

        val txnumber1 = Transactions.decodeTxNumber(sequence, locktime)
        assertEquals(txnumber, txnumber1)
    }

    @Test
    fun `reconstruct txnumber from sequence and locktime`() {
        repeat (1_000) {
            val txnumber = Random.nextLong() and 0xffffffffffffL
            val (sequence, locktime) = Transactions.encodeTxNumber(txnumber)
            val txnumber1 = Transactions.decodeTxNumber(sequence, locktime)
            assertEquals(txnumber, txnumber1)
        }
    }

    @Test
    fun `compute fees`() {
        // see BOLT #3 specs
        val htlcs = setOf(
            OutgoingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 5000000.msat, ByteVector32.Zeroes, CltvExpiry(552), TestConstants.emptyOnionPacket)),
            OutgoingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 1000000.msat, ByteVector32.Zeroes, CltvExpiry(553), TestConstants.emptyOnionPacket)),
            IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 7000000.msat, ByteVector32.Zeroes, CltvExpiry(550), TestConstants.emptyOnionPacket)),
            IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, 800000.msat, ByteVector32.Zeroes, CltvExpiry(551), TestConstants.emptyOnionPacket))
        )
        val spec = CommitmentSpec(htlcs, feeratePerKw = 5000, toLocal = 0.msat, toRemote = 0.msat)
        val fee = Transactions.commitTxFee(546.sat, spec)
        assertEquals(5340.sat, fee)
    }

}
