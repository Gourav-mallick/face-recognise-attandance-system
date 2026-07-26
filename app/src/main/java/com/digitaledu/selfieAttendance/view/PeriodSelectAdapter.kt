package com.example.selfieAttendance.view

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.selfieAttendance.databinding.ItemPeriodCheckboxBinding
import com.example.selfieAttendance.db.entity.SchoolPeriod

class PeriodSelectAdapter(
    private val periodList: List<SchoolPeriod>,
    private val autoAssignedSpId: String,
    private val onPeriodCheckedChange: (spId: String, isChecked: Boolean) -> Unit
) : RecyclerView.Adapter<PeriodSelectAdapter.PeriodViewHolder>() {

    private val selectedPeriodIds = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeriodViewHolder {
        val binding = ItemPeriodCheckboxBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PeriodViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PeriodViewHolder, position: Int) {
        val item = periodList[position]

        // Display: title + time range in the TextView
        val displayText = "${item.spTitle}  (${item.spIstTime} - ${item.spEndTime})"
        holder.binding.tvPeriodLabel.text = displayText

        // Remove listener before setting checked state
        holder.binding.checkboxPeriod.setOnCheckedChangeListener(null)
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

    override fun getItemCount() = periodList.size

    fun getSelectedPeriodIds(): List<String> = selectedPeriodIds.toList()

    inner class PeriodViewHolder(val binding: ItemPeriodCheckboxBinding) :
        RecyclerView.ViewHolder(binding.root)
}

