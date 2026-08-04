package com.digitaledu.selfieattendance.view


import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.Attendance
import com.digitaledu.selfieattendance.db.entity.AttendanceIdGenerator
import com.digitaledu.selfieattendance.db.entity.Session
import com.digitaledu.selfieattendance.db.entity.Student
import kotlinx.coroutines.launch
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.String
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.digitaledu.selfieattendance.utility.AutoSyncWorker
import java.util.concurrent.TimeUnit
import android.os.SystemClock
import android.net.ConnectivityManager
import com.digitaledu.selfieattendance.db.entity.ActiveClassCycle
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.digitaledu.selfieattendance.utility.SchoolPeriodTimeResolver


class AttendanceActivity : AppCompatActivity() {

    private val TAG = "ATTANDANCE_ACTIVITY"

    private val CAMERA_PERMISSION_REQUEST_CODE = 1001

    companion object {
        private const val TAG_CLASSROOM = "CLASSROOM"
        private const val TAG_TEACHER = "TEACHER"
        private const val TAG_STUDENT = "STUDENT"

    }


    data class AttendanceCycle(
        val classroomId: String,
        var classroomName: String,
        var teacherId: String? = null,
        var teacherName: String? = null,
        var sessionId: String? = null,
        var startedAtMillis: Long = System.currentTimeMillis()
    )

    enum class StudentAttendanceResult {
        MARKED,
        ALREADY_MARKED,
        ACTIVE_IN_ANOTHER_CLASS,
        NO_ACTIVE_SESSION
    }

    private val activeSessions = mutableMapOf<Pair<String, String>, AttendanceCycle>()

    //  Track which teacher is currently active for student scans
    private var currentTeacherId: String? = null
    private var currentVisibleClassroomId: String? = null
    private var isTimeValid = false

    // --- Save App State Helper ---
    private fun updateAppState(screen: String) {
        val prefs = getSharedPreferences("APP_STATE", MODE_PRIVATE).edit()
        prefs.putString("CURRENT_SCREEN", screen)
        prefs.putString("SESSION_ID", activeSessions.values.firstOrNull()?.sessionId)
        prefs.putString("CLASSROOM_ID", currentVisibleClassroomId)
        prefs.putString("TEACHER_ID", currentTeacherId)
        prefs.putString("TEACHER_NAME", activeSessions.values.firstOrNull()?.teacherName)
        prefs.apply()
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_attendance)

        WorkManager.getInstance(this)
            .enqueue(OneTimeWorkRequest.from(AutoSyncWorker::class.java))

        // 🔹 Check camera permission on app start
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }


        // 🔹 Restore screen if app was killed mid-session (UI Thread)
        val statePrefs = getSharedPreferences("APP_STATE", MODE_PRIVATE)
        val currentScreen = statePrefs.getString("CURRENT_SCREEN", null)

        when (currentScreen) {
            "TEACHER_SCAN" -> {
                val classId = statePrefs.getString("CLASSROOM_ID", "1").let { if (it.isNullOrEmpty()) "1" else it }
                currentVisibleClassroomId = classId
                val frag = TeacherScanFragment.newInstance(classId)
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, frag, "TEACHER")
                    .commitAllowingStateLoss()
            }
            "STUDENT_SCAN" -> {
                val teacherName = statePrefs.getString("TEACHER_NAME", "") ?: ""
                val sessionId = statePrefs.getString("SESSION_ID", "") ?: ""
                val classId = statePrefs.getString("CLASSROOM_ID", "") ?: ""
                val teacherId = statePrefs.getString("TEACHER_ID", "") ?: ""

                if (classId.isNotEmpty()) currentVisibleClassroomId = classId
                if (teacherId.isNotEmpty()) currentTeacherId = teacherId

                showResumeSessionDialog(teacherName, sessionId, classId, teacherId)
            }
            else -> {
                startClassroomScanFragment()
            }
        }

        // 🔹 Check device time validity in the background
        checkDeviceTime()

        //if app exit then restore last cycle
        restoreLastCycleIfExists()

        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "HourlySync", ExistingPeriodicWorkPolicy.KEEP, request
        )


        // Restore pending sessions (endTime empty) into activeClasses
       restorePendingSessions()

    }

    // ----------------- Scan: Classroom -----------------
    private fun handleClassScan(classroomId: String, classroomName: String) {
        lifecycleScope.launch {
            // 🔹 Check if this classroom already has any active sessions (any teacher)
            val existingForClass = activeSessions.keys.any { it.first == classroomId }

            if (!existingForClass) {
                //  Start a new classroom cycle (no active session yet)
                val cycle = AttendanceCycle(classroomId, classroomName)
                // Note: No teacher yet, teacher will scan next.
                currentVisibleClassroomId = classroomId

                Toast.makeText(
                    this@AttendanceActivity,
                    "Classroom ready: Awaiting teacher face verification.",
                    Toast.LENGTH_SHORT
                ).show()

                // Go to teacher scan fragment
                val frag = TeacherScanFragment.newInstance(classroomId)
                val transaction = supportFragmentManager.beginTransaction()
                transaction.replace(R.id.fragment_container, frag, TAG_TEACHER)
                transaction.addToBackStack("TEACHER_SCAN")
                updateAppState("TEACHER_SCAN")

                transaction.commitAllowingStateLoss()
           }
            //            else {
//
//                Toast.makeText(
//                    this@AttendanceActivity,
//                    "Classroom $classroomName already assigned. Scan teacher card to continue.",
//                    Toast.LENGTH_SHORT
//                ).show()
//            }
        }
    }



// ----------------- Scan: Teacher -----------------
private fun handleTeacherScan(teacherId: String, teacherName: String) {
    lifecycleScope.launch {

        // Make sure a classroom is active first
        val classroomId = currentVisibleClassroomId
        if (classroomId.isNullOrEmpty()) {
            //  Show popup with teacher details
            val dialog = AlertDialog.Builder(this@AttendanceActivity)
                .setTitle("Teacher Card Details")
                .setMessage(
                            "NAME : $teacherName\n" +
                            "ID : $teacherId\n"
                )
                .setCancelable(false)
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                }
                .create()
            dialog.show()

            // 🕒 Auto-dismiss after 3 seconds (3000 ms)
            lifecycleScope.launch {
                delay(2_000)
                if (dialog.isShowing) {
                    dialog.dismiss()
                }
            }

            Toast.makeText(
                this@AttendanceActivity,
                "Please scan a classroom card first.",
                Toast.LENGTH_SHORT
            ).show()
            return@launch
        }

        val db = AppDatabase.getDatabase(this@AttendanceActivity)
        val key = Pair(classroomId, teacherId)

        // Check if this teacher already has an active session
        val activeCycle = activeSessions[key]
        val existingDbSession = getActiveSession(classroomId, teacherId)

        //  CASE 1: Teacher already has active session in memory or DB
        if (activeCycle != null || (existingDbSession != null && !existingDbSession.sessionId.isNullOrEmpty())) {
            // If teacher is currently visible → ask to close
            val resumedSessionId = activeCycle?.sessionId ?: existingDbSession?.sessionId ?: ""

            if (currentTeacherId == teacherId) {
                showEndClassDialog(classroomId)  // ask to close
                return@launch
            } else {
                // Resume their own paused session
                currentTeacherId = teacherId
                Toast.makeText(this@AttendanceActivity, "Resuming your session...", Toast.LENGTH_SHORT).show()
                val frag = StudentScanFragment.newInstance(teacherName,resumedSessionId)
                val transaction =supportFragmentManager.beginTransaction()
                transaction.replace(R.id.fragment_container, frag, TAG_STUDENT)
                updateAppState("STUDENT_SCAN")
                transaction.commitAllowingStateLoss()
                return@launch
            }
        }

        // CASE 2: No active session yet — start new one

        val isSwitchingTeacher = !currentTeacherId.isNullOrEmpty() && currentTeacherId != teacherId

        if (isSwitchingTeacher) {
            AlertDialog.Builder(this@AttendanceActivity)
                .setTitle("New Session Detected")
                .setMessage("Session has been started By\n $teacherName")
                .setCancelable(false)
                .setPositiveButton("Yes") { dialog, _ ->
                    dialog.dismiss()
                    chooseInstituteAndStartSession(teacherId, teacherName, classroomId)
                }
                .setNegativeButton("No") { dialog, _ ->
                    //  Just close dialog and stay on current activity
                    dialog.dismiss()
                }
                .show()
        } else {
            // First teacher — start directly without popup
            chooseInstituteAndStartSession(teacherId, teacherName, classroomId)
        }

    }
}

    private fun chooseInstituteAndStartSession(
        teacherId: String,
        teacherName: String,
        classId: String
    ) {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@AttendanceActivity)
            val selectedOnDevice = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
                .getString("selectedInstituteIds", "")
                .orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()

            val availableInstitutes = withContext(Dispatchers.IO) {
                db.teacherInstituteMapDao()
                    .getInstitutesForTeacher(teacherId)
                    .filter { it.id in selectedOnDevice }
                    .distinctBy { it.id }
            }

            when (availableInstitutes.size) {
                0 -> {
                    AlertDialog.Builder(this@AttendanceActivity)
                        .setTitle("Institute not available")
                        .setMessage(
                            "No institute assigned to $teacherName is available on this device. " +
                                "Please sync the latest institute data and try again."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }

                1 -> {
                    val institute = availableInstitutes.first()
                    Toast.makeText(
                        this@AttendanceActivity,
                        "Institute: ${institute.shortName}",
                        Toast.LENGTH_SHORT
                    ).show()
                    startNewTeacherSession(
                        teacherId,
                        teacherName,
                        classId,
                        institute.id
                    )
                }

                else -> {
                    var selectedIndex = -1
                    val labels = availableInstitutes.map { institute ->
                        val name = institute.title
                            ?.takeIf { it.isNotBlank() }
                            ?: institute.shortName
                        "$name (${institute.id})"
                    }.toTypedArray()

                    val dialog = AlertDialog.Builder(this@AttendanceActivity)
                        .setTitle("Choose institute")
                        .setSingleChoiceItems(labels, -1) { _, which ->
                            selectedIndex = which
                        }
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Continue", null)
                        .create()

                    dialog.setOnShowListener {
                        val continueButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        continueButton.isEnabled = false
                        dialog.listView.setOnItemClickListener { _, _, position, _ ->
                            selectedIndex = position
                            dialog.listView.setItemChecked(position, true)
                            continueButton.isEnabled = true
                        }
                        continueButton.setOnClickListener {
                            if (selectedIndex !in availableInstitutes.indices) {
                                return@setOnClickListener
                            }
                            val institute = availableInstitutes[selectedIndex]
                            dialog.dismiss()
                            startNewTeacherSession(
                                teacherId,
                                teacherName,
                                classId,
                                institute.id
                            )
                        }
                    }
                    dialog.show()
                }
            }
        }
    }

    private fun startNewTeacherSession(
        teacherId: String,
        teacherName: String,
        classId: String,
        selectedInstId: String
    ) {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@AttendanceActivity)
            val assignedInstituteIds = withContext(Dispatchers.IO) {
                db.teacherInstituteMapDao().getInstituteIdsForTeacher(teacherId)
            }
            if (selectedInstId.isBlank() || selectedInstId !in assignedInstituteIds) {
                Toast.makeText(
                    this@AttendanceActivity,
                    "Teacher is not assigned to the selected institute. Please sync and try again.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val periods = withContext(Dispatchers.IO) {
                db.schoolPeriodDao().getAll().filter { it.instId == selectedInstId }
            }
            val enforcedManualPeriodSelection = withContext(Dispatchers.IO) {
                db.globalAttendanceConfigDao()
                    .getBySchoolId(selectedInstId)
                    ?.enforcedManualPeriodSelection
                    ?: com.digitaledu.selfieattendance.utility.GlobalAttendanceConfigParser
                        .DEFAULT_MANUAL_PERIOD_SELECTION
            }

            val attendanceStartedAt = getEstimatedCurrentTime()
            val attendanceStartTime = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(attendanceStartedAt)
            val liveTimePeriodSelection =
                enforcedManualPeriodSelection.equals("Y", ignoreCase = true)
            val spId = if (liveTimePeriodSelection) {
                SchoolPeriodTimeResolver.findStrictPeriod(periods, attendanceStartTime)?.spId.orEmpty()
            } else {
                // Preserve the existing manual period selection starting state.
                "999"
            }

            Log.i(
                "PERIOD_ASSIGN",
                "mode=${if (liveTimePeriodSelection) "LIVE_LOCKED" else "MANUAL"} " +
                    "attendanceStart=$attendanceStartTime resolvedSpId='${spId}'"
            )
            proceedWithNewTeacherSession(
                teacherId,
                teacherName,
                classId,
                spId,
                selectedInstId,
                attendanceStartedAt
            )
        }
    }

    private fun proceedWithNewTeacherSession(
        teacherId: String,
        teacherName: String,
        classId: String,
        spId: String,
        selectedInstId: String,
        attendanceStartedAt: Date
    ) {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@AttendanceActivity)
            val sessionId = UUID.randomUUID().toString()
            val startTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(attendanceStartedAt)
            Log.d("SESSION_DEBUG", "Session start time: $startTime")
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(attendanceStartedAt)

            Log.d(
                "PERIOD_SAVE",
                "Institute=$selectedInstId Session Start=$startTime → spId=$spId"
            )

            val session = Session(
                sessionId = sessionId,
                classId = classId,
                teacherId = teacherId,
                subjectId = "",
                date = date,
                startTime = startTime,
                endTime = "",
                isMerged = 0,
                instId = selectedInstId,
                attSchoolPeriodId = spId,
                syncStatus = "pending",
                periodId = ""
            )
            Log.d("SESSION_DEBUG", "Session data log: $session")
            db.sessionDao().insertSession(session)

            val newCycle = AttendanceCycle(
                classroomId = classId,
                classroomName = classId,
                teacherId = teacherId,
                teacherName = teacherName,
                sessionId = sessionId
            )

            activeSessions[Pair(classId, teacherId)] = newCycle
            saveActiveSession(newCycle)
            currentTeacherId = teacherId

            val frag = StudentScanFragment.newInstance(teacherName, sessionId)
            val transaction = supportFragmentManager.beginTransaction()
            transaction.replace(R.id.fragment_container, frag, TAG_STUDENT)
            updateAppState("STUDENT_SCAN")
            transaction.commitAllowingStateLoss()
        }
    }



    // ----------------- Scan: Student -----------------
    private fun handleStudentScan(
        student: Student,
        onResult: (StudentAttendanceResult) -> Unit = {}
    ) {
        lifecycleScope.launch {

            // ✅ If no classroom started yet show student card details
            if (currentVisibleClassroomId == null) {

                val dialog =AlertDialog.Builder(this@AttendanceActivity)
                    .setTitle("Students Card Details")
                    .setMessage(
                        "NAME : ${student.studentName}\n" +
                        "ID   : ${student.studentId}\n"
                    )
                    .setCancelable(false)
                    .setPositiveButton("OK") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .create()
                dialog.show()

                // 🕒 Auto-dismiss after 3 seconds
                lifecycleScope.launch {
                    delay(2_000)
                    if (dialog.isShowing) {
                        dialog.dismiss()
                    }
                }

                Toast.makeText(
                    this@AttendanceActivity,
                    "Please scan classroom card Or Teacher card first.",
                    Toast.LENGTH_LONG
                ).show()

                Log.d(TAG, "Student scanned without classroom: ID=${student.studentId}, Name=${student.studentName}")
                onResult(StudentAttendanceResult.NO_ACTIVE_SESSION)
                return@launch
            }

            val classroomId = currentVisibleClassroomId ?: run {
                onResult(StudentAttendanceResult.NO_ACTIVE_SESSION)
                return@launch
            }
            val teacherId = currentTeacherId ?: run {
                Toast.makeText(this@AttendanceActivity, "Please scan teacher  first!", Toast.LENGTH_SHORT).show()
                onResult(StudentAttendanceResult.NO_ACTIVE_SESSION)
                return@launch
            }
            val cycle = activeSessions[Pair(classroomId, teacherId)] ?: run {
                Toast.makeText(this@AttendanceActivity, "No active session found for this teacher!", Toast.LENGTH_SHORT).show()
                onResult(StudentAttendanceResult.NO_ACTIVE_SESSION)
                return@launch
            }


            if (cycle.sessionId.isNullOrEmpty()) {
                Toast.makeText(this@AttendanceActivity, "Scan teacher first!", Toast.LENGTH_SHORT).show()
                onResult(StudentAttendanceResult.NO_ACTIVE_SESSION)
                return@launch
            }

            val db = AppDatabase.getDatabase(this@AttendanceActivity)
            // 🔹 Already marked in this session?
            val existing = db.attendanceDao().getAttendanceForStudentInSession(cycle.sessionId!!, student.studentId)
            if (existing != null) {
                Toast.makeText(this@AttendanceActivity, "${student.studentName} already marked!", Toast.LENGTH_SHORT).show()
                onResult(StudentAttendanceResult.ALREADY_MARKED)
                return@launch
            }


            // 🔹  Prevent student from joining another open session
            val currentSessionId = cycle.sessionId!!
            val alreadyActive = db.attendanceDao()
                .countActiveAttendancesForStudent(student.studentId, currentSessionId)

            if (alreadyActive > 0) {
                Toast.makeText(this@AttendanceActivity, "You are already marked present in another ongoing class.", Toast.LENGTH_LONG).show()
                onResult(StudentAttendanceResult.ACTIVE_IN_ANOTHER_CLASS)
                return@launch
            }




            val estimated = getEstimatedCurrentTime()
            val timeStamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(estimated)
            val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(estimated)
            val startTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(estimated)

            val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
           // val savedInstituteId = prefs.getString("selectedInstituteIds", "")
           // val savedInstituteName = prefs.getString("selectedInstituteNames", "")

            val sessionObj = db.sessionDao().getSessionById(cycle.sessionId!!)
            val inst_id = sessionObj?.instId ?: ""
            val attSchoolPeriodId= sessionObj?.attSchoolPeriodId ?: ""

            if (inst_id.isBlank()) {
                Toast.makeText(
                    this@AttendanceActivity,
                    "The active session has no institute. Attendance was not saved.",
                    Toast.LENGTH_LONG
                ).show()
                onResult(StudentAttendanceResult.NO_ACTIVE_SESSION)
                return@launch
            }

            if (student.instId.trim() != inst_id.trim()) {
                Toast.makeText(
                    this@AttendanceActivity,
                    "This student belongs to a different institute than the active session.",
                    Toast.LENGTH_LONG
                ).show()
                Log.w(
                    TAG,
                    "Blocked cross-institute attendance: session=${cycle.sessionId}, " +
                        "sessionInst=$inst_id, studentInst=${student.instId}"
                )
                onResult(StudentAttendanceResult.NO_ACTIVE_SESSION)
                return@launch
            }


            Log.d("SYNC_DEBUG_attandance", "Institute Id get: $inst_id")
            val instName = inst_id?.let { db.instituteDao().getInstituteNameById(it) } ?: ""
            val academicYear = inst_id?.let { db.instituteDao().getInstituteYearById(it) } ?: ""
            Log.d("SYNC_DEBUG_attandance", "Academic Year: $academicYear")
          Log.d("SYNC_DEBUG_attandance", "Institute Name: $instName")


            val attendance = Attendance(
                atteId = AttendanceIdGenerator.nextId(),
                sessionId = cycle.sessionId!!,
                studentId = student.studentId,
                studentName = student.studentName,
                classId = student.classId,
                status = "P",
                markedAt = timeStamp,
                syncStatus = "pending",
                instId = inst_id!!,
                instShortName = instName,
                date = currentDate,
                startTime = startTime,
                endTime = "",
                academicYear = academicYear,
                period = "",
                teacherId =cycle.teacherId!!,
                teacherName = cycle.teacherName!!,
                attSchoolPeriodId = attSchoolPeriodId,
                isFaceCaptured = true
            )


            Log.d("SYNC_DEBUG_attandance", "Attendance list details: $attendance")
            db.attendanceDao().insertAttendance(attendance)

            showUserMessage("Attendance marked for ${student.studentName}")

            saveCurrentCycle()
            val frag = supportFragmentManager.findFragmentByTag(TAG_STUDENT)
            if (frag is StudentScanFragment) frag.addStudentUI(student)
            onResult(StudentAttendanceResult.MARKED)

        }
    }



    // ----------------- End Class -----------------
    private fun showEndClassDialog(
        classroomId: String,
        onCancelled: () -> Unit = {}
    ) {
        val teacherId = currentTeacherId ?: return
        val cycle = activeSessions[Pair(classroomId, teacherId)] ?: return
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@AttendanceActivity)
            val presentCount = if (!cycle.sessionId.isNullOrEmpty())
                db.attendanceDao().getAttendancesForClass(cycle.sessionId!!, classroomId).size else 0

            val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(getEstimatedCurrentTime())

            AlertDialog.Builder(this@AttendanceActivity)
                .setTitle("Close Class: ${cycle.classroomId}")
                .setMessage("Would you like to end this class now?")
                .setPositiveButton("Yes") { _, _ ->
                    lifecycleScope.launch {
                        cycle.sessionId?.let { db.sessionDao().updateSessionEnd(it, currentTime) }
                        cycle.sessionId?.let{db.attendanceDao().updateAttendanceEndTime(it, currentTime)}

                        // remove from ActiveClassCycle table
                        removeActiveSession(classroomId, teacherId)
                        activeSessions.remove(Pair(classroomId, teacherId))


                        val broadcastIntent  = Intent("UPDATE_UNSUBMITTED_COUNT")
                        sendBroadcast(broadcastIntent )


                        Log.d("SESSION_END", "Session ${cycle.sessionId} closed at $currentTime")

                        //  Clear saved app state before starting new flow
                        val prefs1 = getSharedPreferences("APP_STATE", MODE_PRIVATE)
                        prefs1.edit().clear().apply()
                        val prefs2 = getSharedPreferences("AttendancePrefs", MODE_PRIVATE)
                        prefs2.edit().clear().apply()

                        val intent = Intent(this@AttendanceActivity, ClassSelectActivity::class.java)
                        intent.putExtra("SESSION_ID", cycle.sessionId)
                        intent.putExtra("TEACHER_ID", cycle.teacherId)
                        startActivity(intent)
                        finish()
                        activeSessions.remove(Pair(classroomId, teacherId))

                        currentVisibleClassroomId = null

                    }
                }
                .setNegativeButton("No") { dialog, _ ->
                    dialog.dismiss()
                    onCancelled()
                }
                .show()

            val prefs = getSharedPreferences("AttendancePrefs", MODE_PRIVATE)
            prefs.edit().clear().apply()

        }
    }

    // ---------------- Utilities ----------------
    fun startClassroomScanFragment() {
        val fragment = ClassroomScanFragment.newInstance()
        val transaction = supportFragmentManager.beginTransaction()
        transaction.replace(R.id.fragment_container, fragment, TAG_CLASSROOM)
        updateAppState("CLASSROOM_SCAN")
        transaction.commitAllowingStateLoss()
    }

    private fun showResumeSessionDialog(
        teacherName: String,
        sessionId: String,
        classId: String,
        teacherId: String
    ) {
        val displayName = if (teacherName.isNotBlank()) teacherName else "Teacher"
        AlertDialog.Builder(this)
            .setTitle("Active Session Detected")
            .setMessage("An active attendance session for $displayName is currently running.\n\nDo you want to continue capturing attendance or discard and cancel this session?")
            .setCancelable(false)
            .setPositiveButton("Continue Session") { dialog, _ ->
                dialog.dismiss()
                val frag = StudentScanFragment.newInstance(displayName, sessionId)
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, frag, TAG_STUDENT)
                    .commitAllowingStateLoss()
            }
            .setNegativeButton("Discard & Cancel Session") { dialog, _ ->
                dialog.dismiss()
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = AppDatabase.getDatabase(this@AttendanceActivity)
                        if (sessionId.isNotEmpty()) {
                            db.sessionDao().deleteSessionById(sessionId)
                            db.attendanceDao().deleteAttendanceForSession(sessionId)
                        }
                        if (classId.isNotEmpty()) {
                            db.activeClassCycleDao().deleteByClassroomId(classId)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error discarding session: ${e.message}", e)
                    }

                    withContext(Dispatchers.Main) {
                        if (classId.isNotEmpty() && teacherId.isNotEmpty()) {
                            activeSessions.remove(Pair(classId, teacherId))
                        }
                        currentTeacherId = null
                        currentVisibleClassroomId = null

                        val prefs1 = getSharedPreferences("APP_STATE", MODE_PRIVATE)
                        prefs1.edit().clear().apply()
                        val prefs2 = getSharedPreferences("AttendancePrefs", MODE_PRIVATE)
                        prefs2.edit().clear().apply()

                        Toast.makeText(this@AttendanceActivity, "Session discarded", Toast.LENGTH_SHORT).show()
                        startClassroomScanFragment()
                    }
                }
            }
            .show()
    }

    private fun checkDeviceTime() {
        if (!isNetworkAvailable()) {
            isTimeValid = true
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val connection = URL("https://google.com").openConnection().apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                }
                connection.connect()
                val networkTime = Date(connection.date)
                val deviceTime = Date(System.currentTimeMillis())

                val difference = kotlin.math.abs(deviceTime.time - networkTime.time)
                val diffMinutes = difference / (1000 * 60)

                Log.d("TIME_CHECK", "Device Time: $deviceTime")
                Log.d("TIME_CHECK", "Network Time: $networkTime")
                Log.d("TIME_CHECK", "Difference (minutes): $diffMinutes")

                if (diffMinutes > 2) {
                    isTimeValid = false
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) showTimeMismatchDialog()
                    }
                } else {
                    isTimeValid = true
                }
            } catch (e: Exception) {
                Log.e("TIME_CHECK", "Error checking time: ${e.message}")
                isTimeValid = true
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetworkInfo
        return net != null && net.isConnected
    }


    private fun showTimeMismatchDialog() {
        AlertDialog.Builder(this)
            .setTitle("Incorrect Time Detected")
            .setMessage("Your device time doesn't match the actual time.\nPlease correct it before proceeding.")
            .setCancelable(false)
            .setPositiveButton("Set Time") { _, _ ->
                startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
                finish()
            }
            .show()
    }


    private fun saveCurrentCycle() {
        val prefs = getSharedPreferences("AttendancePrefs", MODE_PRIVATE)
        val editor = prefs.edit()

        val classroomId = currentVisibleClassroomId
        val teacherId = currentTeacherId

        if (classroomId != null && teacherId != null) {
            val cycle = activeSessions[Pair(classroomId, teacherId)]
            if (cycle != null) {
                editor.putString("classroomId", cycle.classroomId)
                editor.putString("classroomName", cycle.classroomName)
                editor.putString("teacherId", cycle.teacherId)
                editor.putString("teacherName", cycle.teacherName)
                editor.putString("sessionId", cycle.sessionId)
                editor.apply()
                Log.d(TAG, "Saved current cycle: class=$classroomId teacher=$teacherId session=${cycle.sessionId}")
            }
        } else {
            Log.d(TAG, "No active classroom/teacher to save.")
        }
    }


    private fun restoreLastCycleIfExists() {
        val prefs = getSharedPreferences("AttendancePrefs", MODE_PRIVATE)
        val classroomId = prefs.getString("classroomId", null)
        val sessionId = prefs.getString("sessionId", null)
        val className = prefs.getString("classroomName", null)
        val teacherId = prefs.getString("teacherId", null)
        val teacherName = prefs.getString("teacherName", null)

        // 🔹 Check if valid data exists
        if (classroomId.isNullOrEmpty() || sessionId.isNullOrEmpty() || teacherId.isNullOrEmpty()) {
            Log.d(TAG, "No previous attendance cycle found to restore.")
            return
        }

        // 🔹 Recreate the previous attendance cycle object
        val cycle = AttendanceCycle(
            classroomId = classroomId,
            classroomName = className ?: "",
            teacherId = teacherId,
            teacherName = teacherName,
            sessionId = sessionId
        )

        // 🔹 Restore in memory
        activeSessions[Pair(classroomId, teacherId)] = cycle
        currentVisibleClassroomId = classroomId
        currentTeacherId = teacherId

        Log.d(TAG, "Restoring last cycle for class: $classroomId, teacher=$teacherId, session=$sessionId")

        // 🔹 Inflate ClassroomScanFragment (for resume UI)
        var classroomFragment = supportFragmentManager.findFragmentByTag("CLASSROOM") as? ClassroomScanFragment
        if (classroomFragment == null) {
            classroomFragment = ClassroomScanFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, classroomFragment, "CLASSROOM")
                .commitNow()
        } else {
            supportFragmentManager.executePendingTransactions()
        }

        // 🔹 Restore UI info
        val fragView = classroomFragment.view
        val layout = fragView?.findViewById<LinearLayout>(R.id.layoutResumeInfo)
        val tvClass = fragView?.findViewById<TextView>(R.id.tvResumeClass)
        val tvTeacher = fragView?.findViewById<TextView>(R.id.tvResumeTeacher)
        val tvStudents = fragView?.findViewById<TextView>(R.id.tvResumeStudents)

        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@AttendanceActivity)
                val studentCount = db.attendanceDao().getAttendancesForClass(sessionId, classroomId).size

                runOnUiThread {
                    layout?.visibility = View.VISIBLE
                    tvClass?.text = "Resumed Class: ${className ?: "-"}"
                    tvTeacher?.text = "Teacher: ${teacherName ?: "-"}"
                    tvStudents?.text = "Present Students: $studentCount"
                }

                Toast.makeText(
                    this@AttendanceActivity,
                    "Resumed Class- $teacherName",
                    Toast.LENGTH_LONG
                ).show()

            } catch (e: Exception) {
                Log.e(TAG, "Error restoring last cycle UI: ${e.message}", e)
            }
        }
    }


    private fun restorePendingSessions() {
        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@AttendanceActivity)
                val sessions = db.sessionDao().getAllSessions()

                // 🔹 Loop through all sessions that are still open (no endTime)
                sessions.filter { it.endTime.isNullOrEmpty() }.forEach { s ->
                    val classObj = db.classDao().getClassById(s.classId)
                    val classShort = classObj?.classShortName ?: s.classId
                    val teacherId = s.teacherId ?: return@forEach
                    val key = Pair(s.classId, teacherId)

                    val teacherName = db.teachersDao().getTeacherNameById(teacherId) ?: "Unknown"
                    if (!activeSessions.containsKey(key)) {
                        val restoredCycle = AttendanceCycle(
                            classroomId = s.classId,
                            classroomName = classShort,
                            teacherId = teacherId,
                            teacherName = teacherName,
                            sessionId = s.sessionId
                        )
                        activeSessions[key] = restoredCycle

                        // 🔹 Store in ActiveClassCycle table (DB persistence)
                        db.activeClassCycleDao().insert(
                            ActiveClassCycle(
                                classroomId = s.classId,
                                classroomName = classShort,
                                teacherId = teacherId,
                                teacherName = "",
                                sessionId = s.sessionId,
                                startedAtMillis = System.currentTimeMillis()
                            )
                        )

                        Log.d(TAG, "Restored pending session: ${s.sessionId} for class ${s.classId} (teacher $teacherId)")
                    }
                }

                // 🔹 Auto-resume first available class on UI
                if (activeSessions.isNotEmpty()) {
                    val first = activeSessions.values.first()
                    currentVisibleClassroomId = first.classroomId
                    currentTeacherId = first.teacherId

                    val frag = StudentScanFragment.newInstance(first.teacherName ?: "", first.sessionId ?: "")

                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, frag, "STUDENT")
                        .commitAllowingStateLoss()

                    Toast.makeText(
                        this@AttendanceActivity,
                        "Resumed class: ${first.classroomId} (teacher ${first.teacherId})",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error restoring sessions: ${e.message}", e)
            }
        }
    }

    private fun getEstimatedCurrentTime(): Date {
        val prefs = getSharedPreferences("SyncPrefs", MODE_PRIVATE)
        val lastSyncStr = prefs.getString("last_sync_time", null) ?: return Date()
        val lastUptime = prefs.getLong("last_sync_uptime", 0L)
        if (lastUptime == 0L) return Date()

        return try {
            val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault())
            val lastSyncDate = sdf.parse(lastSyncStr) ?: return Date()
            val uptimeDiff = SystemClock.elapsedRealtime() - lastUptime
            Date(lastSyncDate.time + uptimeDiff)
        } catch (e: Exception) {
            Date()
        }
    }


    private fun isLastSyncExpired(): Boolean {
        val prefs = getSharedPreferences("SyncPrefs", MODE_PRIVATE)
        val lastUptime = prefs.getLong("last_sync_uptime", 0L)
        if (lastUptime == 0L) return true

        val diffMillis = SystemClock.elapsedRealtime() - lastUptime
        val diffHours = diffMillis / (1000 * 60 * 60)

        return diffHours > 24
    }


    // Fetch active session from DB
    private suspend fun getActiveSession(classId: String, teacherId: String): ActiveClassCycle? {
        val db = AppDatabase.getDatabase(this)
        return db.activeClassCycleDao().getAll()
            .find { it.classroomId == classId && it.teacherId == teacherId }
    }

    // Save active session to DB
    private suspend fun saveActiveSession(cycle: AttendanceCycle) {
        val db = AppDatabase.getDatabase(this)
        db.activeClassCycleDao().insert(
            ActiveClassCycle(
                classroomId = cycle.classroomId,
                classroomName = cycle.classroomName,
                teacherId = cycle.teacherId,
                teacherName = cycle.teacherName,
                sessionId = cycle.sessionId,
                startedAtMillis = cycle.startedAtMillis
            )
        )
    }

    //  Remove active session from DB
    private suspend fun removeActiveSession(classId: String, teacherId: String?) {
        val db = AppDatabase.getDatabase(this)
        val all = db.activeClassCycleDao().getAll()
        all.find { it.classroomId == classId && it.teacherId == teacherId }?.let {
            db.activeClassCycleDao().delete(it)
        }
    }

    fun simulateClassroomScan(classroomId: String, classroomName: String) {
        handleClassScan(classroomId, classroomName)
    }


    fun simulateTeacherScan(teacherId: String) {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@AttendanceActivity)
            val t = db.teachersDao().getTeacherById(teacherId)
            if (t != null) handleTeacherScan(t.staffId, t.staffName)
        }
    }


    fun simulateStudentScan(
        student: Student,
        onResult: (StudentAttendanceResult) -> Unit = {}
    ) {
        handleStudentScan(student, onResult)
    }


    fun showEndClassDialogForVisibleClass(onCancelled: () -> Unit = {}) {
        // just expose the existing private one
        // (if you have a private method with same name, rename that to something like doShowEndClassDialogForVisibleClass())
        val classroomId = /* your existing field */ currentVisibleClassroomId ?: return
        // call the existing private showEndClassDialog(classroomId)
        showEndClassDialog(classroomId, onCancelled)
    }

    fun startMassBunkFlow() {
        val classroomId = currentVisibleClassroomId ?: return
        val teacherId = currentTeacherId ?: return
        val cycle = activeSessions[Pair(classroomId, teacherId)] ?: return
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@AttendanceActivity)
            val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(getEstimatedCurrentTime())

            cycle.sessionId?.let { db.sessionDao().updateSessionEnd(it, currentTime) }
            cycle.sessionId?.let { db.attendanceDao().updateAttendanceEndTime(it, currentTime) }

            // remove from ActiveClassCycle table
            removeActiveSession(classroomId, teacherId)
            activeSessions.remove(Pair(classroomId, teacherId))

            val broadcastIntent = Intent("UPDATE_UNSUBMITTED_COUNT")
            sendBroadcast(broadcastIntent)

            Log.d("SESSION_END_MB", "Session ${cycle.sessionId} closed at $currentTime for Mass Bunk")

            // Clear saved app state before starting new flow
            val prefs1 = getSharedPreferences("APP_STATE", MODE_PRIVATE)
            prefs1.edit().clear().apply()
            val prefs2 = getSharedPreferences("AttendancePrefs", MODE_PRIVATE)
            prefs2.edit().clear().apply()

            val intent = Intent(this@AttendanceActivity, ClassSelectActivity::class.java).apply {
                putExtra("SESSION_ID", cycle.sessionId)
                putExtra("TEACHER_ID", cycle.teacherId)
                putExtra("IS_MASS_BUNK", true)
            }
            startActivity(intent)
            finish()
            activeSessions.remove(Pair(classroomId, teacherId))

            currentVisibleClassroomId = null
        }
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Camera permission is required for face verification. Please enable it", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showUserMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }



    /*
    private suspend fun getFlexibleSchoolPeriodId(instId: String, startTime: String): String {
        val db = AppDatabase.getDatabase(this)
        val periods = db.schoolPeriodDao().getAll().filter { it.instId == instId }

        if (periods.isEmpty()) {
            Log.w("PERIOD_ASSIGN", "No periods found in DB for instId=$instId, returning default 999")
            return "999"
        }

        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val now = sdf.parse(startTime) ?: return "999"

        val GRACE_MINUTES = 10

        for (p in periods) {
            val start = sdf.parse(p.spIstTime) ?: continue
            val end = sdf.parse(p.spEndTime) ?: continue
            val graceStart = Date(start.time - GRACE_MINUTES * 60 * 1000)

            if (now.after(start) && now.before(end)) {
                Log.d("PERIOD_ASSIGN", "Inside → ${p.spTitle} spId=${p.spId}")
                return p.spId
            }
            if (now.after(graceStart) && now.before(start)) {
                Log.d("PERIOD_ASSIGN", "Grace → ${p.spTitle} spId=${p.spId}")
                return p.spId
            }
        }

        Log.w("PERIOD_ASSIGN", "No matching period for $startTime")

        val defaultSpId = periods.firstOrNull()?.spId ?: "999"
        Log.w("PERIOD_ASSIGN", "No match for $startTime, fallback → spId=$defaultSpId")
        return defaultSpId
    }
    */

}
