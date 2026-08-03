package com.digitaledu.selfieattendance.utility

import android.content.Context
import android.util.Log
import com.digitaledu.selfieattendance.api.ApiClient
import com.digitaledu.selfieattendance.api.ApiService
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.Attendance
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Rebases local attendance intent onto the server attendance for one exact selection.
 * Precedence: explicit edit > face capture > server baseline > automatic local default.
 */
object AttendanceSyncMerger {

    private const val TAG = "ATT_SYNC_MERGER"
    const val FLOW_TAG = "ATT_REPORT_FLOW"

    data class ServerStudentStatus(
        val studentId: String,
        val studentName: String,
        val altId: String,
        val rollNo: String,
        val status: String
    )

    sealed class ServerFetchResult {
        data class Existing(val students: Map<String, ServerStudentStatus>) : ServerFetchResult()
        object NotFound : ServerFetchResult()
        data class Unavailable(val reason: String) : ServerFetchResult()
    }

    data class MergeOutcome(
        val attendance: List<Attendance>,
        val hasUnavailableSelection: Boolean,
        val hadExistingServerAttendance: Boolean
    )

    private data class SelectionKey(
        val schoolId: String,
        val academicYear: String,
        val mpId: String,
        val classId: String,
        val cpId: String,
        val attendanceDate: String,
        val schoolPeriodId: String,
        val periodTitle: String
    )

    suspend fun fetchServerAttendanceReport(
        context: Context,
        schoolIds: String,
        syear: String,
        mpId: String,
        classIds: String,
        cpIds: String,
        attendanceDate: String,
        periodTitle: String
    ): ServerFetchResult {
        return try {
            val reportPeriodTitle = canonicalReportPeriodTitle(periodTitle)
            Log.i(
                FLOW_TAG,
                "API_CHECK_START schoolId=$schoolIds syear=$syear mpId=$mpId " +
                    "classId=$classIds cpId=$cpIds selectedTitle='$periodTitle' " +
                    "reportTitle='$reportPeriodTitle' attendanceDate=$attendanceDate"
            )
            if (schoolIds.isBlank() || syear.isBlank() || mpId.isBlank() ||
                classIds.isBlank() || cpIds.isBlank() || attendanceDate.isBlank() ||
                periodTitle.isBlank()
            ) {
                Log.e(FLOW_TAG, "API_CHECK_SKIPPED incomplete attendance selection")
                return ServerFetchResult.Unavailable("Incomplete attendance selection")
            }

            val loginPrefs = context.getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
            val attendancePrefs = context.getSharedPreferences("AttendancePrefs", Context.MODE_PRIVATE)
            val rawBaseUrl = loginPrefs.getString("baseUrl", null)
                ?: attendancePrefs.getString("baseUrl", null)
                ?: attendancePrefs.getString("BASE_URL", null)
                ?: ""
            val hash = loginPrefs.getString("hash", null)
                ?: attendancePrefs.getString("hash", null)
                ?: attendancePrefs.getString("HASH", null)

            if (rawBaseUrl.isBlank()) {
                Log.e(FLOW_TAG, "API_CHECK_SKIPPED base URL is missing")
                return ServerFetchResult.Unavailable("Base URL is missing")
            }

            val baseUrl = when {
                rawBaseUrl.endsWith("///") -> rawBaseUrl
                rawBaseUrl.endsWith("/") -> rawBaseUrl.removeSuffix("/") + "///"
                else -> "$rawBaseUrl///"
            }
            val apiService = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)
            val dataParam = JSONObject().apply {
                put("attParamDataObj", JSONObject().apply {
                    put("attReportType", "landscapeMusterAttSubjectPeriodWise")
                    put("attReportTypeParamDataObj", JSONObject().apply {
                        put("schoolIds", schoolIds)
                        put("syears", syear)
                        put("mpIds", mpId)
                        put("classIds", classIds)
                        put("cpIds", cpIds)
                        put("frmDate", attendanceDate)
                        put("toDate", attendanceDate)
                    })
                })
            }.toString()

            Log.i(
                FLOW_TAG,
                "API_REQUEST r=api/v1/Att/AttReport attendanceDate=$attendanceDate " +
                    "periodTitle='$reportPeriodTitle' data=$dataParam"
            )
            val response = apiService.getAttendanceReport(data = dataParam)
            Log.i(
                FLOW_TAG,
                "API_HTTP_RESPONSE code=${response.code()} successful=${response.isSuccessful}"
            )
            if (!response.isSuccessful || response.body() == null) {
                val errorBody = response.errorBody()?.string().orEmpty()
                Log.e(FLOW_TAG, "API_HTTP_ERROR code=${response.code()} body=$errorBody")
                return ServerFetchResult.Unavailable("Attendance report HTTP ${response.code()}")
            }

            val responseBody = response.body()!!.string()
            Log.i(FLOW_TAG, "API_RESPONSE_BODY $responseBody")
            val parsed = parseServerResponse(responseBody, reportPeriodTitle, attendanceDate)
            Log.i(FLOW_TAG, "API_CHECK_RESULT ${describe(parsed)}")
            parsed
        } catch (e: Exception) {
            Log.e(TAG, "Attendance report check failed", e)
            Log.e(FLOW_TAG, "API_CHECK_EXCEPTION ${e.message}", e)
            ServerFetchResult.Unavailable(e.message ?: "Attendance report check failed")
        }
    }

    private fun parseServerResponse(
        jsonString: String,
        periodTitle: String,
        today: String
    ): ServerFetchResult {
        return try {
            val response = JSONObject(jsonString)
                .optJSONObject("collection")
                ?.optJSONObject("response")
                ?: return ServerFetchResult.Unavailable("Invalid attendance report response")
            val status = response.optString("status")
            val message = response.optString("msg")
            Log.i(FLOW_TAG, "API_RESPONSE_STATUS status=$status message='$message'")

            if (status.equals("FAILED", true) &&
                response.opt("data") == false &&
                message.contains("not found", true)
            ) {
                return ServerFetchResult.NotFound
            }
            if (!status.equals("SUCCESS", true)) {
                return ServerFetchResult.Unavailable(message.ifBlank { "Attendance report failed" })
            }

            val data = response.optJSONArray("data")
                ?: return ServerFetchResult.NotFound
            if (data.length() == 0) return ServerFetchResult.NotFound

            val expectedKey = "$today(${periodTitle.trim()})"
            Log.i(FLOW_TAG, "PERIOD_COLUMN_EXPECTED '$expectedKey'")
            val students = linkedMapOf<String, ServerStudentStatus>()
            var matchedPeriodColumn = false
            var selectedServerKey: String? = null

            for (index in 0 until data.length()) {
                val student = data.optJSONObject(index) ?: continue
                val matchingKey = selectedServerKey
                    ?: findSelectedPeriodKey(student, today, periodTitle)?.also {
                        selectedServerKey = it
                        Log.i(
                            FLOW_TAG,
                            "PERIOD_COLUMN_MATCHED selectedTitle='$periodTitle' serverKey='$it'"
                        )
                    }
                    ?: continue
                matchedPeriodColumn = true

                val studentId = student.optString("Student Id").trim()
                if (studentId.isBlank()) continue
                students[studentId] = ServerStudentStatus(
                    studentId = studentId,
                    studentName = student.optString("Student Name"),
                    altId = student.optString("Alt Id"),
                    rollNo = student.optString("Roll No"),
                    status = extractStatusFromHtml(student.optString(matchingKey))
                )
            }

            if (!matchedPeriodColumn) {
                Log.d(TAG, "No exact report column '$expectedKey'; treating selection as fresh")
                Log.w(FLOW_TAG, "PERIOD_COLUMN_NOT_FOUND '$expectedKey' -> FRESH_ATTENDANCE")
                ServerFetchResult.NotFound
            } else {
                Log.i(
                    FLOW_TAG,
                    "PERIOD_COLUMN_PARSED serverKey='$selectedServerKey' students=${students.size}"
                )
                ServerFetchResult.Existing(students)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot parse attendance report", e)
            ServerFetchResult.Unavailable(e.message ?: "Cannot parse attendance report")
        }
    }

    /**
     * Report columns can use session names (Morning/Afternoon) while local setup uses
     * Period 1/Period 2. Match a real title/alias first, then deliberately map a
     * numbered local period to the same numbered dated report column.
     */
    private fun findSelectedPeriodKey(
        student: JSONObject,
        today: String,
        selectedTitle: String
    ): String? {
        val datePrefix = "$today("
        val datedKeys = student.keys().asSequence()
            .filter { it.startsWith(datePrefix, ignoreCase = true) && it.endsWith(")") }
            .toList()
        Log.i(FLOW_TAG, "PERIOD_COLUMNS_AVAILABLE $datedKeys")
        if (datedKeys.isEmpty()) return null

        val canonicalSelected = canonicalReportPeriodTitle(selectedTitle)
        val normalizedSelected = normalizePeriodTitle(canonicalSelected)
        datedKeys.firstOrNull { key ->
            normalizePeriodTitle(key.substringAfter("(").substringBeforeLast(")")) == normalizedSelected
        }?.let { return it }

        // Safe alias matching, now that an empty selected title is rejected earlier.
        datedKeys.firstOrNull { key ->
            val serverTitle = normalizePeriodTitle(key.substringAfter("(").substringBeforeLast(")"))
            serverTitle.contains(normalizedSelected) || normalizedSelected.contains(serverTitle)
        }?.let { return it }

        val selectedNumber = Regex("""(?:period|session)\s*[-_]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(selectedTitle)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (selectedNumber != null && selectedNumber in 1..datedKeys.size) {
            val mapped = datedKeys[selectedNumber - 1]
            Log.i(
                FLOW_TAG,
                "PERIOD_COLUMN_NUMBER_MAPPING '$selectedTitle' -> '$mapped' index=$selectedNumber"
            )
            return mapped
        }

        return null
    }

    private fun normalizePeriodTitle(value: String): String = value
        .lowercase(Locale.getDefault())
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    /**
     * Period-details API titles contain institute/year/time suffixes, while AttReport
     * uses the session prefix only.
     *
     * Example: "Morning Session - MMS Part Time - 2026 [7:00 AM to 11:59 AM]"
     * becomes "Morning Session".
     */
    internal fun canonicalReportPeriodTitle(periodSetupTitle: String): String {
        val sessionMatch = Regex(
            """\b(morning|afternoon|evening)\s+session\b""",
            RegexOption.IGNORE_CASE
        ).find(periodSetupTitle)
        if (sessionMatch != null) {
            val sessionName = sessionMatch.groupValues[1]
                .lowercase(Locale.getDefault())
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
            return "$sessionName Session"
        }
        return periodSetupTitle.trim()
    }

    private fun extractStatusFromHtml(value: String): String {
        val text = value.replace(Regex("<[^>]*>"), "").trim().lowercase()
        return when {
            text.contains("present") -> "P"
            text.contains("late") -> "L"
            text.contains("exempt") -> "E"
            text.contains("absent") || text.isBlank() -> "A"
            else -> "A"
        }
    }

    private fun describe(result: ServerFetchResult): String = when (result) {
        is ServerFetchResult.Existing -> {
            val statuses = result.students.values.groupingBy { it.status }.eachCount()
            "EXISTING students=${result.students.size} P=${statuses["P"] ?: 0} " +
                "L=${statuses["L"] ?: 0} E=${statuses["E"] ?: 0} A=${statuses["A"] ?: 0}"
        }
        ServerFetchResult.NotFound -> "NOT_FOUND_FRESH"
        is ServerFetchResult.Unavailable -> "UNAVAILABLE reason='${result.reason}'"
    }

    private fun mergeSelection(
        local: List<Attendance>,
        server: Map<String, ServerStudentStatus>
    ): List<Attendance> {
        val merged = mergeAttendance(
            local = local,
            serverStatuses = server.mapValues { it.value.status }
        ).toMutableList()

        // Normally every server student is already in the locally persisted class roster.
        // Preserve an unexpected server row as well so an update never drops attendance.
        val localIds = local.mapTo(hashSetOf()) { it.studentId }
        val template = local.firstOrNull()
        if (template != null) {
            server.values.filter { it.studentId !in localIds }.forEach { serverStudent ->
                merged += template.copy(
                    atteId = UUID.randomUUID().toString(),
                    studentId = serverStudent.studentId,
                    studentName = serverStudent.studentName,
                    status = serverStudent.status,
                    isFaceCaptured = false,
                    isExplicitEdit = false,
                    syncStatus = "pending"
                )
            }
        }
        return merged
    }

    /** Pure merge rule, kept visible to unit tests. */
    internal fun mergeAttendance(
        local: List<Attendance>,
        serverStatuses: Map<String, String>
    ): List<Attendance> {
        return local.map { attendance ->
            val serverStatus = serverStatuses[attendance.studentId]
            when {
                attendance.isExplicitEdit -> attendance
                attendance.isFaceCaptured -> attendance.copy(status = "P")
                serverStatus != null -> attendance.copy(status = serverStatus)
                else -> attendance
            }
        }
    }

    /** Fetches and merges each exact class/course selection independently. */
    suspend fun fetchAndMerge(
        context: Context,
        localAttendanceList: List<Attendance>
    ): MergeOutcome {
        if (localAttendanceList.isEmpty()) {
            return MergeOutcome(emptyList(), hasUnavailableSelection = false, hadExistingServerAttendance = false)
        }

        val groups = localAttendanceList.groupBy {
            SelectionKey(
                schoolId = it.instId,
                academicYear = it.academicYear.orEmpty(),
                mpId = it.mpId.orEmpty(),
                classId = it.classId,
                cpId = it.cpId.orEmpty(),
                attendanceDate = it.date,
                schoolPeriodId = it.attSchoolPeriodId,
                periodTitle = it.period
            )
        }

        val result = mutableListOf<Attendance>()
        var unavailable = false
        var existing = false

        for ((selection, localRows) in groups) {
            Log.i(FLOW_TAG, "MERGE_SELECTION $selection localStudents=${localRows.size}")
            when (val serverResult = fetchServerAttendanceReport(
                context = context,
                schoolIds = selection.schoolId,
                syear = selection.academicYear,
                mpId = selection.mpId,
                classIds = selection.classId,
                cpIds = selection.cpId,
                attendanceDate = selection.attendanceDate,
                periodTitle = selection.periodTitle
            )) {
                is ServerFetchResult.Existing -> {
                    existing = true
                    val mergedRows = mergeSelection(localRows, serverResult.students)
                    val counts = mergedRows.groupingBy { it.status }.eachCount()
                    Log.i(
                        FLOW_TAG,
                        "MERGE_RESULT students=${mergedRows.size} P=${counts["P"] ?: 0} " +
                            "L=${counts["L"] ?: 0} E=${counts["E"] ?: 0} A=${counts["A"] ?: 0}"
                    )
                    result += mergedRows
                }
                ServerFetchResult.NotFound -> result += localRows
                is ServerFetchResult.Unavailable -> {
                    unavailable = true
                    Log.w(TAG, "Could not check $selection: ${serverResult.reason}")
                    result += localRows
                }
            }
        }

        return MergeOutcome(result, unavailable, existing)
    }

    /** Hydrates Room so Overview and Edit use the merged server baseline. */
    suspend fun fetchMergeAndPersist(
        context: Context,
        localAttendanceList: List<Attendance>,
        db: AppDatabase
    ): MergeOutcome {
        val outcome = fetchAndMerge(context, localAttendanceList)
        if (!outcome.hasUnavailableSelection) {
            outcome.attendance.forEach { db.attendanceDao().insertAttendance(it) }
            Log.i(FLOW_TAG, "ROOM_SAVE_COMPLETE rows=${outcome.attendance.size}")
        } else {
            Log.w(FLOW_TAG, "ROOM_SERVER_MERGE_NOT_SAVED because server check was unavailable")
        }
        return outcome
    }
}
