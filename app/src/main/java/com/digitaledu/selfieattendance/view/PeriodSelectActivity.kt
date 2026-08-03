package com.digitaledu.selfieattendance.view

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.digitaledu.selfieattendance.databinding.ActivityPeriodSelectBinding
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.SchoolPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.digitaledu.selfieattendance.utility.AttendanceSyncMerger

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
                db.attendanceDao().updateAttendanceSchoolPeriod(
                    sessionId,
                    "999",
                    "Default / Extra Class"
                )
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
            Log.i(AttendanceSyncMerger.FLOW_TAG, "PERIOD_CONTINUE_CLICK selectedPeriodIds=$selected")
            if (selected.isEmpty()) {
                Log.w(AttendanceSyncMerger.FLOW_TAG, "PERIOD_CONTINUE_STOPPED no period selected")
                Toast.makeText(this, "Please select at least one period, or tap Skip", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val selectedSpId = selected.single()
                val selectedPeriod = db.schoolPeriodDao().getAll().firstOrNull { it.spId == selectedSpId }
                if (selectedPeriod == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PeriodSelectActivity, "Selected period not found", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                Log.d(TAG, "Teacher selected period ${selectedPeriod.spTitle} ($selectedSpId)")
                val reportPeriodTitle = AttendanceSyncMerger.canonicalReportPeriodTitle(
                    selectedPeriod.spTitle
                )
                Log.i(
                    AttendanceSyncMerger.FLOW_TAG,
                    "PERIOD_SELECTED spId=$selectedSpId title='${selectedPeriod.spTitle}' " +
                        "reportTitle='$reportPeriodTitle' internetFetchStarting=true"
                )

                db.sessionDao().updateSessionSchoolPeriodId(sessionId, selectedSpId)
                db.attendanceDao().updateAttendanceSchoolPeriod(
                    sessionId,
                    selectedSpId,
                    reportPeriodTitle
                )

                val localAttendance = db.attendanceDao().getAttendanceBySessionId(sessionId)
                Log.i(
                    AttendanceSyncMerger.FLOW_TAG,
                    "LOCAL_ROWS_READY sessionId=$sessionId rows=${localAttendance.size}"
                )
                val mergeOutcome = AttendanceSyncMerger.fetchMergeAndPersist(
                    context = this@PeriodSelectActivity,
                    localAttendanceList = localAttendance,
                    db = db
                )
                Log.i(
                    AttendanceSyncMerger.FLOW_TAG,
                    "PERIOD_FETCH_FINISHED unavailable=${mergeOutcome.hasUnavailableSelection} " +
                        "existingServerAttendance=${mergeOutcome.hadExistingServerAttendance} " +
                        "finalRows=${mergeOutcome.attendance.size}"
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@PeriodSelectActivity,
                        if (mergeOutcome.hasUnavailableSelection) {
                            "Server could not be checked. Attendance is saved locally."
                        } else if (mergeOutcome.hadExistingServerAttendance) {
                            "Existing attendance loaded and merged"
                        } else {
                            "Fresh attendance for ${selectedPeriod.spTitle}"
                        },
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
                                db.attendanceDao().updateAttendanceSchoolPeriod(
                                    sessionId,
                                    "999",
                                    "Default / Extra Class"
                                )
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

            // Check submitted periods for selected classes (for display label only, not blocking)
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

            // No server period lock check — periods are never blocked

            withContext(Dispatchers.Main) {
                binding.tvAutoAssigned.text = "Default Period ID: 999 (Out of Period / Extra Class)"

                adapter = PeriodSelectAdapter(
                    periodList = allPeriods,
                    autoAssignedSpId = autoAssignedSpId,
                    submittedPeriodsMap = submittedPeriodsMap,
                    onPeriodCheckedChange = { spId, isChecked ->
                        Log.d(TAG, "Period $spId checked=$isChecked")
                    }
                )

                binding.recyclerViewPeriods.layoutManager = LinearLayoutManager(this@PeriodSelectActivity)
                binding.recyclerViewPeriods.adapter = adapter
            }
        }
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
