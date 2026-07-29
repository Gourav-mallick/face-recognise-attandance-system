package com.digitaledu.selfieattendance.utility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttendanceInstituteValidatorTest {
    @Test
    fun acceptsAttendanceFromTheSessionInstitute() {
        assertNull(
            AttendanceInstituteValidator.validate(
                sessionInstituteId = "I1",
                attendanceInstituteIds = listOf("I1", " I1 ")
            )
        )
    }

    @Test
    fun rejectsMissingSessionInstitute() {
        assertEquals(
            "Session institute is missing",
            AttendanceInstituteValidator.validate(
                sessionInstituteId = "",
                attendanceInstituteIds = listOf("I1")
            )
        )
    }

    @Test
    fun rejectsCrossInstituteAttendance() {
        assertEquals(
            "Attendance institute does not match session institute",
            AttendanceInstituteValidator.validate(
                sessionInstituteId = "I1",
                attendanceInstituteIds = listOf("I1", "I2")
            )
        )
    }
}
