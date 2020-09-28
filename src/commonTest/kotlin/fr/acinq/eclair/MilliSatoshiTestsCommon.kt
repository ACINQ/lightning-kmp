package fr.acinq.eclair

import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.tests.utils.EclairTestSuite
import fr.acinq.eclair.utils.msat
import fr.acinq.eclair.utils.sat
import fr.acinq.eclair.utils.toMilliSatoshi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MilliSatoshiTestsCommon : EclairTestSuite() {

    @Test fun `millisatoshi numeric operations`() {
        // add
        assertEquals(MilliSatoshi(561), MilliSatoshi(561) + 0.msat)
        assertEquals(MilliSatoshi(561), MilliSatoshi(561) + 0.sat.toMilliSatoshi())
        assertEquals(MilliSatoshi(1666), MilliSatoshi(561) + 1105.msat)
        assertEquals(MilliSatoshi(5000), MilliSatoshi(2000) + 3.sat.toMilliSatoshi())

        // subtract
        assertEquals(MilliSatoshi(561), MilliSatoshi(561) - 0.msat)
        assertEquals(MilliSatoshi(544), MilliSatoshi(1105) - 561.msat)
        assertEquals(-MilliSatoshi(544), 561.msat - 1105.msat)
        assertEquals(-MilliSatoshi(544), MilliSatoshi(561) - 1105.msat)
        assertEquals(MilliSatoshi(105), MilliSatoshi(1105) - 1.sat.toMilliSatoshi())

        // multiply
        assertEquals(561.msat, MilliSatoshi(561) * 1)
        assertEquals(1122.msat, MilliSatoshi(561) * 2)
        assertEquals(1402.msat, MilliSatoshi(561) * 2.5)

        // divide
        assertEquals(MilliSatoshi(561), MilliSatoshi(561) / 1)
        assertEquals(MilliSatoshi(280), MilliSatoshi(561) / 2)

        // compare
        assertTrue(MilliSatoshi(561) <= MilliSatoshi(561))
        assertTrue(MilliSatoshi(561) <= 1105.msat)
        assertTrue(MilliSatoshi(561) < MilliSatoshi(1105))
        assertTrue(MilliSatoshi(561) >= MilliSatoshi(561))
        assertTrue(MilliSatoshi(1105) >= MilliSatoshi(561))
        assertTrue(MilliSatoshi(1105) > MilliSatoshi(561))
        assertTrue(MilliSatoshi(1000) <= Satoshi(1).toMilliSatoshi())
        assertTrue(MilliSatoshi(1000) <= 2.sat.toMilliSatoshi())
        assertTrue(MilliSatoshi(1000) < Satoshi(2).toMilliSatoshi())
        assertTrue(MilliSatoshi(1000) >= Satoshi(1).toMilliSatoshi())
        assertTrue(MilliSatoshi(2000) >= Satoshi(1).toMilliSatoshi())
        assertTrue(MilliSatoshi(2000) > Satoshi(1).toMilliSatoshi())

        // maxOf
        assertEquals(MilliSatoshi(1105), (561.msat).coerceAtLeast(1105.msat))
        assertEquals(MilliSatoshi(1105), (1105.msat).coerceAtLeast(1.sat.toMilliSatoshi()))
        assertEquals(MilliSatoshi(2000), (1105.msat).coerceAtLeast(2.sat.toMilliSatoshi()))
        assertEquals(Satoshi(2), (1.sat).max(2.sat))

        // minOf
        assertEquals(MilliSatoshi(561), (561.msat).coerceAtMost(1105.msat))
        assertEquals(MilliSatoshi(1000), (1105.msat).coerceAtMost(1.sat.toMilliSatoshi()))
        assertEquals(MilliSatoshi(1105), (1105.msat).coerceAtMost(2.sat.toMilliSatoshi()))
        assertEquals(Satoshi(1), (1.sat).min(2.sat))
    }

}
