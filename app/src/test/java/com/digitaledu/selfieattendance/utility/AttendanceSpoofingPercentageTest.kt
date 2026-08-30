package com.digitaledu.selfieattendance.utility

import com.digitaledu.selfieattendance.db.entity.Attendance
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttendanceSpoofingPercentageTest {

    private fun createDummyAttendance(spoofingPercentage: String? = null): Attendance {
        return Attendance(
            atteId = "ATT_001",
            instId = "INST_100",
            classId = "CLASS_01",
            markedAt = "2026-08-30T10:00:00",
            sessionId = "SESS_99",
            status = "P",
            studentId = "STU_123",
            studentName = "John Doe",
            syncStatus = "pending",
            teacherId = "TCH_01",
            teacherName = "Prof. Smith",
            date = "2026-08-30",
            startTime = "10:00",
            endTime = "11:00",
            period = "1",
            attSchoolPeriodId = "PER_1",
            spoofingPercentage = spoofingPercentage
        )
    }

    @Test
    fun `Attendance entity defaults spoofingPercentage to null`() {
        val att = createDummyAttendance()
        assertNull(att.spoofingPercentage)
    }

    @Test
    fun `Attendance entity retains spoofingPercentage when provided`() {
        val att = createDummyAttendance(spoofingPercentage = "98.50")
        assertEquals("98.50", att.spoofingPercentage)
    }

    @Test
    fun `JSON mapping includes spoofing_percentage key with value`() {
        val att = createDummyAttendance(spoofingPercentage = "99.12")
        val json = JSONObject().apply {
            put("studentId", att.studentId)
            put("spoofing_percentage", att.spoofingPercentage ?: "")
        }
        assertEquals("99.12", json.getString("spoofing_percentage"))
    }

    @Test
    fun `JSON mapping includes empty string when spoofing_percentage is null`() {
        val att = createDummyAttendance(spoofingPercentage = null)
        val json = JSONObject().apply {
            put("studentId", att.studentId)
            put("spoofing_percentage", att.spoofingPercentage ?: "")
        }
        assertEquals("", json.getString("spoofing_percentage"))
    }
}
