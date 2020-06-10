package utils

import fr.acinq.eklair.utils.BitSet
import kotlin.test.Test
import kotlin.test.assertEquals

class BitSetsCommon {

    @Test fun `Creation and simple get and set`() {
        val bs = BitSet(1)

        assertEquals("0x00", bs.toString())
        assertEquals("00000000", bs.toBinaryString())
        assertEquals(false, bs[1])
        assertEquals(false, bs[2])

        bs.set(1)
        assertEquals("0x40", bs.toString())
        assertEquals("01000000", bs.toBinaryString())
        assertEquals(true, bs[1])
        assertEquals(false, bs[2])

        bs.set(2)
        assertEquals("0x60", bs.toString())
        assertEquals("01100000", bs.toBinaryString())
        assertEquals(true, bs[1])
        assertEquals(true, bs[2])

        bs.clear(1)
        assertEquals("0x20", bs.toString())
        assertEquals("00100000", bs.toBinaryString())
        assertEquals(false, bs[1])
        assertEquals(true, bs[2])
    }

    @Test fun `Reversion of a bitField`() {
        val bs = BitSet.from(byteArrayOf(0x60, 0x01))
        val rbs = bs.reversed()

        assertEquals("0x6001", bs.toString())
        assertEquals("0x8006", rbs.toString())
    }

    @Test fun `Creation from binary String`() {
        assertEquals(BitSet.from(byteArrayOf(0x02)), BitSet.fromBin("10"), BitSet.fromBin("10").toBinaryString())
    }
}
