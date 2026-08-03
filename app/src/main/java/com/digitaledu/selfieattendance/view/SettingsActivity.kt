package com.digitaledu.selfieattendance.view

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.Attendance
import com.digitaledu.selfieattendance.api.ApiClient
import com.digitaledu.selfieattendance.api.ApiService
import com.digitaledu.selfieattendance.utility.CheckNetworkAndInternetUtils
import com.digitaledu.selfieattendance.utility.DatabaseCleanupUtils
import com.digitaledu.selfieattendance.utility.AttendanceInstituteValidator
import com.digitaledu.selfieattendance.utility.AttendanceSyncMerger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener {
            finish()
        }

        // Initialize real app version
        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.0"
        }
        findViewById<TextView>(R.id.tvVersionSettings).text = "Version v$appVersion"

        // Initialize real used login and details
        val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
        val username = prefs.getString("username", "Admin User") ?: "Admin User"
        val baseUrl = prefs.getString("baseUrl", "") ?: ""
        val loggedStaffId = prefs.getString("loggedStaffId", "") ?: ""

        val tvProfileName = findViewById<TextView>(R.id.tvProfileName)
        val tvProfileEmail = findViewById<TextView>(R.id.tvProfileEmail)

        tvProfileName.text = username
        tvProfileEmail.text = baseUrl

        // Resolve staffName from DB if available
        if (loggedStaffId.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@SettingsActivity)
                val teacher = db.teachersDao().getTeacherById(loggedStaffId)
                if (teacher != null && teacher.staffName.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        tvProfileName.text = teacher.staffName
                        tvProfileEmail.text = "$username | $baseUrl"
                    }
                }
            }
        }

        findViewById<LinearLayout>(R.id.btnPortalConfig).setOnClickListener {
            val intent = Intent(this, SelectInstituteActivity::class.java)
            startActivity(intent)
        }

        findViewById<ImageView>(R.id.btnEditProfile).setOnClickListener {
            Toast.makeText(this, "Profile editing is managed by Administrator", Toast.LENGTH_LONG).show()
        }

        findViewById<LinearLayout>(R.id.btnLogout).setOnClickListener {
            showLogoutDialog()
        }
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Yes") { _, _ ->
                checkAndSyncBeforeLogout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAndSyncBeforeLogout() {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("Checking pending data...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@SettingsActivity)
            val pendingList = db.attendanceDao().getPendingAttendancesByStatus("pending")

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (pendingList.isEmpty()) {
                    proceedWithLogout(true)
                } else {
                    showSyncWarningDialog(pendingList)
                }
            }
        }
    }

    private fun showSyncWarningDialog(pendingList: List<Attendance>) {
        AlertDialog.Builder(this)
            .setTitle("Pending Attendance Detected")
            .setMessage("You have ${pendingList.size} attendance records that are not synced to the server. Would you like to sync them now before logging out?\n\nWarning: Logging out without syncing will permanently delete these local attendance records.")
            .setPositiveButton("Sync & Logout") { _, _ ->
                syncAndThenLogout(pendingList)
            }
            .setNegativeButton("Logout Anyway (Delete Data)") { _, _ ->
                proceedWithLogout(true)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun syncAndThenLogout(pendingList: List<Attendance>) {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("Syncing attendance to server... Please wait...")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val hasNetwork = CheckNetworkAndInternetUtils.isNetworkAvailable(this@SettingsActivity)
            val hasInternet = CheckNetworkAndInternetUtils.hasInternetAccess()

            if (!hasNetwork || !hasInternet) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    showSyncErrorDialog("No internet connection available. Please connect to the internet to sync and logout, or logout anyway.", pendingList)
                }
                return@launch
            }

            try {
                val db = AppDatabase.getDatabase(this@SettingsActivity)
                val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
                val baseUrl = prefs.getString("baseUrl", "") ?: ""
                val hash = prefs.getString("hash", "") ?: ""
                val loggedStaffId = prefs.getString("loggedStaffId", "") ?: ""

                val normalizedBaseUrl = if (baseUrl.endsWith("/")) {
                    baseUrl.removeSuffix("/") + "///"
                } else {
                    "$baseUrl///"
                }

                val apiService = ApiClient.getClient(normalizedBaseUrl, hash).create(ApiService::class.java)
                val groupedBySession = pendingList.groupBy { it.sessionId }
                var allSuccess = true

                for ((sessionId, sessionAttendances) in groupedBySession) {
                    val session = db.sessionDao().getSessionById(sessionId)
                    val consistencyError = AttendanceInstituteValidator.validate(
                        session?.instId,
                        sessionAttendances.map { it.instId }
                    )
                    if (consistencyError != null) {
                        Log.e(
                            "LOGOUT_SYNC",
                            "Blocked session $sessionId: $consistencyError"
                        )
                        allSuccess = false
                        continue
                    }

                    val mergeOutcome = AttendanceSyncMerger.fetchAndMerge(
                        context = this@SettingsActivity,
                        localAttendanceList = sessionAttendances
                    )
                    if (mergeOutcome.hasUnavailableSelection) {
                        Log.w("LOGOUT_SYNC", "Server attendance could not be checked for $sessionId")
                        allSuccess = false
                        continue
                    }
                    val mergedAttendances = mergeOutcome.attendance

                    val attArray = JSONArray()
                    for (att in mergedAttendances) {
                        val date = att.date
                        val startTime = att.startTime
                        val endTime = att.endTime
                        val dataStartTime = "$date $startTime:00"
                        val dataEndTime = "$date $endTime:00"

                        val classShort = db.classDao().getClassById(att.classId)?.classShortName ?: ""

                        val attCode = att.status
                        val instId = att.instId ?: ""
                        val codeEntity = db.attendanceCodeDao().getByCodeAndSchool(attCode, instId)
                            ?: db.attendanceCodeDao().getByCode(attCode)
                        val attCodeId = codeEntity?.atcId ?: when (attCode) {
                            "L" -> "4"
                            "E" -> "3"
                            "A" -> "2"
                            else -> "1"
                        }
                        val attCodeLngName = codeEntity?.atcLongName ?: when (attCode) {
                            "L" -> "late"
                            "E" -> "exempted"
                            "A" -> "absent"
                            else -> "present"
                        }


                        val attJson = JSONObject().apply {
                            put("studentId", att.studentId)
                            put("instId", att.instId)
                            put("instShortName", att.instShortName ?: "")
                            put("academicYear", att.academicYear)
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
                            put("studentClass", classShort)
                            put("attCodetitle", "present")
                            put("courseSelectionMode", "")
                            put("stfId", att.teacherId)
                            put("stfFML", "")
                            put("studId", att.studentId)
                            put("studfFML", "")
                            put("studfLFM", "")
                            put("studentName", att.studentName)
                            put("studAltId", att.atteId)
                            put("studRollNo", "")
                            put("int_rollNo", "")
                            put("attCycleId", "")
                            put("attSessionId", att.sessionId)
                            put("attSchoolPeriodId", att.attSchoolPeriodId)
                            put("attSchoolPeriodTitle", "")
                            put("attSessionStartDateTime", dataStartTime)
                            put("attSessionEndDateTime", dataEndTime)
                            put("attCapturingIntervalDateTime", "")
                            put("attCapturingIntervalInSec", "")
                            put("attCapturingCycleState", "")
                            put("attCategory", "Regular")
                            put("studAttComment", "")
                            put("attSessionStudId", "")
                            put("attCodeId", attCodeId)
                            put("attCodeLngName", attCodeLngName)
                            put("attCode", attCode)
                            put("studAttStartDateTime", dataStartTime)
                            put("studAttEndDateTime", dataEndTime)
                            put("studAttTotalDuration", "")
                            put("atsaId", "")
                            put("atsaIsProxy", "")
                            put("atsaDistanceDeltaInMeter", "")
                            put("isSelfUsrAttMarked", "")
                            put("attCoLectureCpIds", "")
                            put("toRemoveCoLecturerCpIds", "")
                            put("toAddCoLecturerCpIds", "")
                            put("status", "A")
                        }
                        attArray.put(attJson)
                    }

                    val requestBodyJson = JSONObject().apply {
                        put("attParamDataObj", JSONObject().apply {
                            put("attDataArr", attArray)
                            put("attAttachmentArr", JSONArray())
                            put("attendanceMethod", "periodDayWiseAttendance")
                            put("loggedInUsrId", loggedStaffId)
                        })
                    }

                    val mediaType = okhttp3.MediaType.parse("application/json; charset=utf-8")
                    val requestBody = okhttp3.RequestBody.create(mediaType, requestBodyJson.toString())

                    val response = apiService.postAttendanceSync(
                        r = "api/v1/Att/ManageMarkingGlobalAtt",
                        requestBody = requestBody
                    )

                    if (response.isSuccessful && response.body() != null) {
                        val json = JSONObject(response.body()!!.string())
                        val status = json.optJSONObject("collection")
                            ?.optJSONObject("response")
                            ?.optString("status", "FAILED") ?: "FAILED"

                        if (status.equals("SUCCESS", ignoreCase = true)) {
                            db.attendanceDao().updateSyncStatusBySession(sessionId, "complete")
                            db.sessionDao().updateSessionSyncStatusToComplete(sessionId, "complete")
                        } else {
                            allSuccess = false
                        }
                    } else {
                        allSuccess = false
                    }
                }

                DatabaseCleanupUtils.deleteSyncedAttendances(this@SettingsActivity)
                DatabaseCleanupUtils.deleteSyncedSessions(this@SettingsActivity)

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (allSuccess) {
                        Toast.makeText(this@SettingsActivity, "Sync completed successfully!", Toast.LENGTH_SHORT).show()
                        proceedWithLogout(true)
                    } else {
                        showSyncErrorDialog("Some attendance records failed to sync. Please try again or logout anyway.", pendingList)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    showSyncErrorDialog("Error syncing data: ${e.message}", pendingList)
                }
            }
        }
    }

    private fun showSyncErrorDialog(errorMessage: String, pendingList: List<Attendance>) {
        AlertDialog.Builder(this)
            .setTitle("Sync Failed")
            .setMessage(errorMessage)
            .setPositiveButton("Retry Sync") { _, _ ->
                syncAndThenLogout(pendingList)
            }
            .setNegativeButton("Logout Anyway (Delete Data)") { _, _ ->
                proceedWithLogout(true)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun proceedWithLogout(clearDb: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (clearDb) {
                try {
                    val db = AppDatabase.getDatabase(this@SettingsActivity)
                    db.clearAllTables()
                    Log.d("LOGOUT", "Local database cleared completely.")
                } catch (e: Exception) {
                    Log.e("LOGOUT", "Error clearing local database: ${e.message}", e)
                }
            }

            getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
                .edit().clear().apply()

            getSharedPreferences("APP_STATE", Context.MODE_PRIVATE)
                .edit().clear().apply()

            getSharedPreferences("AttendancePrefs", Context.MODE_PRIVATE)
                .edit().clear().apply()

            getSharedPreferences("SyncPrefs", Context.MODE_PRIVATE)
                .edit().clear().apply()

            withContext(Dispatchers.Main) {
                Toast.makeText(this@SettingsActivity, "Logged out and local data destroyed.", Toast.LENGTH_SHORT).show()
                val intent = Intent(this@SettingsActivity, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
        }
    }
}
