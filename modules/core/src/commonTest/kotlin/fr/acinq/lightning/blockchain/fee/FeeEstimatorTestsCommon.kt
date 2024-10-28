package fr.acinq.lightning.blockchain.fee

import fr.acinq.lightning.blockchain.fee.FeeratePerKw.Companion.MinimumFeeratePerKw
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.sat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeeEstimatorTestsCommon : LightningTestSuite() {

    @Test
    fun `convert fee rates`() {
        assertEquals(FeeratePerByte(FeeratePerKw(2000.sat)), FeeratePerByte(8.sat))
        assertEquals(FeeratePerKB(FeeratePerByte(10.sat)), FeeratePerKB(10000.sat))
        assertEquals(FeeratePerKB(FeeratePerKw(25.sat)), FeeratePerKB(100.sat))
        assertEquals(FeeratePerKw(FeeratePerKB(10000.sat)), FeeratePerKw(2500.sat))
        assertEquals(FeeratePerKw(FeeratePerByte(10.sat)), FeeratePerKw(2500.sat))
    }

    @Test
    fun `enforce a minimum feerate-per-kw`() {
        assertEquals(FeeratePerKw(FeeratePerKB(1000.sat)), MinimumFeeratePerKw)
        assertEquals(FeeratePerKw(FeeratePerKB(500.sat)), MinimumFeeratePerKw)
        assertEquals(FeeratePerKw(FeeratePerByte(1.sat)), MinimumFeeratePerKw)
    }

    @Test
    fun `compare feerates`() {
        assertTrue(FeeratePerKw(500.sat) > FeeratePerKw(499.sat))
        assertTrue(FeeratePerKw(500.sat) >= FeeratePerKw(500.sat))
        assertTrue(FeeratePerKw(499.sat) < FeeratePerKw(500.sat))
        assertTrue(FeeratePerKw(500.sat) <= FeeratePerKw(500.sat))
        assertEquals(FeeratePerKw(500.sat).max(FeeratePerKw(499.sat)), FeeratePerKw(500.sat))
        assertEquals(FeeratePerKw(499.sat).max(FeeratePerKw(500.sat)), FeeratePerKw(500.sat))
        assertEquals(FeeratePerKw(500.sat).min(FeeratePerKw(499.sat)), FeeratePerKw(499.sat))
        assertEquals(FeeratePerKw(499.sat).min(FeeratePerKw(500.sat)), FeeratePerKw(499.sat))
    }

    @Test
    fun `numeric operations`() {
        assertEquals(FeeratePerKw(100.sat) * 0.4, FeeratePerKw(40.sat))
        assertEquals(FeeratePerKw(501.sat) * 0.5, FeeratePerKw(250.sat))
        assertEquals(FeeratePerKw(5.sat) * 5, FeeratePerKw(25.sat))
        assertEquals(FeeratePerKw(100.sat) / 10, FeeratePerKw(10.sat))
        assertEquals(FeeratePerKw(101.sat) / 10, FeeratePerKw(10.sat))
        assertEquals(FeeratePerKw(100.sat) + FeeratePerKw(50.sat), FeeratePerKw(150.sat))
    }

}