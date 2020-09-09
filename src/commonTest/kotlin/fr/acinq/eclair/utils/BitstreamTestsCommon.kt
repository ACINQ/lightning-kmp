package fr.acinq.eclair.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitstreamTestsCommon {
    @Test
    fun `add bits`() {
        val bits = BitStream()
        bits.writeBit(true)
        assertEquals(bits.bitCount(),  1)
        assertTrue(bits.isSet(0))
        bits.writeBit(false)
        assertEquals(bits.bitCount(), 2)
        assertTrue(bits.isSet(0))
        assertFalse(bits.isSet(1))
        bits.writeBit(true)
        assertEquals(bits.bitCount(), 3)
        assertFalse(bits.isSet(1))
        assertTrue(bits.isSet(2))

        assertTrue(bits.popBit())
        assertFalse(bits.popBit())
        assertTrue(bits.popBit())
    }

    @Test
    fun `add bytes`() {
        val bits = BitStream()
        bits.writeByte(0xb5.toByte())
        assertEquals(bits.bitCount(),  8)
        bits.writeBit(false)
        assertEquals(bits.bitCount(),  9)
        bits.writeBit(true)
        assertEquals(bits.bitCount(),  10)
        val bits1 = bits.clone()
        assertEquals(bits.popByte(), 0xd5.toByte())
        assertEquals(bits1.readByte(), 0xb5.toByte())
    }
}