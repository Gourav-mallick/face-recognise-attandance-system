package com.example.login.view

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.login.R
import com.example.login.db.entity.IncompleteSession

class IncompleteSessionAdapter(
    private val sessions: List<IncompleteSession>,
    private val onResumeClick: (IncompleteSession) -> Unit
) : RecyclerView.Adapter<IncompleteSessionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTeacherName: TextView = view.findViewById(R.id.tvCardTeacherName)
        val tvStageBadge: TextView = view.findViewById(R.id.tvCardStageBadge)
        val tvSessionDate: TextView = view.findViewById(R.id.tvCardSessionDate)
        val tvClassInfo: TextView = view.findViewById(R.id.tvCardClassInfo)
        val tvSubjectInfo: TextView = view.findViewById(R.id.tvCardSubjectInfo)
        val tvPeriodInfo: TextView = view.findViewById(R.id.tvCardPeriodInfo)
        val tvMarkedCount: TextView = view.findViewById(R.id.tvCardMarkedCount)
        val tvSyncStatus: TextView = view.findViewById(R.id.tvCardSyncStatus)
        val btnResume: Button = view.findViewById(R.id.btnResumeSession)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_incomplete_session, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = sessions[position]

        val teacherName = session.teacherName ?: "Teacher (${session.teacherId})"
        holder.tvTeacherName.text = teacherName

        // Stage badge text
        val stageLabel = when (session.currentStage) {
            "STAGE_STUDENT_SCAN" -> "Student Scan"
            "STAGE_PERIOD_SELECT" -> "Period Select"
            "STAGE_CLASS_SELECT" -> "Class Select"
            "STAGE_SUBJECT_SELECT" -> "Subject Select"
            "STAGE_OVERVIEW" -> "Overview"
            else -> session.currentStage
        }
        holder.tvStageBadge.text = stageLabel

        // Date and Time
        holder.tvSessionDate.text = "📅 Date: ${session.sessionDate} | Start: ${session.startTime}"

        // Class Info
        val className = session.classShortName ?: session.classId ?: "Not Selected"
        holder.tvClassInfo.text = "🏫 Class: $className"

        // Subject Info
        var subjectTitle: String? = null
        if (!session.attendancesJson.isNullOrEmpty()) {
            try {
                val jsonArray = org.json.JSONArray(session.attendancesJson)
                if (jsonArray.length() > 0) {
                    val firstAtt = jsonArray.getJSONObject(0)
                    subjectTitle = firstAtt.optString("subjectTitle", "").ifEmpty { null }
                }
            } catch (e: Exception) {}
        }
        if (subjectTitle.isNullOrEmpty() && !session.sessionObjectJson.isNullOrEmpty()) {
            try {
                val jsonObj = org.json.JSONObject(session.sessionObjectJson)
                subjectTitle = jsonObj.optString("subjectTitle", "")
                    .ifEmpty { jsonObj.optString("subjectName", "").ifEmpty { null } }
            } catch (e: Exception) {}
        }

        if (!subjectTitle.isNullOrEmpty()) {
            holder.tvSubjectInfo.visibility = View.VISIBLE
            holder.tvSubjectInfo.text = "📚 Subject: $subjectTitle"
        } else {
            holder.tvSubjectInfo.visibility = View.GONE
        }

        // Period Info
        val periodText = if (session.autoAssignedPeriod == 1) {
            "⏰ Period: Auto-Assigned (${session.attSchoolPeriodId ?: "—"})"
        } else {
            "⏰ Period: ${session.attSchoolPeriodId ?: "—"}"
        }
        holder.tvPeriodInfo.text = periodText

        // Marked Students Count
        holder.tvMarkedCount.text = "👥 Marked Students: ${session.markedStudentCount}"

        // Sync Status
        holder.tvSyncStatus.text = "☁ Sync Status: ${session.syncStatus}"

        // Resume click listener
        holder.btnResume.setOnClickListener {
            onResumeClick(session)
        }
    }

    override fun getItemCount(): Int = sessions.size
}
