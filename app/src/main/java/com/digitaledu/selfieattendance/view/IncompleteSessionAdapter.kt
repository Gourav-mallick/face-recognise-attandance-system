package com.digitaledu.selfieattendance.view

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.db.entity.IncompleteSession

class IncompleteSessionAdapter(
    private val sessions: List<IncompleteSession>,
    private val onResume: (IncompleteSession) -> Unit
) : RecyclerView.Adapter<IncompleteSessionAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val teacher: TextView = view.findViewById(R.id.tvIncompleteTeacher)
        val details: TextView = view.findViewById(R.id.tvIncompleteDetails)
        val stage: TextView = view.findViewById(R.id.tvIncompleteStage)
        val sync: TextView = view.findViewById(R.id.tvIncompleteSync)
        val resume: Button = view.findViewById(R.id.btnResumeIncomplete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_incomplete_session, parent, false)
        )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = sessions[position]
        holder.teacher.text = item.teacherName ?: "Teacher ${item.teacherId}"
        holder.details.text = buildString {
            append(item.sessionDate)
            append("  ")
            append(item.startTime)
            append("\nClass: ")
            append(item.classNames.ifBlank { item.classIds.ifBlank { "Not selected" } })
            append("\nMarked students: ")
            append(item.markedStudentCount)
        }
        holder.stage.text = when (item.currentStage) {
            "STAGE_STUDENT_SCAN" -> "Student scan"
            "STAGE_PERIOD_SELECT" -> "Period selection"
            "STAGE_CLASS_SELECT" -> "Class selection"
            "STAGE_SUBJECT_SELECT" -> "Subject selection"
            "STAGE_OVERVIEW" -> "Attendance overview"
            else -> item.currentStage
        }
        holder.sync.text =
            if (item.syncStatus == "SYNCED") "Available on all synced devices"
            else "Saved on this device"
        holder.resume.setOnClickListener { onResume(item) }
    }

    override fun getItemCount(): Int = sessions.size
}
