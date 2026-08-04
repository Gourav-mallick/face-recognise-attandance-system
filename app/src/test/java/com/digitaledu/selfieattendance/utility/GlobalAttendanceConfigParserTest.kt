package com.digitaledu.selfieattendance.utility

import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalAttendanceConfigParserTest {

    @Test
    fun `reads Y from other details`() {
        assertEquals(
            "Y",
            GlobalAttendanceConfigParser.enforcedManualPeriodSelection(
                """{"enforcedManualPeriodSelection":"Y"}"""
            )
        )
    }

    @Test
    fun `reads N from other details`() {
        assertEquals(
            "N",
            GlobalAttendanceConfigParser.enforcedManualPeriodSelection(
                """{"enforcedManualPeriodSelection":"N"}"""
            )
        )
    }

    @Test
    fun `missing key defaults to N`() {
        assertEquals(
            "N",
            GlobalAttendanceConfigParser.enforcedManualPeriodSelection("{}")
        )
    }

    @Test
    fun `invalid JSON defaults to N`() {
        assertEquals(
            "N",
            GlobalAttendanceConfigParser.enforcedManualPeriodSelection("not-json")
        )
    }
}
