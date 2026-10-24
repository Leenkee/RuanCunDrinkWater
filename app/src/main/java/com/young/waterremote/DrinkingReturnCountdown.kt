package com.young.waterremote

/** Uses elapsed real time so suspended callbacks never extend the return deadline. */
data class DrinkingReturnCountdown(val startedAtMillis: Long) {
    fun secondsRemaining(nowMillis: Long): Int {
        val remaining = (3_000L - (nowMillis - startedAtMillis)).coerceIn(0L, 3_000L)
        return ((remaining + 999L) / 1_000L).toInt()
    }
}
