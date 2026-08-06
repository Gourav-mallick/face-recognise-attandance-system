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

    /**
     * Auto Period Resolution when enforcedManualPeriodSelection = "Y":
     * 1) Exact Match: If attendanceStartTime is inside [start, end], return that period.
     * 2) Case 1 (Before First Period): If time is before 1st period start, assign 1st period.
     * 3) Case 2 (After Last Period): If time is after last period end, assign last period.
     * 4) Case 3 (Break Gap): If time is in a break between periods, assign past period (the period that just ended).
     */
    fun resolveAutoPeriod(
        periods: List<SchoolPeriod>,
        attendanceStartTime: String
    ): SchoolPeriod? {
        val attendanceMinute = parseMinuteOfDay(attendanceStartTime) ?: return null
        if (periods.isEmpty()) return null

        val parsedPeriods = periods
            .mapNotNull { period ->
                val start = parseMinuteOfDay(period.spIstTime) ?: return@mapNotNull null
                val end = parseMinuteOfDay(period.spEndTime) ?: return@mapNotNull null
                Triple(period, start, end)
            }
            .sortedBy { (_, start, _) -> start }

        if (parsedPeriods.isEmpty()) return null

        // 1) Exact Match
        val exactMatch = parsedPeriods.firstOrNull { (_, start, end) ->
            when {
                start < end -> attendanceMinute >= start && attendanceMinute < end
                start > end -> attendanceMinute >= start || attendanceMinute < end
                else -> false
            }
        }
        if (exactMatch != null) return exactMatch.first

        // 2) Case 1: Before first school period -> assign first period
        val firstPeriod = parsedPeriods.first()
        if (attendanceMinute < firstPeriod.second) {
            return firstPeriod.first
        }

        // 3) Case 2: After last school period -> assign last period
        val lastPeriod = parsedPeriods.last()
        if (attendanceMinute >= lastPeriod.third) {
            return lastPeriod.first
        }

        // 4) Case 3: In a break gap between periods -> assign past period (period before the gap)
        for (i in 0 until parsedPeriods.size - 1) {
            val currentEnd = parsedPeriods[i].third
            val nextStart = parsedPeriods[i + 1].second
            if (attendanceMinute >= currentEnd && attendanceMinute < nextStart) {
                return parsedPeriods[i].first
            }
        }

        return lastPeriod.first
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
