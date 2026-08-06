package com.digitaledu.selfieattendance.utility

import org.json.JSONObject

object GlobalAttendanceConfigParser {
    const val DEFAULT_MANUAL_PERIOD_SELECTION = "N"

    fun enforcedManualPeriodSelection(otherDetails: String?): String {
        if (otherDetails.isNullOrBlank()) return DEFAULT_MANUAL_PERIOD_SELECTION
        return try {
            var valStr = try {
                JSONObject(otherDetails).optString("enforcedManualPeriodSelection", "")
            } catch (_: Throwable) {
                ""
            }

            if (valStr.isBlank()) {
                val match = Regex(""""enforcedManualPeriodSelection"\s*:\s*"([^"]+)"""").find(otherDetails)
                valStr = match?.groupValues?.get(1) ?: DEFAULT_MANUAL_PERIOD_SELECTION
            }

            when (valStr.trim().uppercase()) {
                "Y" -> "Y"
                else -> DEFAULT_MANUAL_PERIOD_SELECTION
            }
        } catch (_: Exception) {
            DEFAULT_MANUAL_PERIOD_SELECTION
        }
    }
}
