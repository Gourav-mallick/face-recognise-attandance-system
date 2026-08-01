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
    private val onPeriodCheckedChange: (spId: String, isChecked: Boolean) -> Unit,
    private val onSubmittedPeriodClick: (item: SchoolPeriod, info: SubmittedPeriodInfo) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<PeriodSelectAdapter.PeriodViewHolder>() {

    private val selectedPeriodIds = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeriodViewHolder {
        val binding = ItemPeriodCheckboxBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PeriodViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PeriodViewHolder, position: Int) {
        val item = periodList[position]
        val submittedInfo = submittedPeriodsMap[item.spId]

        holder.binding.checkboxPeriod.setOnCheckedChangeListener(null)

        if (submittedInfo != null) {
            // Submitted Period: Faded UI, checkbox disabled
            val displayText = "${item.spTitle}  (${item.spIstTime} - ${item.spEndTime})  [Submitted]"
            holder.binding.tvPeriodLabel.text = displayText

            holder.itemView.alpha = 0.5f
            holder.binding.checkboxPeriod.isChecked = false
            holder.binding.checkboxPeriod.isEnabled = false

            val clickListener = {
                onSubmittedPeriodClick(item, submittedInfo)
            }
            holder.itemView.setOnClickListener { clickListener() }
            holder.binding.checkboxPeriod.setOnClickListener { clickListener() }
        } else {
            // Normal Unsubmitted Period
            val displayText = "${item.spTitle}  (${item.spIstTime} - ${item.spEndTime})"
            holder.binding.tvPeriodLabel.text = displayText

            holder.itemView.alpha = 1.0f
            holder.binding.checkboxPeriod.isEnabled = true
            holder.binding.checkboxPeriod.isChecked = selectedPeriodIds.contains(item.spId)

            holder.binding.checkboxPeriod.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedPeriodIds.add(item.spId)
                else selectedPeriodIds.remove(item.spId)
                onPeriodCheckedChange(item.spId, isChecked)
            }

            // Tap anywhere on row to toggle checkbox
            holder.itemView.setOnClickListener {
                holder.binding.checkboxPeriod.isChecked = !holder.binding.checkboxPeriod.isChecked
            }
        }
    }

    override fun getItemCount() = periodList.size

    fun getSelectedPeriodIds(): List<String> = selectedPeriodIds.toList()

    inner class PeriodViewHolder(val binding: ItemPeriodCheckboxBinding) :
        RecyclerView.ViewHolder(binding.root)
}
