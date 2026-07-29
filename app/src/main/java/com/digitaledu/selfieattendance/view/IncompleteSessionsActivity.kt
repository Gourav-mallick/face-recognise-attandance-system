package com.digitaledu.selfieattendance.view

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.db.entity.IncompleteSession
import com.digitaledu.selfieattendance.repository.IncompleteSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IncompleteSessionsActivity : AppCompatActivity() {
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var status: TextView
    private var teacherId: String? = null
    private var pendingResume: IncompleteSession? = null

    private val verification = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val checkpoint = pendingResume
        pendingResume = null
        if (result.resultCode == Activity.RESULT_OK && checkpoint != null) {
            resume(checkpoint)
        } else {
            Toast.makeText(
                this,
                "The same teacher must verify before this session can resume.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_incomplete_sessions)
        teacherId = intent.getStringExtra("TEACHER_ID")
        list = findViewById(R.id.recyclerIncompleteSessions)
        empty = findViewById(R.id.tvIncompleteEmpty)
        status = findViewById(R.id.tvIncompleteStatus)
        list.layoutManager = LinearLayoutManager(this)
        findViewById<ImageButton>(R.id.btnIncompleteBack).setOnClickListener { goHome() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            status.text = "Checking this device and server..."
            val synced = withContext(Dispatchers.IO) {
                IncompleteSessionManager.sync(this@IncompleteSessionsActivity)
            }
            val values = IncompleteSessionManager.getPending(
                this@IncompleteSessionsActivity,
                teacherId
            )
            status.text = if (synced) {
                "${values.size} pending session(s), synchronized"
            } else {
                "${values.size} pending session(s); showing available local data"
            }
            empty.visibility = if (values.isEmpty()) View.VISIBLE else View.GONE
            list.visibility = if (values.isEmpty()) View.GONE else View.VISIBLE
            list.adapter = IncompleteSessionAdapter(values, ::verifyOwner)
        }
    }

    private fun verifyOwner(value: IncompleteSession) {
        pendingResume = value
        verification.launch(
            Intent(this, FaceVerificationActivity::class.java).apply {
                putExtra("STUDENT_ID", value.teacherId)
                putExtra("STUDENT_NAME", value.teacherName ?: "Teacher")
                putExtra("VERIFY_USER_TYPE", "teacher")
            }
        )
    }

    private fun resume(value: IncompleteSession) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    IncompleteSessionManager.restoreNormalRows(
                        this@IncompleteSessionsActivity,
                        value
                    )
                }
                val classes = ArrayList(
                    value.classIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                )
                val target = when (value.currentStage) {
                    IncompleteSessionManager.STAGE_PERIOD_SELECT ->
                        Intent(this@IncompleteSessionsActivity, PeriodSelectActivity::class.java)
                    IncompleteSessionManager.STAGE_CLASS_SELECT ->
                        Intent(this@IncompleteSessionsActivity, ClassSelectActivity::class.java)
                    IncompleteSessionManager.STAGE_SUBJECT_SELECT ->
                        Intent(this@IncompleteSessionsActivity, SubjectSelectActivity::class.java)
                    IncompleteSessionManager.STAGE_OVERVIEW ->
                        Intent(this@IncompleteSessionsActivity, AttendanceOverviewActivity::class.java)
                    else -> Intent(this@IncompleteSessionsActivity, AttendanceActivity::class.java)
                        .putExtra("RESUME_INCOMPLETE_SESSION", true)
                        .putExtra("TEACHER_NAME", value.teacherName.orEmpty())
                }.apply {
                    putExtra("SESSION_ID", value.sessionId)
                    putExtra("TEACHER_ID", value.teacherId)
                    putStringArrayListExtra("SELECTED_CLASSES", classes)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(target)
                finish()
            } catch (error: Exception) {
                Toast.makeText(
                    this@IncompleteSessionsActivity,
                    "Could not restore this session: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun goHome() = IncompleteSessionManager.navigateHome(this)
}
