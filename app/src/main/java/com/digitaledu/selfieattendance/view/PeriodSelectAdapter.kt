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
    initialSelectedPeriodId: String? = null,
    private val isSelectionLocked: Boolean = false,
    private val onPeriodCheckedChange: (spId: String, isChecked: Boolean) -> Unit
) : RecyclerView.Adapter<PeriodSelectAdapter.PeriodViewHolder>() {

    private var selectedPeriodId: String? = initialSelectedPeriodId

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeriodViewHolder {
        val binding = ItemPeriodCheckboxBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PeriodViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PeriodViewHolder, position: Int) {
        val item = periodList[position]
        val submittedInfo = submittedPeriodsMap[item.spId]

        holder.binding.checkboxPeriod.setOnCheckedChangeListener(null)

        // Manual mode remains selectable; live-time mode shows one locked period.
        val tagValues = buildList {
            if (isSelectionLocked && selectedPeriodId == item.spId) add("Auto-selected")
            if (submittedInfo != null) add("Submitted")
        }
        val tags = if (tagValues.isEmpty()) "" else tagValues.joinToString("] [", prefix = "  [", postfix = "]")
        val displayText = "${item.spTitle}  (${item.spIstTime} - ${item.spEndTime})$tags"
        holder.binding.tvPeriodLabel.text = displayText

        holder.itemView.alpha = if (isSelectionLocked && selectedPeriodId != item.spId) 0.45f else 1.0f
        holder.binding.checkboxPeriod.isEnabled = !isSelectionLocked
        holder.binding.checkboxPeriod.isChecked = selectedPeriodId == item.spId

        holder.binding.checkboxPeriod.setOnCheckedChangeListener { _, isChecked ->
            if (isSelectionLocked) return@setOnCheckedChangeListener
            selectedPeriodId = if (isChecked) item.spId else null
            onPeriodCheckedChange(item.spId, isChecked)
            notifyDataSetChanged()
        }

        // Tap anywhere on row to toggle checkbox
        holder.itemView.setOnClickListener {
            if (!isSelectionLocked) {
                holder.binding.checkboxPeriod.isChecked = !holder.binding.checkboxPeriod.isChecked
            }
        }
    }

    override fun getItemCount() = periodList.size

    fun getSelectedPeriodIds(): List<String> = selectedPeriodId?.let(::listOf) ?: emptyList()

    inner class PeriodViewHolder(val binding: ItemPeriodCheckboxBinding) :
        RecyclerView.ViewHolder(binding.root)
}
