package com.digitaledu.selfieattendance.utility

import com.digitaledu.selfieattendance.db.entity.Attendance
import org.junit.Assert.assertEquals
import org.junit.Test

class AttendanceSyncMergerTest {

    @Test
    fun `period setup long titles map to attendance report session titles`() {
        assertEquals(
            "Morning Session",
            AttendanceSyncMerger.canonicalReportPeriodTitle(
                "Morning Session - MMS Part Time - 2026 [7:00 AM to 11:59 AM]"
            )
        )
        assertEquals(
            "Afternoon Session",
            AttendanceSyncMerger.canonicalReportPeriodTitle(
                "Afternoon Session - MMS Part Time - 2026 [12:00 PM to 4:59 PM]"
            )
        )
        assertEquals(
            "Evening Session",
            AttendanceSyncMerger.canonicalReportPeriodTitle(
                "Evening Session - MMS Part Time - 2026 [5:00 PM to 9:00 PM]"
            )
        )
    }

    @Test
    fun `face captures update server baseline without erasing untouched statuses`() {
        val server = mapOf(
            "1" to "P", "2" to "P", "3" to "P",
            "4" to "L", "5" to "L", "6" to "E",
            "7" to "A", "8" to "A", "9" to "A", "10" to "A"
        )
        val capturedIds = setOf("1", "6", "7", "8")
        val local = (1..10).map { id ->
            attendance(id.toString(), if (id.toString() in capturedIds) "P" else "A")
                .copy(isFaceCaptured = id.toString() in capturedIds)
        }

        val merged = AttendanceSyncMerger.mergeAttendance(local, server)

        assertEquals(6, merged.count { it.status == "P" })
        assertEquals(2, merged.count { it.status == "L" })
        assertEquals(0, merged.count { it.status == "E" })
        assertEquals(2, merged.count { it.status == "A" })
    }

    @Test
    fun `explicit edit overrides face capture and server status`() {
        val local = listOf(
            attendance("1", "E").copy(isFaceCaptured = true, isExplicitEdit = true),
            attendance("2", "A").copy(isExplicitEdit = true)
        )

        val merged = AttendanceSyncMerger.mergeAttendance(local, mapOf("1" to "P", "2" to "L"))

        assertEquals(listOf("E", "A"), merged.map { it.status })
    }

    @Test
    fun `automatic absent preserves server status`() {
        val local = listOf(attendance("1", "A"), attendance("2", "A"), attendance("3", "A"))

        val merged = AttendanceSyncMerger.mergeAttendance(
            local,
            mapOf("1" to "P", "2" to "L", "3" to "E")
        )

        assertEquals(listOf("P", "L", "E"), merged.map { it.status })
    }

    private fun attendance(studentId: String, status: String) = Attendance(
        atteId = studentId,
        instId = "1",
        academicYear = "2026",
        classId = "10A",
        markedAt = "2026-08-03 10:00:00",
        sessionId = "session",
        status = status,
        studentId = studentId,
        studentName = "Student $studentId",
        syncStatus = "pending",
        teacherId = "teacher",
        date = "2026-08-03",
        startTime = "10:00",
        endTime = "11:00",
        period = "Afternoon Session",
        cpId = "23",
        mpId = "5",
        attSchoolPeriodId = "2"
    )
}
