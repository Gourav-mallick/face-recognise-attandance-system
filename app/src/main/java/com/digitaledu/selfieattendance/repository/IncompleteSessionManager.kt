package com.digitaledu.selfieattendance.repository

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.room.withTransaction
import com.digitaledu.selfieattendance.api.ApiClient
import com.digitaledu.selfieattendance.api.ApiService
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.Attendance
import com.digitaledu.selfieattendance.db.entity.IncompleteSession
import com.digitaledu.selfieattendance.db.entity.Session
import com.digitaledu.selfieattendance.view.AttendanceActivity
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject

object IncompleteSessionManager {
    const val STAGE_STUDENT_SCAN = "STAGE_STUDENT_SCAN"
    const val STAGE_PERIOD_SELECT = "STAGE_PERIOD_SELECT"
    const val STAGE_CLASS_SELECT = "STAGE_CLASS_SELECT"
    const val STAGE_SUBJECT_SELECT = "STAGE_SUBJECT_SELECT"
    const val STAGE_OVERVIEW = "STAGE_OVERVIEW"

    private const val TAG = "IncompleteSessionMgr"
    private const val LOCAL = "LOCAL"
    private const val SYNCED = "SYNCED"
    private const val PENDING = "PENDING"
    private const val COMPLETED = "COMPLETED"

    /**
     * Creates one atomic checkpoint and removes it from the normal "active cycle"
     * restoration path. Existing session/attendance rows remain in Room.
     */
    suspend fun save(
        context: Context,
        sessionId: String,
        stage: String
    ): IncompleteSession {
        val db = AppDatabase.getDatabase(context)
        val session = db.sessionDao().getSessionById(sessionId)
            ?: error("Session $sessionId does not exist")
        val attendances = db.attendanceDao().getAttendanceBySessionId(sessionId)
        val teacherName = db.teachersDao().getTeacherNameById(session.teacherId)
        val classIds = session.classId.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val classNames = classIds.map { id ->
            db.classDao().getClassById(id)?.classShortName ?: id
        }
        val now = System.currentTimeMillis()
        val existing = db.incompleteSessionDao().getBySessionId(sessionId)
        val checkpoint = IncompleteSession(
            sessionId = sessionId,
            instId = session.instId,
            teacherId = session.teacherId,
            teacherName = teacherName,
            classIds = classIds.joinToString(","),
            classNames = classNames.joinToString(", "),
            schoolPeriodId = session.attSchoolPeriodId,
            currentStage = stage,
            sessionDate = session.date,
            startTime = session.startTime,
            markedStudentCount = attendances.size,
            sourceDeviceGuid = deviceGuid(context),
            sessionJson = sessionToJson(session).toString(),
            attendancesJson = attendancesToJson(attendances).toString(),
            syncStatus = LOCAL,
            recordStatus = PENDING,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )

        db.withTransaction {
            db.incompleteSessionDao().insertOrUpdate(checkpoint)
            db.activeClassCycleDao().getAll()
                .firstOrNull { it.sessionId == sessionId }
                ?.let { db.activeClassCycleDao().delete(it) }
        }
        clearActivePreferences(context)
        return checkpoint
    }

    /**
     * Recreates normal Room rows when the checkpoint came from another device.
     * Existing local rows win, so resuming never destroys newer local edits.
     */
    suspend fun restoreNormalRows(context: Context, checkpoint: IncompleteSession) {
        val db = AppDatabase.getDatabase(context)
        db.withTransaction {
            if (db.sessionDao().getSessionById(checkpoint.sessionId) == null) {
                db.sessionDao().insertSession(sessionFromJson(checkpoint.sessionJson, checkpoint))
            }
            val array = JSONArray(checkpoint.attendancesJson)
            for (index in 0 until array.length()) {
                val attendance = attendanceFromJson(array.getJSONObject(index), checkpoint)
                if (db.attendanceDao().getAttendanceById(attendance.atteId) == null) {
                    db.attendanceDao().insertAttendance(attendance)
                }
            }
        }
    }

    suspend fun isIncomplete(context: Context, sessionId: String): Boolean =
        AppDatabase.getDatabase(context).incompleteSessionDao()
            .getBySessionId(sessionId)?.recordStatus == PENDING

    suspend fun getPending(
        context: Context,
        teacherId: String? = null
    ): List<IncompleteSession> {
        val dao = AppDatabase.getDatabase(context).incompleteSessionDao()
        return if (teacherId.isNullOrBlank()) dao.getAllPending()
        else dao.getPendingForTeacher(teacherId)
    }

    /**
     * A successfully submitted attendance leaves a completion tombstone until the
     * backend acknowledges it. This prevents another device from resurrecting it.
     */
    suspend fun markAttendanceSubmitted(context: Context, sessionId: String) {
        val db = AppDatabase.getDatabase(context)
        val checkpoint = db.incompleteSessionDao().getBySessionId(sessionId) ?: return
        db.incompleteSessionDao().markCompleted(sessionId, LOCAL)
        val synced = uploadOne(context, checkpoint.copy(
            recordStatus = COMPLETED,
            syncStatus = LOCAL,
            updatedAt = System.currentTimeMillis()
        ))
        if (synced) db.incompleteSessionDao().deleteBySessionId(sessionId)
    }

    /**
     * Upload local checkpoints/tombstones first, then merge the server list.
     * Network failure is intentionally non-fatal: local pending work remains visible.
     */
    suspend fun sync(context: Context): Boolean {
        val db = AppDatabase.getDatabase(context)
        val prefs = context.getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("baseUrl", "").orEmpty()
        val hash = prefs.getString("hash", "").orEmpty()
        val instituteIds = prefs.getString("selectedInstituteIds", "").orEmpty()
        if (baseUrl.isBlank() || instituteIds.isBlank()) return false

        var allUploadsSucceeded = true
        db.incompleteSessionDao().getLocalForUpload().forEach { local ->
            if (uploadOne(context, local)) {
                if (local.recordStatus == COMPLETED) {
                    db.incompleteSessionDao().deleteBySessionId(local.sessionId)
                } else {
                    db.incompleteSessionDao().updateSyncStatus(local.sessionId, SYNCED)
                }
            } else {
                allUploadsSucceeded = false
            }
        }

        return try {
            val api = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)
            val query = JSONObject().apply {
                put("instIds", JSONArray(instituteIds.split(',').map { it.trim() }))
            }
            val response = api.getIncompleteSessions(data = query.toString())
            if (!response.isSuccessful || response.body() == null) return false
            val records = extractRecordArray(response.body()!!.string())
            for (index in 0 until records.length()) {
                val remote = incompleteFromServerJson(records.getJSONObject(index))
                val local = db.incompleteSessionDao().getBySessionId(remote.sessionId)
                if (remote.recordStatus == COMPLETED) {
                    if (local?.syncStatus != LOCAL) {
                        db.incompleteSessionDao().deleteBySessionId(remote.sessionId)
                    }
                } else if (local == null || local.syncStatus != LOCAL ||
                    remote.updatedAt > local.updatedAt
                ) {
                    db.incompleteSessionDao().insertOrUpdate(remote.copy(syncStatus = SYNCED))
                }
            }
            allUploadsSucceeded
        } catch (error: Exception) {
            Log.w(TAG, "Incomplete-session sync unavailable", error)
            false
        }
    }

    fun navigateHome(activity: Activity) {
        clearActivePreferences(activity)
        val intent = Intent(activity, AttendanceActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("RESET_TO_HOME", true)
        }
        activity.startActivity(intent)
        activity.finish()
    }

    private suspend fun uploadOne(context: Context, value: IncompleteSession): Boolean {
        val prefs = context.getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("baseUrl", "").orEmpty()
        val hash = prefs.getString("hash", "").orEmpty()
        if (baseUrl.isBlank()) return false
        return try {
            val api = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)
            val body = RequestBody.create(
                MediaType.parse("application/json; charset=utf-8"),
                incompleteToServerJson(value).toString()
            )
            val response = api.postIncompleteSession(body = body)
            if (!response.isSuccessful || response.body() == null) false
            else responseIndicatesSuccess(response.body()!!.string())
        } catch (error: Exception) {
            Log.w(TAG, "Could not upload checkpoint ${value.sessionId}", error)
            false
        }
    }

    private fun responseIndicatesSuccess(raw: String): Boolean {
        if (raw.isBlank()) return true
        return try {
            val root = JSONObject(raw)
            val status = root.optString("status").ifBlank {
                root.optJSONObject("collection")
                    ?.optJSONObject("response")
                    ?.optString("status")
                    .orEmpty()
            }
            status.isBlank() || status.equals("SUCCESS", true) || status.equals("OK", true)
        } catch (_: Exception) {
            true
        }
    }

    private fun extractRecordArray(raw: String): JSONArray {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) return JSONArray(trimmed)
        val root = JSONObject(trimmed)
        val candidates = listOf(
            root.optJSONArray("incompleteSessions"),
            root.optJSONArray("data"),
            root.optJSONObject("collection")?.optJSONArray("incompleteSessions"),
            root.optJSONObject("collection")?.optJSONObject("response")?.optJSONArray("dataArr"),
            root.optJSONObject("collection")?.optJSONObject("response")
                ?.optJSONArray("incompleteSessions")
        )
        return candidates.firstOrNull { it != null } ?: JSONArray()
    }

    private fun incompleteToServerJson(value: IncompleteSession) = JSONObject().apply {
        put("sessionId", value.sessionId)
        put("instId", value.instId)
        put("teacherId", value.teacherId)
        put("teacherName", value.teacherName.orEmpty())
        put("classIds", value.classIds)
        put("classNames", value.classNames)
        put("schoolPeriodId", value.schoolPeriodId)
        put("currentStage", value.currentStage)
        put("sessionDate", value.sessionDate)
        put("startTime", value.startTime)
        put("markedStudentCount", value.markedStudentCount)
        put("sourceDeviceGuid", value.sourceDeviceGuid)
        put("sessionJson", value.sessionJson)
        put("attendancesJson", value.attendancesJson)
        put("recordStatus", value.recordStatus)
        put("createdAt", value.createdAt)
        put("updatedAt", value.updatedAt)
    }

    private fun incompleteFromServerJson(obj: JSONObject) = IncompleteSession(
        sessionId = obj.getString("sessionId"),
        instId = obj.optString("instId"),
        teacherId = obj.optString("teacherId"),
        teacherName = obj.optString("teacherName").ifBlank { null },
        classIds = obj.optString("classIds"),
        classNames = obj.optString("classNames"),
        schoolPeriodId = obj.optString("schoolPeriodId", "999"),
        currentStage = obj.optString("currentStage", STAGE_STUDENT_SCAN),
        sessionDate = obj.optString("sessionDate"),
        startTime = obj.optString("startTime"),
        markedStudentCount = obj.optInt("markedStudentCount"),
        sourceDeviceGuid = obj.optString("sourceDeviceGuid", "SERVER"),
        sessionJson = obj.optString("sessionJson", "{}"),
        attendancesJson = obj.optString("attendancesJson", "[]"),
        syncStatus = SYNCED,
        recordStatus = obj.optString("recordStatus", PENDING),
        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun sessionToJson(value: Session) = JSONObject().apply {
        put("sessionId", value.sessionId)
        put("classId", value.classId)
        put("teacherId", value.teacherId)
        put("subjectId", value.subjectId)
        put("date", value.date)
        put("startTime", value.startTime)
        put("endTime", value.endTime)
        put("instId", value.instId)
        put("isMerged", value.isMerged)
        put("periodId", value.periodId)
        put("syncStatus", value.syncStatus)
        put("isSubmitted", value.isSubmitted)
        put("attSchoolPeriodId", value.attSchoolPeriodId)
    }

    private fun sessionFromJson(raw: String, fallback: IncompleteSession): Session {
        val obj = JSONObject(raw)
        return Session(
            sessionId = obj.optString("sessionId", fallback.sessionId),
            classId = obj.optString("classId", fallback.classIds),
            teacherId = obj.optString("teacherId", fallback.teacherId),
            subjectId = obj.optString("subjectId"),
            date = obj.optString("date", fallback.sessionDate),
            startTime = obj.optString("startTime", fallback.startTime),
            endTime = obj.optString("endTime"),
            instId = obj.optString("instId", fallback.instId),
            isMerged = obj.optInt("isMerged"),
            periodId = obj.optString("periodId"),
            syncStatus = "pending",
            isSubmitted = obj.optInt("isSubmitted"),
            attSchoolPeriodId = obj.optString("attSchoolPeriodId", fallback.schoolPeriodId)
        )
    }

    private fun attendancesToJson(values: List<Attendance>) = JSONArray().apply {
        values.forEach { value ->
            put(JSONObject().apply {
                put("atteId", value.atteId)
                put("instId", value.instId)
                putNullable("instShortName", value.instShortName)
                putNullable("academicYear", value.academicYear)
                put("classId", value.classId)
                put("markedAt", value.markedAt)
                put("sessionId", value.sessionId)
                put("status", value.status)
                put("studentId", value.studentId)
                putNullable("studentName", value.studentName)
                put("teacherId", value.teacherId)
                putNullable("teacherName", value.teacherName)
                put("date", value.date)
                put("startTime", value.startTime)
                put("endTime", value.endTime)
                put("period", value.period)
                putNullable("cpId", value.cpId)
                putNullable("courseId", value.courseId)
                putNullable("courseTitle", value.courseTitle)
                putNullable("courseShortName", value.courseShortName)
                putNullable("subjectId", value.subjectId)
                putNullable("subjectTitle", value.subjectTitle)
                putNullable("classShortName", value.classShortName)
                putNullable("mpId", value.mpId)
                putNullable("mpLongTitle", value.mpLongTitle)
                put("attSchoolPeriodId", value.attSchoolPeriodId)
            })
        }
    }

    private fun attendanceFromJson(obj: JSONObject, fallback: IncompleteSession) = Attendance(
        atteId = obj.optString("atteId", "${fallback.sessionId}_${obj.optString("studentId")}"),
        instId = obj.optString("instId", fallback.instId),
        instShortName = obj.optNullableString("instShortName"),
        academicYear = obj.optNullableString("academicYear"),
        classId = obj.optString("classId"),
        markedAt = obj.optString("markedAt"),
        sessionId = obj.optString("sessionId", fallback.sessionId),
        status = obj.optString("status", "P"),
        studentId = obj.optString("studentId"),
        studentName = obj.optNullableString("studentName"),
        syncStatus = "pending",
        teacherId = obj.optString("teacherId", fallback.teacherId),
        teacherName = obj.optNullableString("teacherName"),
        date = obj.optString("date", fallback.sessionDate),
        startTime = obj.optString("startTime", fallback.startTime),
        endTime = obj.optString("endTime"),
        period = obj.optString("period"),
        cpId = obj.optNullableString("cpId"),
        courseId = obj.optNullableString("courseId"),
        courseTitle = obj.optNullableString("courseTitle"),
        courseShortName = obj.optNullableString("courseShortName"),
        subjectId = obj.optNullableString("subjectId"),
        subjectTitle = obj.optNullableString("subjectTitle"),
        classShortName = obj.optNullableString("classShortName"),
        mpId = obj.optNullableString("mpId"),
        mpLongTitle = obj.optNullableString("mpLongTitle"),
        attSchoolPeriodId = obj.optString("attSchoolPeriodId", fallback.schoolPeriodId)
    )

    private fun JSONObject.putNullable(name: String, value: String?) {
        put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).ifBlank { null }

    private fun clearActivePreferences(context: Context) {
        context.getSharedPreferences("APP_STATE", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("AttendancePrefs", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Suppress("HardwareIds")
    private fun deviceGuid(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "UNKNOWN"
}
