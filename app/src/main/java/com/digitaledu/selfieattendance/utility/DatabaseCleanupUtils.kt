package com.digitaledu.selfieattendance.utility

import android.content.Context
import android.util.Log
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DatabaseCleanupUtils {

    private fun getTodayDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    /**
     * Delete synced attendance records older than today
     */
    suspend fun deleteSyncedAttendances(context: Context): Int {
        return withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(context)
                val todayDate = getTodayDate()
                val deletedCount = db.attendanceDao().deleteSyncedAttendancesOlderThan(todayDate)
                Log.d("DB_CLEANUP", "Deleted $deletedCount synced attendance records older than $todayDate")
                deletedCount
            } catch (e: Exception) {
                Log.e("DB_CLEANUP", "Error deleting attendance: ${e.message}", e)
                0
            }
        }
    }

    /**
     * Delete synced session records older than today
     */
    suspend fun deleteSyncedSessions(context: Context): Int {
        return withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(context)
                val todayDate = getTodayDate()
                val deletedCount = db.sessionDao().deleteSyncedSessionsOlderThan(todayDate)
                Log.d("DB_CLEANUP", "Deleted $deletedCount synced session records older than $todayDate")
                deletedCount
            } catch (e: Exception) {
                Log.e("DB_CLEANUP", "Error deleting sessions: ${e.message}", e)
                0
            }
        }
    }

    /**
     * ✅ Combined cleanup (calls both above)
     */
    suspend fun deleteAllSyncedData(context: Context) {
        withContext(Dispatchers.IO) {
            val att = deleteSyncedAttendances(context)
            val ses = deleteSyncedSessions(context)
            Log.d("DB_CLEANUP", "Total deleted: Attendance=$att, Session=$ses")
        }
    }
}
