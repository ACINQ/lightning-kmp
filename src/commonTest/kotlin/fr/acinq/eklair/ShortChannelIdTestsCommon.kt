package fr.acinq.eklair

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ShortChannelIdTestsCommon {
    @ExperimentalUnsignedTypes
    @Test fun `handle values from 0 to 0xffffffffffff`() {

        val expected = mapOf(
            TxCoordinates(0, 0, 0) to ShortChannelId(0),
        TxCoordinates(42000, 27, 3) to ShortChannelId(0x0000a41000001b0003L),
        TxCoordinates(1258612, 63, 0) to ShortChannelId(0x13347400003f0000L),
        TxCoordinates(0xffffff, 0x000000, 0xffff) to ShortChannelId(0xffffff000000ffffuL.toLong()),
        TxCoordinates(0x000000, 0xffffff, 0xffff) to ShortChannelId(0x000000ffffffffffL),
        TxCoordinates(0xffffff, 0xffffff, 0x0000) to ShortChannelId(0xffffffffffff0000uL.toLong()),
        TxCoordinates(0xffffff, 0xffffff, 0xffff) to ShortChannelId(0xffffffffffffffffuL.toLong())
        )
        for ((coord, shortChannelId) in expected) {
            assertEquals(ShortChannelId(coord.blockHeight, coord.txIndex, coord.outputIndex), shortChannelId)
            assertEquals(shortChannelId.coordinates(), coord)
        }
    }

    @Test fun `human readable format as per spec`() {
        assertEquals("42000x27x3", ShortChannelId(0x0000a41000001b0003L).toString())
    }

    @Test fun `parse a short channel it`() {
        assertEquals(0x0000a41000001b0003L, ShortChannelId("42000x27x3").toLong())
    }

    @Test fun `fail parsing a short channel id if not in the required form`() {
        assertFails { ShortChannelId("42000x27x3.1") }
        assertFails { ShortChannelId("4200aa0x27x3") }
        assertFails { ShortChannelId("4200027x3") }
        assertFails { ShortChannelId("42000x27ax3") }
        assertFails { ShortChannelId("42000x27x") }
        assertFails { ShortChannelId("42000x27") }
        assertFails { ShortChannelId("42000x") }
    }

}
