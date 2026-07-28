package com.digitaledu.selfieattendance.view

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.api.ApiClient
import com.digitaledu.selfieattendance.api.ApiService
import com.digitaledu.selfieattendance.databinding.ActivityEditAttendanceBinding
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.Attendance
import com.digitaledu.selfieattendance.db.entity.AttendanceCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class EditAttendanceActivity : ComponentActivity() {

    private lateinit var binding: ActivityEditAttendanceBinding
    private lateinit var db: AppDatabase
    private lateinit var classId: String
    private lateinit var sessionId: String
    private lateinit var selectedClasses: List<String>

    private var allListItems = mutableListOf<EditListItem>()
    private var filteredListItems = mutableListOf<EditListItem>()
    private lateinit var adapter: EditAttendanceAdapter

    private var isLaunchingVerification = false
    private var pendingVerifyItem: EditListItem.StudentItem? = null

    private val faceVerificationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isLaunchingVerification = false
        if (result.resultCode == Activity.RESULT_OK) {
            val verifiedStudentId = result.data?.getStringExtra("VERIFIED_STUDENT_ID")
            val item = pendingVerifyItem
            if (item != null && item.studentId == verifiedStudentId) {
                item.status = "P"
                adapter.notifyDataSetChanged()
                Toast.makeText(this, "${item.studentName} marked Present!", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Face verification failed or cancelled", Toast.LENGTH_SHORT).show()
        }
        pendingVerifyItem = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditAttendanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)
        classId = intent.getStringExtra("CLASS_ID") ?: ""
        sessionId = intent.getStringExtra("SESSION_ID") ?: ""
        selectedClasses = intent.getStringArrayListExtra("SELECTED_CLASSES") ?: emptyList()

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnSave.setOnClickListener {
            saveChanges()
        }

        setupSearch()
        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val sampleAtt = db.attendanceDao().getAttendancesForSession(sessionId).firstOrNull()
            if (sampleAtt != null) {
                // Fetch dynamic codes from API
                fetchAttendanceCodes(sampleAtt)
            }

            val allStudents = db.studentsDao().getStudentsByClass(classId)
            val attendances = db.attendanceDao().getAttendancesForClass(sessionId, classId)

            val presentStudents = allStudents.filter { s ->
                attendances.any { it.studentId == s.studentId && (it.status == "P" || it.status == "L") }
            }
            val absentStudents = allStudents.filter { s -> !presentStudents.contains(s) }

            val items = mutableListOf<EditListItem>()

            // 1. Present Section
            items.add(EditListItem.Header("Present/Late Students (${presentStudents.size})"))
            presentStudents.forEach { s ->
                val att = attendances.find { it.studentId == s.studentId }
                items.add(
                    EditListItem.StudentItem(
                        studentId = s.studentId,
                        studentName = s.studentName ?: "",
                        isOriginallyPresent = true,
                        status = att?.status ?: "P"
                    )
                )
            }

            // 2. Absent Section
            items.add(EditListItem.Header("Absent/Exempted Students (${absentStudents.size})"))
            absentStudents.forEach { s ->
                val att = attendances.find { it.studentId == s.studentId }
                items.add(
                    EditListItem.StudentItem(
                        studentId = s.studentId,
                        studentName = s.studentName ?: "",
                        isOriginallyPresent = false,
                        status = att?.status ?: "A"
                    )
                )
            }

            withContext(Dispatchers.Main) {
                allListItems.clear()
                allListItems.addAll(items)
                filterList(binding.editSearch.text.toString())

                adapter = EditAttendanceAdapter(filteredListItems) { studentItem ->
                    if (isLaunchingVerification) return@EditAttendanceAdapter
                    isLaunchingVerification = true
                    pendingVerifyItem = studentItem
                    val intent = Intent(this@EditAttendanceActivity, FaceVerificationActivity::class.java).apply {
                        putExtra("STUDENT_ID", studentItem.studentId)
                        putExtra("STUDENT_NAME", studentItem.studentName)
                    }
                    faceVerificationLauncher.launch(intent)
                }
                binding.recyclerViewStudents.layoutManager = LinearLayoutManager(this@EditAttendanceActivity)
                binding.recyclerViewStudents.adapter = adapter
            }
        }
    }

    private suspend fun fetchAttendanceCodes(sampleAtt: Attendance) {
        try {
            val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
            val baseUrl = prefs.getString("baseUrl", "") ?: ""
            val hash = prefs.getString("hash", "") ?: ""

            if (baseUrl.isBlank() || hash.isBlank()) return

            val apiService = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)

            // Construct payload
            val dataJson = JSONObject().apply {
                put("attParamDataObj", JSONObject().apply {
                    put("actionType", "getStudentDetailsForMarkingGlobalAtt")
                    put("actionData", JSONObject().apply {
                        put("instId", sampleAtt.instId)
                        put("cpId", sampleAtt.cpId ?: "")
                        put("attDate", sampleAtt.date)
                        put("spId", sampleAtt.attSchoolPeriodId)
                        put("attendanceMethod", "periodDayWiseAttendance")
                        put("attCategory", "Regular")
                    })
                })
                put("teacher", sampleAtt.teacherName ?: "")
                put("period", sampleAtt.period)
            }

            Log.d("EDIT_ATT_API", "Request payload: $dataJson")

            val response = apiService.getUserAuthenticatedDataRaw(
                r = "api/v1/Att/ManageMarkingGlobalAtt",
                data = dataJson.toString()
            )

            if (response.isSuccessful && response.body() != null) {
                val bodyStr = response.body()!!.string()
                Log.d("EDIT_ATT_API", "Response: $bodyStr")
                val rootJson = JSONObject(bodyStr)
                val collection = rootJson.optJSONObject("collection")
                val responseObj = collection?.optJSONObject("response")
                val dataObj = responseObj?.optJSONObject("data")
                val codeDataObj = dataObj?.optJSONObject("attCodeDataArr")

                if (codeDataObj != null) {
                    val keys = codeDataObj.keys()
                    if (keys.hasNext()) {
                        val firstKey = keys.next()
                        val arr = codeDataObj.optJSONArray(firstKey)
                        if (arr != null) {
                            val codesList = mutableListOf<AttendanceCode>()
                            for (i in 0 until arr.length()) {
                                val item = arr.getJSONObject(i)
                                val code = item.optString("atcCode")
                                val id = item.optString("atcId")
                                val longName = item.optString("atcLongName")
                                val schoolId = item.optString("atcSchoolId")
                                codesList.add(
                                    AttendanceCode(
                                        atcCode = code,
                                        atcId = id,
                                        atcLongName = longName,
                                        atcSchoolId = schoolId
                                    )
                                )
                            }
                            db.attendanceCodeDao().insertAll(codesList)
                            Log.d("EDIT_ATT_API", "Successfully loaded dynamic codes to Room DB")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("EDIT_ATT_API", "Error fetching dynamic codes", e)
        }
    }

    private fun setupSearch() {
        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterList(query: String) {
        if (query.isBlank()) {
            filteredListItems.clear()
            filteredListItems.addAll(allListItems)
        } else {
            val q = query.lowercase(Locale.getDefault())
            val filtered = mutableListOf<EditListItem>()

            var currentHeader: EditListItem.Header? = null
            var addedItemsForHeader = 0

            for (item in allListItems) {
                if (item is EditListItem.Header) {
                    if (currentHeader != null && addedItemsForHeader > 0) {
                        filtered.add(0, currentHeader) // Insert header if it had matches
                    }
                    currentHeader = item
                    addedItemsForHeader = 0
                } else if (item is EditListItem.StudentItem) {
                    if (item.studentName.lowercase(Locale.getDefault()).contains(q) ||
                        item.studentId.lowercase(Locale.getDefault()).contains(q)) {
                        if (addedItemsForHeader == 0 && currentHeader != null) {
                            filtered.add(currentHeader)
                            currentHeader = null
                        }
                        filtered.add(item)
                        addedItemsForHeader++
                    }
                }
            }
            filteredListItems.clear()
            filteredListItems.addAll(filtered)
        }
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun saveChanges() {
        lifecycleScope.launch(Dispatchers.IO) {
            val sampleAtt = db.attendanceDao().getAttendancesForSession(sessionId).firstOrNull()
            if (sampleAtt == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EditAttendanceActivity, "Error saving: Session not found", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            for (item in allListItems) {
                if (item is EditListItem.StudentItem) {
                    val studentId = item.studentId
                    val status = item.status

                    if (item.isOriginallyPresent) {
                        // Was originally present/late
                        if (status == "P" || status == "L") {
                            // Update status in SQLite
                            val att = db.attendanceDao().getAttendanceForStudentInSession(sessionId, studentId)
                            if (att != null) {
                                val updated = att.copy(status = status)
                                db.attendanceDao().insertAttendance(updated)
                            }
                        } else if (status == "A") {
                            // Mark absent by deleting attendance record
                            db.attendanceDao().deleteAttendanceForStudent(sessionId, studentId)
                        }
                    } else {
                        // Was originally absent
                        if (status == "P" || status == "E") {
                            // Create present/exempted record
                            val existing = db.attendanceDao().getAttendanceForStudentInSession(sessionId, studentId)
                            if (existing == null) {
                                val newAtt = Attendance(
                                    atteId = UUID.randomUUID().toString(),
                                    instId = sampleAtt.instId,
                                    instShortName = sampleAtt.instShortName,
                                    academicYear = sampleAtt.academicYear,
                                    classId = sampleAtt.classId,
                                    markedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                                    sessionId = sampleAtt.sessionId,
                                    status = status,
                                    studentId = studentId,
                                    studentName = item.studentName,
                                    syncStatus = "pending",
                                    teacherId = sampleAtt.teacherId,
                                    teacherName = sampleAtt.teacherName,
                                    date = sampleAtt.date,
                                    startTime = sampleAtt.startTime,
                                    endTime = sampleAtt.endTime,
                                    period = sampleAtt.period,
                                    cpId = sampleAtt.cpId,
                                    courseId = sampleAtt.courseId,
                                    courseTitle = sampleAtt.courseTitle,
                                    courseShortName = sampleAtt.courseShortName,
                                    subjectId = sampleAtt.subjectId,
                                    subjectTitle = sampleAtt.subjectTitle,
                                    classShortName = sampleAtt.classShortName,
                                    mpId = sampleAtt.mpId,
                                    mpLongTitle = sampleAtt.mpLongTitle,
                                    attSchoolPeriodId = sampleAtt.attSchoolPeriodId
                                )
                                db.attendanceDao().insertAttendance(newAtt)
                            } else {
                                val updated = existing.copy(status = status)
                                db.attendanceDao().insertAttendance(updated)
                            }
                        } else if (status == "A") {
                            // Delete record if it was previously marked in this edit session
                            db.attendanceDao().deleteAttendanceForStudent(sessionId, studentId)
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@EditAttendanceActivity, "Attendance saved successfully", Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
    }
}

sealed class EditListItem {
    data class Header(val title: String) : EditListItem()
    data class StudentItem(
        val studentId: String,
        val studentName: String,
        val isOriginallyPresent: Boolean,
        var status: String // P, L, A, E
    ) : EditListItem()
}

class EditAttendanceAdapter(
    private val items: List<EditListItem>,
    private val onVerifyFace: (EditListItem.StudentItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_STUDENT = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is EditListItem.Header -> TYPE_HEADER
            is EditListItem.StudentItem -> TYPE_STUDENT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_student_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_student_edit, parent, false)
            StudentViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderViewHolder) {
            val header = items[position] as EditListItem.Header
            holder.textTitle.text = header.title
        } else if (holder is StudentViewHolder) {
            val item = items[position] as EditListItem.StudentItem
            holder.textName.text = item.studentName
            holder.textId.text = "ID: ${item.studentId}"

            // Reset listeners and click listeners
            holder.cbP.setOnCheckedChangeListener(null)
            holder.cbL.setOnCheckedChangeListener(null)
            holder.cbA.setOnCheckedChangeListener(null)
            holder.cbE.setOnCheckedChangeListener(null)
            holder.cbP.setOnClickListener(null)
            holder.cbL.setOnClickListener(null)
            holder.cbA.setOnClickListener(null)
            holder.cbE.setOnClickListener(null)

            if (item.isOriginallyPresent) {
                // Originally present students: can be toggled between P and L
                holder.cbP.visibility = View.VISIBLE
                holder.cbL.visibility = View.VISIBLE
                holder.cbA.visibility = View.GONE
                holder.cbE.visibility = View.GONE

                holder.cbP.isChecked = (item.status == "P")
                holder.cbL.isChecked = (item.status == "L")

                holder.cbP.setOnClickListener {
                    if (holder.cbP.isChecked) {
                        item.status = "P"
                        holder.cbL.isChecked = false
                    } else {
                        item.status = "A"
                        holder.cbP.isChecked = false
                    }
                }

                holder.cbL.setOnClickListener {
                    if (holder.cbL.isChecked) {
                        item.status = "L"
                        holder.cbP.isChecked = false
                    } else {
                        item.status = "A"
                        holder.cbL.isChecked = false
                    }
                }
            } else {
                // Originally absent students: show cbP (requires face verification), cbA, and cbE
                holder.cbP.visibility = View.VISIBLE
                holder.cbL.visibility = View.GONE
                holder.cbA.visibility = View.VISIBLE
                holder.cbE.visibility = View.VISIBLE

                holder.cbP.isChecked = (item.status == "P")
                holder.cbA.isChecked = (item.status == "A")
                holder.cbE.isChecked = (item.status == "E")

                // Camera icon overlay (tint/decoration on cbP text is optional, standard is enough)
                holder.cbP.text = "P 📷"

                holder.cbP.setOnClickListener {
                    if (item.status != "P") {
                        // User wants to mark present: reset checkbox and trigger face verification
                        holder.cbP.isChecked = false
                        onVerifyFace(item)
                    } else {
                        // Unchecking present goes back to Absent
                        item.status = "A"
                        holder.cbP.isChecked = false
                        holder.cbA.isChecked = true
                        holder.cbE.isChecked = false
                    }
                }

                holder.cbA.setOnClickListener {
                    item.status = "A"
                    holder.cbA.isChecked = true
                    holder.cbP.isChecked = false
                    holder.cbE.isChecked = false
                }

                holder.cbE.setOnClickListener {
                    item.status = "E"
                    holder.cbE.isChecked = true
                    holder.cbP.isChecked = false
                    holder.cbA.isChecked = false
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textTitle: TextView = view.findViewById(R.id.textHeaderTitle)
    }

    class StudentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textName: TextView = view.findViewById(R.id.textStudentName)
        val textId: TextView = view.findViewById(R.id.textStudentId)
        val cbP: CheckBox = view.findViewById(R.id.cbP)
        val cbL: CheckBox = view.findViewById(R.id.cbL)
        val cbA: CheckBox = view.findViewById(R.id.cbA)
        val cbE: CheckBox = view.findViewById(R.id.cbE)
    }
}
