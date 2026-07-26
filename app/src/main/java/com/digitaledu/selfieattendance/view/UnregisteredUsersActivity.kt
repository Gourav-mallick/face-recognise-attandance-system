package com.digitaledu.selfieattendance.view

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
    private lateinit var editSearch: EditText
    private lateinit var spinnerUserType: Spinner
    private lateinit var spinnerClass: Spinner
    private lateinit var recyclerViewUsers: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: ProgressBar

    // Database cache
    private var allUnregisteredStudents = listOf<Student>()
    private var allUnregisteredTeachers = listOf<Teacher>()
    private var classMap = mapOf<String, String>() // classId -> classShortName
    private var classList = listOf<Class>()

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

        btnBack = findViewById(R.id.btnBack)
        editSearch = findViewById(R.id.editSearch)
        spinnerUserType = findViewById(R.id.spinnerUserType)
        spinnerClass = findViewById(R.id.spinnerClass)
        recyclerViewUsers = findViewById(R.id.recyclerViewUsers)
        tvEmpty = findViewById(R.id.tvEmpty)
        progressBar = findViewById(R.id.progressBar)

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

                // Fetch unregistered students and teachers
                allUnregisteredStudents = db.studentsDao().getUnregisteredStudents()
                allUnregisteredTeachers = db.teachersDao().getUnregisteredTeachers()

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
        // User Type Spinner
        val userTypes = arrayOf("All Users", "Students", "Teachers")
        val userTypeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, userTypes)
        userTypeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerUserType.adapter = userTypeAdapter

        // Class Spinner
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
                val filteredStudents = allUnregisteredStudents.filter { student ->
                    val matchesClass = selectedClassId == null || student.classId == selectedClassId
                    val matchesSearch = searchQuery.isEmpty() || 
                            student.studentName.lowercase().contains(searchQuery) ||
                            student.studentId.lowercase().contains(searchQuery)
                    matchesClass && matchesSearch
                }
                combinedList.addAll(filteredStudents.map { student ->
                    UnregisteredUser(
                        id = student.studentId,
                        name = student.studentName,
                        type = "Student",
                        classId = student.classId,
                        className = classMap[student.classId] ?: student.classId
                    )
                })
            }

            // 2. Process Teachers
            if (selectedType == "All Users" || selectedType == "Teachers") {
                val filteredTeachers = allUnregisteredTeachers.filter { teacher ->
                    val matchesSearch = searchQuery.isEmpty() || 
                            teacher.staffName.lowercase().contains(searchQuery) ||
                            teacher.staffId.lowercase().contains(searchQuery)
                    matchesSearch
                }
                combinedList.addAll(filteredTeachers.map { teacher ->
                    UnregisteredUser(
                        id = teacher.staffId,
                        name = teacher.staffName,
                        type = "Teacher"
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
            recyclerViewUsers.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            recyclerViewUsers.visibility = View.VISIBLE
        }
    }

    // Unregistered User unified representation
    data class UnregisteredUser(
        val id: String,
        val name: String,
        val type: String,
        val classId: String? = null,
        val className: String? = null
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
                holder.tvUserTypeBadge.setTextColor(0xFF7C3AED.toInt()) // purple text color
                holder.tvUserTypeBadge.setBackgroundResource(R.drawable.bg_badge_teacher)
            }
        }

        override fun getItemCount(): Int = users.size
    }
}
