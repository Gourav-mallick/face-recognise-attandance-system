package com.example.login.utility

import android.content.Context
import android.util.Log
import com.example.login.db.dao.AppDatabase
import com.example.login.db.entity.SchoolPeriod
import java.text.SimpleDateFormat
import java.util.*

/**
 * Utility to auto-detect the current school period based on timestamp.
 * Used when a teacher saves an incomplete session without manually selecting a period.
 */
object SchoolPeriodHelper {

    private const val TAG = "SchoolPeriodHelper"
    private const val GRACE_MINUTES = 10

    /**
     * Resolves the matching SchoolPeriod for a given timestamp and institute.
     * Checks if the current time falls within any defined period's start-end range (with grace).
     *
     * @param db The AppDatabase instance
     * @param instId The institute ID to filter periods
     * @param timestamp The timestamp to match against period ranges (defaults to now)
     * @return The matching SchoolPeriod or null if no match found
     */
    suspend fun resolvePeriodForTimestamp(
        db: AppDatabase,
        instId: String,
        timestamp: Long = System.currentTimeMillis()
    ): SchoolPeriod? {
        val periods = db.schoolPeriodDao().getAll().filter { it.instId == instId }

        if (periods.isEmpty()) {
            Log.w(TAG, "No school periods found for instId=$instId")
            return null
        }

        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val currentTimeStr = sdf.format(Date(timestamp))
        val now = sdf.parse(currentTimeStr) ?: return null

        for (p in periods) {
            val start = sdf.parse(p.spIstTime) ?: continue
            val end = sdf.parse(p.spEndTime) ?: continue
            val graceStart = Date(start.time - GRACE_MINUTES * 60 * 1000)

            // Check if current time is within the period range
            if (now.after(start) && now.before(end)) {
                Log.d(TAG, "Matched period within range: ${p.spTitle} (${p.spIstTime}-${p.spEndTime})")
                return p
            }

            // Check grace window before the period starts
            if (now.after(graceStart) && now.before(start)) {
                Log.d(TAG, "Matched period within grace: ${p.spTitle} (grace from ${sdf.format(graceStart)})")
                return p
            }
        }

        Log.w(TAG, "No matching period for time $currentTimeStr, returning first available")
        return periods.firstOrNull()
    }

    /**
     * Returns the spId for the matching period, or "999" as fallback.
     */
    suspend fun resolveSpIdForTimestamp(
        db: AppDatabase,
        instId: String,
        timestamp: Long = System.currentTimeMillis()
    ): String {
        return resolvePeriodForTimestamp(db, instId, timestamp)?.spId ?: "999"
    }
}
