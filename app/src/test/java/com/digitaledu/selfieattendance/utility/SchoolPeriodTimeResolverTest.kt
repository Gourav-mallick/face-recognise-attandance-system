package com.digitaledu.selfieattendance.utility

import com.digitaledu.selfieattendance.db.entity.SchoolPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SchoolPeriodTimeResolverTest {

    private val periods = listOf(
        period("1", "Period 1", "10:00", "11:00"),
        period("2", "Period 2", "11:00", "12:00")
    )

    @Test
    fun `10 59 selects period 1`() {
        assertEquals("1", SchoolPeriodTimeResolver.findStrictPeriod(periods, "10:59")?.spId)
    }

    @Test
    fun `11 00 selects period 2 without grace`() {
        assertEquals("2", SchoolPeriodTimeResolver.findStrictPeriod(periods, "11:00")?.spId)
    }

    @Test
    fun `11 01 selects period 2`() {
        assertEquals("2", SchoolPeriodTimeResolver.findStrictPeriod(periods, "11:01")?.spId)
    }

    @Test
    fun `outside configured periods has no fallback`() {
        assertNull(SchoolPeriodTimeResolver.findStrictPeriod(periods, "12:01"))
    }

    @Test
    fun `supports twelve hour API times`() {
        val twelveHourPeriods = listOf(period("1", "Period 1", "10:00 AM", "11:00 AM"))
        assertEquals("1", SchoolPeriodTimeResolver.findStrictPeriod(twelveHourPeriods, "10:30")?.spId)
    }

    private fun period(id: String, title: String, start: String, end: String) = SchoolPeriod(
        spId = id,
        spTitle = title,
        spStartTime = start,
        spEndTime = end,
        spIstTime = start,
        instId = "school"
    )
}
