package com.digitaledu.selfieattendance.utility

import org.json.JSONObject

object GlobalAttendanceConfigParser {
    const val DEFAULT_MANUAL_PERIOD_SELECTION = "N"

    fun enforcedManualPeriodSelection(otherDetails: String?): String {
        if (otherDetails.isNullOrBlank()) return DEFAULT_MANUAL_PERIOD_SELECTION
        return try {
            when (
                JSONObject(otherDetails)
                    .optString("enforcedManualPeriodSelection", DEFAULT_MANUAL_PERIOD_SELECTION)
                    .trim()
                    .uppercase()
            ) {
                "Y" -> "Y"
                else -> DEFAULT_MANUAL_PERIOD_SELECTION
            }
        } catch (_: Exception) {
            DEFAULT_MANUAL_PERIOD_SELECTION
        }
    }
}
