package com.digitaledu.selfieattendance.repository

import android.content.Context
import android.os.Handler
import android.util.Log
import android.widget.Toast
import androidx.room.withTransaction
import com.digitaledu.selfieattendance.api.ApiClient
import com.digitaledu.selfieattendance.api.ApiService
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.*
import com.digitaledu.selfieattendance.utility.TripleDESUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class DataSyncRepository(context: Context) {
    private val context = context.applicationContext

    private val TAG = "DataSyncRepository"

    suspend fun fetchAndSaveStudents(
        apiService: ApiService,
        db: AppDatabase,
        instIds: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val rParam = "api/v1/User/GetUserRegisteredDetails"
            val dataParam = "{\"userRegParamData\":{\"userType\":\"student\",\"registrationType\":\"Biometric\",\"school_id\":\"$instIds\"}}"
            val response = apiService.getStudents(rParam, dataParam)

            if (response.isSuccessful && response.body() != null) {
                val jsonString = response.body()!!.string()
                val json = JSONObject(jsonString)
                val collection = json.optJSONObject("collection")
                val responseObj = collection?.optJSONObject("response")
                val dataArray = responseObj?.optJSONArray("userRegisteredData") ?: JSONArray()

                val studentsList = mutableListOf<Student>()
                val classList = mutableListOf<Class>()

                for (i in 0 until dataArray.length()) {
                    val obj = dataArray.getJSONObject(i)
                    val studentId = obj.optString("studentId", "")
                    val studentName = obj.optString("studentName", "")
                    val classId = obj.optString("classId", "")
                    val classShortName = obj.optString("userClassShortName", "")
                    val fingerType = obj.optString("fingerType", "")
                    val fingerData = obj.optString("fingerData", "")
                    val instId = instIds
                    studentsList.add(Student(studentId, studentName, classId, instId, fingerType, fingerData))
                    classList.add(Class(classId, classShortName))
                }

                if (studentsList.isEmpty()) {
                    showToast("Student not found on server for institute: $instIds")
                    return@withContext false
                }

                db.studentsDao().insertAll(studentsList)
                db.classDao().insertAll(classList)
                Log.d(TAG, "Inserted ${studentsList.size} students and ${classList.size} classes.")
                Log.d(TAG, "Inserted ${studentsList} students.")
                true
            } else {
                showToast("Students API failed: Server returned error ${response.code()}")
                Log.e(TAG, "STUDENT_API_FAILED: ${response.errorBody()?.string()}")
                false
            }
        } catch (e: Exception) {
            showToast("Students API connection failed: ${e.localizedMessage ?: "Unknown network error"}")
            Log.e(TAG, "STUDENT_EXCEPTION: ${e.message}", e)
            false
        }
    }

    suspend fun fetchAndSaveTeachers(
        apiService: ApiService,
        db: AppDatabase,
        instIds: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val instituteId = instIds.trim()
            if (instituteId.isEmpty() || instituteId.contains(",")) {
                Log.e(TAG, "Teacher sync requires exactly one institute ID: $instIds")
                return@withContext false
            }

            val rParam = "api/v1/User/GetUserRegisteredDetails"
            val dataParam = "{\"userRegParamData\":{\"userType\":\"staff\",\"registrationType\":\"Biometric\",\"school_id\":\"$instituteId\"}}"
            val response = apiService.getTeachers(rParam, dataParam)

            if (response.isSuccessful && response.body() != null) {
                val jsonString = response.body()!!.string()
                val json = JSONObject(jsonString)
                val responseObject = json.optJSONObject("collection")
                    ?.optJSONObject("response")
                val dataArray = responseObject?.optJSONArray("userRegisteredData")
                if (dataArray == null) {
                    Log.e(TAG, "Teacher response is missing userRegisteredData for $instituteId")
                    return@withContext false
                }

                val teachersList = mutableListOf<Teacher>()
                for (i in 0 until dataArray.length()) {
                    val obj = dataArray.getJSONObject(i)
                    if (obj.optString("staffProfile", "").equals("teacher", ignoreCase = true)) {
                        val staffId = obj.optString("staffId", "").trim()
                        if (staffId.isEmpty()) continue
                        val existing = db.teachersDao().getTeacherById(staffId)
                        val apiName = obj.optString("staffName", "").trim()
                        val apiFingerType = obj.optString("fingerType", "")
                        val apiEmbedding = obj.optString("fingerData", "")
                        teachersList.add(
                            Teacher(
                                staffId = staffId,
                                staffName = apiName.ifBlank {
                                    existing?.staffName.orEmpty()
                                },
                                // Kept only for compatibility during the staged migration.
                                // Session creation never reads this value.
                                instId = existing?.instId?.takeIf { it.isNotBlank() }
                                    ?: instituteId,
                                fingerType = apiFingerType.ifBlank {
                                    existing?.fingerType.orEmpty()
                                },
                                embedding = apiEmbedding.ifBlank {
                                    existing?.embedding.orEmpty()
                                }
                            )
                        )
                    }
                }

                db.withTransaction {
                    // The successful response is authoritative for this institute.
                    // Removing only this institute's rows preserves memberships
                    // belonging to every other institute.
                    db.teacherInstituteMapDao().deleteForInstitute(instituteId)
                    db.teachersDao().insertAll(teachersList)
                    db.teacherInstituteMapDao().insertAll(
                        teachersList.map {
                            TeacherInstituteMap(
                                teacherId = it.staffId,
                                instId = instituteId
                            )
                        }
                    )
                }

                if (teachersList.isEmpty()) {
                    showToast("No teachers found for institute: $instituteId")
                }
                Log.d(
                    TAG,
                    "Updated ${teachersList.size} teachers and memberships for institute $instituteId."
                )
                true
            } else {
                showToast("Teachers API failed: Server returned error ${response.code()}")
                Log.e(TAG, "TEACHER_API_FAILED: ${response.errorBody()?.string()}")
                false
            }
        } catch (e: Exception) {
            showToast("Teachers API connection failed: ${e.localizedMessage ?: "Unknown network error"}")
            Log.e(TAG, "TEACHER_EXCEPTION: ${e.message}", e)
            false
        }
    }

    suspend fun syncSubjectInstances(
        apiService: ApiService,
        db: AppDatabase
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val rParam = "api/v1/CoursePeriod/SubjectInstances"
            val dataParam = "{\"cpParamData\":{\"actionType\":\"markCpAttendance2\"}}"
            val response = apiService.getSubjectInstances(rParam, dataParam)

            if (response.isSuccessful && response.body() != null) {
                val jsonString = response.body()!!.string()
                val json = JSONObject(jsonString)
                val dataArray = json.optJSONObject("collection")
                    ?.optJSONObject("response")
                    ?.optJSONArray("subjectInstancesData") ?: JSONArray()

                if (dataArray.length() == 0) {
                    showToast("Subject configuration setup not found on the server.")
                    Log.w(TAG, "No subject instance data found.")
                    return@withContext false
                }

                val coursePeriodList = mutableListOf<CoursePeriod>()
                val courseList = mutableListOf<Course>()
                val subjectList = mutableListOf<Subject>()

                val teacherClassMapList = mutableListOf<TeacherClassMap>()

                for (i in 0 until dataArray.length()) {
                    val obj = dataArray.getJSONObject(i)

                    val cpId = obj.optString("cpIds")
                    val courseId = obj.optString("courseIds")
                    val subjectId = obj.optString("subjectIds")
                    val subjectTitle = obj.optString("subjectTitles")
                    val courseTitle = obj.optString("courseTitles")
                    val classId = obj.optString("classIds")
                    val classShortName = obj.optString("classShortNames")
                    val mpId = obj.optString("mpId")
                    val mpLongTitle = obj.optString("mpLongTitle")
                    val subjectTypeRaw = obj.optString("subjectType", "").trim()
                    val subjectType = if (subjectTypeRaw.isNotEmpty()) subjectTypeRaw else null

                    // Extract teacher IDs correctly
                    val teacherIdsRaw = obj.optString("teacherIds", "")
                    val teacherIdsList = teacherIdsRaw
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }  // ["382", "524"]

                    // Primary teacher (first in list)
                    val primaryTeacherId = teacherIdsList.firstOrNull() ?: ""

                    // Save subject
                    subjectList.add(
                        Subject(subjectId, subjectTitle, subjectType)
                    )

                    // Save course
                    courseList.add(
                        Course(courseId, subjectId, courseTitle, courseTitle, subjectType)
                    )

                    // Save course period (only primary teacher in this table)
                    // ➤ Save course period ONCE FOR EACH TEACHER
                    teacherIdsList.forEach { tId ->
                        coursePeriodList.add(
                            CoursePeriod(
                                cpId = cpId,
                                courseId = courseId,
                                classId = classId,
                                teacherId = tId,   // EACH TEACHER GETS OWN ROW
                                mpId = mpId,
                                mpLongTitle = mpLongTitle
                            )
                        )
                    }


                    // Save ALL teacher ↔ class mappings
                    teacherIdsList.forEach { tId ->
                        teacherClassMapList.add(
                            TeacherClassMap(
                                teacherId = tId,
                                classId = classId
                            )
                        )
                    }
                }


                db.subjectDao().insertAll(subjectList)
                db.courseDao().insertAll(courseList)
                db.coursePeriodDao().insertAll(coursePeriodList)

                db.teacherClassMapDao().clear()
                db.teacherClassMapDao().insertAll(teacherClassMapList)

                Log.d(TAG, "Subjects: ${subjectList.size}, Courses: ${courseList.size}, CoursePeriods: ${coursePeriodList.size}")
                Log.d(TAG, "Teacher-Class mapping saved: ${teacherClassMapList.size}")

                true
            } else {
                showToast("Subject Instances API failed: Server returned error ${response.code()}")
                Log.e(TAG, "SUBJECT_INSTANCE_API_FAILED: ${response.errorBody()?.string()}")
                false
            }
        } catch (e: Exception) {
            showToast("Subject Instances API connection failed: ${e.localizedMessage ?: "Unknown network error"}")
            Log.e(TAG, "SUBJECT_INSTANCE_EXCEPTION: ${e.message}", e)
            false
        }
    }

    suspend fun fetchDeviceDataToServer(
        apiService: ApiService,
        db: AppDatabase,
        instIds: String
    ): Boolean = withContext(Dispatchers.IO) {
        val rParam = "api/v1/Hardware/DeviceUtilityMgmt"
        val dataParam = (context as? com.digitaledu.selfieattendance.view.SelectInstituteActivity)?.getDeviceUtilityQueryParams(context)
            ?: return@withContext false

        val response = apiService.getDeveiceDataToserver(rParam, dataParam)
        if (response.isSuccessful && response.body() != null) {
            val jsonString = response.body()!!.string()
            val json = JSONObject(jsonString)
            Log.d(TAG, "HARDWARE_RESPONSE: $jsonString")

            val hwMgmtData = json.optJSONObject("collection")
                ?.optJSONObject("response")
                ?.optJSONObject("hwMgmtData")

            if (hwMgmtData != null) {
                val cfg = hwMgmtData.optJSONObject("cfg")
                val deconfigstr = cfg?.optString("deconfigstr")
                if (!deconfigstr.isNullOrEmpty()) {
                    val decryptedStr = TripleDESUtility().getDecryptedStr(deconfigstr)
                    Log.d(TAG, "Decrypted Config: $decryptedStr")
                }
            }
            true
        } else {
            Log.e(TAG, "DEVICE_API_FAILED: ${response.errorBody()?.string()}")
            false
        }
    }


    suspend fun fetchAndSaveStudentSchedulingData(
        apiService: ApiService,
        db: AppDatabase,
        instIds: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val rParam = "api/v1/Schedule/GetStudList"
            val dataParam =
                "{\"schedulingParamData\":{\"actionType\":\"FingerPrint\",\"school_id\":\"$instIds\"}}"

            val response = apiService.getStudentScheduleList(rParam, dataParam)

            if (!response.isSuccessful || response.body() == null) {
                showToast("Student Scheduling API failed: Server returned error ${response.code()}")
                Log.e(TAG, "SCHEDULE_API_FAILED: ${response.errorBody()?.string()}")
                return@withContext false
            }

            val jsonString = response.body()!!.string()
            Log.d(TAG, "RAW_SCHEDULE_JSON: $jsonString")

            val json = JSONObject(jsonString)
            val dataArray = json
                .optJSONObject("collection")
                ?.optJSONObject("response")
                ?.optJSONArray("studentSchedulingData") ?: JSONArray()

            val scheduleList = mutableListOf<StudentSchedule>()

            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                scheduleList.add(
                    StudentSchedule(
                        scheduleId = obj.optString("scheduleId", ""),
                        studentId = obj.optString("studentId", ""),
                        cpId = obj.optString("cpId", ""),
                        courseId = obj.optString("courseId", ""),
                        scheduleStartDate = obj.optString("scheduleStartDate", ""),
                        scheduleEndDate = obj.optString("scheduleEndDate", ""),
                        syncStatus = "complete"
                    )
                )
            }

            if (scheduleList.isEmpty()) {
                showToast("Student scheduling setup not found on server for institute: $instIds")
                return@withContext false
            }

            db.studentScheduleDao().insertAll(scheduleList)
            Log.d(TAG, "Saved ${scheduleList.size} student schedule rows")
            return@withContext true

        } catch (e: Exception) {
            showToast("Student Scheduling API connection failed: ${e.localizedMessage ?: "Unknown network error"}")
            Log.e(TAG, "SCHEDULE_EXCEPTION: ${e.message}")
            return@withContext false
        }
    }



    private fun showToast(message: String) {
        Handler(context.mainLooper).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }


    suspend fun syncPendingStudentSchedules(context: Context) = withContext(Dispatchers.IO) {


        Log.e("AUTO_SYNC", "syncPendingStudentSchedules() CALLED")

        val db = AppDatabase.getDatabase(context)
        Log.e("AUTO_SYNC", "Fetching pending schedules from Room")

        // fetch pending schedules from new table
        val pendingList = db.pendingScheduleDao().getPendingSchedules()

        Log.e("AUTO_SYNC", "PendingScheduleEntity count = ${pendingList.size}")
        if (pendingList.isEmpty()) {
            Log.i("SCHEDULER_SYNC", "No pending schedules to sync (PendingScheduleEntity empty)")
            return@withContext
        }

        val prefs = context.getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("baseUrl", "")!!
        val hash = prefs.getString("hash", "")!!

        val apiService = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)

        // Directly build payload from PendingScheduleEntity (NO EXTRA MAPPING)
        val actionArray = JSONArray()

        pendingList.forEach { p ->

            val obj = JSONObject()

            // copy all fields exactly as stored
            obj.put("school_id",        p.school_id)
            obj.put("syear",            p.syear)
            obj.put("marking_period_id",p.marking_period_id)
            obj.put("mp",               p.mp)

            obj.put("class_id",         p.class_id)
            obj.put("class_title",      p.class_title)

            obj.put("subjectId",        p.subjectId)
            obj.put("headId",           p.headId)

            obj.put("course_id",        p.course_id)
            obj.put("course_period_id", p.course_period_id)
            obj.put("cp_title",         p.cp_title)

            obj.put("teacher_id",       p.teacher_id)
            obj.put("teacher_name",     p.teacher_name)

            obj.put("student_id",       p.student_id)
            obj.put("student_name",     p.student_name)

            obj.put("start_date",       p.start_date)
            obj.put("created_by",       p.created_by)

            obj.put("isCreateScheduling", p.isCreateScheduling)
            obj.put("isUpdateScheduling", p.isUpdateScheduling)

            actionArray.put(obj)
        }

        val bodyJson = JSONObject().apply {
            put("smParamDataObj", JSONObject().apply {
                put("actionType", "addUpdateStudentSubjectSchedulingTblDetails")
                put("actionData", actionArray)
            })
        }

        val requestBody = okhttp3.RequestBody.create(
            okhttp3.MediaType.parse("application/json"),
            bodyJson.toString()
        )

        Log.e("SCHEDULER_SYNC", "Sending ${pendingList.size} pending schedules...\n$bodyJson")

        try {
            val response = apiService.postStudentSubjectSchedule(body = requestBody)

            if (response.isSuccessful && response.body() != null) {

                val respStr = response.body()!!.string()
                Log.e("SCHEDULER_SYNC", "Response: $respStr")

                val respJson = JSONObject(respStr)
                val status = respJson.optJSONObject("collection")
                    ?.optJSONObject("response")
                    ?.optString("status")
                    ?: respJson.optJSONObject("collection")
                        ?.optJSONObject("response")
                        ?.optString("statusMsg")

                if (status.equals("SUCCESS", true)) {

                    pendingList.forEach {
                        db.pendingScheduleDao().updateSyncStatus(it.id, "complete")

                        Log.e("AUTO_SYNC", "✔ Server SUCCESS — Updating pending rows to complete")

                    }

                    Log.e("SCHEDULER_SYNC", "✔ Pending schedules synced successfully")
                } else {
                    Log.e("SCHEDULER_SYNC", "❌ Server returned FAILURE - will retry")
                }
            }
        } catch (e: Exception) {
            Log.e("SCHEDULER_SYNC", "Exception: ${e.message}")
        }
    }



    suspend fun syncPendingTeacherAllocation(context: Context) = withContext(Dispatchers.IO) {

        val db = AppDatabase.getDatabase(context)
        val pending = db.pendingTeacherAllocationDao().getPending()

        if (pending.isEmpty()) return@withContext

        val prefs = context.getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("baseUrl", "")!!
        val hash = prefs.getString("hash", "")!!

        val api = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)

        pending.forEach { p ->
            try {
                val body = okhttp3.RequestBody.create(
                    okhttp3.MediaType.parse("application/json"),
                    p.jsonPayload
                )

                val response = api.postTeacherAllocation(body)

                if (response.isSuccessful && response.body() != null) {
                    val respStr = response.body()!!.string()
                    val respJson = JSONObject(respStr)
                    val status = respJson
                        .optJSONObject("collection")
                        ?.optJSONObject("response")
                        ?.optJSONObject("updationStatus")
                        ?.optString("status")

                    if (status == "SUCCESS") {
                        db.pendingTeacherAllocationDao().updateStatus(p.id, "complete")
                    }
                }

            } catch (e: Exception) {
                // keep pending
            }
        }
    }



    suspend fun fetchAndSaveSchoolPeriods(
        apiService: ApiService,
        db: AppDatabase,
        instId: String
    ): Boolean = withContext(Dispatchers.IO) {

        try {
            // 🔹 Get syear dynamically from DB
            val sYear = db.instituteDao().getInstituteYearById(instId) ?: ""
            Log.d("SYNC_PERIOD", "Institute: $instId | Academic Year: $sYear")

            val rParam = "api/v1/Att/ManageMarkingGlobalAtt"
            val dataParam = """
            {
                "attParamDataObj":{
                    "actionType":"getPeriodDetailsForMarkingGlobalAtt",
                    "actionData":{
                        "instId":"$instId",
                        "syear":"$sYear",
                        "attendanceMethod":"periodDayWiseAttendance"
                    }
                }
            }
        """.trimIndent()

            // 🔹 Log the outgoing request data
            Log.d("PERIOD_API_REQUEST", "URL Query: r=$rParam")
            Log.d("PERIOD_API_REQUEST", "Payload: $dataParam")

            val response = apiService.getPeriodDetails(rParam, dataParam)

            if (!response.isSuccessful || response.body() == null) {
                showToast("School Periods API failed: Server returned error ${response.code()}")
                Log.e("PERIOD_API_FAILED", "${response.errorBody()?.string()}")
                return@withContext false
            }

            val respString = response.body()!!.string()
            // 🔹 Log raw response
            Log.d("PERIOD_API_RESPONSE", respString)

            val json = JSONObject(respString)
            val dataArr = json
                .optJSONObject("collection")
                ?.optJSONObject("response")
                ?.optJSONArray("data") ?: JSONArray()

            val periodList = mutableListOf<SchoolPeriod>()

            for (i in 0 until dataArr.length()) {
                val obj = dataArr.getJSONObject(i)
                periodList.add(
                    SchoolPeriod(
                        spId = obj.optString("spId"),
                        spTitle = obj.optString("spTitle"),
                        spStartTime = obj.optString("spStartTime"),
                        spEndTime = obj.optString("spEndTime"),
                        spIstTime = obj.optString("spIstTime"),
                        instId = instId
                    )
                )
            }

            if (periodList.isEmpty()) {
                showToast("School period setup not found on server for institute: $instId")
                return@withContext false
            }

            // Optional: Clear old before saving new
            db.schoolPeriodDao().insertAll(periodList)

            Log.d("SYNC_PERIOD", "Saved ${periodList.size} period rows")
            Log.d("SYNC_PERIOD_DATA", periodList.toString())
            return@withContext true

        } catch (e: Exception) {
            showToast("School Periods API connection failed: ${e.localizedMessage ?: "Unknown network error"}")
            Log.e("SYNC_PERIOD_ERROR", "Exception: ${e.message}")
            return@withContext false
        }
    }



    /**
     * Fetches face detection/recognition thresholds from the
     * `ManageProgramConfig` API and saves them to Room. On success,
     * immediately updates the in-memory [FaceDetectionConfig] singleton.
     *
     * Failure is **non-blocking**: the sync continues and the existing
     * hardcoded defaults remain in effect.
     */
    suspend fun fetchAndSaveFaceDetectionConfig(
        apiService: ApiService,
        db: AppDatabase,
        schoolId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val syear = db.instituteDao().getInstituteYearById(schoolId) ?: ""
            val dataParam = """{"programName":"SelfieAttendance","title":"FaceDetectionThreshold","syear":"$syear","school_id":"$schoolId"}"""

            Log.d(TAG, "CONFIG_API_REQUEST: $dataParam")

            val response = apiService.getProgramConfig(data = dataParam)

            if (!response.isSuccessful || response.body() == null) {
                Log.w(TAG, "CONFIG_API_FAILED: Server returned ${response.code()} — keeping defaults")
                return@withContext false
            }

            val jsonString = response.body()!!.string()
            Log.d(TAG, "CONFIG_API_RESPONSE: $jsonString")

            val json = JSONObject(jsonString)
            val retConfigData = json
                .optJSONObject("collection")
                ?.optJSONObject("response")
                ?.optJSONArray("retConfigData")

            if (retConfigData == null || retConfigData.length() == 0) {
                Log.w(TAG, "CONFIG_API: No retConfigData found — keeping defaults")
                return@withContext false
            }

            val configObj = retConfigData.getJSONObject(0)
            val value = configObj.optString("value", "")
Log.d(TAG, "CONFIG_API_VALUE: $value")
            val programConfId = configObj.optString("programConfId", "")

Log.d(TAG, "CONFIG_API_PROGRAM_CONF_ID: $programConfId")
            if (value.isBlank()) {
                Log.w(TAG, "CONFIG_API: Empty value field — keeping defaults")
                return@withContext false
            }

            // Save to Room for offline access on next app launch
            db.programConfigDao().insertOrUpdate(
                com.digitaledu.selfieattendance.db.entity.ProgramConfig(
                    title = "FaceDetectionThreshold",
                    program = "SelfieAttendance",
                    value = value,
                    schoolId = schoolId,
                    syear = syear,
                    programConfId = programConfId
                )
            )

            // Update in-memory singleton immediately
            com.digitaledu.selfieattendance.ml.FaceDetectionConfig.loadFromJson(value)

            Log.i(TAG, "✔ Face detection config synced and applied (confId=$programConfId)")
            true

        } catch (e: Exception) {
            Log.e(TAG, "CONFIG_EXCEPTION: ${e.message} — keeping defaults", e)
            false
        }
    }

    suspend fun fetchAndSaveAttendanceCodes(
        apiService: ApiService,
        db: AppDatabase,
        schoolId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val instId = schoolId.trim()
            if (instId.isEmpty()) return@withContext false

            val dataParam = JSONObject().apply {
                put("attParamDataObj", JSONObject().apply {
                    put("schoolId", instId)
                })
            }.toString()

            Log.d(TAG, "Fetching attendance codes for institute $instId with data: $dataParam")

            val response = apiService.getSchoolAttendanceCodes(data = dataParam)

            if (response.isSuccessful && response.body() != null) {
                val jsonString = response.body()!!.string()
                Log.d(TAG, "ATT_CODE_RESPONSE: $jsonString")

                val json = JSONObject(jsonString)
                val collection = json.optJSONObject("collection")
                val responseObj = collection?.optJSONObject("response")
                val dataArray = responseObj?.optJSONArray("data")

                if (dataArray == null || dataArray.length() == 0) {
                    Log.w(TAG, "ATT_CODES: Empty data array for institute $instId")
                    return@withContext false
                }

                val allowedNames = setOf("present", "absent", "exempted", "late")
                val allowedCodes = setOf("p", "a", "e", "l")
                val codesList = mutableListOf<AttendanceCode>()

                for (i in 0 until dataArray.length()) {
                    val obj = dataArray.getJSONObject(i)
                    val atcId = obj.optString("atcId", "")
                    val atcSchoolId = obj.optString("atcSchoolId", instId).ifBlank { instId }
                    val atcLongName = obj.optString("atcLongName", "")
                    val atcShortName = obj.optString("atcShortName", "")
                    val atcCode = obj.optString("atcCode", "")

                    // Filtering: Only map Present, Absent, Exempted, Late (ignore other codes)
                    val nameMatch = allowedNames.contains(atcShortName.trim().lowercase()) ||
                            allowedNames.contains(atcLongName.trim().lowercase())
                    val codeMatch = allowedCodes.contains(atcCode.trim().lowercase())

                    if (nameMatch || codeMatch) {
                        val normalizedCode = when {
                            atcCode.equals("P", true) || atcShortName.equals("Present", true) || atcLongName.equals("Present", true) -> "P"
                            atcCode.equals("A", true) || atcShortName.equals("Absent", true) || atcLongName.equals("Absent", true) -> "A"
                            atcCode.equals("E", true) || atcShortName.equals("Exempted", true) || atcLongName.equals("Exempted", true) -> "E"
                            atcCode.equals("L", true) || atcShortName.equals("Late", true) || atcLongName.equals("Late", true) -> "L"
                            else -> atcCode.uppercase()
                        }

                        codesList.add(
                            AttendanceCode(
                                atcCode = normalizedCode,
                                atcId = atcId,
                                atcLongName = if (atcLongName.isNotBlank()) atcLongName else atcShortName,
                                atcSchoolId = atcSchoolId,
                                atcShortName = atcShortName
                            )
                        )
                    } else {
                        Log.d(TAG, "ATT_CODES: Ignoring code: shortName='$atcShortName', longName='$atcLongName', code='$atcCode'")
                    }
                }

                if (codesList.isNotEmpty()) {
                    db.attendanceCodeDao().insertAll(codesList)
                    Log.i(TAG, "✔ Saved ${codesList.size} attendance codes for institute $instId")
                    true
                } else {
                    Log.w(TAG, "ATT_CODES: No matching attendance codes found for institute $instId")
                    false
                }
            } else {
                Log.e(TAG, "ATT_CODES_API_FAILED: Code ${response.code()}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "ATT_CODES_EXCEPTION: ${e.message}", e)
            false
        }
    }

}

