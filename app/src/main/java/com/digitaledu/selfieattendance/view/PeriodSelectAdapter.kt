package com.digitaledu.selfieattendance.view

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.digitaledu.selfieattendance.databinding.ItemPeriodCheckboxBinding
import com.digitaledu.selfieattendance.db.entity.SchoolPeriod

data class SubmittedPeriodInfo(
    val spId: String,
    val spTitle: String,
    val teacherName: String,
    val classNames: String,
    val subjectTitle: String,
    val presentCount: Int,
    val exemptedCount: Int,
    val absentCount: Int,
    val lateCount: Int
)

class PeriodSelectAdapter(
    private val periodList: List<SchoolPeriod>,
    private val autoAssignedSpId: String,
    private val submittedPeriodsMap: Map<String, SubmittedPeriodInfo> = emptyMap(),
    private val onPeriodCheckedChange: (spId: String, isChecked: Boolean) -> Unit
) : RecyclerView.Adapter<PeriodSelectAdapter.PeriodViewHolder>() {

    private var selectedPeriodId: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeriodViewHolder {
        val binding = ItemPeriodCheckboxBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PeriodViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PeriodViewHolder, position: Int) {
        val item = periodList[position]
        val submittedInfo = submittedPeriodsMap[item.spId]

        holder.binding.checkboxPeriod.setOnCheckedChangeListener(null)

        // All periods are selectable — no blocking
        val displayText = if (submittedInfo != null) {
            "${item.spTitle}  (${item.spIstTime} - ${item.spEndTime})  [Submitted]"
        } else {
            "${item.spTitle}  (${item.spIstTime} - ${item.spEndTime})"
        }
        holder.binding.tvPeriodLabel.text = displayText

        holder.itemView.alpha = 1.0f
        holder.binding.checkboxPeriod.isEnabled = true
        holder.binding.checkboxPeriod.isChecked = selectedPeriodId == item.spId

        holder.binding.checkboxPeriod.setOnCheckedChangeListener { _, isChecked ->
            selectedPeriodId = if (isChecked) item.spId else null
            onPeriodCheckedChange(item.spId, isChecked)
            notifyDataSetChanged()
        }

        // Tap anywhere on row to toggle checkbox
        holder.itemView.setOnClickListener {
            holder.binding.checkboxPeriod.isChecked = !holder.binding.checkboxPeriod.isChecked
        }
    }

    override fun getItemCount() = periodList.size

    fun getSelectedPeriodIds(): List<String> = selectedPeriodId?.let(::listOf) ?: emptyList()

    inner class PeriodViewHolder(val binding: ItemPeriodCheckboxBinding) :
        RecyclerView.ViewHolder(binding.root)
}
