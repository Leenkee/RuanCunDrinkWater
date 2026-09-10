package com.young.waterremote.updates

import java.time.ZonedDateTime

internal object UpdateSchedule {
    fun nextNoon(now: ZonedDateTime): ZonedDateTime {
        val noon = now.toLocalDate().atTime(12, 0).atZone(now.zone)
        return if (noon.isAfter(now)) noon else noon.plusDays(1)
    }

    fun isDue(now: ZonedDateTime, lastAttemptDate: String?): Boolean =
        now.hour >= 12 && lastAttemptDate != now.toLocalDate().toString()
}
