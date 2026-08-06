package com.digitaledu.selfieattendance.utility

/**
 * Prevents attendance captured for one institute from being submitted under an
 * invalid/unrecognized institute. Supports single-institute and multi-institute sessions.
 */
object AttendanceInstituteValidator {
    fun validate(
        sessionInstituteId: String?,
        attendanceInstituteIds: Iterable<String>,
        allowedInstituteIds: Set<String>? = null
    ): String? {
        val sessionId = sessionInstituteId?.trim().orEmpty()
        if (sessionId.isEmpty() && allowedInstituteIds.isNullOrEmpty()) {
            return "Session institute is missing"
        }

        val allowedSet = allowedInstituteIds?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: setOf(sessionId)

        val mismatchedId = attendanceInstituteIds
            .map { it.trim() }
            .firstOrNull { it.isEmpty() || !allowedSet.contains(it) }

        return if (mismatchedId != null) {
            "Attendance institute does not match session institute"
        } else {
            null
        }
    }
}

