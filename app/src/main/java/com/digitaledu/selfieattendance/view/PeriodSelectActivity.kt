package com.digitaledu.selfieattendance.view

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.databinding.ActivityPeriodSelectBinding
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.SchoolPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.digitaledu.selfieattendance.api.ApiClient

class PeriodSelectActivity : ComponentActivity() {

    private lateinit var binding: ActivityPeriodSelectBinding
    private lateinit var db: AppDatabase
    private lateinit var sessionId: String
    private lateinit var teacherId: String
    private var selectedClasses: ArrayList<String> = arrayListOf()
    private var autoAssignedSpId: String = ""
    private lateinit var adapter: PeriodSelectAdapter

    companion object {
        private const val TAG = "PERIOD_SELECT"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPeriodSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)
        sessionId = intent.getStringExtra("SESSION_ID") ?: run {
            Toast.makeText(this, "Missing session ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        teacherId = intent.getStringExtra("TEACHER_ID") ?: ""
        selectedClasses = intent.getStringArrayListExtra("SELECTED_CLASSES") ?: arrayListOf()

        // Disable back button to prevent skipping this screen
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@PeriodSelectActivity, "Back disabled on this screen", Toast.LENGTH_SHORT).show()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        loadPeriods()

        binding.btnSkipPeriod.text = "No Period Setup(Use Default Id 999)"
        binding.btnSkipPeriod.setOnClickListener {
            Log.d(TAG, "Teacher selected No Period. Assigning default spId=999")
            lifecycleScope.launch(Dispatchers.IO) {
                db.sessionDao().updateSessionSchoolPeriodId(sessionId, "999")
                db.attendanceDao().updateAttendanceSchoolPeriodId(sessionId, "999")
                withContext(Dispatchers.Main) {
                    navigateToOverview()
                }
            }
        }

        // Continue button → override with selected periods + duplicate for multi
        binding.btnContinuePeriod.setOnClickListener {
            if (!::adapter.isInitialized) {
                return@setOnClickListener
            }
            val selected = adapter.getSelectedPeriodIds()
            if (selected.isEmpty()) {
                Toast.makeText(this, "Please select at least one period, or tap Skip", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val firstSpId = selected.first()
                Log.d(TAG, "Teacher selected ${selected.size} period(s): $selected")

                // 1) Update existing attendance rows with FIRST selected period
                db.sessionDao().updateSessionSchoolPeriodId(sessionId, firstSpId)
                db.attendanceDao().updateAttendanceSchoolPeriodId(sessionId, firstSpId)
                Log.d(TAG, "Updated existing rows with spId=$firstSpId")

                // 2) For each ADDITIONAL period, duplicate all attendance rows
                if (selected.size > 1) {
                    val originalRows = db.attendanceDao().getAttendanceBySessionId(sessionId)
                    Log.d(TAG, "Found ${originalRows.size} attendance rows to duplicate for ${selected.size - 1} extra period(s)")

                    for (i in 1 until selected.size) {
                        val extraSpId = selected[i]
                        for (origAtt in originalRows) {
                            val duplicated = origAtt.copy(
                                atteId = java.util.UUID.randomUUID().toString(),
                                attSchoolPeriodId = extraSpId
                            )
                            db.attendanceDao().insertAttendance(duplicated)
                        }
                        Log.d(TAG, "Duplicated ${originalRows.size} rows for spId=$extraSpId")
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@PeriodSelectActivity,
                        "Periods applied: ${selected.size} selected",
                        Toast.LENGTH_SHORT
                    ).show()

                    navigateToOverview()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadPeriods()
    }

    private fun loadPeriods() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Get the session to find instId and current auto-assigned spId
            val session = db.sessionDao().getSessionById(sessionId)
            if (session == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PeriodSelectActivity, "Session not found", Toast.LENGTH_SHORT).show()
                    finish()
                }
                return@launch
            }

            val instId = session.instId
            val sessionDate = session.date
            autoAssignedSpId = session.attSchoolPeriodId

            // Get all school periods for this institute
            val allPeriods = db.schoolPeriodDao().getAll().filter { it.instId == instId }

            if (allPeriods.isEmpty()) {
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@PeriodSelectActivity)
                        .setTitle("Setup Missing")
                        .setMessage("school period setup not done yet .please contact authority and setup..\n\ndo you want to procced")
                        .setCancelable(false)
                        .setPositiveButton("Yes") { dialog, _ ->
                            dialog.dismiss()
                            lifecycleScope.launch(Dispatchers.IO) {
                                db.sessionDao().updateSessionSchoolPeriodId(sessionId, "999")
                                db.attendanceDao().updateAttendanceSchoolPeriodId(sessionId, "999")
                                withContext(Dispatchers.Main) { navigateToOverview() }
                            }
                        }
                        .setNegativeButton("No") { dialog, _ ->
                            dialog.dismiss()
                            getSharedPreferences("APP_STATE", MODE_PRIVATE).edit().clear().apply()
                            getSharedPreferences("AttendancePrefs", MODE_PRIVATE).edit().clear().apply()
                            val intent = Intent(this@PeriodSelectActivity, AttendanceActivity::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            startActivity(intent)
                            finish()
                        }
                        .show()
                }
                return@launch
            }

            // Check submitted periods for selected classes
            val submittedPeriodsMap = mutableMapOf<String, SubmittedPeriodInfo>()
            val currentTeacherName = if (teacherId.isNotBlank()) db.teachersDao().getTeacherNameById(teacherId) ?: "" else ""

            for (period in allPeriods) {
                val existingAtts = db.attendanceDao().getAttendancesForPeriodAndDate(
                    spId = period.spId,
                    date = sessionDate,
                    classIds = selectedClasses,
                    currentSessionId = sessionId
                )

                if (existingAtts.isNotEmpty()) {
                    val tName = existingAtts.mapNotNull { it.teacherName }.firstOrNull { it.isNotBlank() }
                        ?: currentTeacherName.ifBlank { "Teacher" }

                    val cNames = existingAtts.mapNotNull { it.classShortName }.distinct().filter { it.isNotBlank() }
                        .joinToString(", ").ifBlank {
                            selectedClasses.mapNotNull { db.classDao().getClassById(it)?.classShortName }.joinToString(", ")
                        }

                    val sTitle = existingAtts.mapNotNull { it.subjectTitle }.distinct().filter { it.isNotBlank() }
                        .joinToString(", ").ifBlank { "Subject" }

                    val pCount = existingAtts.count { it.status == "P" }
                    val eCount = existingAtts.count { it.status == "E" }
                    val aCount = existingAtts.count { it.status == "A" }
                    val lCount = existingAtts.count { it.status == "L" }

                    submittedPeriodsMap[period.spId] = SubmittedPeriodInfo(
                        spId = period.spId,
                        spTitle = period.spTitle,
                        teacherName = tName,
                        classNames = cNames,
                        subjectTitle = sTitle,
                        presentCount = pCount,
                        exemptedCount = eCount,
                        absentCount = aCount,
                        lateCount = lCount
                    )
                }
            }

            // Also check server AttReport for multi-device locks
            val selectedInstId = session.instId
            fetchServerSubmittedPeriods(selectedInstId, sessionDate, selectedClasses, allPeriods, submittedPeriodsMap)

            withContext(Dispatchers.Main) {
                binding.tvAutoAssigned.text = "Default Period ID: 999 (Out of Period / Extra Class)"

                adapter = PeriodSelectAdapter(
                    periodList = allPeriods,
                    autoAssignedSpId = autoAssignedSpId,
                    submittedPeriodsMap = submittedPeriodsMap,
                    onPeriodCheckedChange = { spId, isChecked ->
                        Log.d(TAG, "Period $spId checked=$isChecked")
                    },
                    onSubmittedPeriodClick = { item, info ->
                        showSubmittedPeriodWarningDialog(item, info)
                    }
                )

                binding.recyclerViewPeriods.layoutManager = LinearLayoutManager(this@PeriodSelectActivity)
                binding.recyclerViewPeriods.adapter = adapter
            }
        }
    }

    private fun showSubmittedPeriodWarningDialog(item: SchoolPeriod, info: SubmittedPeriodInfo) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_submitted_period_warning, null)

        dialogView.findViewById<TextView>(R.id.tvDialogTitle).text = "Period ${info.spTitle} Already Taken Today!"
        dialogView.findViewById<TextView>(R.id.tvPeriodSubtitle).text = "Period: ${info.spTitle} (${item.spIstTime} - ${item.spEndTime})"
        dialogView.findViewById<TextView>(R.id.tvTeacherName).text = "👤 Teacher: ${info.teacherName}"
        dialogView.findViewById<TextView>(R.id.tvClassName).text = "🏫 Class: ${info.classNames}"
        dialogView.findViewById<TextView>(R.id.tvSubjectName).text = "📖 Subject: ${info.subjectTitle}"

        dialogView.findViewById<TextView>(R.id.tvPresentCount).text = info.presentCount.toString()
        dialogView.findViewById<TextView>(R.id.tvExemptedCount).text = info.exemptedCount.toString()
        dialogView.findViewById<TextView>(R.id.tvLateCount).text = info.lateCount.toString()
        dialogView.findViewById<TextView>(R.id.tvAbsentCount).text = info.absentCount.toString()

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialogView.findViewById<Button>(R.id.btnCancelDialog).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private suspend fun fetchServerSubmittedPeriods(
        instId: String,
        date: String,
        selectedClasses: List<String>,
        allPeriods: List<SchoolPeriod>,
        submittedPeriodsMap: MutableMap<String, SubmittedPeriodInfo>
    ) {
        try {
            val prefs = getSharedPreferences("AttendancePrefs", MODE_PRIVATE)
            val loginPrefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)

            val rawBaseUrl = prefs.getString("baseUrl", null)
                ?: loginPrefs.getString("baseUrl", null)
                ?: prefs.getString("BASE_URL", null)
                ?: ""

            val hash = prefs.getString("HASH", null)
                ?: prefs.getString("hash", null)
                ?: loginPrefs.getString("hash", null)
                ?: loginPrefs.getString("HASH", null)

            if (rawBaseUrl.isBlank()) {
                Log.w("PERIOD_LOCK_DEBUG", "Base URL is blank in SharedPreferences (AttendancePrefs & LoginPrefs), skipping server period report fetch.")
                return
            }

            val baseUrl = if (rawBaseUrl.endsWith("///")) rawBaseUrl else if (rawBaseUrl.endsWith("/")) rawBaseUrl.removeSuffix("/") + "///" else "$rawBaseUrl///"
            Log.d("PERIOD_LOCK_DEBUG", "Using baseUrl=$baseUrl for AttReport API")

            val apiService = ApiClient.getClient(baseUrl, hash).create(com.digitaledu.selfieattendance.api.ApiService::class.java)

            val savedInsts = db.instituteDao().getAll().map { it.id }.filter { it.isNotBlank() }
            val selectedSchoolIds = if (savedInsts.isNotEmpty()) savedInsts.joinToString(",") else if (instId.isNotBlank()) instId else "1"

            val dataParam = "{\"attParamDataObj\":{\"attReportType\":\"classLectureLandscapeReportDetails\",\"attReportTypeParamDataObj\":{\"schoolIds\":\"$selectedSchoolIds\",\"classType\":\"Academic\",\"courseSelectionMode\":\"AcademicProcessed\",\"frmDate\":\"$date\",\"toDate\":\"$date\",\"attCategory\":\"Regular\"}}}"

            Log.d("PERIOD_LOCK_DEBUG", "Calling AttReport API with schoolIds=$selectedSchoolIds, date=$date, param=$dataParam")

            val response = apiService.getAttendanceReport(data = dataParam)
            Log.d("PERIOD_LOCK_DEBUG", "AttReport Response Code=${response.code()}")

            if (response.isSuccessful && response.body() != null) {
                val jsonString = response.body()!!.string()
                Log.d("PERIOD_LOCK_DEBUG", "AttReport Response JSON=$jsonString")

                val json = JSONObject(jsonString)
                val collection = json.optJSONObject("collection")
                val responseObj = collection?.optJSONObject("response")
                val dataArray = responseObj?.optJSONArray("data") ?: JSONArray()

                val selectedClassObjs = selectedClasses.mapNotNull { db.classDao().getClassById(it) }
                val selectedClassNames = selectedClassObjs.map { it.classShortName.lowercase().trim() }
                Log.d("PERIOD_LOCK_DEBUG", "Selected classes to match: $selectedClassNames")

                for (i in 0 until dataArray.length()) {
                    val item = dataArray.getJSONObject(i)
                    val className = item.optString("Class", "").trim()
                    Log.d("PERIOD_LOCK_DEBUG", "Processing report item for Class=$className")

                    val isClassMatch = selectedClassNames.isEmpty() || selectedClassNames.any { name ->
                        isClassMatchExact(className, name)
                    }

                    if (!isClassMatch) {
                        Log.d("PERIOD_LOCK_DEBUG", "Class $className did not match $selectedClassNames, skipping")
                        continue
                    }

                    val keys = item.keys()
                    while (keys.hasNext()) {
                        val rawKey = keys.next()
                        if (rawKey.equals("Class", ignoreCase = true)) continue

                        val slotArray = item.optJSONArray(rawKey) ?: continue
                        var pCount = 0
                        var aCount = 0
                        var eCount = 0
                        var lCount = 0
                        var totalLectures = 0
                        var detailsStr = ""

                        for (j in 0 until slotArray.length()) {
                            val str = slotArray.getString(j)
                            if (str.startsWith("P:")) pCount = str.substringAfter("P:").trim().toIntOrNull() ?: 0
                            else if (str.startsWith("A:")) aCount = str.substringAfter("A:").trim().toIntOrNull() ?: 0
                            else if (str.startsWith("E:")) eCount = str.substringAfter("E:").trim().toIntOrNull() ?: 0
                            else if (str.startsWith("L:")) lCount = str.substringAfter("L:").trim().toIntOrNull() ?: 0
                            else if (str.startsWith("Total-Lectures:")) totalLectures = str.substringAfter("Total-Lectures:").trim().toIntOrNull() ?: 0
                            else if (!str.contains("%")) detailsStr = str
                        }

                        Log.d("PERIOD_LOCK_DEBUG", "Slot Key=$rawKey → P=$pCount, A=$aCount, E=$eCount, L=$lCount, TotalLec=$totalLectures, Details=$detailsStr")

                        if (totalLectures > 0 || (pCount + aCount + eCount + lCount) > 0) {
                            val teacherName = if (detailsStr.contains("[") && detailsStr.contains("]")) {
                                detailsStr.substringAfter("[").substringBefore("]")
                            } else "Teacher"

                            val subjectTitle = if (detailsStr.contains("[")) {
                                detailsStr.substringBefore("[").trim()
                            } else detailsStr.ifBlank { "Subject" }

                            val cleanKey = rawKey.replace("</br>", " ").replace("<br>", " ").replace("<br/>", " ")

                            var matchedPeriod = allPeriods.find { period ->
                                cleanKey.lowercase().contains(period.spTitle.lowercase()) ||
                                detailsStr.lowercase().contains(period.spTitle.lowercase())
                            }

                            if (matchedPeriod == null) {
                                val isMorning = cleanKey.lowercase().contains("morning") || cleanKey.contains("7:") || cleanKey.contains("8:") || cleanKey.contains("9:") || cleanKey.contains("10:") || cleanKey.contains("11:")
                                val isAfternoon = cleanKey.lowercase().contains("afternoon") || cleanKey.contains("12:") || cleanKey.contains("1:") || cleanKey.contains("2:") || cleanKey.contains("3:") || cleanKey.contains("4:") || cleanKey.contains("5:")

                                matchedPeriod = allPeriods.find { period ->
                                    val hour = period.spIstTime.substringBefore(":").toIntOrNull() ?: 9
                                    if (isMorning && hour < 12) true
                                    else if (isAfternoon && hour >= 12) true
                                    else false
                                } ?: allPeriods.firstOrNull()
                            }

                            if (matchedPeriod != null) {
                                Log.d("PERIOD_LOCK_DEBUG", "✅ Locked Period spId=${matchedPeriod.spId} (${matchedPeriod.spTitle}) from server report!")
                                submittedPeriodsMap[matchedPeriod.spId] = SubmittedPeriodInfo(
                                    spId = matchedPeriod.spId,
                                    spTitle = matchedPeriod.spTitle,
                                    teacherName = teacherName,
                                    classNames = className,
                                    subjectTitle = subjectTitle,
                                    presentCount = pCount,
                                    exemptedCount = eCount,
                                    absentCount = aCount,
                                    lateCount = lCount
                                )
                            }
                        }
                    }
                }
            } else {
                Log.e("PERIOD_LOCK_DEBUG", "AttReport response un-successful or null body: ${response.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            Log.e("PERIOD_LOCK_DEBUG", "Exception in fetchServerSubmittedPeriods: ${e.message}", e)
        }
    }

    private fun isClassMatchExact(serverName: String, localName: String): Boolean {
        val sName = serverName.substringBefore("#").trim().lowercase()
        val lName = localName.substringBefore("#").trim().lowercase()

        if (sName == lName) return true

        // If local name specifies a Part (e.g. "part 1", "part ii") but server is generic ("msc finance"), DO NOT match!
        if (lName.contains("part") && !sName.contains("part")) {
            return false
        }

        // If both contain part, ensure exact part matches
        if (sName.contains("part") && lName.contains("part")) {
            val sPart = sName.substringAfter("part").trim()
            val lPart = lName.substringAfter("part").trim()
            return sPart == lPart || sName == lName
        }

        return sName.contains(lName)
    }

    private fun navigateToOverview() {
        val isMassBunk = intent.getBooleanExtra("IS_MASS_BUNK", false)
        val intent = Intent(this@PeriodSelectActivity, AttendanceOverviewActivity::class.java).apply {
            putExtra("SESSION_ID", sessionId)
            putExtra("TEACHER_ID", teacherId)
            putStringArrayListExtra("SELECTED_CLASSES", selectedClasses)
            putExtra("IS_MASS_BUNK", isMassBunk)
        }
        startActivity(intent)
        getSharedPreferences("APP_STATE", MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("AttendancePrefs", MODE_PRIVATE).edit().clear().apply()
        finish()
    }

    override fun onPause() {
        super.onPause()
        getSharedPreferences("APP_STATE", MODE_PRIVATE).edit()
            .putString("CURRENT_SCREEN", "PERIOD_SELECT")
            .putString("SESSION_ID", sessionId)
            .apply()
    }
}
