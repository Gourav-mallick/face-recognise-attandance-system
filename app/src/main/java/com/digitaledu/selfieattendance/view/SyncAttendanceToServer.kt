package com.digitaledu.selfieattendance.view

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.api.ApiClient
import com.digitaledu.selfieattendance.api.ApiService
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.Attendance
import com.digitaledu.selfieattendance.utility.CheckNetworkAndInternetUtils
import com.digitaledu.selfieattendance.utility.DatabaseCleanupUtils
import com.digitaledu.selfieattendance.utility.AttendanceInstituteValidator
import com.digitaledu.selfieattendance.utility.AttendanceSyncMerger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response


class SyncAttendanceToServer : AppCompatActivity(){

    private lateinit var db: AppDatabase
    private lateinit var apiService: ApiService
    private lateinit var sharedPreferences: SharedPreferences

   // private val hash = "trr36pdthb9xbhcppyqkgbpkq"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Layout should contain a "Sync" button (e.g. R.layout.activity_sync_attendance)
        setContentView(R.layout.activity_sync_attendance)

        db = AppDatabase.Companion.getDatabase(this)
        sharedPreferences = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
        val hash= sharedPreferences.getString("hash", null)

        val baseUrl = sharedPreferences.getString("baseUrl", "https://testvps.digitaledu.in/") ?: ""
     //   val hash = sharedPreferences.getString("HASH_KEY", null)

        apiService = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)

        findViewById<Button>(R.id.btnSyncNow).setOnClickListener {
            syncPendingAttendance()
        }
    }

    private fun syncPendingAttendance() {
        val progressBar = findViewById<ProgressBar>(R.id.progressSync)
        val statusText = findViewById<TextView>(R.id.tvSyncStatus)

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.VISIBLE
                statusText.text = "Checking network..."
            }

            // Step 1: Check network
            val hasNetwork = CheckNetworkAndInternetUtils.isNetworkAvailable(this@SyncAttendanceToServer)
            val hasInternet = CheckNetworkAndInternetUtils.hasInternetAccess()

            if (!hasNetwork || !hasInternet) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusText.text = "No internet connection."
                    Toast.makeText(
                        this@SyncAttendanceToServer,
                        "Please connect to Wi-Fi or Mobile data.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                statusText.text = "Fetching pending sessions..."
            }

            try {
                //  Only get attendance with syncStatus = "pending"
                val pendingList = db.attendanceDao().getPendingAttendancesByStatus("pending")

                if (pendingList.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        statusText.text = "No pending attendance to sync."
                    }
                    return@launch
                }

                // Group by sessionId
                val groupedBySession = pendingList.groupBy { it.sessionId }
                val totalSessions = groupedBySession.size
                var currentSession = 1
                var allSessionsSynced = true

                //  Sync each session one by one
                for ((sessionId, sessionAttendances) in groupedBySession) {

                    // Double-check session sync status before sending
                    val session = db.sessionDao().getSessionById(sessionId)
                    if (session != null && session.syncStatus == "complete") {
                        Log.d("SYNC_SESSION", "Skipping already synced session $sessionId")
                        continue
                    }

                    val consistencyError = AttendanceInstituteValidator.validate(
                        sessionInstituteId = session?.instId,
                        attendanceInstituteIds = sessionAttendances.map { it.instId }
                    )
                    if (consistencyError != null) {
                        allSessionsSynced = false
                        Log.e(
                            "SYNC_SESSION",
                            "Blocked session $sessionId: $consistencyError"
                        )
                        withContext(Dispatchers.Main) {
                            statusText.text =
                                "Session $currentSession has inconsistent institute data."
                        }
                        currentSession++
                        continue
                    }


                    withContext(Dispatchers.Main) {
                        statusText.text = "Syncing session $currentSession of $totalSessions..."
                    }

                    val mergeOutcome = AttendanceSyncMerger.fetchAndMerge(
                        context = this@SyncAttendanceToServer,
                        localAttendanceList = sessionAttendances
                    )
                    if (mergeOutcome.hasUnavailableSelection) {
                        allSessionsSynced = false
                        withContext(Dispatchers.Main) {
                            statusText.text = "Could not check server attendance for session $currentSession; kept pending."
                        }
                        currentSession++
                        continue
                    }
                    val mergedAttendances = mergeOutcome.attendance

                    val attArray = JSONArray()
                    for (att in mergedAttendances) {
                        attArray.put(mapAttendanceToApiFormat(att))
                    }

                    val loggedStaffId = sharedPreferences.getString("loggedStaffId", null)
                    val requestBodyJson = JSONObject().apply {
                        put("attParamDataObj", JSONObject().apply {
                            put("attDataArr", attArray)
                            put("attAttachmentArr", JSONArray())
                            put("attendanceMethod", "periodDayWiseAttendance")
                            put("loggedInUsrId", loggedStaffId)
                        })
                    }
                    Log.d("SYNC_REQUEST_server", requestBodyJson.toString())


                    val jsonString = requestBodyJson.toString()
                    val response = sendToServer(jsonString)

                    if (response.isSuccessful && response.body() != null) {
                        val bodyString = response.body()!!.string()
                        val json = JSONObject(bodyString)
                        val collection = json.optJSONObject("collection")
                        val responseObj = collection?.optJSONObject("response")
                        val apiStatus = responseObj?.optString("status", "FAILED") ?: "FAILED"

                        if (apiStatus.equals("SUCCESS", ignoreCase = true)) {
                            db.attendanceDao().updateSyncStatusBySession(sessionId, "complete")
                            db.sessionDao().updateSessionSyncStatusToComplete(sessionId, "complete")

                            withContext(Dispatchers.Main) {
                                statusText.text = " Session $currentSession synced successfully!"
                            }
                            Log.d("SYNC_SESSION", "Session $sessionId synced OK")
                        } else {
                            allSessionsSynced = false
                            withContext(Dispatchers.Main) {
                                statusText.text = "⚠ Session $currentSession failed to sync!"
                            }
                            Log.e("SYNC_SESSION", "Session $sessionId failed!")
                        }
                    } else {
                        allSessionsSynced = false
                        withContext(Dispatchers.Main) {
                            statusText.text = " Network error for session $currentSession"
                        }
                    }

                    currentSession++
                    kotlinx.coroutines.delay(1000) // Optional: short delay between sessions
                }

                // 🧹 Cleanup
                DatabaseCleanupUtils.deleteSyncedAttendances(this@SyncAttendanceToServer)
                DatabaseCleanupUtils.deleteSyncedSessions(this@SyncAttendanceToServer)

                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusText.text = if (allSessionsSynced) {
                        " All sessions synced successfully!"
                    } else {
                        "Some sessions remain pending. Please try again."
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    statusText.text = "❌ Error: ${e.message}"
                }
            }
        }
    }

    private suspend fun sendToServer(dataParam: String): Response<ResponseBody> {
        val mediaType = MediaType.parse("application/json; charset=utf-8")
        val requestBody = RequestBody.create(mediaType, dataParam)

        return apiService.postAttendanceSync(
            r = "api/v1/Att/ManageMarkingGlobalAtt",
            requestBody = requestBody
        )
    }

    private suspend fun mapAttendanceToApiFormat(att: Attendance): JSONObject {

        val date=att.date
        val startTime=att.startTime
        val endtime=att.endTime
        val dataStartTime="$date $startTime:00"

        val dataEndTime="$date $endtime:00"

        val classShort = db.classDao().getClassById(att.classId)?.classShortName ?: ""

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
            put("status", "A")
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
            put("toAddCoLecturerCpIds", att.attCoLectureCpIds ?: "")
            put("spoofing_percentage", att.spoofingPercentage ?: "")
            put("status", "A")
        }
    }
}
