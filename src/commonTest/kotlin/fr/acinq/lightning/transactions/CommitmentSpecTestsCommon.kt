package fr.acinq.lightning.transactions

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.tests.TestConstants
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.wire.UpdateAddHtlc
import fr.acinq.lightning.wire.UpdateFailHtlc
import fr.acinq.lightning.wire.UpdateFulfillHtlc
import kotlin.test.Test
import kotlin.test.assertEquals

class CommitmentSpecTestsCommon : LightningTestSuite() {
    @Test
    fun `add, fulfill and fail htlcs from the sender side`() {
        val spec = CommitmentSpec(htlcs = setOf(), feerate = FeeratePerKw(1_000.sat), toLocal = MilliSatoshi(5000000), toRemote = MilliSatoshi(0))
        val R = randomBytes32()
        val H = ByteVector32(Crypto.sha256(R))

        val add1 = UpdateAddHtlc(ByteVector32.Zeroes, 1, MilliSatoshi(2000L * 1000), H, CltvExpiry(400), TestConstants.emptyOnionPacket)
        val spec1 = CommitmentSpec.reduce(spec, listOf(add1), listOf())
        assertEquals(spec1, spec.copy(htlcs = setOf(OutgoingHtlc(add1)), toLocal = MilliSatoshi(3000000)))

        val add2 = UpdateAddHtlc(ByteVector32.Zeroes, 2, MilliSatoshi(1000L * 1000), H, CltvExpiry(400), TestConstants.emptyOnionPacket)
        val spec2 = CommitmentSpec.reduce(spec1, listOf(add2), listOf())
        assertEquals(spec2, spec1.copy(htlcs = setOf(OutgoingHtlc(add1), OutgoingHtlc(add2)), toLocal = MilliSatoshi(2000000)))

        val ful1 = UpdateFulfillHtlc(ByteVector32.Zeroes, add1.id, R)
        val spec3 = CommitmentSpec.reduce(spec2, listOf(), listOf(ful1))
        assertEquals(spec3, spec2.copy(htlcs = setOf(OutgoingHtlc(add2)), toRemote = MilliSatoshi(2000000)))

        val fail1 = UpdateFailHtlc(ByteVector32.Zeroes, add2.id, R)
        val spec4 = CommitmentSpec.reduce(spec3, listOf(), listOf(fail1))
        assertEquals(spec4, spec3.copy(htlcs = setOf(), toLocal = MilliSatoshi(3000000)))
    }

    @Test
    fun `add, fulfill and fail htlcs from the receiver side`() {
        val spec = CommitmentSpec(htlcs = setOf(), feerate = FeeratePerKw(1_000.sat), toLocal = MilliSatoshi(0), toRemote = MilliSatoshi(5000L * 1000))
        val R = randomBytes32()
        val H = ByteVector32(Crypto.sha256(R))

        val add1 = UpdateAddHtlc(ByteVector32.Zeroes, 1, MilliSatoshi(2000L * 1000), H, CltvExpiry(400), TestConstants.emptyOnionPacket)
        val spec1 = CommitmentSpec.reduce(spec, listOf(), listOf(add1))
        assertEquals(spec1, spec.copy(htlcs = setOf(IncomingHtlc(add1)), toRemote = MilliSatoshi(3000L * 1000)))

        val add2 = UpdateAddHtlc(ByteVector32.Zeroes, 2, MilliSatoshi(1000L * 1000), H, CltvExpiry(400), TestConstants.emptyOnionPacket)
        val spec2 = CommitmentSpec.reduce(spec1, listOf(), listOf(add2))
        assertEquals(spec2, spec1.copy(htlcs = setOf(IncomingHtlc(add1), IncomingHtlc(add2)), toRemote = MilliSatoshi(2000L * 1000)))

        val ful1 = UpdateFulfillHtlc(ByteVector32.Zeroes, add1.id, R)
        val spec3 = CommitmentSpec.reduce(spec2, listOf(ful1), listOf())
        assertEquals(spec3, spec2.copy(htlcs = setOf(IncomingHtlc(add2)), toLocal = MilliSatoshi(2000L * 1000)))

        val fail1 = UpdateFailHtlc(ByteVector32.Zeroes, add2.id, R)
        val spec4 = CommitmentSpec.reduce(spec3, listOf(fail1), listOf())
        assertEquals(spec4, spec3.copy(htlcs = setOf(), toRemote = MilliSatoshi(3000L * 1000)))
    }

}