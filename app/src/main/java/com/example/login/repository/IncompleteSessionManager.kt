package com.example.login.repository

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.example.login.db.dao.AppDatabase
import com.example.login.db.entity.Attendance
import com.example.login.db.entity.IncompleteSession
import com.example.login.utility.SchoolPeriodHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages saving ongoing attendance sessions as incomplete and navigating the user
 * back to the Home Screen. Supports offline-first architecture with later server sync.
 */
object IncompleteSessionManager {

    private const val TAG = "IncompleteSessionMgr"

    /**
     * Saves the current attendance session as an incomplete session in the local Room DB.
     * Auto-detects the school period if not already selected.
     *
     * @param context The activity context
     * @param sessionId The current session ID
     * @param currentStage The lifecycle stage where the session is being saved
     * @param onComplete Callback after save completes (navigates to home)
     */
    suspend fun saveAsIncompleteSession(
        context: Context,
        sessionId: String,
        currentStage: String,
        onComplete: (() -> Unit)? = null
    ) {
        val db = AppDatabase.getDatabase(context)
        val session = db.sessionDao().getSessionById(sessionId)

        if (session == null) {
            Log.e(TAG, "Session $sessionId not found in DB, cannot save as incomplete")
            return
        }

        val teacherId = session.teacherId
        val instId = session.instId
        val teacherName = db.teachersDao().getTeacherNameById(teacherId) ?: ""

        // Fetch attendance records for this session
        val attendances = db.attendanceDao().getAttendanceBySessionId(sessionId)
        val markedStudentCount = attendances.size

        // Build attendances JSON array
        val attendancesJson = buildAttendancesJson(attendances)

        // Auto-detect school period if not set
        var spId = session.attSchoolPeriodId
        var autoAssigned = 0
        if (spId.isNullOrEmpty() || spId == "999") {
            val resolvedPeriod = SchoolPeriodHelper.resolvePeriodForTimestamp(db, instId)
            if (resolvedPeriod != null) {
                spId = resolvedPeriod.spId
                autoAssigned = 1
                Log.d(TAG, "Auto-assigned period: ${resolvedPeriod.spTitle} (spId=$spId)")
            }
        }

        // Get class info (support multi-class comma list)
        val classId = session.classId
        val classShortName = if (!classId.isNullOrEmpty()) {
            val ids = classId.split(",")
            ids.mapNotNull { id ->
                db.classDao().getClassById(id.trim())?.classShortName ?: id.trim().ifEmpty { null }
            }.joinToString(", ")
        } else null

        // Get subject title from attendance rows
        val subjectTitle = attendances.firstOrNull { !it.subjectTitle.isNullOrEmpty() }?.subjectTitle

        // Get device GUID
        val deviceGuid = getDeviceGuid(context)

        // Build session object JSON for full resumption context
        val sessionObjectJson = buildSessionObjectJson(session, teacherName, classShortName, subjectTitle)

        // Create the IncompleteSession entity
        val incompleteSession = IncompleteSession(
            sessionId = sessionId,
            instId = instId,
            classId = classId,
            classShortName = classShortName,
            teacherId = teacherId,
            teacherName = teacherName,
            attSchoolPeriodId = spId,
            autoAssignedPeriod = autoAssigned,
            deviceGuid = deviceGuid,
            sessionDate = session.date,
            startTime = session.startTime,
            currentStage = currentStage,
            markedStudentCount = markedStudentCount,
            attendancesJson = attendancesJson,
            sessionObjectJson = sessionObjectJson,
            syncStatus = "LOCAL"
        )

        // Save to Room DB
        db.incompleteSessionDao().insertOrUpdate(incompleteSession)
        Log.d(TAG, "Saved incomplete session: id=$sessionId, stage=$currentStage, students=$markedStudentCount, subject=$subjectTitle")

        // Clear app state prefs
        clearAppStatePrefs(context)

        onComplete?.invoke()
    }

    /**
     * Navigates the user back to the Home Screen (AttendanceActivity),
     * clearing the activity back stack.
     */
    fun navigateToHome(activity: Activity) {
        clearAppStatePrefs(activity)

        if (activity is com.example.login.view.AttendanceActivity) {
            activity.resetToClassroomScanFragment()
        } else {
            val homeClass = try {
                Class.forName("com.example.login.view.AttendanceActivity")
            } catch (e: ClassNotFoundException) {
                Log.e(TAG, "AttendanceActivity class not found", e)
                return
            }

            val intent = Intent(activity, homeClass).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("RESET_TO_HOME", true)
            }
            activity.startActivity(intent)
            activity.finish()
        }
    }

    /**
     * Fetches incomplete sessions for a teacher from local DB.
     */
    suspend fun getIncompleteSessionsForTeacher(
        context: Context,
        teacherId: String
    ): List<IncompleteSession> {
        val db = AppDatabase.getDatabase(context)
        return db.incompleteSessionDao().getIncompleteSessionsByTeacher(teacherId)
    }

    /**
     * Counts incomplete sessions for a teacher.
     */
    suspend fun countIncompleteSessionsForTeacher(
        context: Context,
        teacherId: String
    ): Int {
        val db = AppDatabase.getDatabase(context)
        return db.incompleteSessionDao().countIncompleteSessionsForTeacher(teacherId)
    }

    /**
     * Removes an incomplete session after it has been completed/submitted.
     */
    suspend fun markSessionComplete(context: Context, sessionId: String) {
        val db = AppDatabase.getDatabase(context)
        db.incompleteSessionDao().deleteIncompleteSession(sessionId)
        Log.d(TAG, "Removed incomplete session: $sessionId (marked complete)")
    }

    // ---- Private helpers ----

    private fun buildAttendancesJson(attendances: List<Attendance>): String {
        val jsonArray = JSONArray()
        for (att in attendances) {
            val obj = JSONObject().apply {
                put("studentId", att.studentId)
                put("studentName", att.studentName ?: "")
                put("classId", att.classId)
                put("status", att.status)
                put("markedAt", att.markedAt)
                put("subjectTitle", att.subjectTitle ?: "")
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    private fun buildSessionObjectJson(
        session: com.example.login.db.entity.Session,
        teacherName: String?,
        classShortName: String?,
        subjectTitle: String? = null
    ): String {
        return JSONObject().apply {
            put("sessionId", session.sessionId)
            put("classId", session.classId)
            put("classShortName", classShortName ?: "")
            put("teacherId", session.teacherId)
            put("teacherName", teacherName ?: "")
            put("subjectId", session.subjectId)
            put("subjectTitle", subjectTitle ?: "")
            put("date", session.date)
            put("startTime", session.startTime)
            put("periodId", session.periodId)
            put("attSchoolPeriodId", session.attSchoolPeriodId)
            put("instId", session.instId)
        }.toString()
    }

    private fun clearAppStatePrefs(context: Context) {
        context.getSharedPreferences("APP_STATE", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("AttendancePrefs", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Suppress("HardwareIds")
    private fun getDeviceGuid(context: Context): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }
}
