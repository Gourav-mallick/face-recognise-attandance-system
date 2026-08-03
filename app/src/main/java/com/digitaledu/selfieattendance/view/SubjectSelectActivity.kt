package com.digitaledu.selfieattendance.view


import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.databinding.ActivityPeriodCourseSelectBinding
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import kotlinx.coroutines.launch
import android.widget.LinearLayout
import com.digitaledu.selfieattendance.db.entity.StudentSchedule
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import com.digitaledu.selfieattendance.api.ApiClient
import com.digitaledu.selfieattendance.api.ApiService
import com.digitaledu.selfieattendance.db.entity.PendingScheduleEntity
import com.digitaledu.selfieattendance.utility.CheckNetworkAndInternetUtils
import android.widget.Spinner
import com.digitaledu.selfieattendance.db.entity.Course
import com.digitaledu.selfieattendance.db.entity.CoursePeriod
import com.digitaledu.selfieattendance.db.entity.PendingTeacherAllocationEntity


class SubjectSelectActivity : ComponentActivity() {

    private lateinit var binding: ActivityPeriodCourseSelectBinding
    private lateinit var db: AppDatabase
    private lateinit var sessionId: String
    private lateinit var selectedClasses: List<String>

    private val selectedCourseIds = mutableListOf<String>()

    private var validCpsForSelection: List<CoursePeriod> = emptyList()



    private var isMassBunk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPeriodCourseSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)
        sessionId = intent.getStringExtra("SESSION_ID") ?: return
        selectedClasses = intent.getStringArrayListExtra("SELECTED_CLASSES") ?: emptyList()
        isMassBunk = intent.getBooleanExtra("IS_MASS_BUNK", false)


        //  Disable back press (both button and gesture)
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(
                    this@SubjectSelectActivity,
                    "Back disabled on this screen",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        //  Save app state so that reopening resumes here
        getSharedPreferences("APP_STATE", MODE_PRIVATE)
            .edit()
            .putBoolean("IS_IN_PERIOD_SELECT", true)
            .putString("SESSION_ID", sessionId)
            .apply()

       // setupPeriodDropdown()
        loadCourses()
/*
            binding.btnSubjectAllocation.setOnClickListener {
            openTeacherAllocationPopup()
        }

 */

        binding.btnContinue.setOnClickListener {
            handleContinue()
        }



    }

/*
    //  Load courses from DB
    private fun loadCourses() {
        lifecycleScope.launch {
            val teacherId = db.sessionDao().getSessionById(sessionId)?.teacherId ?: ""

            // 1️⃣ Get course periods for this teacher AND the selected classes
            val assignedCoursePeriods = db.coursePeriodDao().getAllCoursePeriods()
                .filter { cp ->
                    cp.teacherId == teacherId && selectedClasses.contains(cp.classId)
                }

            // 2️⃣ Extract only the courseIds teacher  in selected classes
            val teacherSelectedClassCourseIds = assignedCoursePeriods.map { it.courseId }.toSet()

            // 1️⃣ First try to load teacher's assigned courses
            var courses = db.courseDao().getAllCourses().filter { course ->
                teacherSelectedClassCourseIds.contains(course.courseId)
            }

       // 2️⃣ If teacher has NO assigned courses for this class → load ALL class courses
            // 3️⃣ If no course assigned → Load ALL courses for this class
    /*
            if (courses.isEmpty()) {

                // 1) Get ALL CPs for this class
                val allCpForClass = db.coursePeriodDao().getAllCoursePeriods()
                    .filter { cp -> selectedClasses.contains(cp.classId) }

                // 2) Build popup view
                val inflater = LayoutInflater.from(this@SubjectSelectActivity)
                val view = inflater.inflate(R.layout.dialog_student_not_schedule_checkbox_list, null)
                val container = view.findViewById<LinearLayout>(R.id.containerStudents)

                // 3) Temp selected cpIds
                val selectedCpIds = mutableSetOf<String>()

                allCpForClass.forEach { cp ->
                    selectedCpIds.add(cp.cpId)

                    val course = db.courseDao().getAllCourses().firstOrNull { it.courseId == cp.courseId }
                    val courseName = course?.courseTitle ?: "Course ${cp.courseId}"
                    val cpName = cp.mpLongTitle ?: "Period"

                    val cb = CheckBox(this@SubjectSelectActivity)
                    cb.text = "$courseName\n$cpName (CP: ${cp.cpId})"
                    cb.isChecked = true

                    cb.setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) selectedCpIds.add(cp.cpId)
                        else selectedCpIds.remove(cp.cpId)
                    }

                    container.addView(cb)
                }

                // 4) Popup
                AlertDialog.Builder(this@SubjectSelectActivity)
                    .setTitle("Assign Teacher to Courses")
                    .setView(view)
                    .setPositiveButton("OK") { _, _ ->
                        lifecycleScope.launch {
                            if (selectedCpIds.isEmpty()) {
                                showToast("Select at least one course")
                            } else {
                                allocateTeacherToCourse(selectedCpIds)
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()

                return@launch
            }



     */

            Log.d("CourseSelectActivity", "Filtered Courses: $courses")

            val adapter = SubjectSelectAdapter(courses) { selectedIds ->
                selectedCourseIds.clear()
                selectedCourseIds.addAll(selectedIds)
            }

            binding.recyclerViewCourses.layoutManager =
                LinearLayoutManager(this@SubjectSelectActivity)

            binding.recyclerViewCourses.adapter = adapter
        }
    }



 */


    // 🔹 Load courses from DB
    private fun loadCourses() {
        lifecycleScope.launch(Dispatchers.IO) {

            val session = db.sessionDao().getSessionById(sessionId)
            val teacherId = session?.teacherId ?: ""
            val teacherName = db.teachersDao().getTeacherById(teacherId)?.staffName ?: ""

            Log.d("LOAD_COURSES", "sessionId = $sessionId")
            Log.d("LOAD_COURSES", "teacherId = $teacherId")
            Log.d("LOAD_COURSES", "selectedClasses = $selectedClasses")

            // 1️⃣ All course periods
            val allCoursePeriods = db.coursePeriodDao().getAllCoursePeriods()

            Log.d("LOAD_COURSES", "allCoursePeriods size = ${allCoursePeriods.size}")

            // Detailed CP log (optional but useful)
            allCoursePeriods.forEach { cp ->
                Log.d(
                    "LOAD_COURSES_CP",
                    "cpId=${cp.cpId}, courseId=${cp.courseId}, classId=${cp.classId}, teacherId=${cp.teacherId}, mpId=${cp.mpId}, mpLongTitle=${cp.mpLongTitle}"
                )
            }

            // 2️⃣ Only CPs for the teacher AND selected classes
            val assignedCoursePeriods = allCoursePeriods.filter { cp ->
                cp.teacherId == teacherId &&          // teacher must match
                        selectedClasses.contains(cp.classId)   // class must match
            }


            Log.d("LOAD_COURSES", "assignedCoursePeriods size = ${assignedCoursePeriods.size}")

            assignedCoursePeriods.forEach { cp ->
                Log.d(
                    "ASSIGNED_CP",
                    "cpId=${cp.cpId}, courseId=${cp.courseId}, classId=${cp.classId}, teacherId=${cp.teacherId}"
                )
            }

            // 3️⃣ Load all courses
            val allCourses = db.courseDao().getAllCourses()


            Log.d("LOAD_COURSES", "allCourses size = ${allCourses.size}")

            allCourses.forEach { c ->
                Log.d("ALL_COURSES", "courseId=${c.courseId}, title=${c.courseTitle}, short=${c.courseShortName}")
            }

            // 4️⃣ Convert CP → Course and deduplicate by courseId
            val assignedCourses = assignedCoursePeriods.mapNotNull { cp ->
                allCourses.firstOrNull { it.courseId == cp.courseId }
            }.distinctBy { it.courseId }

            assignedCourses.forEach { c ->
                Log.d("ASSIGNED_COURSES", "courseId=${c.courseId}, title=${c.courseTitle}, short=${c.courseShortName}")
            }
            // 5️⃣ Update UI
            withContext(Dispatchers.Main) {
                if (assignedCourses.isEmpty()) {
                    AlertDialog.Builder(this@SubjectSelectActivity)
                        .setTitle("Setup Missing")
                        .setMessage("$teacherName, you are not assigned to any course. Please contact authority and do setup first, then conduct the session again.")
                        .setCancelable(false)
                        .setPositiveButton("Discard") { dialog, _ ->
                            dialog.dismiss()
                            lifecycleScope.launch(Dispatchers.IO) {
                                db.attendanceDao().deleteAttendanceForSession(sessionId)
                                db.sessionDao().deleteSessionById(sessionId)
                                db.activeClassCycleDao().getAll()
                                    .find { it.sessionId == sessionId }
                                    ?.let { db.activeClassCycleDao().delete(it) }

                                withContext(Dispatchers.Main) {
                                    getSharedPreferences("APP_STATE", MODE_PRIVATE).edit().clear().apply()
                                    getSharedPreferences("AttendancePrefs", MODE_PRIVATE).edit().clear().apply()

                                    val intent = Intent(this@SubjectSelectActivity, AttendanceActivity::class.java)
                                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    startActivity(intent)
                                    finish()
                                }
                            }
                        }
                        .show()
                } else {
                    updateCourseUI(assignedCourses)
                }
            }
        }
    }




    private fun updateCourseUI(courses: List<Course>) {

        val adapter = SubjectSelectAdapter(courses) { selectedIds ->
            selectedCourseIds.clear()
            selectedCourseIds.addAll(selectedIds)
        }

        binding.recyclerViewCourses.layoutManager =
            LinearLayoutManager(this@SubjectSelectActivity)

        binding.recyclerViewCourses.adapter = adapter
    }

    private fun handleContinue() {

        lifecycleScope.launch(Dispatchers.IO) {

            val db = AppDatabase.getDatabase(this@SubjectSelectActivity)

            val session = db.sessionDao().getSessionById(sessionId)
            val teacherId = session?.teacherId ?: ""

            if (isMassBunk) {
                val existingCount = db.attendanceDao().getAttendancesForSession(sessionId).size
                if (existingCount == 0 && session != null) {
                    val students = db.studentsDao().getStudentsByClasses(selectedClasses)
                    val teacherName = db.teachersDao().getTeacherById(session.teacherId)?.staffName ?: ""
                    val instName = db.instituteDao().getInstituteNameById(session.instId) ?: ""
                    val academicYear = db.instituteDao().getInstituteYearById(session.instId) ?: ""
                    val currentTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                    val sessionEndTime = if (session.endTime.isNullOrBlank()) currentTime else session.endTime

                    val attendanceList = students.map { student ->
                        com.digitaledu.selfieattendance.db.entity.Attendance(
                            atteId = com.digitaledu.selfieattendance.db.entity.AttendanceIdGenerator.nextId(),
                            sessionId = sessionId,
                            studentId = student.studentId,
                            studentName = student.studentName,
                            classId = student.classId,
                            status = "A", // Default is Absent
                            markedAt = currentTime,
                            syncStatus = "pending",
                            instId = session.instId,
                            instShortName = instName,
                            date = session.date,
                            startTime = session.startTime,
                            endTime = sessionEndTime,
                            academicYear = academicYear,
                            period = "",
                            teacherId = session.teacherId,
                            teacherName = teacherName,
                            attSchoolPeriodId = session.attSchoolPeriodId
                        )
                    }

                    attendanceList.forEach { att ->
                        db.attendanceDao().insertAttendance(att)
                    }
                    Log.d("MASS_BUNK", "Inserted ${attendanceList.size} absent attendance records for mass bunk")
                }
            }

            Log.d("HANDLE_CONTINUE", "---------------- START ----------------")
            Log.d("HANDLE_CONTINUE", "sessionId=$sessionId")
            Log.d("HANDLE_CONTINUE", "teacherId=$teacherId")
            Log.d("HANDLE_CONTINUE", "selectedClasses=$selectedClasses")
            Log.d("HANDLE_CONTINUE", "selectedCourseIds=$selectedCourseIds")

            // 0) Basic validations
            if (teacherId.isBlank()) {
                Log.e("HANDLE_CONTINUE", "teacherId empty -> stop")
                withContext(Dispatchers.Main) { showToast("Teacher not found") }
                return@launch
            }

            if (selectedCourseIds.isEmpty()) {
                Log.e("HANDLE_CONTINUE", "No courses selected -> stop")
                withContext(Dispatchers.Main) { showToast("Please select or add a course") }
                return@launch
            }

            if (selectedClasses.isEmpty()) {
                Log.e("HANDLE_CONTINUE", "No classes selected -> stop")
                withContext(Dispatchers.Main) { showToast("Please select class first") }
                return@launch
            }

            // 1) ✅ Get ONLY valid CP rows for this Teacher + Selected Classes + Selected Courses
            // IMPORTANT: this DAO must exist
            // getValidCoursePeriods(teacherId, classIds, courseIds)
            val validCps = db.coursePeriodDao().getValidCoursePeriods(
                teacherId = teacherId,
                classIds = selectedClasses,
                courseIds = selectedCourseIds
            )

            validCpsForSelection = validCps

            Log.d("HANDLE_CONTINUE", "validCps size = ${validCps.size}")
            validCps.forEach {
                Log.d(
                    "HANDLE_VALID_CP",
                    "cpId=${it.cpId}, courseId=${it.courseId}, classId=${it.classId}, teacherId=${it.teacherId}, mpId=${it.mpId}, mpLongTitle=${it.mpLongTitle}"
                )
            }

            if (validCps.isEmpty()) {
                Log.e("HANDLE_CONTINUE", "No valid CP found for selected courses/classes for this teacher")
                withContext(Dispatchers.Main) {
                    showToast("No course periods found for your selection")
                }
                return@launch
            }

            // 2) Get present students for this session
            val sessionAttendances = db.attendanceDao().getAttendancesForSession(sessionId)
            Log.d("HANDLE_CONTINUE", "sessionAttendances size = ${sessionAttendances.size}")

            val studentsInSession = sessionAttendances.mapNotNull { att ->
                try {
                    db.studentsDao().getStudentById(att.studentId)
                } catch (e: Exception) {
                    Log.e("HANDLE_CONTINUE", "Student not found in DB: ${att.studentId}")
                    null
                }
            }

            Log.d("HANDLE_CONTINUE", "studentsInSession size = ${studentsInSession.size}")
            studentsInSession.forEach {
                Log.d("HANDLE_CONTINUE_STUDENT", "id=${it.studentId}, name=${it.studentName}, classId=${it.classId}, instId=${it.instId}")
            }

            // 3) Load all schedules (local)
            val schedules = db.studentScheduleDao().getAll()
            Log.d("HANDLE_CONTINUE", "student_schedule rows = ${schedules.size}")

            // 4) ✅ Find students NOT scheduled for the selected course(s)
            // Logic: for each student -> requiredCpIds = validCps filtered by student.classId
            // If student's saved schedules have no intersection with requiredCpIds -> not scheduled
            val notScheduleStudents = studentsInSession.filter { student ->

                val requiredCpIdsForStudent = validCps
                    .filter { it.classId == student.classId }   // ✅ per-student class filter
                    .map { it.cpId }
                    .toSet()

                val studentCpIds = schedules
                    .filter { it.studentId == student.studentId }
                    .map { it.cpId }
                    .toSet()

                val isNotEnrolled = (studentCpIds.intersect(requiredCpIdsForStudent)).isEmpty()

                Log.d(
                    "HANDLE_CONTINUE_CHECK",
                    "student=${student.studentId} classId=${student.classId} " +
                            "requiredCpIds=$requiredCpIdsForStudent " +
                            "studentCpIds=$studentCpIds " +
                            "NOT_ENROLLED=$isNotEnrolled"
                )

                isNotEnrolled
            }

            Log.d("HANDLE_CONTINUE", "notScheduleStudents size = ${notScheduleStudents.size}")

            // 5) If not scheduled students exist -> show checkbox popup
            if (notScheduleStudents.isNotEmpty()) {

                withContext(Dispatchers.Main) {

                    val inflater = LayoutInflater.from(this@SubjectSelectActivity)
                    val view = inflater.inflate(R.layout.dialog_student_not_schedule_checkbox_list, null)
                    val container = view.findViewById<LinearLayout>(R.id.containerStudents)

                    val tempSelected = mutableSetOf<String>()

                    notScheduleStudents.forEach { s ->
                        tempSelected.add(s.studentId)

                        val cb = CheckBox(this@SubjectSelectActivity)
                        cb.text = "${s.studentName} (${s.studentId})"
                        cb.isChecked = true

                        cb.setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) tempSelected.add(s.studentId)
                            else tempSelected.remove(s.studentId)
                        }

                        container.addView(cb)
                    }

                    AlertDialog.Builder(this@SubjectSelectActivity)
                        .setTitle("Students Not Schedule : (${notScheduleStudents.size})")
                        .setView(view)
                        .setPositiveButton("OK") { _, _ ->

                            lifecycleScope.launch(Dispatchers.IO) {

                                var removedCount = 0

                                // remove unchecked students from attendance
                                notScheduleStudents.forEach { s ->
                                    if (!tempSelected.contains(s.studentId)) {
                                        removedCount++
                                        Log.d(
                                            "HANDLE_CONTINUE_DELETE",
                                            "Removing attendance for studentId=${s.studentId} name=${s.studentName} session=$sessionId"
                                        )
                                        db.attendanceDao().deleteAttendanceForStudent(sessionId, s.studentId)
                                    }
                                }

                                Log.d("HANDLE_CONTINUE", "removedCount=$removedCount")

                                if (removedCount > 0) {
                                    withContext(Dispatchers.Main) {
                                        showToast("Unchecked $removedCount students, their attendance ignored by the system.")
                                    }
                                }

                                // ✅ Scheduling call (IMPORTANT)
                                // You should update your scheduling function to use validCps per student class.
                                // Example:
                                // scheduleStudentsForSelectedCoursesV2(tempSelected, validCps, selectedCourseIds)

                                Log.d("HANDLE_CONTINUE", "Calling scheduling API for selected students = ${tempSelected.size}")
                                // TODO: replace with your updated scheduling function
                                scheduleStudentsForSelectedCourses(tempSelected, validCps)

                                // continue flow
                                val remainingAttendance = db.attendanceDao().getAttendancesForSession(sessionId)
                                Log.d("HANDLE_CONTINUE", "remainingAttendance size after delete = ${remainingAttendance.size}")

                                withContext(Dispatchers.Main) {
                                    continueAndSaveSelectedCourse()
                                }
                            }
                        }
                        .setNegativeButton("Cancel") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .setCancelable(false)
                        .show()
                }

                Log.d("HANDLE_CONTINUE", "Popup shown -> stop until OK")
                return@launch
            }

            // 6) If all students scheduled -> continue directly
            Log.d("HANDLE_CONTINUE", "All students are scheduled -> continue")
            withContext(Dispatchers.Main) {
                continueAndSaveSelectedCourse()
            }

            Log.d("HANDLE_CONTINUE", "---------------- END ----------------")
        }
    }


//    private fun continueAndSaveSelectedCourse() {
//
//        lifecycleScope.launch {
//
//            val db = AppDatabase.getDatabase(this@SubjectSelectActivity)
//            val isMultiCourse = selectedCourseIds.size > 1
//            val isNoCourse = selectedCourseIds.isEmpty()
//
//            try {
//
//                when {
//                    isNoCourse -> {
//                        showToast("Please select or add a course")
//
//                    }
//
//                    isMultiCourse -> {
//
//                        val teacherId = db.sessionDao().getSessionById(sessionId)?.teacherId ?: ""
//
//                        val validCps = validCpsForSelection
//                            .filter { cp ->
//                                cp.teacherId == teacherId &&
//                                        selectedCourseIds.contains(cp.courseId) &&
//                                        selectedClasses.contains(cp.classId)
//                            }
//
//                        Log.d("ATT_UPDATE", "validCpsForSelection size=${validCpsForSelection.size}")
//                        Log.d("ATT_UPDATE", "validCps used size=${validCps.size}")
//                        validCps.forEach {
//                            Log.d("ATT_UPDATE_CP", "cpId=${it.cpId}, courseId=${it.courseId}, classId=${it.classId}, teacherId=${it.teacherId}")
//                        }
//
//                        if (validCps.isEmpty()) {
//                            showToast("No valid CP found for selected courses")
//                            return@launch
//                        }
//
//                        // ✅ cpId/mp must come from validCps
//                        val combinedCpIds = validCps.map { it.cpId }.distinct().joinToString(",")
//                        val combinedCourseIds = validCps.map { it.courseId }.distinct().joinToString(",")
//                        val combinedMpIds = validCps.mapNotNull { it.mpId }.distinct().joinToString(",")
//                        val combinedMpLongTitles = validCps.mapNotNull { it.mpLongTitle }.distinct().joinToString(",")
//
//                        // titles can still come from courseDetails (but DO NOT use its cpId)
//                        val courseDetails = db.courseDao().getCourseDetailsForIds(selectedCourseIds)
//
//                        val combinedCourseTitles = courseDetails.mapNotNull { it.courseTitle }.distinct().joinToString(",")
//                        val combinedCourseShortNames = courseDetails.mapNotNull { it.courseShortName }.distinct().joinToString(",")
//                        val combinedSubjectIds = courseDetails.mapNotNull { it.subjectId }.distinct().joinToString(",")
//                        val combinedSubjectTitles = courseDetails.mapNotNull { it.subjectTitle }.distinct().joinToString(",")
//                        val combinedClassShortNames = courseDetails.mapNotNull { it.classShortName }.distinct().joinToString(",")
//
//                        Log.d("ATT_UPDATE_FINAL",
//                            "combinedCpIds=$combinedCpIds | combinedCourseIds=$combinedCourseIds | mpIds=$combinedMpIds"
//                        )
//
//                        db.attendanceDao().updateAttendanceWithCourseDetails(
//                            sessionId = sessionId,
//                            cpId = combinedCpIds,
//                            courseId = combinedCourseIds,
//                            courseTitle = combinedCourseTitles,
//                            subjectId = combinedSubjectIds,
//                            courseShortName = combinedCourseShortNames,
//                            subjectTitle = combinedSubjectTitles,
//                            classShortName = combinedClassShortNames,
//                            mpId = combinedMpIds,
//                            mpLongTitle = combinedMpLongTitles
//                        )
//
//                        db.sessionDao().updateSessionPeriodAndSubject(sessionId, combinedCourseIds)
//
//                        val intent = Intent(this@SubjectSelectActivity, AttendanceOverviewActivity::class.java)
//                        intent.putStringArrayListExtra("SELECTED_CLASSES", ArrayList(selectedClasses))
//                        intent.putExtra("SESSION_ID", sessionId)
//                        startActivity(intent)
//
//                        kotlinx.coroutines.delay(500)
//                        getSharedPreferences("APP_STATE", MODE_PRIVATE).edit().clear().apply()
//                        getSharedPreferences("AttendancePrefs", MODE_PRIVATE).edit().clear().apply()
//                        finish()
//                    }
//
//
//                    else -> {
//
//                        val courseId = selectedCourseIds.first()
//                        val teacherId = db.sessionDao().getSessionById(sessionId)?.teacherId ?: ""
//
//                        // titles etc. can still come from CourseFullInfo (SAFE)
//                        val courseDetails = db.courseDao().getCourseDetailsForIds(listOf(courseId)).firstOrNull()
//                        if (courseDetails == null) {
//                            showToast("Course details not found")
//                            return@launch
//                        }
//
//                        // ✅ cpId / mp must come from VALID CoursePeriod (teacher + selectedClasses + courseId)
//                        val validCps = validCpsForSelection
//                            .filter { cp ->
//                                cp.teacherId == teacherId &&
//                                        cp.courseId == courseId &&
//                                        selectedClasses.contains(cp.classId)
//                            }
//
//                        if (validCps.isEmpty()) {
//                            showToast("No valid course period found for this course")
//                            Log.e("ATT_UPDATE_SINGLE", "No valid CP found for teacher=$teacherId course=$courseId classes=$selectedClasses")
//                            return@launch
//                        }
//
//                        // If multiple classes selected, you may get multiple CPs -> join them
//                        val correctCpIds = validCps.map { it.cpId }.distinct().joinToString(",")
//                        val correctMpIds = validCps.mapNotNull { it.mpId }.distinct().joinToString(",")
//                        val correctMpLongTitles = validCps.mapNotNull { it.mpLongTitle }.distinct().joinToString(",")
//
//                        Log.d("ATT_UPDATE_SINGLE", "courseId=$courseId teacherId=$teacherId")
//                        validCps.forEach {
//                            Log.d("ATT_UPDATE_SINGLE_CP", "cpId=${it.cpId} classId=${it.classId} mpId=${it.mpId}")
//                        }
//                        Log.d("ATT_UPDATE_SINGLE_FINAL", "cpIds=$correctCpIds mpIds=$correctMpIds")
//
//                        // ⚠️ Your DAO updates by sessionId only, so looping classes is not needed
//                        db.attendanceDao().updateAttendanceWithCourseDetails(
//                            sessionId = sessionId,
//                            cpId = correctCpIds,                 // ✅ FIXED
//                            courseId = courseDetails.courseId,
//                            courseTitle = courseDetails.courseTitle,
//                            subjectId = courseDetails.subjectId,
//                            courseShortName = courseDetails.courseShortName,
//                            subjectTitle = courseDetails.subjectTitle,
//                            classShortName = courseDetails.classShortName,
//                            mpId = correctMpIds,                 // ✅ FIXED
//                            mpLongTitle = correctMpLongTitles    // ✅ FIXED
//                        )
//
//                        db.sessionDao().updateSessionPeriodAndSubject(sessionId, courseId)
//
//                        val intent = Intent(this@SubjectSelectActivity, AttendanceOverviewActivity::class.java)
//                        intent.putStringArrayListExtra("SELECTED_CLASSES", ArrayList(selectedClasses))
//                        intent.putExtra("SESSION_ID", sessionId)
//                        startActivity(intent)
//
//                        kotlinx.coroutines.delay(500)
//                        getSharedPreferences("APP_STATE", MODE_PRIVATE).edit().clear().apply()
//                        getSharedPreferences("AttendancePrefs", MODE_PRIVATE).edit().clear().apply()
//                        finish()
//                    }
//
//                }
//
//            } catch (e: Exception) {
//                e.printStackTrace()
//            }
//        }
//    }

    // =========================================
// 2) SubjectSelectActivity - FULL FUNCTION
//    continueAndSaveSelectedCourse()
// =========================================
    private fun continueAndSaveSelectedCourse() {

        lifecycleScope.launch(Dispatchers.IO) {

            val db = AppDatabase.getDatabase(this@SubjectSelectActivity)

            val isMultiCourse = selectedCourseIds.size > 1
            val isNoCourse = selectedCourseIds.isEmpty()

            try {

                when {
                    isNoCourse -> {
                        withContext(Dispatchers.Main) {
                            showToast("Please select or add a course")
                        }
                        return@launch
                    }

                    isMultiCourse -> {

                        val teacherId = db.sessionDao().getSessionById(sessionId)?.teacherId ?: ""
                        if (teacherId.isBlank()) {
                            withContext(Dispatchers.Main) { showToast("Teacher not found") }
                            return@launch
                        }

                        // ✅ Use validCpsForSelection (already computed in handleContinue())
                        // Re-filter just to be safe
                        val validCps = validCpsForSelection.filter { cp ->
                            cp.teacherId == teacherId &&
                                    selectedCourseIds.contains(cp.courseId) &&
                                    selectedClasses.contains(cp.classId)
                        }

                        if (validCps.isEmpty()) {
                            withContext(Dispatchers.Main) { showToast("No valid course periods found") }
                            return@launch
                        }

                        // ✅ Pull CourseFullInfo once (titles, subjectTitle, classShortName)
                        val courseInfoList = db.courseDao().getCourseDetailsForIds(selectedCourseIds)
                        val courseInfoMap = courseInfoList
                            .filter { !it.courseId.isNullOrBlank() }
                            .associateBy { it.courseId!! }

                        // ✅ cpId -> CoursePeriod (for mpId/mpLongTitle/classId)
                        val cpMap = validCps.associateBy { it.cpId }

                        // ✅ Get session attendance rows (each student)
                        val attendances = db.attendanceDao().getAttendancesForSession(sessionId)

                        // ✅ Load schedules once
                        val allSchedules = db.studentScheduleDao().getAll()

                        // We want deterministic selection order (UI order)
                        val selectedCourseOrder = selectedCourseIds.toList()

                        // ✅ For each attendance/student: pick correct cp/course and update attendance row
                        for (att in attendances) {

                            val studentId = att.studentId
                            val student = db.studentsDao().getStudentById(studentId)
                            val studentClassId = student.classId

                            val studentSchedules = allSchedules.filter { it.studentId == studentId }

                            // ✅ find schedule that matches:
                            // - course is one of selectedCourseIds
                            // - cpId exists in validCps
                            // - cp.classId matches student's class (important)
                            val matchedSchedule: StudentSchedule? = selectedCourseOrder
                                .asSequence()
                                .mapNotNull { selectedCourseId ->
                                    studentSchedules.firstOrNull { sch ->
                                        sch.courseId == selectedCourseId &&
                                                cpMap.containsKey(sch.cpId) &&
                                                (cpMap[sch.cpId]?.classId == studentClassId)
                                    }
                                }
                                .firstOrNull()

                            if (matchedSchedule == null) {
                                // No match -> skip update (or you can clear fields if you want)
                                Log.w("ATT_PER_STUDENT", "No schedule match for student=$studentId class=$studentClassId")
                                continue
                            }

                            val matchedCp = cpMap[matchedSchedule.cpId]
                            val matchedInfo = courseInfoMap[matchedSchedule.courseId]

                            // class short name should come from class table (more accurate per student class)
                            val classShortName = db.classDao().getClassById(studentClassId)?.classShortName
                                ?: matchedInfo?.classShortName

                            // ✅ Update ONLY this student's attendance row
                            db.attendanceDao().updateAttendanceWithCourseDetailsForStudent(
                                sessionId = sessionId,
                                studentId = studentId,
                                cpId = matchedSchedule.cpId,
                                courseId = matchedSchedule.courseId,
                                courseTitle = matchedInfo?.courseTitle,
                                courseShortName = matchedInfo?.courseShortName,
                                subjectId = matchedInfo?.subjectId,
                                subjectTitle = matchedInfo?.subjectTitle,
                                classShortName = classShortName,
                                mpId = matchedCp?.mpId,
                                mpLongTitle = matchedCp?.mpLongTitle
                            )

                            Log.d(
                                "ATT_PER_STUDENT",
                                "UPDATED student=$studentId course=${matchedSchedule.courseId} cpId=${matchedSchedule.cpId} mpId=${matchedCp?.mpId}"
                            )
                        }

                        // ✅ Session field is not truly single now (multi-course); store comma list for display only
                        val combinedCourseIds = selectedCourseIds.distinct().joinToString(",")
                        db.sessionDao().updateSessionPeriodAndSubject(sessionId, combinedCourseIds)

                        // Ensure all non-scanned students in selected classes have absent ("A") records
                        ensureAbsentRecordsForSession(db, sessionId, selectedClasses)

                        // ✅ Navigate to PeriodSelectActivity
                        withContext(Dispatchers.Main) {
                            val intent = Intent(this@SubjectSelectActivity, PeriodSelectActivity::class.java).apply {
                                putExtra("SESSION_ID", sessionId)
                                putExtra("TEACHER_ID", teacherId)
                                putStringArrayListExtra("SELECTED_CLASSES", ArrayList(selectedClasses))
                                putExtra("IS_MASS_BUNK", isMassBunk)
                            }
                            startActivity(intent)
                            finish()
                        }
                    }

                    else -> {

                        val courseId = selectedCourseIds.first()
                        val teacherId = db.sessionDao().getSessionById(sessionId)?.teacherId ?: ""

                        val courseDetails = db.courseDao()
                            .getCourseDetailsForIds(listOf(courseId))
                            .firstOrNull()

                        if (courseDetails == null) {
                            withContext(Dispatchers.Main) { showToast("Course details not found") }
                            return@launch
                        }

                        // ✅ Only valid CPs for this teacher + course + selected classes
                        val validCps = validCpsForSelection.filter { cp ->
                            cp.teacherId == teacherId &&
                                    cp.courseId == courseId &&
                                    selectedClasses.contains(cp.classId)
                        }

                        if (validCps.isEmpty()) {
                            withContext(Dispatchers.Main) { showToast("No valid course period found for this course") }
                            return@launch
                        }

                        // ✅ Map classId -> cp (so each class gets its own single CP)
                        val cpByClass = validCps.associateBy { it.classId }

                        // ✅ Update attendance per student with correct cpId based on student's class
                        val attendances = db.attendanceDao().getAttendancesForSession(sessionId)

                        for (att in attendances) {

                            val studentId = att.studentId
                            val student = try { db.studentsDao().getStudentById(studentId) } catch (e: Exception) { null }
                            if (student == null) continue

                            val studentClassId = student.classId
                            val matchedCp = cpByClass[studentClassId]

                            if (matchedCp == null) {
                                Log.w("ATT_SINGLE", "No CP for student=$studentId class=$studentClassId")
                                continue
                            }

                            val classShortName = db.classDao().getClassById(studentClassId)?.classShortName
                                ?: courseDetails.classShortName

                            db.attendanceDao().updateAttendanceWithCourseDetailsForStudent(
                                sessionId = sessionId,
                                studentId = studentId,
                                cpId = matchedCp.cpId,           // ✅ SINGLE cpId
                                courseId = courseId,             // ✅ SINGLE courseId
                                courseTitle = courseDetails.courseTitle,
                                courseShortName = courseDetails.courseShortName,
                                subjectId = courseDetails.subjectId,
                                subjectTitle = courseDetails.subjectTitle,
                                classShortName = classShortName,
                                mpId = matchedCp.mpId,
                                mpLongTitle = matchedCp.mpLongTitle
                            )

                            Log.d("ATT_SINGLE", "UPDATED student=$studentId cpId=${matchedCp.cpId} class=$studentClassId")
                        }

                        // Session can keep courseId for display
                        db.sessionDao().updateSessionPeriodAndSubject(sessionId, courseId)

                        // Ensure all non-scanned students in selected classes have absent ("A") records
                        ensureAbsentRecordsForSession(db, sessionId, selectedClasses)

                        withContext(Dispatchers.Main) {
                            val intent = Intent(this@SubjectSelectActivity, PeriodSelectActivity::class.java).apply {
                                putExtra("SESSION_ID", sessionId)
                                putExtra("TEACHER_ID", teacherId)
                                putStringArrayListExtra("SELECTED_CLASSES", ArrayList(selectedClasses))
                                putExtra("IS_MASS_BUNK", isMassBunk)
                            }
                            startActivity(intent)
                            finish()
                        }
                    }

                }

            } catch (e: Exception) {
                Log.e("CONTINUE_SAVE", "Error: ${e.message}", e)
                withContext(Dispatchers.Main) { showToast("Something went wrong") }
            }
        }
    }

    private suspend fun ensureAbsentRecordsForSession(db: AppDatabase, sessionId: String, classIds: List<String>) {
        val sampleAtt = db.attendanceDao().getAttendancesForSession(sessionId).firstOrNull()
        val session = db.sessionDao().getSessionById(sessionId) ?: return

        for (classId in classIds) {
            val allStudents = db.studentsDao().getStudentsByClass(classId)
            val existingAttendances = db.attendanceDao().getAttendancesForClass(sessionId, classId)
            val existingStudentIds = existingAttendances.map { it.studentId }.toSet()

            val missingStudents = allStudents.filter { it.studentId !in existingStudentIds }
            for (student in missingStudents) {
                val absentAtt = com.digitaledu.selfieattendance.db.entity.Attendance(
                    atteId = java.util.UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    studentId = student.studentId,
                    studentName = student.studentName ?: "",
                    classId = classId,
                    status = "A",
                    markedAt = sampleAtt?.markedAt ?: java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()),
                    syncStatus = "pending",
                    instId = session.instId ?: sampleAtt?.instId ?: "",
                    instShortName = sampleAtt?.instShortName ?: "",
                    date = session.date ?: sampleAtt?.date ?: "",
                    startTime = session.startTime ?: sampleAtt?.startTime ?: "",
                    endTime = session.endTime ?: sampleAtt?.endTime ?: "",
                    academicYear = sampleAtt?.academicYear ?: "",
                    period = sampleAtt?.period ?: session.periodId ?: "",
                    teacherId = session.teacherId ?: sampleAtt?.teacherId ?: "",
                    teacherName = sampleAtt?.teacherName ?: "",
                    attSchoolPeriodId = session.attSchoolPeriodId ?: sampleAtt?.attSchoolPeriodId ?: "",
                    cpId = sampleAtt?.cpId ?: "",
                    courseId = sampleAtt?.courseId ?: "",
                    courseTitle = sampleAtt?.courseTitle ?: "",
                    courseShortName = sampleAtt?.courseShortName ?: "",
                    subjectId = sampleAtt?.subjectId ?: session.subjectId ?: "",
                    subjectTitle = sampleAtt?.subjectTitle ?: "",
                    classShortName = sampleAtt?.classShortName ?: "",
                    mpId = sampleAtt?.mpId ?: "",
                    mpLongTitle = sampleAtt?.mpLongTitle ?: ""
                )
                db.attendanceDao().insertAttendance(absentAtt)
            }
        }
    }


    // ✅ FIXED VERSION (cpId will be correct per student class + teacher + selected course)
    private suspend fun scheduleStudentsForSelectedCourses(
        tempSelected: Set<String>,
        validCps: List<CoursePeriod>
    ) = withContext(Dispatchers.IO) {

        val db = AppDatabase.getDatabase(this@SubjectSelectActivity)

        val session = db.sessionDao().getSessionById(sessionId)
        val teacherId = session?.teacherId ?: ""
        val teacherName = teacherId.let { db.teachersDao().getTeacherNameById(it) } ?: "UNKNOWN"
        val todayDate = session?.date ?: java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date())

        Log.d("SCHEDULER", "START scheduleStudentsForSelectedCourses")
        Log.d("SCHEDULER", "teacherId=$teacherId selectedClasses=$selectedClasses tempSelected=${tempSelected.size}")
        Log.d("SCHEDULER", "validCps=${validCps.size}")

        val actionArray = JSONArray()

        tempSelected.forEach { studentId ->

            val student = try { db.studentsDao().getStudentById(studentId) } catch (e: Exception) { null }
            if (student == null) {
                Log.e("SCHEDULER", "Student not found: $studentId")
                return@forEach
            }

            val studentClassId = student.classId
            val instId = student.instId
            val syear = db.instituteDao().getInstituteYearById(instId) ?: ""
            val classTitle = db.classDao().getClassById(studentClassId)?.classShortName ?: ""

            // ✅ only cp rows for THIS student class
            val cpsForStudent = validCps.filter { it.classId == studentClassId }

            Log.d("SCHEDULER_STUDENT", "student=$studentId class=$studentClassId cpsForStudent=${cpsForStudent.size}")

            cpsForStudent.forEach { cp ->

                val obj = JSONObject()
                obj.put("school_id", instId)
                obj.put("syear", syear)
                obj.put("marking_period_id", cp.mpId ?: "")
                obj.put("mp", cp.mpLongTitle ?: "")

                obj.put("class_id", studentClassId)
                obj.put("class_title", classTitle)

                obj.put("subjectId", "") // optional
                obj.put("headId", "")

                obj.put("course_id", cp.courseId)
                obj.put("course_period_id", cp.cpId) // ✅ correct cpId

                obj.put("cp_title", "") // optional (fill if you want)
                obj.put("teacher_id", teacherId)
                obj.put("teacher_name", teacherName)

                obj.put("student_id", studentId)
                obj.put("student_name", student.studentName ?: "")

                obj.put("start_date", todayDate)
                obj.put("created_by", "1")
                obj.put("isCreateScheduling", "Y")
                obj.put("isUpdateScheduling", "N")

                actionArray.put(obj)

                Log.d("SCHEDULER_ROW", "ADD student=$studentId course=${cp.courseId} cpId=${cp.cpId}")
            }
        }

        if (actionArray.length() == 0) {
            Log.d("SCHEDULER", "No rows to send.")
            return@withContext
        }

        val bodyObj = JSONObject().apply {
            put("smParamDataObj", JSONObject().apply {
                put("actionType", "addUpdateStudentSubjectSchedulingTblDetails")
                put("actionData", actionArray)
            })
        }

        val jsonString = bodyObj.toString()
        Log.d("SCHEDULER_PAYLOAD", jsonString)
        // Network checks
        val hasNetwork = CheckNetworkAndInternetUtils.isNetworkAvailable(this@SubjectSelectActivity)
        val hasInternet = if (hasNetwork) CheckNetworkAndInternetUtils.hasInternetAccess() else false
        Log.d("SCHEDULER_NET", "hasNetwork=$hasNetwork hasInternet=$hasInternet")

        var serverSuccess = false

        if (hasNetwork && hasInternet) {
            try {
                val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
                val baseUrl = prefs.getString("baseUrl", "")!!
                val hash = prefs.getString("hash", "")!!

                val apiService = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)

                val mediaType = okhttp3.MediaType.parse("application/json")
                val requestBody = okhttp3.RequestBody.create(mediaType, jsonString)

                Log.d("SCHEDULER_REQUEST", "Posting schedules count=${actionArray.length()}")

                val response = apiService.postStudentSubjectSchedule(body = requestBody)

                if (response.isSuccessful && response.body() != null) {

                    val respStr = response.body()!!.string()
                    Log.d("SCHEDULER", "Server response: $respStr")

                    val respJson = JSONObject(respStr)
                    val status = respJson.optJSONObject("collection")
                        ?.optJSONObject("response")
                        ?.optString("status")

                    if (status.equals("SUCCESS", ignoreCase = true)) {
                        serverSuccess = true

                        val msgArr = respJson
                            .optJSONObject("collection")
                            ?.optJSONObject("response")
                            ?.optJSONArray("msgArr")

                        val successMsg = if (msgArr != null && msgArr.length() > 0) {
                            msgArr.getString(0)
                        } else {
                            "Students scheduled successfully"
                        }

                        showToast(successMsg)

                        Log.e("SCHEDULER_SUCCESS", "----- SERVER SUCCESS -----")
                        Log.e("SCHEDULER_SUCCESS", "sentRows=${actionArray.length()}")
                        Log.e("SCHEDULER_SUCCESS", "--------------------------")

                    } else {
                        Log.w("SCHEDULER", "Server returned non-success status=$status -> will save pending")
                        serverSuccess = false
                    }

                } else {
                    val err = response.errorBody()?.string()
                    Log.e("SCHEDULER", "Server call failed. code=${response.code()} err=$err")
                    serverSuccess = false
                }

            } catch (e: Exception) {
                Log.e("SCHEDULER", "Exception while calling scheduling API: ${e.message}", e)
                serverSuccess = false
            }
        } else {
            Log.w("SCHEDULER", "No network/internet -> save pending")
        }

        // IF API FAIL -> SAVE PENDING ROWS (same as your logic)
        if (!serverSuccess) {

            for (i in 0 until actionArray.length()) {
                val o = actionArray.getJSONObject(i)

                val pending = PendingScheduleEntity(
                    school_id = o.getString("school_id"),
                    syear = o.getString("syear"),
                    marking_period_id = o.getString("marking_period_id"),
                    mp = o.getString("mp"),

                    class_id = o.getString("class_id"),
                    class_title = o.getString("class_title"),

                    subjectId = o.getString("subjectId"),
                    headId = o.getString("headId"),

                    course_id = o.getString("course_id"),
                    course_period_id = o.getString("course_period_id"),
                    cp_title = o.getString("cp_title"),

                    teacher_id = o.getString("teacher_id"),
                    teacher_name = o.getString("teacher_name"),

                    student_id = o.getString("student_id"),
                    student_name = o.getString("student_name"),

                    start_date = o.getString("start_date"),
                    created_by = o.getString("created_by"),

                    isCreateScheduling = o.getString("isCreateScheduling"),
                    isUpdateScheduling = o.getString("isUpdateScheduling"),

                    syncStatus = "pending"
                )

                db.pendingScheduleDao().insertSchedule(pending)
            }

            Log.e("SCHEDULER", "API FAILED -> Saved ${actionArray.length()} pending schedules locally")
            showToast("Students scheduled successfully")

            return@withContext
        }

        //  LOCAL student_schedule insertion should use the SAME cpIds we actually sent (from actionArray)
        val nowTs = System.currentTimeMillis()
        val toInsert = mutableListOf<StudentSchedule>()

        // Load schedules once (performance + correct duplicate check)
        val existingSchedules = db.studentScheduleDao().getAll()

        for (i in 0 until actionArray.length()) {
            val o = actionArray.getJSONObject(i)

            val studentId = o.getString("student_id")
            val cpId = o.getString("course_period_id")
            val courseId = o.getString("course_id")

            val exists = existingSchedules.any { it.studentId == studentId && it.cpId == cpId }
            if (exists) {
                Log.d("SCHEDULER", "Skipping duplicate schedule for student=$studentId cp=$cpId")
                continue
            }

            val scheduleId = "sch_${nowTs}_${studentId}_${cpId}"

            toInsert.add(
                StudentSchedule(
                    scheduleId = scheduleId,
                    studentId = studentId,
                    cpId = cpId,
                    courseId = courseId,
                    scheduleStartDate = nowTs.toString(),
                    scheduleEndDate = null,
                    syncStatus = "complete"
                )
            )

            Log.d("SCHEDULER_LOCAL", "Will save local schedule: id=$scheduleId student=$studentId cp=$cpId course=$courseId")
        }

        if (toInsert.isNotEmpty()) {
            try {
                db.studentScheduleDao().insertAll(toInsert)
                Log.d("SCHEDULER_LOCAL", "Inserted local student_schedule rows = ${toInsert.size}")
            } catch (e: Exception) {
                Log.e("SCHEDULER_LOCAL", "Error inserting local schedules: ${e.message}", e)
            }
        } else {
            Log.d("SCHEDULER_LOCAL", "Nothing to insert into student_schedule")
        }

        Log.d("SCHEDULER", "---------------- END scheduleStudentsForSelectedCourses ----------------")
    }

    private fun showToast(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }




    private suspend fun allocateTeacherToCourse(selectedCpIds: Set<String>) = withContext(Dispatchers.IO) {

        Log.e("ALLOC_TEACHER", "START allocateTeacherToCourse")
        Log.e("ALLOC_TEACHER", "Selected CP IDs = $selectedCpIds")

        val session = db.sessionDao().getSessionById(sessionId)
        val teacherId = session?.teacherId ?: ""
        Log.e("ALLOC_TEACHER", "Session teacherId = $teacherId")

        val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
        val baseUrl = prefs.getString("baseUrl", "")!!
        val hash = prefs.getString("hash", "")!!

        Log.e("ALLOC_TEACHER", "BaseURL = $baseUrl   Hash = $hash")

        // Build JSON (same as old code)
        val req = JSONObject().apply {
            put("actionType", "addTeacher")
            put("actionData", JSONArray(selectedCpIds.toList()))
            put("otherDetails", JSONObject().apply {
                put("comment", "add another teacher to this course")
                put("utilityName", "Manage Teacher Allocation")
                put("user_id", "199")
                put("teacherToAdd", teacherId)
            })
        }

        val jsonString = req.toString()
        Log.e("ALLOC_TEACHER", "Request JSON = $jsonString")
        // Check network
        val hasNetwork = CheckNetworkAndInternetUtils.isNetworkAvailable(this@SubjectSelectActivity)
        val hasInternet = if (hasNetwork) CheckNetworkAndInternetUtils.hasInternetAccess() else false
        Log.e("ALLOC_TEACHER", "NetworkAvailable=$hasNetwork  Internet=$hasInternet")

        var serverSuccess = false

        if (hasNetwork && hasInternet) {
            try {
                Log.e("ALLOC_TEACHER", "Trying API call now…")
                val api = ApiClient.getClient(baseUrl, hash).create(ApiService::class.java)
                val body = okhttp3.RequestBody.create(
                    okhttp3.MediaType.parse("application/json"), jsonString
                )
                val response = api.postTeacherAllocation(body)

                Log.e("ALLOC_TEACHER", "API Response code = ${response.code()}")

                if (response.isSuccessful && response.body() != null) {

                    val respStr = response.body()!!.string()
                    Log.e("ALLOC_TEACHER", "API Response body = $respStr")
                    val respJson = JSONObject(respStr)

                    val status = respJson
                        .optJSONObject("collection")
                        ?.optJSONObject("response")
                        ?.optJSONObject("updationStatus")
                        ?.optString("status")

                    Log.e("ALLOC_TEACHER", "Server status = $status")

                    if (status == "SUCCESS") {
                        serverSuccess = true
                        Log.e("ALLOC_TEACHER", "Teacher allocation SUCCESS from server")
                        showToast("Teacher allocated successfully")
                    }else {
                        Log.e("ALLOC_TEACHER", "Server returned FAILURE / null")
                    }
                }else {
                    Log.e("ALLOC_TEACHER", "API not successful. Code = ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e("ALLOC_TEACHER", "Exception during API call = ${e.message}")
                serverSuccess = false
            }
        }else {
            Log.e("ALLOC_TEACHER", "Skipping API call → No network OR no internet")
        }


        // If FAIL → SAVE LOCALLY
        if (!serverSuccess) {
            Log.e("ALLOC_TEACHER", "Saving teacher allocation OFFLINE")
            val pending = PendingTeacherAllocationEntity(
                teacherId = teacherId,
                cpIds = selectedCpIds.joinToString(","),
                jsonPayload = jsonString,
                syncStatus = "pending"
            )

            db.pendingTeacherAllocationDao().insert(pending)

            Log.e("ALLOC_TEACHER", "Inserted into pending_teacher_allocation table")

            // STILL update local CPs for immediate UI
            selectedCpIds.forEach { cpId ->
                Log.e("ALLOC_TEACHER", "Updating local CP teacherId for cpId = $cpId")
                val cp = db.coursePeriodDao().getCoursePeriodByCpId(cpId)
                if (cp != null) {
                    val updated = cp.copy(teacherId = teacherId)
                    db.coursePeriodDao().insertAll(listOf(updated))
                    Log.e("ALLOC_TEACHER", "Local CP updated")
                }else {
                    Log.e("ALLOC_TEACHER", "cpId $cpId not found in DB")
                }
            }

            showToast("Saved offline. Will sync when internet is available.")
        }

        withContext(Dispatchers.Main) { loadCourses() }
    }



    private fun openTeacherAllocationPopup() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.dialog_teacher_allocation, null)

        val spinnerMp = view.findViewById<Spinner>(R.id.spinnerMp)
        val container = view.findViewById<LinearLayout>(R.id.containerCourses)

        lifecycleScope.launch {

            // Load MP list from CoursePeriods
            val allCps = db.coursePeriodDao().getAllCoursePeriods()
            // Build a list of pairs (mpId, mpLongTitle)
            val mpItems = allCps
                .filter { it.mpId != null && it.mpLongTitle != null }
                .groupBy { it.mpId }
                .map { entry ->
                    Pair(entry.key!!, entry.value.first().mpLongTitle!!) // mpId -> mpLongTitle
                }

            spinnerMp.adapter = ArrayAdapter(
                this@SubjectSelectActivity,
                android.R.layout.simple_spinner_dropdown_item,
                mpItems.map { it.second }  // show mpLongTitle
            )


            // Handle MP selection change
            spinnerMp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>, view: View?, position: Int, id: Long
                ) {
                    val selectedMpId = mpItems[position].first  // the actual MP ID
                    lifecycleScope.launch {
                        loadCpCheckboxes(selectedMpId, container)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>) {}
            }



            AlertDialog.Builder(this@SubjectSelectActivity)
                .setTitle("Teacher Allocation")
                .setView(view)
                .setPositiveButton("OK") { _, _ ->
                    saveTeacherAllocation(container)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private suspend fun loadCpCheckboxes(mpId: String, container: LinearLayout) {
        withContext(Dispatchers.Main) { container.removeAllViews() }

        val cps = db.coursePeriodDao().getAllCoursePeriods()
            .filter { it.mpId == mpId }

        cps.forEach { cp ->
            val course = db.courseDao().getAllCourses()
                .firstOrNull { it.courseId == cp.courseId }

            val cb = CheckBox(this)
            cb.text = course?.courseShortName ?: "Course"
            cb.tag = cp.cpId

            withContext(Dispatchers.Main) {
                container.addView(cb)
            }
        }
    }


    private fun saveTeacherAllocation(container: LinearLayout) {
        val selectedCpIds = mutableSetOf<String>()

        for (i in 0 until container.childCount) {
            val cb = container.getChildAt(i) as CheckBox
            if (cb.isChecked) selectedCpIds.add(cb.tag.toString())
        }

        if (selectedCpIds.isEmpty()) {
            showToast("Select at least one course period")
            return
        }

        lifecycleScope.launch {
            allocateTeacherToCourse(selectedCpIds)
        }
    }



}
