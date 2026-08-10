package com.digitaledu.selfieattendance.view

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.digitaledu.selfieattendance.databinding.ItemClassOverviewBinding

class AttendanceOverviewAdapter(
    private val classList: List<ClassOverviewData>,
    private val onEditClick: (String) -> Unit,
    private val onAddCoLecturerClick: (ClassOverviewData) -> Unit
) : RecyclerView.Adapter<AttendanceOverviewAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemClassOverviewBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemClassOverviewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int = classList.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val data = classList[position]
        holder.binding.textClassName.text = "Class: ${data.className}"
        holder.binding.textSubject.text = "Subject: ${data.subjectName}"

        holder.binding.textTotal.text = "Total Students: ${data.totalStudents}"
        holder.binding.textPresent.text = "Present: ${data.presentCount}"
        holder.binding.textLate.text = "Late: ${data.lateCount}"
        holder.binding.textExempted.text = "Exempted: ${data.exemptedCount}"
        holder.binding.textAbsent.text = "Absent: ${data.absentCount}"

        if (!data.coLecturerNames.isNullOrEmpty()) {
            holder.binding.textCoLecturers.visibility = android.view.View.VISIBLE
            holder.binding.textCoLecturers.text = "Co-Lecturers: ${data.coLecturerNames}"
        } else {
            holder.binding.textCoLecturers.visibility = android.view.View.GONE
        }

        holder.binding.btnEdit.setOnClickListener {
            onEditClick(data.classId)
        }

        holder.binding.btnAddCoLecturer.setOnClickListener {
            onAddCoLecturerClick(data)
        }
    }
}

