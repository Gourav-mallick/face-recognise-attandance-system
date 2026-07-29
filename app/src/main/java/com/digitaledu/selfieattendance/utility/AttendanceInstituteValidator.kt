package com.digitaledu.selfieattendance.utility

/**
 * Prevents attendance captured for one institute from being submitted under a
 * different session institute.
 */
object AttendanceInstituteValidator {
    fun validate(
        sessionInstituteId: String?,
        attendanceInstituteIds: Iterable<String>
    ): String? {
        val sessionId = sessionInstituteId?.trim().orEmpty()
        if (sessionId.isEmpty()) {
            return "Session institute is missing"
        }

        val mismatchedId = attendanceInstituteIds
            .map { it.trim() }
            .firstOrNull { it.isEmpty() || it != sessionId }

        return if (mismatchedId != null) {
            "Attendance institute does not match session institute"
        } else {
            null
        }
    }
}
