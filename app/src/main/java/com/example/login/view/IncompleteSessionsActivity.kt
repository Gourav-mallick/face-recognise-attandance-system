package com.example.login.view

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.login.R
import com.example.login.db.dao.AppDatabase
import com.example.login.db.entity.Attendance
import com.example.login.db.entity.IncompleteSession
import com.example.login.db.entity.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

import android.app.Activity
import androidx.activity.result.contract.ActivityResultContracts

class IncompleteSessionsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "IncompleteSessionsAct"
    }

    private lateinit var db: AppDatabase
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var editSearch: EditText
    private var masterIncompleteList: List<IncompleteSession> = emptyList()

    private var teacherIdArg: String = ""
    private var pendingSessionToResume: IncompleteSession? = null

    private val verifyTeacherLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val session = pendingSessionToResume
            if (session != null) {
                Toast.makeText(this, "Teacher face verified successfully!", Toast.LENGTH_SHORT).show()
                resumeIncompleteSession(session)
            }
        } else {
            Toast.makeText(this, "Teacher face verification required to resume session.", Toast.LENGTH_LONG).show()
        }
        pendingSessionToResume = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_incomplete_sessions)

        db = AppDatabase.getDatabase(this)
        teacherIdArg = intent.getStringExtra("TEACHER_ID") ?: ""

        recyclerView = findViewById(R.id.recyclerViewIncomplete)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        btnBack = findViewById(R.id.btnBack)
        editSearch = findViewById(R.id.editSearchIncomplete)

        recyclerView.layoutManager = LinearLayoutManager(this)

        btnBack.setOnClickListener {
            com.example.login.repository.IncompleteSessionManager.navigateToHome(this)
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                com.example.login.repository.IncompleteSessionManager.navigateToHome(this@IncompleteSessionsActivity)
            }
        })

        editSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterIncompleteSessions(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        loadIncompleteSessions()
    }

    private fun loadIncompleteSessions() {
        lifecycleScope.launch {
            val sessions = if (teacherIdArg.isNotEmpty()) {
                db.incompleteSessionDao().getIncompleteSessionsByTeacher(teacherIdArg)
            } else {
                db.incompleteSessionDao().getAllIncompleteSessions()
            }

            masterIncompleteList = sessions
            filterIncompleteSessions(editSearch.text.toString())
        }
    }

    private fun filterIncompleteSessions(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            masterIncompleteList
        } else {
            masterIncompleteList.filter { session ->
                val teacherMatch = session.teacherName?.lowercase()?.contains(q) == true || session.teacherId.lowercase().contains(q)
                val classMatch = session.classShortName?.lowercase()?.contains(q) == true || session.classId?.lowercase()?.contains(q) == true

                var subjectTitle: String? = null
                if (!session.attendancesJson.isNullOrEmpty()) {
                    try {
                        val jsonArray = org.json.JSONArray(session.attendancesJson)
                        if (jsonArray.length() > 0) {
                            subjectTitle = jsonArray.getJSONObject(0).optString("subjectTitle", "").ifEmpty { null }
                        }
                    } catch (e: Exception) {}
                }
                if (subjectTitle.isNullOrEmpty() && !session.sessionObjectJson.isNullOrEmpty()) {
                    try {
                        val jsonObj = org.json.JSONObject(session.sessionObjectJson)
                        subjectTitle = jsonObj.optString("subjectTitle", "").ifEmpty { jsonObj.optString("subjectName", "").ifEmpty { null } }
                    } catch (e: Exception) {}
                }
                val subjectMatch = subjectTitle?.lowercase()?.contains(q) == true

                teacherMatch || classMatch || subjectMatch
            }
        }

        if (filtered.isEmpty()) {
            recyclerView.visibility = View.GONE
            tvEmptyState.visibility = View.VISIBLE
            tvEmptyState.text = if (q.isEmpty()) "No incomplete sessions found." else "No matching incomplete sessions found."
        } else {
            recyclerView.visibility = View.VISIBLE
            tvEmptyState.visibility = View.GONE
            tvSubtitle.text = "${filtered.size} incomplete session(s) pending for completion"

            val adapter = IncompleteSessionAdapter(filtered) { session ->
                startTeacherVerification(session)
            }
            recyclerView.adapter = adapter
        }
    }

    private fun startTeacherVerification(session: IncompleteSession) {
        pendingSessionToResume = session
        val intent = Intent(this, FaceVerificationActivity::class.java).apply {
            putExtra("STUDENT_ID", session.teacherId)
            putExtra("STUDENT_NAME", session.teacherName ?: "Teacher")
        }
        verifyTeacherLauncher.launch(intent)
    }

    private fun resumeIncompleteSession(incompleteSession: IncompleteSession) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1) Restore Session in Room DB if missing
                val existingSession = db.sessionDao().getSessionById(incompleteSession.sessionId)
                if (existingSession == null) {
                    val sessionObj = Session(
                        sessionId = incompleteSession.sessionId,
                        classId = incompleteSession.classId ?: "",
                        teacherId = incompleteSession.teacherId,
                        subjectId = "",
                        date = incompleteSession.sessionDate,
                        startTime = incompleteSession.startTime,
                        endTime = "",
                        isMerged = 0,
                        instId = incompleteSession.instId,
                        attSchoolPeriodId = incompleteSession.attSchoolPeriodId ?: "999",
                        syncStatus = "pending",
                        periodId = ""
                    )
                    db.sessionDao().insertSession(sessionObj)
                }

                // 2) Restore attendances if present in JSON
                val attendancesJson = incompleteSession.attendancesJson
                if (!attendancesJson.isNullOrEmpty()) {
                    try {
                        val jsonArray = JSONArray(attendancesJson)
                        for (i in 0 until jsonArray.length()) {
                            val item = jsonArray.getJSONObject(i)
                            val studentId = item.optString("studentId")
                            val existing = db.attendanceDao().getAttendanceForStudentInSession(
                                incompleteSession.sessionId,
                                studentId
                            )
                            if (existing == null) {
                                val att = Attendance(
                                    atteId = "${incompleteSession.sessionId}_$studentId",
                                    sessionId = incompleteSession.sessionId,
                                    studentId = studentId,
                                    studentName = item.optString("studentName"),
                                    classId = item.optString("classId", incompleteSession.classId ?: ""),
                                    status = item.optString("status", "P"),
                                    markedAt = item.optString("markedAt"),
                                    syncStatus = "pending",
                                    instId = incompleteSession.instId,
                                    date = incompleteSession.sessionDate,
                                    startTime = incompleteSession.startTime,
                                    endTime = "",
                                    period = "",
                                    teacherId = incompleteSession.teacherId,
                                    teacherName = incompleteSession.teacherName,
                                    attSchoolPeriodId = incompleteSession.attSchoolPeriodId ?: "999"
                                )
                                db.attendanceDao().insertAttendance(att)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing attendances JSON: ${e.message}", e)
                    }
                }

                // 3) Update AppState SharedPreferences
                withContext(Dispatchers.Main) {
                    val screenState = when (incompleteSession.currentStage) {
                        "STAGE_STUDENT_SCAN" -> "STUDENT_SCAN"
                        "STAGE_PERIOD_SELECT" -> "PERIOD_SELECT"
                        "STAGE_CLASS_SELECT" -> "CLASS_SELECT"
                        "STAGE_SUBJECT_SELECT" -> "PERIOD_SELECT"
                        "STAGE_OVERVIEW" -> "ATTENDANCE_OVERVIEW"
                        else -> "STUDENT_SCAN"
                    }

                    getSharedPreferences("APP_STATE", MODE_PRIVATE).edit()
                        .putString("CURRENT_SCREEN", screenState)
                        .putString("SESSION_ID", incompleteSession.sessionId)
                        .putString("CLASSROOM_ID", incompleteSession.classId)
                        .putString("TEACHER_ID", incompleteSession.teacherId)
                        .putString("TEACHER_NAME", incompleteSession.teacherName)
                        .apply()

                    // 4) Navigate to appropriate target activity
                    val intent = when (incompleteSession.currentStage) {
                        "STAGE_STUDENT_SCAN" -> {
                            Intent(this@IncompleteSessionsActivity, AttendanceActivity::class.java)
                        }
                        "STAGE_PERIOD_SELECT" -> {
                            Intent(this@IncompleteSessionsActivity, PeriodSelectActivity::class.java).apply {
                                putExtra("SESSION_ID", incompleteSession.sessionId)
                                putExtra("TEACHER_ID", incompleteSession.teacherId)
                            }
                        }
                        "STAGE_CLASS_SELECT" -> {
                            Intent(this@IncompleteSessionsActivity, ClassSelectActivity::class.java).apply {
                                putExtra("SESSION_ID", incompleteSession.sessionId)
                                putExtra("TEACHER_ID", incompleteSession.teacherId)
                            }
                        }
                        "STAGE_SUBJECT_SELECT" -> {
                            Intent(this@IncompleteSessionsActivity, SubjectSelectActivity::class.java).apply {
                                putExtra("SESSION_ID", incompleteSession.sessionId)
                                putExtra("TEACHER_ID", incompleteSession.teacherId)
                                incompleteSession.classId?.let {
                                    putStringArrayListExtra("SELECTED_CLASSES", arrayListOf(it))
                                }
                            }
                        }
                        "STAGE_OVERVIEW" -> {
                            Intent(this@IncompleteSessionsActivity, AttendanceOverviewActivity::class.java).apply {
                                putExtra("SESSION_ID", incompleteSession.sessionId)
                                incompleteSession.classId?.let {
                                    putStringArrayListExtra("SELECTED_CLASSES", arrayListOf(it))
                                }
                            }
                        }
                        else -> {
                            Intent(this@IncompleteSessionsActivity, AttendanceActivity::class.java)
                        }
                    }

                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resuming incomplete session: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@IncompleteSessionsActivity, "Failed to resume session", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
