package com.digitaledu.selfieattendance.view

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.Class
import com.digitaledu.selfieattendance.db.entity.Student
import com.digitaledu.selfieattendance.db.entity.Teacher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UnregisteredUsersActivity : AppCompatActivity() {

    private lateinit var btnBack: ImageButton
    private lateinit var tvHeaderTitle: TextView
    private lateinit var editSearch: EditText
    private lateinit var spinnerUserType: Spinner
    private lateinit var spinnerStatus: Spinner
    private lateinit var spinnerClass: Spinner
    private lateinit var recyclerViewUsers: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: ProgressBar

    // Database cache
    private var allStudents = listOf<Student>()
    private var allTeachers = listOf<Teacher>()
    private var classMap = mapOf<String, String>() // classId -> classShortName
    private var classList = listOf<Class>()

    // Mode passed via intent: "ALL" / "TOTAL" vs "UNREGISTERED"
    private var showMode: String = "UNREGISTERED"

    // Filtered/combined cache
    private var filteredCombinedList = listOf<UnregisteredUser>()

    // Pagination parameters
    private val PAGE_SIZE = 20
    private var currentPage = 0
    private var isLoading = false
    private var hasMoreItems = true

    private lateinit var adapter: UnregisteredUsersAdapter
    private var displayedUsers = mutableListOf<UnregisteredUser>()

    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unregistered_users)

        showMode = intent.getStringExtra("SHOW_MODE") ?: "UNREGISTERED"

        btnBack = findViewById(R.id.btnBack)
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle)
        editSearch = findViewById(R.id.editSearch)
        spinnerUserType = findViewById(R.id.spinnerUserType)
        spinnerStatus = findViewById(R.id.spinnerStatus)
        spinnerClass = findViewById(R.id.spinnerClass)
        recyclerViewUsers = findViewById(R.id.recyclerViewUsers)
        tvEmpty = findViewById(R.id.tvEmpty)
        progressBar = findViewById(R.id.progressBar)

        if (showMode.equals("ALL", ignoreCase = true) || showMode.equals("TOTAL", ignoreCase = true)) {
            tvHeaderTitle.text = "Total Users"
        } else {
            tvHeaderTitle.text = "Unregistered Users"
        }

        btnBack.setOnClickListener { finish() }

        setupRecyclerView()
        loadInitialData()
    }

    private fun setupRecyclerView() {
        adapter = UnregisteredUsersAdapter(displayedUsers)
        recyclerViewUsers.layoutManager = LinearLayoutManager(this)
        recyclerViewUsers.adapter = adapter

        recyclerViewUsers.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val visibleItemCount = layoutManager.childCount
                val totalItemCount = layoutManager.itemCount
                val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                if (!isLoading && hasMoreItems) {
                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount - 5
                        && firstVisibleItemPosition >= 0
                    ) {
                        recyclerViewUsers.post {
                            loadNextPage()
                        }
                    }
                }
            }
        })
    }

    private fun loadInitialData() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(this@UnregisteredUsersActivity)

                // Fetch classes
                classList = db.classDao().getAllClasses()
                classMap = classList.associate { it.classId to it.classShortName }

                // Fetch ALL students and teachers so filtering by status works dynamically
                allStudents = db.studentsDao().getAllStudents()
                allTeachers = db.teachersDao().getAllTeachers()

                withContext(Dispatchers.Main) {
                    setupSpinners()
                    setupSearch()
                    applyFiltersAndRefresh()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@UnregisteredUsersActivity, "Failed to load data", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupSpinners() {
        // 1. User Type Spinner ("All Users", "Students", "Teachers")
        val userTypes = arrayOf("All Users", "Students", "Teachers")
        val userTypeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, userTypes)
        userTypeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerUserType.adapter = userTypeAdapter

        // 2. Status Spinner ("All Status", "Registered", "Unregistered")
        val statuses = arrayOf("All Status", "Registered", "Unregistered")
        val statusAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, statuses)
        statusAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerStatus.adapter = statusAdapter

        // Default selection based on Intent SHOW_MODE
        if (showMode.equals("ALL", ignoreCase = true) || showMode.equals("TOTAL", ignoreCase = true)) {
            spinnerStatus.setSelection(0) // "All Status"
        } else {
            spinnerStatus.setSelection(2) // "Unregistered"
        }

        // 3. Class Spinner ("All Classes", class 1, class 2...)
        val classesList = mutableListOf("All Classes")
        classesList.addAll(classList.map { it.classShortName })
        val classSpinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, classesList)
        classSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerClass.adapter = classSpinnerAdapter

        // Spinner Listeners
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // If Teachers is selected, disable Class filter
                val selectedType = spinnerUserType.selectedItem.toString()
                if (selectedType == "Teachers") {
                    spinnerClass.isEnabled = false
                    spinnerClass.alpha = 0.5f
                } else {
                    spinnerClass.isEnabled = true
                    spinnerClass.alpha = 1.0f
                }
                applyFiltersAndRefresh()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerUserType.onItemSelectedListener = listener
        spinnerStatus.onItemSelectedListener = listener
        spinnerClass.onItemSelectedListener = listener
    }

    private fun setupSearch() {
        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(300) // debounce
                    applyFiltersAndRefresh()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun applyFiltersAndRefresh() {
        progressBar.visibility = View.VISIBLE
        val selectedType = spinnerUserType.selectedItem?.toString() ?: "All Users"
        val selectedStatus = spinnerStatus.selectedItem?.toString() ?: "All Status"
        val selectedClassShort = spinnerClass.selectedItem?.toString() ?: "All Classes"
        val searchQuery = editSearch.text.toString().trim().lowercase()

        // Resolve class ID if selected
        val selectedClassId = if (selectedClassShort == "All Classes") null else {
            classList.firstOrNull { it.classShortName == selectedClassShort }?.classId
        }

        lifecycleScope.launch(Dispatchers.Default) {
            val combinedList = mutableListOf<UnregisteredUser>()

            // 1. Process Students
            if (selectedType == "All Users" || selectedType == "Students") {
                val filteredStudents = allStudents.filter { student ->
                    val isRegistered = !student.embedding.isNullOrBlank()
                    val matchesStatus = when (selectedStatus) {
                        "Registered" -> isRegistered
                        "Unregistered" -> !isRegistered
                        else -> true
                    }
                    val matchesClass = selectedClassId == null || student.classId == selectedClassId
                    val matchesSearch = searchQuery.isEmpty() ||
                            student.studentName.lowercase().contains(searchQuery) ||
                            student.studentId.lowercase().contains(searchQuery)
                    matchesStatus && matchesClass && matchesSearch
                }
                combinedList.addAll(filteredStudents.map { student ->
                    UnregisteredUser(
                        id = student.studentId,
                        name = student.studentName,
                        type = "Student",
                        classId = student.classId,
                        className = classMap[student.classId] ?: student.classId,
                        isRegistered = !student.embedding.isNullOrBlank()
                    )
                })
            }

            // 2. Process Teachers
            if (selectedType == "All Users" || selectedType == "Teachers") {
                val filteredTeachers = allTeachers.filter { teacher ->
                    val isRegistered = !teacher.embedding.isNullOrBlank()
                    val matchesStatus = when (selectedStatus) {
                        "Registered" -> isRegistered
                        "Unregistered" -> !isRegistered
                        else -> true
                    }
                    val matchesSearch = searchQuery.isEmpty() ||
                            teacher.staffName.lowercase().contains(searchQuery) ||
                            teacher.staffId.lowercase().contains(searchQuery)
                    matchesStatus && matchesSearch
                }
                combinedList.addAll(filteredTeachers.map { teacher ->
                    UnregisteredUser(
                        id = teacher.staffId,
                        name = teacher.staffName,
                        type = "Teacher",
                        isRegistered = !teacher.embedding.isNullOrBlank()
                    )
                })
            }

            // Sort alphabetically by name
            combinedList.sortBy { it.name.lowercase() }

            withContext(Dispatchers.Main) {
                filteredCombinedList = combinedList
                progressBar.visibility = View.GONE
                resetPaginationAndLoadFirstPage()
            }
        }
    }

    private fun resetPaginationAndLoadFirstPage() {
        displayedUsers.clear()
        adapter.notifyDataSetChanged()
        currentPage = 0
        hasMoreItems = true
        loadNextPage()
    }

    private fun loadNextPage() {
        if (isLoading || !hasMoreItems) return

        isLoading = true
        val start = currentPage * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, filteredCombinedList.size)

        if (start >= filteredCombinedList.size) {
            hasMoreItems = false
            isLoading = false
            updateEmptyState()
            return
        }

        val pageItems = filteredCombinedList.subList(start, end)
        displayedUsers.addAll(pageItems)
        adapter.notifyItemRangeInserted(start, pageItems.size)

        currentPage++
        isLoading = false
        if (end >= filteredCombinedList.size) {
            hasMoreItems = false
        }
        updateEmptyState()
    }

    private fun updateEmptyState() {
        if (displayedUsers.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = if (showMode.equals("ALL", ignoreCase = true) || showMode.equals("TOTAL", ignoreCase = true)) {
                "No users found matching filters"
            } else {
                "No unregistered users found matching filters"
            }
            recyclerViewUsers.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            recyclerViewUsers.visibility = View.VISIBLE
        }
    }

    // User representation (Both Students & Teachers)
    data class UnregisteredUser(
        val id: String,
        val name: String,
        val type: String,
        val classId: String? = null,
        val className: String? = null,
        val isRegistered: Boolean = false
    )

    // RecyclerView Adapter and ViewHolder
    private inner class UnregisteredUsersAdapter(
        private val users: List<UnregisteredUser>
    ) : RecyclerView.Adapter<UnregisteredUsersAdapter.UserViewHolder>() {

        inner class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvUserName: TextView = view.findViewById(R.id.tvUserName)
            val tvUserId: TextView = view.findViewById(R.id.tvUserId)
            val tvUserClass: TextView = view.findViewById(R.id.tvUserClass)
            val tvUserTypeBadge: TextView = view.findViewById(R.id.tvUserTypeBadge)
            val tvStatusBadge: TextView = view.findViewById(R.id.tvStatusBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_unregistered_user, parent, false)
            return UserViewHolder(view)
        }

        override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
            val user = users[position]
            holder.tvUserName.text = user.name
            holder.tvUserId.text = "ID: ${user.id}"

            if (user.type == "Student") {
                holder.tvUserClass.visibility = View.VISIBLE
                holder.tvUserClass.text = "Class: ${user.className ?: "N/A"}"
                holder.tvUserTypeBadge.text = "STUDENT"
                holder.tvUserTypeBadge.setTextColor(ContextCompat.getColor(this@UnregisteredUsersActivity, R.color.blue_primary))
                holder.tvUserTypeBadge.setBackgroundResource(R.drawable.bg_badge_student)
            } else {
                holder.tvUserClass.visibility = View.GONE
                holder.tvUserTypeBadge.text = "TEACHER"
                holder.tvUserTypeBadge.setTextColor(Color.parseColor("#7C3AED")) // purple text color
                holder.tvUserTypeBadge.setBackgroundResource(R.drawable.bg_badge_teacher)
            }

            if (user.isRegistered) {
                holder.tvStatusBadge.text = "REGISTERED"
                holder.tvStatusBadge.setTextColor(Color.parseColor("#15803D")) // green text
                holder.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_ongoing)
            } else {
                holder.tvStatusBadge.text = "UNREGISTERED"
                holder.tvStatusBadge.setTextColor(Color.parseColor("#DC2626")) // red text
                holder.tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_student)
            }
        }

        override fun getItemCount(): Int = users.size
    }
}
