package com.digitaledu.selfieattendance.utility

import com.digitaledu.selfieattendance.db.entity.SchoolPeriod
import java.util.Locale

/** Strictly matches an attendance-cycle time to a configured school period. */
object SchoolPeriodTimeResolver {

    fun findStrictPeriod(
        periods: List<SchoolPeriod>,
        attendanceStartTime: String
    ): SchoolPeriod? {
        val attendanceMinute = parseMinuteOfDay(attendanceStartTime) ?: return null

        return periods
            .mapNotNull { period ->
                val start = parseMinuteOfDay(period.spIstTime) ?: return@mapNotNull null
                val end = parseMinuteOfDay(period.spEndTime) ?: return@mapNotNull null
                Triple(period, start, end)
            }
            .sortedBy { (_, start, _) -> start }
            .firstOrNull { (_, start, end) ->
                when {
                    start < end -> attendanceMinute >= start && attendanceMinute < end
                    start > end -> attendanceMinute >= start || attendanceMinute < end
                    else -> false
                }
            }
            ?.first
    }

    internal fun parseMinuteOfDay(value: String): Int? {
        val match = Regex(
            pattern = """^\s*(\d{1,2}):(\d{2})(?::\d{2})?\s*([AaPp][Mm])?\s*$"""
        ).matchEntire(value) ?: return null

        var hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        val meridiem = match.groupValues[3].uppercase(Locale.US)
        if (minute !in 0..59) return null

        if (meridiem.isNotEmpty()) {
            if (hour !in 1..12) return null
            hour = when {
                meridiem == "AM" && hour == 12 -> 0
                meridiem == "PM" && hour != 12 -> hour + 12
                else -> hour
            }
        } else if (hour !in 0..23) {
            return null
        }

        return hour * 60 + minute
    }
}
