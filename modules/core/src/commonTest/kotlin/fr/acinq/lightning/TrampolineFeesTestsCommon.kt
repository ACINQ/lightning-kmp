package fr.acinq.lightning

import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import kotlin.test.Test
import kotlin.test.assertEquals

class TrampolineFeesTestsCommon {

    @Test
    fun `calculate fees`() {
        val trampolineFees = TrampolineFees(
            feeBase = 4.sat,
            feeProportional = 4_000,
            cltvExpiryDelta = CltvExpiryDelta(576)
        )

        assertEquals(4_000.msat, trampolineFees.calculateFees(0.msat))
        assertEquals(4_000.msat, trampolineFees.calculateFees(1.msat))
        assertEquals(4_004.msat, trampolineFees.calculateFees(1_001.msat))
        assertEquals(8_000.msat, trampolineFees.calculateFees(1_000_000.msat))
        assertEquals(8_003.msat, trampolineFees.calculateFees(1_000_789.msat))
        assertEquals(497_827.msat, trampolineFees.calculateFees(123_456_789.msat))
    }

    @Test
    fun `calculate reverse amount`() {
        val trampolineFees = TrampolineFees(
            feeBase = 4.sat,
            feeProportional = 4_000,
            cltvExpiryDelta = CltvExpiryDelta(576)
        )

        // amount available in the wallet -> amount that should be provided in the trampoline payload
        val testCases = listOf(
            0.msat to null,
            1.msat to null,
            1_000.msat to null,
            4_000.msat to 0.msat,
            4_001.msat to 1.msat,
            4_004.msat to 4.msat,
            8_000.msat to 3_985.msat,
            8_016.msat to 4_000.msat,
            1_000_000.msat to 992_032.msat,
            100_000_000.msat to 99_597_610.msat,
            123_456_789.msat to 122_960_946.msat
        )

        testCases.forEach { (availableAmount, expectedTrampolineAmount) ->
            val trampolineAmount = trampolineFees.calculateReverseAmount(availableAmount)
            println("$availableAmount: expected=$expectedTrampolineAmount actual=$trampolineAmount")
            assertEquals(expectedTrampolineAmount, trampolineAmount)
            if (trampolineAmount != null) {
                assertEquals(availableAmount, trampolineFees.calculateFees(trampolineAmount) + trampolineAmount)
            }
        }
    }
}