package com.digitaledu.selfieattendance.view

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.db.entity.SessionVideo

sealed class SessionHistoryListItem {
    data class DateHeader(val date: String) : SessionHistoryListItem()
    data class SessionCard(val sessionVideo: SessionVideo) : SessionHistoryListItem()
}

class SessionRecordingsAdapter(
    private val onPlayClicked: (SessionVideo) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CARD = 1
    }

    private val items = mutableListOf<SessionHistoryListItem>()

    fun submitList(sessionVideos: List<SessionVideo>) {
        items.clear()
        // Group by date preserving order
        val groupedMap = sessionVideos.groupBy { it.date }
        for ((date, videos) in groupedMap) {
            items.add(SessionHistoryListItem.DateHeader(date))
            for (video in videos) {
                items.add(SessionHistoryListItem.SessionCard(video))
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is SessionHistoryListItem.DateHeader -> TYPE_HEADER
            is SessionHistoryListItem.SessionCard -> TYPE_CARD
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_session_date_header, parent, false)
            DateHeaderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_session_recording_card, parent, false)
            SessionCardViewHolder(view, onPlayClicked)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SessionHistoryListItem.DateHeader -> (holder as DateHeaderViewHolder).bind(item.date)
            is SessionHistoryListItem.SessionCard -> (holder as SessionCardViewHolder).bind(item.sessionVideo)
        }
    }

    override fun getItemCount(): Int = items.size

    class DateHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDateHeader: TextView = itemView.findViewById(R.id.tvDateHeader)
        fun bind(date: String) {
            tvDateHeader.text = date
        }
    }

    class SessionCardViewHolder(
        itemView: View,
        private val onPlayClicked: (SessionVideo) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvTeacherInfo: TextView = itemView.findViewById(R.id.tvTeacherInfo)
        private val tvSessionDate: TextView = itemView.findViewById(R.id.tvSessionDate)
        private val tvTimeRange: TextView = itemView.findViewById(R.id.tvTimeRange)
        private val tvStudentsPresent: TextView = itemView.findViewById(R.id.tvStudentsPresent)
        private val btnViewRecording: Button = itemView.findViewById(R.id.btnViewRecording)

        fun bind(video: SessionVideo) {
            tvTeacherInfo.text = "teacher-${video.teacherName}(${video.teacherId})"
            tvSessionDate.text = video.date
            tvTimeRange.text = "${video.startTime} – ${video.endTime}"
            tvStudentsPresent.text = "Students Present: ${video.studentCount}"

            btnViewRecording.setOnClickListener {
                onPlayClicked(video)
            }
        }
    }
}
