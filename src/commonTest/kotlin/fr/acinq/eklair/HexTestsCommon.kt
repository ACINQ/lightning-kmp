package fr.acinq.eklair

import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HexTestsCommon {
    @Test
    fun `encode`() {
        val bytes = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        assertEquals(Hex.encode(bytes), "deadbeef")
    }

    @Test
    fun `decode`() {
        val bytes = byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())
        assertTrue(Hex.decode("deadbeef").contentEquals(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())))
    }
}