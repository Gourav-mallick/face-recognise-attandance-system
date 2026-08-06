package com.digitaledu.selfieattendance.view

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.digitaledu.selfieattendance.R
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

sealed class PeriodListItem {
    data class Header(
        val instId: String,
        val instName: String,
        val showDivider: Boolean
    ) : PeriodListItem()

    data class Period(
        val period: SchoolPeriod,
        val submittedInfo: SubmittedPeriodInfo?
    ) : PeriodListItem()
}

class PeriodSelectAdapter(
    private val periodList: List<SchoolPeriod>,
    private val instNamesMap: Map<String, String> = emptyMap(),
    private val submittedPeriodsMap: Map<String, SubmittedPeriodInfo> = emptyMap(),
    initialSelectedPeriods: Map<String, String> = emptyMap(), // Map<instId, spId>
    private val isSelectionLocked: Boolean = false,
    private val onPeriodCheckedChange: (spId: String, isChecked: Boolean) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_PERIOD = 1
    }

    // Stores selected period ID for each institute: Map<instId, selectedSpId>
    private val selectedPeriodIdMap = mutableMapOf<String, String>()
    private val listItems = mutableListOf<PeriodListItem>()

    init {
        selectedPeriodIdMap.putAll(initialSelectedPeriods)
        buildListItems()
    }

    private fun buildListItems() {
        listItems.clear()
        val groupedByInst = periodList.groupBy { it.instId }
        var isFirstGroup = true

        groupedByInst.forEach { (instId, instPeriods) ->
            val instName = instNamesMap[instId]
                ?.takeIf { it.isNotBlank() }
                ?: instPeriods.firstOrNull()?.spTitle?.let { title ->
                    val parts = title.split(" - ")
                    if (parts.size >= 2) parts[1].trim() else null
                } ?: "Institute $instId"

            listItems.add(
                PeriodListItem.Header(
                    instId = instId,
                    instName = instName,
                    showDivider = !isFirstGroup
                )
            )
            isFirstGroup = false

            // Auto-select first period if institute has no period selected yet
            if (!selectedPeriodIdMap.containsKey(instId) && instPeriods.isNotEmpty()) {
                selectedPeriodIdMap[instId] = instPeriods.first().spId
            }

            instPeriods.forEach { period ->
                listItems.add(
                    PeriodListItem.Period(
                        period = period,
                        submittedInfo = submittedPeriodsMap[period.spId]
                    )
                )
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (listItems[position]) {
            is PeriodListItem.Header -> VIEW_TYPE_HEADER
            is PeriodListItem.Period -> VIEW_TYPE_PERIOD
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_institute_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val binding = ItemPeriodCheckboxBinding.inflate(inflater, parent, false)
            PeriodViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = listItems[position]) {
            is PeriodListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is PeriodListItem.Period -> (holder as PeriodViewHolder).bind(item)
        }
    }

    override fun getItemCount(): Int = listItems.size

    fun getSelectedPeriodMap(): Map<String, String> = selectedPeriodIdMap.toMap()

    fun getSelectedPeriodIds(): List<String> = selectedPeriodIdMap.values.distinct()

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val topDivider: View = itemView.findViewById(R.id.topDivider)
        private val tvInstituteHeader: TextView = itemView.findViewById(R.id.tvInstituteHeader)

        fun bind(item: PeriodListItem.Header) {
            topDivider.visibility = if (item.showDivider) View.VISIBLE else View.GONE
            tvInstituteHeader.text = "🏫 ${item.instName}"
        }
    }

    inner class PeriodViewHolder(val binding: ItemPeriodCheckboxBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PeriodListItem.Period) {
            val period = item.period
            val submittedInfo = item.submittedInfo
            val instId = period.instId

            binding.checkboxPeriod.setOnCheckedChangeListener(null)

            val isSelected = selectedPeriodIdMap[instId] == period.spId

            val tagValues = mutableListOf<String>()
            if (isSelectionLocked && isSelected) tagValues.add("Auto-selected")
            if (submittedInfo != null) tagValues.add("Submitted")

            val tags = if (tagValues.isEmpty()) "" else tagValues.joinToString("] [", prefix = "  [", postfix = "]")
            val displayText = "${period.spTitle}  (${period.spIstTime} - ${period.spEndTime})$tags"
            binding.tvPeriodLabel.text = displayText

            itemView.alpha = if (isSelectionLocked && !isSelected) 0.45f else 1.0f
            binding.checkboxPeriod.isEnabled = !isSelectionLocked
            binding.checkboxPeriod.isChecked = isSelected

            binding.checkboxPeriod.setOnCheckedChangeListener { _, isChecked ->
                if (isSelectionLocked) return@setOnCheckedChangeListener
                if (isChecked) {
                    selectedPeriodIdMap[instId] = period.spId
                } else if (selectedPeriodIdMap[instId] == period.spId) {
                    selectedPeriodIdMap.remove(instId)
                }
                onPeriodCheckedChange(period.spId, isChecked)
                notifyDataSetChanged()
            }

            itemView.setOnClickListener {
                if (!isSelectionLocked) {
                    val willBeChecked = selectedPeriodIdMap[instId] != period.spId
                    if (willBeChecked) {
                        selectedPeriodIdMap[instId] = period.spId
                    } else {
                        selectedPeriodIdMap.remove(instId)
                    }
                    onPeriodCheckedChange(period.spId, willBeChecked)
                    notifyDataSetChanged()
                }
            }
        }
    }
}
