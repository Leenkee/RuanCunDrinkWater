package com.young.waterremote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConsumptionAmountTest {
    @Test
    fun calculatesConsumptionFromBalanceDecrease() {
        assertEquals("0.33", calculateConsumptionAmount("12.50", "12.17"))
    }

    @Test
    fun decimalCalculationDoesNotLosePrecision() {
        assertEquals("0.20", calculateConsumptionAmount("0.30", "0.10"))
        assertEquals("0.13", calculateConsumptionAmount("12.5", "12.37"))
        assertEquals("0.01", calculateConsumptionAmount("10.00", "9.99"))
    }

    @Test
    fun unchangedBalanceReportsZeroConsumption() {
        assertEquals("0.00", calculateConsumptionAmount("10.00", "10"))
    }

    @Test
    fun increasedBalanceDoesNotDisplayNegativeConsumption() {
        assertNull(calculateConsumptionAmount("10.00", "11.00"))
    }

    @Test
    fun missingOrInvalidBalanceDoesNotDisplayZeroConsumption() {
        assertNull(calculateConsumptionAmount(null, "10.00"))
        assertNull(calculateConsumptionAmount("10.00", null))
        assertNull(calculateConsumptionAmount("unknown", "10.00"))
        assertNull(calculateConsumptionAmount("10.00", ""))
    }
}
