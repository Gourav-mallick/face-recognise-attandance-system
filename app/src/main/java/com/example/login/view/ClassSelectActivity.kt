package com.example.login.view

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.login.databinding.ActivityClassSelectBinding
import com.example.login.db.dao.AppDatabase
import kotlinx.coroutines.launch

class ClassSelectActivity : ComponentActivity() {

    private lateinit var binding: ActivityClassSelectBinding
    private lateinit var db: AppDatabase
    private lateinit var sessionId: String
    private val selectedClassIds = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClassSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)
        sessionId = intent.getStringExtra("SESSION_ID") ?: return



        // 🔹 Disable back button + back gesture using OnBackPressedDispatcher
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // This is called when the user presses the back button or swipes back
                Toast.makeText(this@ClassSelectActivity, "Back disabled on this screen", Toast.LENGTH_SHORT).show()

            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        // 🔹 Setup WhatsApp-style 3-dots overflow menu
        binding.ibOverflowMenu.setOnClickListener { anchorView ->
            val popup = android.widget.PopupMenu(this, anchorView)
            popup.menuInflater.inflate(com.example.login.R.menu.menu_incomplete_session, popup.menu)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    com.example.login.R.id.menu_save_incomplete -> {
                        saveCurrentSessionAsIncomplete()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }


        val isMassBunk = intent.getBooleanExtra("IS_MASS_BUNK", false)

        lifecycleScope.launch {
            val preSelected = if (isMassBunk) emptyList() else db.attendanceDao().getDistinctClassIdsForCurrentSession(sessionId)
            val allClasses = db.classDao().getAllClasses()

            // 🔹 Filter only preselected classes or teacher's mapped classes
            val classesToShow = if (isMassBunk) {
                val session = db.sessionDao().getSessionById(sessionId)
                val teacherId = session?.teacherId ?: ""
                val mappedClassIds = db.teacherClassMapDao().getClassesForTeacher(teacherId)
                allClasses.filter { mappedClassIds.contains(it.classId) }
            } else {
                allClasses.filter { preSelected.contains(it.classId) }
            }

            selectedClassIds.addAll(preSelected)

            val adapter = ClassSelectAdapter(classesToShow, preSelected) { classId, isChecked, wasPreSelected ->
                handleClassSelectionChange(classId, isChecked, wasPreSelected)
            }

            binding.recyclerViewClasses.layoutManager = LinearLayoutManager(this@ClassSelectActivity)
            binding.recyclerViewClasses.adapter = adapter
        }

        binding.btnContinue.setOnClickListener {
            if (selectedClassIds.isEmpty()) {
                Toast.makeText(this, "Please select at least one class", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                // 🔹 Delete attendance of all unselected classes
                db.attendanceDao().deleteAttendanceNotInClasses(selectedClassIds.toList(), sessionId)

                // 🔹 Update session with selected class IDs
                db.sessionDao().updateSessionClasses(sessionId, selectedClassIds.joinToString(","))

                Toast.makeText(this@ClassSelectActivity, "Classes updated successfully", Toast.LENGTH_SHORT).show()



                // ✅ Clear all saved app state to prevent reopening old fragments
                getSharedPreferences("APP_STATE", MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("AttendancePrefs", MODE_PRIVATE).edit().clear().apply()


                // 🔹 Navigate next
                val intent = Intent(this@ClassSelectActivity, SubjectSelectActivity::class.java).apply {
                    putExtra("SESSION_ID", sessionId)
                    putStringArrayListExtra("SELECTED_CLASSES", ArrayList(selectedClassIds))
                    putExtra("IS_MASS_BUNK", isMassBunk)
                }
                startActivity(intent)
                finish()
            }
        }
    }




    // 🔹 Save app state so when reopened, it resumes here
    override fun onPause() {
        super.onPause()
        getSharedPreferences("APP_STATE", MODE_PRIVATE).edit()
            .putString("CURRENT_SCREEN", "CLASS_SELECT")
            .putString("SESSION_ID", sessionId)
            .apply()
    }

    private fun handleClassSelectionChange(classId: String, isChecked: Boolean, wasPreSelected: Boolean) {
        lifecycleScope.launch {
            if (!isChecked && wasPreSelected) {
                // Class was preselected → warn teacher before removing
                val students = db.attendanceDao().getStudentsForClassInSession(sessionId, classId)

                if (students.isNotEmpty()) {
                  //  val studentListText = students.joinToString("\n") { "${it.studentId} - ${it.studentName}" }

                    runOnUiThread {
                        AlertDialog.Builder(this@ClassSelectActivity)
                            .setTitle("Remove Attandance")
                            .setMessage(
                                "This will Ignore all attendance records for Class ID: ${classId}\nNumber of Students -\n${students.size} \n\n" +
                                        "Are you sure?"
                            )
                            .setPositiveButton("Yes") { _, _ ->
                                lifecycleScope.launch {
                                    db.attendanceDao().deleteAttendanceForClass(sessionId, classId)
                                    selectedClassIds.remove(classId)
                                    Toast.makeText(
                                        this@ClassSelectActivity,
                                        "Attendance removed for ${students.size} student(s)",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            .setNegativeButton("No") { dialog, _ ->
                                dialog.dismiss()
                                selectedClassIds.add(classId) // keep it checked again
                                recreate() // refresh UI
                            }
                            .show()
                    }
                } else {
                    // no students found, just remove silently
                    selectedClassIds.remove(classId)
                }
            } else if (isChecked) {
                selectedClassIds.add(classId)
            }
        }
    }

    private fun saveCurrentSessionAsIncomplete() {
        lifecycleScope.launch {
            try {
                com.example.login.repository.IncompleteSessionManager.saveAsIncompleteSession(
                    context = this@ClassSelectActivity,
                    sessionId = sessionId,
                    currentStage = "STAGE_CLASS_SELECT"
                )
                Toast.makeText(this@ClassSelectActivity, "Session saved as incomplete", Toast.LENGTH_SHORT).show()
                com.example.login.repository.IncompleteSessionManager.navigateToHome(this@ClassSelectActivity)
            } catch (e: Exception) {
                android.util.Log.e("ClassSelectActivity", "Error saving incomplete session: ${e.message}", e)
                Toast.makeText(this@ClassSelectActivity, "Error saving session", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
