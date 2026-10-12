package com.young.waterremote.data

import java.math.RoundingMode

internal fun calculateConsumptionAmount(balanceBefore: String?, balanceAfter: String?): String? {
    val before = balanceBefore?.trim()?.toBigDecimalOrNull() ?: return null
    val after = balanceAfter?.trim()?.toBigDecimalOrNull() ?: return null
    val consumption = before.subtract(after)
    if (consumption.signum() < 0) return null
    return consumption.setScale(2, RoundingMode.HALF_UP).toPlainString()
}
