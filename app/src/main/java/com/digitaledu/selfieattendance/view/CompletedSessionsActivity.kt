package com.digitaledu.selfieattendance.view

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.Attendance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CompletedSessionsActivity : ComponentActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmptyState: TextView
    private lateinit var db: AppDatabase
    private var pendingSessionId: String = ""
    private var pendingClasses: ArrayList<String> = arrayListOf()

    private val teacherVerificationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Teacher Verification Successful!", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, AttendanceOverviewActivity::class.java).apply {
                putExtra("SESSION_ID", pendingSessionId)
                putStringArrayListExtra("SELECTED_CLASSES", pendingClasses)
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Teacher Verification Failed or Cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_completed_sessions)

        recyclerView = findViewById(R.id.recyclerViewCompletedSessions)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        recyclerView.layoutManager = LinearLayoutManager(this)

        db = AppDatabase.getDatabase(this)
        loadCompletedSessions()
    }

    override fun onResume() {
        super.onResume()
        loadCompletedSessions()
    }

    private fun loadCompletedSessions() {
        lifecycleScope.launch(Dispatchers.IO) {
            val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val allAttendances = db.attendanceDao().getAllAttendance()

            // Group by sessionId for today
            val sessionGroups = allAttendances
                .filter { it.date == todayDate || it.markedAt.startsWith(todayDate) }
                .groupBy { it.sessionId }

            val cardItems = mutableListOf<CompletedSessionCardItem>()

            for ((sessionId, list) in sessionGroups) {
                if (list.isEmpty()) continue
                val sample = list.first()

                val pCount = list.count { it.status == "P" }
                val lCount = list.count { it.status == "L" }
                val aCount = list.count { it.status == "A" }
                val eCount = list.count { it.status == "E" }

                val periodName = if (sample.period.isNotBlank()) sample.period else "Period ${sample.attSchoolPeriodId}"
                val subjectTitle = sample.subjectTitle ?: "Subject"
                val teacherName = sample.teacherName ?: "Teacher"
                val classNames = list.mapNotNull { it.classShortName }.distinct().joinToString(", ")

                cardItems.add(
                    CompletedSessionCardItem(
                        sessionId = sessionId,
                        periodTitle = periodName,
                        subjectTitle = subjectTitle,
                        teacherName = teacherName,
                        teacherId = sample.teacherId,
                        classNames = classNames,
                        classId = sample.classId,
                        presentCount = pCount,
                        lateCount = lCount,
                        absentCount = aCount,
                        exemptedCount = eCount
                    )
                )
            }

            withContext(Dispatchers.Main) {
                if (cardItems.isEmpty()) {
                    tvEmptyState.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    tvEmptyState.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    recyclerView.adapter = CompletedSessionsAdapter(cardItems) { item ->
                        pendingSessionId = item.sessionId
                        pendingClasses = arrayListOf(item.classId)

                        // Launch Teacher Face Verification Gate
                        val intent = Intent(this@CompletedSessionsActivity, FaceVerificationActivity::class.java).apply {
                            putExtra("TEACHER_ID", item.teacherId)
                            putExtra("TEACHER_NAME", item.teacherName)
                            putExtra("IS_TEACHER_VERIFICATION", true)
                        }
                        teacherVerificationLauncher.launch(intent)
                    }
                }
            }
        }
    }

    data class CompletedSessionCardItem(
        val sessionId: String,
        val periodTitle: String,
        val subjectTitle: String,
        val teacherName: String,
        val teacherId: String,
        val classNames: String,
        val classId: String,
        val presentCount: Int,
        val lateCount: Int,
        val absentCount: Int,
        val exemptedCount: Int
    )

    private class CompletedSessionsAdapter(
        private val items: List<CompletedSessionCardItem>,
        private val onItemClick: (CompletedSessionCardItem) -> Unit
    ) : RecyclerView.Adapter<CompletedSessionsAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvPeriodTitle: TextView = view.findViewById(R.id.tvPeriodTitle)
            val tvSubjectTitle: TextView = view.findViewById(R.id.tvSubjectTitle)
            val tvClassAndTeacher: TextView = view.findViewById(R.id.tvClassAndTeacher)
            val tvPresentCount: TextView = view.findViewById(R.id.tvPresentCount)
            val tvLateCount: TextView = view.findViewById(R.id.tvLateCount)
            val tvAbsentCount: TextView = view.findViewById(R.id.tvAbsentCount)
            val tvExemptedCount: TextView = view.findViewById(R.id.tvExemptedCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_completed_session_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvPeriodTitle.text = item.periodTitle
            holder.tvSubjectTitle.text = item.subjectTitle
            holder.tvClassAndTeacher.text = "${item.classNames} | Teacher: ${item.teacherName}"
            holder.tvPresentCount.text = "${item.presentCount} P"
            holder.tvLateCount.text = "${item.lateCount} L"
            holder.tvAbsentCount.text = "${item.absentCount} A"
            holder.tvExemptedCount.text = "${item.exemptedCount} E"

            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
