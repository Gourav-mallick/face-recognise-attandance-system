package com.digitaledu.selfieattendance.view

import android.app.AlertDialog
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.digitaledu.selfieattendance.databinding.ActivityAttendanceOverviewBinding
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import kotlinx.coroutines.launch
import androidx.activity.OnBackPressedCallback
import com.digitaledu.selfieattendance.api.ApiClient
import com.digitaledu.selfieattendance.api.ApiService
import com.digitaledu.selfieattendance.db.entity.Attendance
import com.digitaledu.selfieattendance.utility.DatabaseCleanupUtils
import com.digitaledu.selfieattendance.utility.AttendanceInstituteValidator
import com.digitaledu.selfieattendance.utility.AttendanceSyncMerger
import com.digitaledu.selfieattendance.utility.AttendanceRosterResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.lang.Exception

class AttendanceOverviewActivity : ComponentActivity() {

    private lateinit var binding: ActivityAttendanceOverviewBinding
    private lateinit var db: AppDatabase
    private lateinit var selectedClasses: List<String>
    private lateinit var sessionId: String
    private val editAttendanceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            loadOverviewData()
        }
    }

    // 🔹 Track whether back press is disabled
    private var backDisabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttendanceOverviewBinding.inflate(layoutInflater)
        setContentView(binding.root)


        db = AppDatabase.getDatabase(this)
        selectedClasses = intent.getStringArrayListExtra("SELECTED_CLASSES") ?: emptyList()
        sessionId = intent.getStringExtra("SESSION_ID") ?: ""


        getSharedPreferences("APP_STATE", MODE_PRIVATE).edit()
            .putString("CURRENT_SCREEN", "ATTENDANCE_OVERVIEW")
            .putString("SESSION_ID", sessionId)
            .apply()

        //  Disable back press & back gesture for this screen
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (backDisabled) {
                    // Do nothing — block back press
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        loadOverviewData()

        binding.btnSubmitAttendance.setOnClickListener {
            submitAttendanceForSession()
   /*
          val intent = Intent(this, AttendanceActivity::class.java)
            startActivity(intent)
            finish()

    */
        }
    }

    private data class CoTeacherItem(
        val teacherId: String,
        val teacherName: String,
        val cpId: String,
        var isVerified: Boolean
    )

    private var pendingCoTeacherItem: CoTeacherItem? = null
    private var pendingClassOverviewData: ClassOverviewData? = null
    private var activeCoTeacherList: MutableList<CoTeacherItem> = mutableListOf()

    private val coTeacherFaceVerificationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val item = pendingCoTeacherItem
            if (item != null) {
                item.isVerified = true
                android.widget.Toast.makeText(
                    this,
                    "Face verified for ${item.teacherName}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                val targetClass = pendingClassOverviewData
                if (targetClass != null) {
                    showCoLecturerSelectionDialog(targetClass, activeCoTeacherList)
                }
            }
        } else {
            val item = pendingCoTeacherItem
            val teacherName = item?.teacherName ?: "Teacher"
            android.widget.Toast.makeText(
                this,
                "Face verification failed/canceled for $teacherName",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        pendingCoTeacherItem = null
    }

    private fun loadOverviewData() {
        lifecycleScope.launch {
            val classSummaries = mutableListOf<ClassOverviewData>()

            for (classId in selectedClasses) {
                val students = AttendanceRosterResolver.forSessionClass(db, sessionId, classId)
                val classObj=db.classDao().getClassById(classId)
                val classShortName = classObj?.classShortName ?: classId
                Log.d("ATTENDANCE_DEBUG", "classShortName is=$classShortName")

                val eligibleStudentIds = students.map { it.studentId }.toSet()
                val attendance = db.attendanceDao().getAttendancesForClass(sessionId, classId)
                    .filter { it.studentId in eligibleStudentIds }

                val totalStudents = students.size
                val presentCount = attendance.count { it.status == "P" }
                val lateCount = attendance.count { it.status == "L" }
                val exemptedCount = attendance.count { it.status == "E" }
                val recordedAbsent = attendance.count { it.status == "A" }
                val unrecordedCount = (totalStudents - attendance.size).coerceAtLeast(0)
                val absentCount = recordedAbsent + unrecordedCount

                // Subject name & Co-Lecturers
                val sampleAtt = attendance.firstOrNull()
                val subjectName = sampleAtt?.subjectTitle

                val rawCpIds = sampleAtt?.attCoLectureCpIds.orEmpty()
                val cpIdList = rawCpIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val coLecturerNameList = mutableListOf<String>()
                for (cpId in cpIdList) {
                    val cpObj = db.coursePeriodDao().getCoursePeriodByCpId(cpId)
                    val tId = cpObj?.teacherId
                    val tName = if (!tId.isNullOrEmpty()) db.teachersDao().getTeacherNameById(tId) else null
                    if (!tName.isNullOrEmpty()) {
                        coLecturerNameList.add(tName)
                    } else {
                        coLecturerNameList.add("Teacher #$cpId")
                    }
                }
                val coLecturerNames = if (coLecturerNameList.isNotEmpty()) coLecturerNameList.joinToString(", ") else null

                classSummaries.add(
                    ClassOverviewData(
                        classId = classId,
                        className = classShortName,
                        subjectName = subjectName,
                        totalStudents = totalStudents,
                        presentCount = presentCount,
                        lateCount = lateCount,
                        exemptedCount = exemptedCount,
                        absentCount = absentCount,
                        coLecturerNames = coLecturerNames,
                        coLectureCpIds = if (cpIdList.isNotEmpty()) rawCpIds else null
                    )
                )
            }

            val adapter = AttendanceOverviewAdapter(
                classSummaries,
                onEditClick = { selectedClassId ->
                    val intent = Intent(this@AttendanceOverviewActivity, EditAttendanceActivity::class.java)
                    intent.putExtra("CLASS_ID", selectedClassId)
                    intent.putExtra("SESSION_ID", sessionId)
                    intent.putStringArrayListExtra("SELECTED_CLASSES", ArrayList(selectedClasses))
                    editAttendanceLauncher.launch(intent)
                },
                onAddCoLecturerClick = { classOverviewData ->
                    showCoLecturerSelectionDialog(classOverviewData)
                }
            )

            binding.recyclerViewOverview.layoutManager = LinearLayoutManager(this@AttendanceOverviewActivity)
            binding.recyclerViewOverview.adapter = adapter
        }
    }

    private fun showCoLecturerSelectionDialog(
        classData: ClassOverviewData,
        existingList: MutableList<CoTeacherItem>? = null
    ) {
        if (existingList != null) {
            displayCoLecturerDialog(classData, existingList)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val session = db.sessionDao().getSessionById(sessionId)
            val primaryTeacherId = session?.teacherId.orEmpty()
            val attendanceRows = db.attendanceDao().getAttendancesForClass(sessionId, classData.classId)
            val existingCoLectureCpIds = attendanceRows.firstOrNull()?.attCoLectureCpIds.orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()

            val activeCourseId = attendanceRows.firstOrNull()?.courseId.orEmpty()

            val coursePeriods = if (activeCourseId.isNotBlank()) {
                db.coursePeriodDao().getCoursePeriodsForClassAndCourse(classData.classId, activeCourseId)
            } else {
                db.coursePeriodDao().getCoursePeriodsForClass(classData.classId)
            }

            val candidates = mutableListOf<CoTeacherItem>()
            val seenTeacherCpPair = mutableSetOf<String>()

            for (cp in coursePeriods) {
                val tId = cp.teacherId.orEmpty()
                if (tId.isBlank() || tId == primaryTeacherId) continue

                val pairKey = "$tId#${cp.cpId}"
                if (seenTeacherCpPair.contains(pairKey)) continue
                seenTeacherCpPair.add(pairKey)

                val teacherObj = db.teachersDao().getTeacherById(tId)
                val teacherName = teacherObj?.staffName ?: "Teacher #$tId"
                val isAlreadyVerified = existingCoLectureCpIds.contains(cp.cpId)

                candidates.add(
                    CoTeacherItem(
                        teacherId = tId,
                        teacherName = teacherName,
                        cpId = cp.cpId,
                        isVerified = isAlreadyVerified
                    )
                )
            }

            withContext(Dispatchers.Main) {
                if (candidates.isEmpty()) {
                    android.widget.Toast.makeText(
                        this@AttendanceOverviewActivity,
                        "No co-lecturers assigned to this course/subject.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    return@withContext
                }
                displayCoLecturerDialog(classData, candidates)
            }
        }
    }

    private fun displayCoLecturerDialog(
        classData: ClassOverviewData,
        candidates: MutableList<CoTeacherItem>
    ) {
        activeCoTeacherList = candidates
        pendingClassOverviewData = classData

        val itemTitles = candidates.map { item ->
            val statusText = if (item.isVerified) " [Verified]" else ""
            "${item.teacherName} (CP ID: ${item.cpId})$statusText"
        }.toTypedArray()

        val checkedArray = BooleanArray(candidates.size) { candidates[it].isVerified }

        var dialog: AlertDialog? = null

        val builder = AlertDialog.Builder(this)
            .setTitle("Select Co-Lecturer for ${classData.className}")
            .setMultiChoiceItems(itemTitles, checkedArray) { _, which, isChecked ->
                val item = candidates[which]
                if (isChecked) {
                    if (!item.isVerified) {
                        pendingCoTeacherItem = item
                        dialog?.dismiss()

                        val intent = Intent(this@AttendanceOverviewActivity, FaceVerificationActivity::class.java).apply {
                            putExtra("USER_TYPE", "staff")
                            putExtra("TEACHER_ID", item.teacherId)
                            putExtra("TEACHER_NAME", item.teacherName)
                        }
                        coTeacherFaceVerificationLauncher.launch(intent)
                    }
                } else {
                    item.isVerified = false
                }
            }
            .setPositiveButton("Save") { d, _ ->
                d.dismiss()
                val verifiedCpIds = candidates.filter { it.isVerified }.map { it.cpId }.distinct().joinToString(",")
                lifecycleScope.launch(Dispatchers.IO) {
                    db.attendanceDao().updateCoLectureCpIdsForClass(sessionId, classData.classId, verifiedCpIds)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            this@AttendanceOverviewActivity,
                            "Co-lecturers updated successfully",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        loadOverviewData()
                    }
                }
            }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }

        dialog = builder.create()
        dialog.show()
    }



    private fun submitAttendanceForSession() {
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d("ATTENDANCE_DEBUG", "submitAttendanceForSession() START for sessionId=$sessionId")
            // Show spinner on main thread
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.VISIBLE
            }
            delay(2000) // show for 2s

            try {
                val attendanceList = db.attendanceDao().getAttendanceBySessionId(sessionId)
                Log.d("AttendanceOverview", "Attendance list: $attendanceList")

                if (attendanceList.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                    }
                    Log.d("AttendanceOverview", "No attendance found for this session.")
                    return@launch
                }

                val session = db.sessionDao().getSessionById(sessionId)
                val allowedInstIds = db.instituteDao().getAll().map { it.id.trim() }.filter { it.isNotEmpty() }.toSet()

                val consistencyError = AttendanceInstituteValidator.validate(
                    session?.instId,
                    attendanceList.map { it.instId },
                    allowedInstIds
                )

                // Write debug report to text file upon submit attempt for easy inspection
                writeAttendanceDebugFile(session?.instId, allowedInstIds, attendanceList, consistencyError)

                if (consistencyError != null) {
                    Log.e(
                        "AttendanceOverview",
                        "Blocked session $sessionId: $consistencyError"
                    )
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        android.widget.Toast.makeText(
                            this@AttendanceOverviewActivity,
                            "Attendance institute data is inconsistent. Sync was blocked. Debug log saved to attendance_submit_debug.txt",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                attendanceList.forEach {
                    Log.d("PAYLOAD_CHECK", "student=${it.studentId} cpId=${it.cpId}")
                }

                // Rebase local face captures and explicit edits onto the latest server baseline.
                val mergeOutcome = AttendanceSyncMerger.fetchAndMerge(
                    context = this@AttendanceOverviewActivity,
                    localAttendanceList = attendanceList
                )
                if (mergeOutcome.hasUnavailableSelection) {
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        showPopupWithOk("Server could not be checked. Attendance is saved locally and can be synced later.")
                    }
                    return@launch
                }
                val mergedAttendanceList = mergeOutcome.attendance
                Log.d("ATTENDANCE_DEBUG", "Final attendance count after merge: ${mergedAttendanceList.size}")

                val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
                val baseUrl = prefs.getString("baseUrl", "")!!
                val hash = prefs.getString("hash", "")!!

                val apiService = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)

                // Prepare JSON payload from merged attendance
                val attArray = JSONArray()
                for (att in mergedAttendanceList) {
                    val mapped = mapAttendanceToApiFormat(att)
                    Log.d("ATT_MAPPED", mapped.toString())
                    attArray.put(mapped)
                }

                val loggedStaffId = prefs.getString("loggedStaffId", null)

                val requestBodyJson = JSONObject().apply {
                    put("attParamDataObj", JSONObject().apply {
                        put("attDataArr", attArray)
                        put("attAttachmentArr", JSONArray())
                        put("attendanceMethod", "periodDayWiseAttendance")
                        put("loggedInUsrId", loggedStaffId)
                    })
                }

                Log.d("SYNC_REQUEST", requestBodyJson.toString())
                val mediaType = MediaType.parse("application/json; charset=utf-8")
                val requestBody = RequestBody.create(mediaType, requestBodyJson.toString())

                val response = apiService.postAttendanceSync(
                    r = "api/v1/Att/ManageMarkingGlobalAtt",
                    requestBody = requestBody
                )

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                }

                if (response.isSuccessful && response.body() != null) {
                    val bodyString = response.body()!!.string()
                    Log.d("SYNC_RESPONSE", bodyString)

                    try {
                        val json = JSONObject(bodyString)
                        val collection = json.optJSONObject("collection")
                        val responseObj = collection?.optJSONObject("response")
                        val apiStatus = responseObj?.optString("status", "FAILED") ?: "FAILED"
                        Log.d("ATTENDANCE_DEBUG", "API reported status=$apiStatus")
                        val apiMsgArray = responseObj?.optJSONArray("msgAr")
                        val msg = apiMsgArray?.optString(0) ?: "Attendance synced successfully"

                        if (apiStatus.equals("SUCCESS", ignoreCase = true)) {
                            Log.d("SYNC_RESPONSE", "Attendance synced successfully")
                            db.attendanceDao().updateSyncStatusBySession(sessionId, "complete")
                            db.sessionDao().updateSessionSyncStatusToComplete(sessionId, "complete")

                            DatabaseCleanupUtils.deleteSyncedAttendances(this@AttendanceOverviewActivity)
                            DatabaseCleanupUtils.deleteSyncedSessions(this@AttendanceOverviewActivity)

                            withContext(Dispatchers.Main) {
                                showPopupWithOk(msg)
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Log.w("ATTENDANCE_DEBUG", "API returned non-success or empty body. Will mark locally.")
                                showPopupWithOk("Attendance saved locally. You can sync later.")
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            showPopupWithOk("Attendance saved locally. You can sync later.")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showPopupWithOk("Attendance saved locally. You can sync later.")
                    }
                }

            } catch (e: Exception) {
                Log.e("ATTENDANCE_DEBUG", "Exception in submitAttendanceForSession: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    showPopupWithOk("Server not reachable. Attendance saved locally, will sync later.")
                }
            }
        }
    }


    private suspend fun mapAttendanceToApiFormat(att: Attendance): JSONObject {
        val date = att.date
        //val year = date.split("-")[0]

        val startTime = att.startTime
        val endTime = att.endTime
        val dataStartTime = "$date $startTime:00"
        val dataEndTime = "$date $endTime:00"

        Log.d("ATTENDANCE_DEBUG", "instId=${att.instId}")
        Log.d("ATTENDANCE_DEBUG", "acadamicyear=${att.academicYear}")

        val classShort = db.classDao().getClassById(att.classId)?.classShortName ?: ""
        Log.d("ATTENDANCE_DEBUG", "acadamicyear=${classShort}")
        return JSONObject().apply {
            put("studentId", att.studentId)
            put("instId", att.instId)
            put("instShortName", att.instShortName ?: "")
            put("academicYear",  att.academicYear)
            put("classId", att.classId)
            put("classShortName", classShort)
            put("subjectId", att.subjectId ?: "")
            put("subjectCode", att.subjectId ?: "")
            put("subjectShortName", att.subjectTitle ?: "")
            put("courseId", att.courseId ?: "")
            put("courseShortName", att.courseShortName ?: "")
            put("cpId", att.cpId ?: "")
            put("cpShortName", "")
            put("mpId", att.mpId ?: "")
            put("mpShortName", att.mpLongTitle ?: "")
            put("attDate", att.date)
            put("attSchoolPeriodStartTime", att.startTime)
            put("attSchoolPeriodEndTime", att.endTime)
            put("period", att.period)
           // put("status", att.status)
            // You can extend more mappings as per your actual backend requirement
            put("studentClass", classShort)
            put("attCodetitle", "present")
            put("courseSelectionMode","")
            put("stfId",att.teacherId)
            put("stfFML","")
            put("studId",att.studentId)
            put("studfFML","")
            put("studfLFM","")
            put("studentName",att.studentName)
            put("studAltId",att.atteId)
            put("studRollNo","")
            put("int_rollNo","")
            put("attCycleId","")
            put("attSessionId",att.sessionId)
            put("attSchoolPeriodId",att.attSchoolPeriodId)
            put("attSchoolPeriodTitle","")
            put("attSessionStartDateTime",dataStartTime )
            put("attSessionEndDateTime",dataEndTime)
            put("attCapturingIntervalDateTime","")
            put("attCapturingIntervalInSec","")
            put("attCapturingCycleState","")
            put("attCategory","Regular")
            put("studAttComment","")
            put("attSessionStudId","")
            val attCode = att.status // "P", "L", "E", "A"
            val instId = att.instId ?: ""
            val codeEntity = db.attendanceCodeDao().getByCodeAndSchool(attCode, instId)
                ?: db.attendanceCodeDao().getByCode(attCode)
            val attCodeId = codeEntity?.atcId ?: when(attCode) {
                "L" -> "4"
                "E" -> "3"
                "A" -> "2"
                else -> "1"
            }
            val attCodeLngName = codeEntity?.atcLongName ?: when(attCode) {
                "L" -> "late"
                "E" -> "exempted"
                "A" -> "absent"
                else -> "present"
            }


            put("attCodeId", attCodeId)
            put("attCodeLngName", attCodeLngName)
            put("attCode", attCode)
            put("studAttStartDateTime",dataStartTime)
            put("studAttEndDateTime",dataEndTime)
            put("studAttTotalDuration","")
            put("atsaId","")
            put("atsaIsProxy","")
            put("atsaDistanceDeltaInMeter","")
            put("isSelfUsrAttMarked","")
            put("attCoLectureCpIds", att.attCoLectureCpIds ?: "")
            put("toRemoveCoLecturerCpIds", att.toRemoveCoLecturerCpIds ?: "")
            put("toAddCoLecturerCpIds", att.toAddCoLecturerCpIds ?: "")
            put("status", "A")

        }

    }

    private fun writeAttendanceDebugFile(
        sessionInstId: String?,
        allowedInstIds: Set<String>,
        attendanceList: List<com.digitaledu.selfieattendance.db.entity.Attendance>,
        consistencyError: String?
    ) {
        try {
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val sb = StringBuilder()
            sb.append("====================================================\n")
            sb.append("ATTENDANCE SUBMIT DEBUG REPORT ($timestamp)\n")
            sb.append("====================================================\n")
            sb.append("Session ID: $sessionId\n")
            sb.append("Session Primary Institute ID: $sessionInstId\n")
            sb.append("Allowed Institute IDs in DB: $allowedInstIds\n")
            sb.append("Consistency Validation Result: ${consistencyError ?: "SUCCESS (VALID)"}\n")
            sb.append("Total Attendance Records: ${attendanceList.size}\n\n")

            sb.append("ATTENDANCE OBJECTS DETAILS:\n")
            sb.append("----------------------------------------------------\n")
            attendanceList.forEachIndexed { index, att ->
                sb.append("[$index] StudentID: ${att.studentId} | Name: ${att.studentName}\n")
                sb.append("     InstID: '${att.instId}' | InstShortName: '${att.instShortName}'\n")
                sb.append("     ClassID: '${att.classId}' | ClassShortName: '${att.classShortName}'\n")
                sb.append("     CpID: '${att.cpId}' | CourseID: '${att.courseId}' | SubjectID: '${att.subjectId}'\n")
                sb.append("     SchoolPeriodID: '${att.attSchoolPeriodId}' | PeriodTitle: '${att.period}'\n")
                sb.append("     Status: '${att.status}' | FaceCaptured: ${att.isFaceCaptured} | ExplicitEdit: ${att.isExplicitEdit}\n")
                sb.append("     SyncStatus: '${att.syncStatus}' | MarkedAt: '${att.markedAt}'\n")
                sb.append("----------------------------------------------------\n")
            }

            val reportContent = sb.toString()
            Log.d("ATTENDANCE_SUBMIT_DEBUG", reportContent)

            // 1. External files directory
            val externalDir = getExternalFilesDir(null)
            if (externalDir != null) {
                val debugFile = java.io.File(externalDir, "attendance_submit_debug.txt")
                debugFile.writeText(reportContent)
                Log.i("ATTENDANCE_SUBMIT_DEBUG", "Saved debug file to: ${debugFile.absolutePath}")
            }

            // 2. Internal files directory
            val internalFile = java.io.File(filesDir, "attendance_submit_debug.txt")
            internalFile.writeText(reportContent)
            Log.i("ATTENDANCE_SUBMIT_DEBUG", "Saved debug file to: ${internalFile.absolutePath}")

        } catch (e: Exception) {
            Log.e("ATTENDANCE_SUBMIT_DEBUG", "Failed to write debug file: ${e.message}", e)
        }
    }

    private fun showPopupWithOk(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                // Use commit() instead of apply() to make it synchronous
                getSharedPreferences("APP_STATE", MODE_PRIVATE).edit().clear().commit()
                getSharedPreferences("AttendancePrefs", MODE_PRIVATE).edit().clear().commit()

                val intent = Intent(this@AttendanceOverviewActivity, AttendanceActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finish()
            }
            .show()
    }

}

data class ClassOverviewData(
    val classId: String,
    val className: String,
    val subjectName: String?,
    val totalStudents: Int,
    val presentCount: Int,
    val lateCount: Int,
    val exemptedCount: Int,
    val absentCount: Int,
    val coLecturerNames: String? = null,
    val coLectureCpIds: String? = null
)

