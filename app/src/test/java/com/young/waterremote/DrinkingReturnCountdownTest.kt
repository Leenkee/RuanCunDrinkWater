package com.young.waterremote

import org.junit.Assert.assertEquals
import org.junit.Test

class DrinkingReturnCountdownTest {
    @Test
    fun countsDownToTheOriginalDeadline() {
        val countdown = DrinkingReturnCountdown(10_000L)
        assertEquals(3, countdown.secondsRemaining(10_000L))
        assertEquals(3, countdown.secondsRemaining(10_999L))
        assertEquals(2, countdown.secondsRemaining(11_000L))
        assertEquals(1, countdown.secondsRemaining(12_999L))
        assertEquals(0, countdown.secondsRemaining(13_000L))
    }

    @Test
    fun backgroundOrSleepGapDoesNotRestartCountdown() {
        val countdown = DrinkingReturnCountdown(10_000L)
        assertEquals(3, countdown.secondsRemaining(10_500L))
        assertEquals(1, countdown.secondsRemaining(12_500L))
        assertEquals(0, countdown.secondsRemaining(70_000L))
        assertEquals(0, countdown.secondsRemaining(90_000L))
    }
}
