package com.digitaledu.selfieattendance.utility

import com.digitaledu.selfieattendance.db.entity.SchoolPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SchoolPeriodTimeResolverTest {

    private val period1 = SchoolPeriod(
        spId = "P1",
        spTitle = "Period 1 (9-10 AM)",
        spStartTime = "09:00 AM",
        spEndTime = "10:00 AM",
        spIstTime = "09:00",
        instId = "1"
    )

    private val period2 = SchoolPeriod(
        spId = "P2",
        spTitle = "Period 2 (11-12 PM)",
        spStartTime = "11:00 AM",
        spEndTime = "12:00 PM",
        spIstTime = "11:00",
        instId = "1"
    )

    private val testPeriods = listOf(period1, period2)

    @Test
    fun testExactMatchWithinPeriodRange() {
        val result1 = SchoolPeriodTimeResolver.resolveAutoPeriod(testPeriods, "09:30")
        assertNotNull(result1)
        assertEquals("P1", result1?.spId)

        val result2 = SchoolPeriodTimeResolver.resolveAutoPeriod(testPeriods, "11:15")
        assertNotNull(result2)
        assertEquals("P2", result2?.spId)
    }

    @Test
    fun testCase1BeforeFirstSchoolPeriodReturnsPeriod1() {
        val resultEarly = SchoolPeriodTimeResolver.resolveAutoPeriod(testPeriods, "08:30")
        assertNotNull(resultEarly)
        assertEquals("P1", resultEarly?.spId)

        val resultMidnight = SchoolPeriodTimeResolver.resolveAutoPeriod(testPeriods, "06:00")
        assertNotNull(resultMidnight)
        assertEquals("P1", resultMidnight?.spId)
    }

    @Test
    fun testCase2AfterLastSchoolPeriodReturnsLastPeriod() {
        val resultLate = SchoolPeriodTimeResolver.resolveAutoPeriod(testPeriods, "12:30")
        assertNotNull(resultLate)
        assertEquals("P2", resultLate?.spId)

        val resultEvening = SchoolPeriodTimeResolver.resolveAutoPeriod(testPeriods, "17:00")
        assertNotNull(resultEvening)
        assertEquals("P2", resultEvening?.spId)
    }

    @Test
    fun testCase3BreakGapBetweenPeriodsReturnsPastPeriod() {
        // Gap is between 10:00 AM and 11:00 AM
        val resultGap = SchoolPeriodTimeResolver.resolveAutoPeriod(testPeriods, "10:30")
        assertNotNull(resultGap)
        assertEquals("P1", resultGap?.spId) // Should return Period 1 (past period before gap)

        val resultBoundary = SchoolPeriodTimeResolver.resolveAutoPeriod(testPeriods, "10:00")
        assertNotNull(resultBoundary)
        assertEquals("P1", resultBoundary?.spId)
    }

    @Test
    fun testEmptyPeriodListReturnsNull() {
        val result = SchoolPeriodTimeResolver.resolveAutoPeriod(emptyList(), "09:30")
        assertNull(result)
    }
}
