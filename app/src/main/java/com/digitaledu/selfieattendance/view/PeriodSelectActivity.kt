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
    private var enforcedManualPeriodSelection: String = "N"

    private val isLiveTimePeriodSelectionEnabled: Boolean
        get() = enforcedManualPeriodSelection.equals("Y", ignoreCase = true)

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

        // Enable back navigation to SubjectSelectActivity for course/subject correction
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBackToSubjectSelect()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        binding.btnBack.setOnClickListener {
            navigateBackToSubjectSelect()
        }

        loadPeriods()

        binding.btnSkipPeriod.text = "No Period Setup(Use Default Id 999)"
        // Hidden until the saved per-school selection mode has loaded.
        binding.btnSkipPeriod.visibility = View.GONE
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
            val selectedMap = adapter.getSelectedPeriodMap() // Map<instId, spId>
            Log.i(AttendanceSyncMerger.FLOW_TAG, "PERIOD_CONTINUE_CLICK selectedMap=$selectedMap")
            if (selectedMap.isEmpty()) {
                Log.w(AttendanceSyncMerger.FLOW_TAG, "PERIOD_CONTINUE_STOPPED no period selected")
                Toast.makeText(this, "Please select at least one period, or tap Skip", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val allPeriods = db.schoolPeriodDao().getAll()
                val selectedPeriods = selectedMap.mapNotNull { (instId, spId) ->
                    allPeriods.firstOrNull { it.spId == spId }
                }

                if (selectedPeriods.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@PeriodSelectActivity, "Selected period not found", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val firstSelected = selectedPeriods.first()
                Log.d(TAG, "Teacher selected ${selectedPeriods.size} period(s): ${selectedPeriods.map { it.spTitle }}")

                selectedMap.forEach { (instId, spId) ->
                    val periodObj = allPeriods.firstOrNull { it.spId == spId } ?: return@forEach
                    val reportPeriodTitle = AttendanceSyncMerger.canonicalReportPeriodTitle(periodObj.spTitle)
                    db.attendanceDao().updateAttendanceSchoolPeriodForInst(
                        sessionId = sessionId,
                        instId = instId,
                        spId = spId,
                        periodTitle = reportPeriodTitle
                    )
                }

                val session = db.sessionDao().getSessionById(sessionId)
                val sessionInstId = session?.instId ?: ""
                val primarySpId = selectedMap[sessionInstId] ?: firstSelected.spId
                db.sessionDao().updateSessionSchoolPeriodId(sessionId, primarySpId)

                val localAttendance = db.attendanceDao().getAttendanceBySessionId(sessionId)
                Log.i(
                    AttendanceSyncMerger.FLOW_TAG,
                    "LOCAL_ROWS_READY sessionId=$sessionId rows=${localAttendance.size}"
                )

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
                            selectedPeriod = firstSelected,
                            localAttendance = localAttendance,
                            mergeOutcome = mergeOutcome
                        )
                    }
                } else {
                    AttendanceSyncMerger.persistMergeOutcome(mergeOutcome, db)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@PeriodSelectActivity,
                            "Fresh attendance updated",
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

            val sessionInstId = session.instId
            val sessionDate = session.date
            autoAssignedSpId = session.attSchoolPeriodId

            val attendances = db.attendanceDao().getAttendancesForSession(sessionId)
            val instIdsInSession = attendances.map { it.instId.trim() }
                .distinct()
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf(sessionInstId) }

            // Fetch institute names map
            val instNamesMap = mutableMapOf<String, String>()
            for (currInstId in instIdsInSession) {
                val instName = db.instituteDao().getInstituteNameById(currInstId)
                if (!instName.isNullOrBlank()) {
                    instNamesMap[currInstId] = instName
                }
            }

            enforcedManualPeriodSelection = db.globalAttendanceConfigDao()
                .getBySchoolId(sessionInstId)
                ?.enforcedManualPeriodSelection
                ?: com.digitaledu.selfieattendance.utility.GlobalAttendanceConfigParser
                    .DEFAULT_MANUAL_PERIOD_SELECTION

            withContext(Dispatchers.Main) {
                binding.btnSkipPeriod.visibility = View.GONE
            }

            // Get all school periods for all institutes in this session
            val allPeriods = db.schoolPeriodDao().getAll().filter { it.instId in instIdsInSession }

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

            // Resolve per-institute auto period
            val resolvedInstPeriods = mutableMapOf<String, SchoolPeriod?>()
            val initialSelectedPeriods = mutableMapOf<String, String>()

            for (currInstId in instIdsInSession) {
                val instPeriods = allPeriods.filter { it.instId == currInstId }
                val resolved = SchoolPeriodTimeResolver.resolveAutoPeriod(instPeriods, session.startTime)
                resolvedInstPeriods[currInstId] = resolved

                val selectedSpId = resolved?.spId
                    ?: instPeriods.firstOrNull()?.spId
                    ?: ""

                if (selectedSpId.isNotEmpty()) {
                    initialSelectedPeriods[currInstId] = selectedSpId
                }

                if (resolved != null) {
                    db.attendanceDao().updateAttendanceSchoolPeriodForInst(
                        sessionId = sessionId,
                        instId = currInstId,
                        spId = resolved.spId,
                        periodTitle = AttendanceSyncMerger.canonicalReportPeriodTitle(resolved.spTitle)
                    )
                } else {
                    db.attendanceDao().updateAttendanceSchoolPeriodForInst(
                        sessionId = sessionId,
                        instId = currInstId,
                        spId = "999",
                        periodTitle = "Default / Extra Class"
                    )
                }
            }

            val primaryResolved = resolvedInstPeriods[sessionInstId]
                ?: resolvedInstPeriods.values.firstOrNull()
            autoAssignedSpId = primaryResolved?.spId ?: "999"
            if (isLiveTimePeriodSelectionEnabled) {
                db.sessionDao().updateSessionSchoolPeriodId(sessionId, autoAssignedSpId)
            }
            val livePeriod = primaryResolved

            if (isLiveTimePeriodSelectionEnabled && livePeriod == null) {
                withContext(Dispatchers.Main) {
                    binding.tvPeriodTitle.text = "Default School Period"
                    binding.tvAutoAssigned.text =
                        "No configured school periods found for institute."
                    binding.btnContinuePeriod.isEnabled = false
                    showDefaultPeriodWarning(
                        title = "No Period Setup Available",
                        message = "No school periods are configured for this institute.\n\n" +
                            "Attendance will be assigned to the default period (ID 999). " +
                            "Please contact the support team to review and setup school periods."
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

            withContext(Dispatchers.Main) {
                if (isLiveTimePeriodSelectionEnabled) {
                    binding.tvPeriodTitle.text = "School Period (Automatically Selected)"
                    binding.tvAutoAssigned.text =
                        "Attendance start: ${session.startTime} • ${livePeriod?.spTitle}\nManual period changes are disabled."
                    binding.btnContinuePeriod.isEnabled = true
                } else {
                    binding.tvAutoAssigned.text = "Please select period(s) for this session below."
                }

                adapter = PeriodSelectAdapter(
                    periodList = allPeriods,
                    instNamesMap = instNamesMap,
                    submittedPeriodsMap = submittedPeriodsMap,
                    initialSelectedPeriods = initialSelectedPeriods,
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

    private fun navigateBackToSubjectSelect() {
        val isMassBunk = intent.getBooleanExtra("IS_MASS_BUNK", false)
        val intent = Intent(this@PeriodSelectActivity, SubjectSelectActivity::class.java).apply {
            putExtra("SESSION_ID", sessionId)
            putExtra("TEACHER_ID", teacherId)
            putStringArrayListExtra("SELECTED_CLASSES", ArrayList(selectedClasses))
            putExtra("IS_MASS_BUNK", isMassBunk)
        }
        startActivity(intent)
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
