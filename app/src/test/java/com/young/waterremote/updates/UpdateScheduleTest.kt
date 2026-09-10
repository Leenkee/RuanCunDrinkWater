package com.young.waterremote.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZonedDateTime

class UpdateScheduleTest {
    @Test
    fun beforeNoonSchedulesTodayAndDoesNotCheckEarly() {
        val now = ZonedDateTime.parse("2026-09-10T11:59:59+08:00[Asia/Shanghai]")
        assertEquals("2026-09-10T12:00+08:00[Asia/Shanghai]", UpdateSchedule.nextNoon(now).toString())
        assertFalse(UpdateSchedule.isDue(now, null))
    }

    @Test
    fun atNoonIsDueOnceAndSchedulesTomorrow() {
        val now = ZonedDateTime.parse("2026-09-10T12:00:00+08:00[Asia/Shanghai]")
        assertTrue(UpdateSchedule.isDue(now, "2026-09-09"))
        assertFalse(UpdateSchedule.isDue(now, "2026-09-10"))
        assertEquals("2026-09-11T12:00+08:00[Asia/Shanghai]", UpdateSchedule.nextNoon(now).toString())
    }

    @Test
    fun delayedCheckRunsOnceAndResetsAtNextLocalNoon() {
        val now = ZonedDateTime.parse("2026-09-10T22:30:00+08:00[Asia/Shanghai]")
        assertTrue(UpdateSchedule.isDue(now, null))
        assertFalse(UpdateSchedule.isDue(now, "2026-09-10"))
        val tomorrow = UpdateSchedule.nextNoon(now)
        assertTrue(UpdateSchedule.isDue(tomorrow, "2026-09-10"))
    }

    @Test
    fun keepsLocalNoonAcrossDaylightSavingChange() {
        val now = ZonedDateTime.parse("2026-03-07T12:00:00-05:00[America/New_York]")
        val next = UpdateSchedule.nextNoon(now)
        assertEquals(12, next.hour)
        assertEquals(23, Duration.between(now, next).toHours())
    }
}
