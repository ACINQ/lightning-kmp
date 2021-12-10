package fr.acinq.lightning.utils

import fr.acinq.lightning.tests.utils.LightningTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals

class BitFieldTestsCommon : LightningTestSuite() {

    @Test
    fun `Creation and simple get and set from left`() {
        val bs = BitField(1)

        assertEquals("0x00", bs.toString())
        assertEquals("00000000", bs.toBinaryString())
        assertEquals(false, bs.getLeft(1))
        assertEquals(false, bs.getLeft(2))

        bs.setLeft(1)
        assertEquals("0x40", bs.toString())
        assertEquals("01000000", bs.toBinaryString())
        assertEquals(true, bs.getLeft(1))
        assertEquals(false, bs.getLeft(2))

        bs.setLeft(2)
        assertEquals("0x60", bs.toString())
        assertEquals("01100000", bs.toBinaryString())
        assertEquals(true, bs.getLeft(1))
        assertEquals(true, bs.getLeft(2))

        bs.clearLeft(1)
        assertEquals("0x20", bs.toString())
        assertEquals("00100000", bs.toBinaryString())
        assertEquals(false, bs.getLeft(1))
        assertEquals(true, bs.getLeft(2))
    }

    @Test
    fun `Creation and simple get and set from right`() {
        val bs = BitField(1)

        assertEquals("0x00", bs.toString())
        assertEquals("00000000", bs.toBinaryString())
        assertEquals(false, bs.getRight(1))
        assertEquals(false, bs.getRight(2))

        bs.setRight(1)
        assertEquals("0x02", bs.toString())
        assertEquals("00000010", bs.toBinaryString())
        assertEquals(true, bs.getRight(1))
        assertEquals(false, bs.getRight(2))

        bs.setRight(2)
        assertEquals("0x06", bs.toString())
        assertEquals("00000110", bs.toBinaryString())
        assertEquals(true, bs.getRight(1))
        assertEquals(true, bs.getRight(2))

        bs.clearRight(1)
        assertEquals("0x04", bs.toString())
        assertEquals("00000100", bs.toBinaryString())
        assertEquals(false, bs.getRight(1))
        assertEquals(true, bs.getRight(2))
    }

    @Test
    fun `Reversion of a bitField`() {
        val bs = BitField.from(byteArrayOf(0x60, 0x01))
        val rbs = bs.reversed()

        assertEquals("0x6001", bs.toString())
        assertEquals("0x8006", rbs.toString())
    }

    @Test
    fun `Creation from binary String`() {
        assertEquals(BitField.from(byteArrayOf(0xaa.toByte())), BitField.fromBin("10101010"), BitField.fromBin("10101010").toBinaryString())
        assertEquals(BitField.from(byteArrayOf(0x02)), BitField.fromBin("10"), BitField.fromBin("10").toBinaryString())
    }
}
