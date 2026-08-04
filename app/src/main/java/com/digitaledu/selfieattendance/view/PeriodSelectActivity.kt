package com.digitaledu.selfieattendance.view

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import android.view.View
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
import com.digitaledu.selfieattendance.utility.SchoolPeriodTimeResolver

class PeriodSelectActivity : ComponentActivity() {

    private lateinit var binding: ActivityPeriodSelectBinding
    private lateinit var db: AppDatabase
    private lateinit var sessionId: String
    private lateinit var teacherId: String
    private var selectedClasses: ArrayList<String> = arrayListOf()
    private var autoAssignedSpId: String = ""
    private lateinit var adapter: PeriodSelectAdapter
    private var isDefaultPeriodWarningShowing = false

    private val isLiveTimePeriodSelectionEnabled: Boolean
        get() = AttendanceActivity.enforcedManualPeriodSelection
            .equals("Y", ignoreCase = true)

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
        binding.btnSkipPeriod.visibility =
            if (isLiveTimePeriodSelectionEnabled) View.GONE else View.VISIBLE
        binding.btnSkipPeriod.setOnClickListener {
            if (isLiveTimePeriodSelectionEnabled) return@setOnClickListener
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
                // Do not persist an existing server baseline until the teacher confirms
                // that this submitted period should be opened for update/edit.
                val mergeOutcome = AttendanceSyncMerger.fetchAndMerge(
                    context = this@PeriodSelectActivity,
                    localAttendanceList = localAttendance
                )
                Log.i(
                    AttendanceSyncMerger.FLOW_TAG,
                    "PERIOD_FETCH_FINISHED unavailable=${mergeOutcome.hasUnavailableSelection} " +
                        "existingServerAttendance=${mergeOutcome.hadExistingServerAttendance} " +
                        "finalRows=${mergeOutcome.attendance.size}"
                )

                if (mergeOutcome.hasUnavailableSelection) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@PeriodSelectActivity,
                            "Server could not be checked. Attendance is saved locally.",
                            Toast.LENGTH_SHORT
                        ).show()
                        navigateToOverview()
                    }
                } else if (mergeOutcome.hadExistingServerAttendance) {
                    withContext(Dispatchers.Main) {
                        showExistingAttendanceDialog(
                            selectedPeriod = selectedPeriod,
                            localAttendance = localAttendance,
                            mergeOutcome = mergeOutcome
                        )
                    }
                } else {
                    AttendanceSyncMerger.persistMergeOutcome(mergeOutcome, db)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@PeriodSelectActivity,
                            "Fresh attendance for ${selectedPeriod.spTitle}",
                            Toast.LENGTH_SHORT
                        ).show()
                        navigateToOverview()
                    }
                }
            }
        }
    }

    private fun showExistingAttendanceDialog(
        selectedPeriod: SchoolPeriod,
        localAttendance: List<com.digitaledu.selfieattendance.db.entity.Attendance>,
        mergeOutcome: AttendanceSyncMerger.MergeOutcome
    ) {
        val classNames = localAttendance.mapNotNull { it.classShortName }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
            .ifBlank { selectedClasses.joinToString(", ") }
        val subjectNames = localAttendance.mapNotNull { it.subjectTitle }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")
            .ifBlank { "Selected subject" }

        val message = SpannableStringBuilder().apply {
            append("Attendance has already been submitted to the server.\n\n")
            appendBoldLabel("Class: ", classNames)
            append("\n")
            appendBoldLabel("Subject: ", subjectNames)
            append("\n")
            appendBoldLabel("Period: ", selectedPeriod.spTitle)
            append("\n\nDo you want to continue and edit/update this attendance?")
        }

        AlertDialog.Builder(this)
            .setTitle("Attendance Already Submitted")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Yes, Continue") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    AttendanceSyncMerger.persistMergeOutcome(mergeOutcome, db)
                    withContext(Dispatchers.Main) {
                        navigateToOverview()
                    }
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                // Server data has not been persisted, so another period can be selected safely.
                dialog.dismiss()
            }
            .show()
    }

    private fun SpannableStringBuilder.appendBoldLabel(label: String, value: String) {
        val start = length
        append(label)
        setSpan(
            StyleSpan(Typeface.BOLD),
            start,
            length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        append(value)
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
                    if (isLiveTimePeriodSelectionEnabled) {
                        showDefaultPeriodWarning(
                            title = "School Period Setup Missing",
                            message = "No school period setup is available for the current time.\n\n" +
                                "Attendance will be assigned to the default period (ID 999). " +
                                "Please contact the support team to configure the correct school periods."
                        )
                    } else {
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
                                returnToAttendance()
                            }
                            .show()
                    }
                }
                return@launch
            }

            val livePeriod = if (isLiveTimePeriodSelectionEnabled) {
                SchoolPeriodTimeResolver.findStrictPeriod(allPeriods, session.startTime)
            } else {
                null
            }

            if (isLiveTimePeriodSelectionEnabled && livePeriod == null) {
                withContext(Dispatchers.Main) {
                    binding.tvPeriodTitle.text = "Default School Period"
                    binding.tvAutoAssigned.text =
                        "No configured period contains attendance start time ${session.startTime}."
                    binding.btnContinuePeriod.isEnabled = false
                    showDefaultPeriodWarning(
                        title = "No Period Available at This Time",
                        message = "Attendance started at ${session.startTime}, but no school period is configured for this time.\n\n" +
                            "Attendance will be assigned to the default period (ID 999). " +
                            "Please contact the support team to review and correct the period setup."
                    )
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
                if (isLiveTimePeriodSelectionEnabled) {
                    binding.tvPeriodTitle.text = "School Period (Automatically Selected)"
                    binding.tvAutoAssigned.text =
                        "Attendance start: ${session.startTime} • ${livePeriod?.spTitle}\nManual period changes are disabled."
                    binding.btnContinuePeriod.isEnabled = true
                } else {
                    binding.tvAutoAssigned.text = "Default Period ID: 999 (Out of Period / Extra Class)"
                }

                adapter = PeriodSelectAdapter(
                    periodList = allPeriods,
                    autoAssignedSpId = autoAssignedSpId,
                    submittedPeriodsMap = submittedPeriodsMap,
                    initialSelectedPeriodId = livePeriod?.spId,
                    isSelectionLocked = isLiveTimePeriodSelectionEnabled,
                    onPeriodCheckedChange = { spId, isChecked ->
                        Log.d(TAG, "Period $spId checked=$isChecked")
                    }
                )

                binding.recyclerViewPeriods.layoutManager = LinearLayoutManager(this@PeriodSelectActivity)
                binding.recyclerViewPeriods.adapter = adapter
            }
        }
    }

    private fun showDefaultPeriodWarning(title: String, message: String) {
        if (isFinishing || isDestroyed || isDefaultPeriodWarningShowing) return
        isDefaultPeriodWarningShowing = true
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Proceed with Default") { dialog, _ ->
                dialog.dismiss()
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
            .create()
            .apply {
                setOnDismissListener { isDefaultPeriodWarningShowing = false }
                show()
            }
    }

    private fun returnToAttendance() {
        getSharedPreferences("APP_STATE", MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("AttendancePrefs", MODE_PRIVATE).edit().clear().apply()
        val intent = Intent(this, AttendanceActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
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
