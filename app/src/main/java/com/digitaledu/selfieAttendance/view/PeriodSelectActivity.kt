package com.example.selfieAttendance.view

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.selfieAttendance.databinding.ActivityPeriodSelectBinding
import com.example.selfieAttendance.db.dao.AppDatabase
import kotlinx.coroutines.launch

class PeriodSelectActivity : ComponentActivity() {

    private lateinit var binding: ActivityPeriodSelectBinding
    private lateinit var db: AppDatabase
    private lateinit var sessionId: String
    private lateinit var teacherId: String
    private var autoAssignedSpId: String = ""
    private lateinit var adapter: PeriodSelectAdapter

    companion object {
        private const val TAG = "PERIOD_SELECT"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPeriodSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)
        sessionId = intent.getStringExtra("SESSION_ID") ?: run {
            Toast.makeText(this, "Missing session ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        teacherId = intent.getStringExtra("TEACHER_ID") ?: ""

        // Disable back button to prevent skipping this screen
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@PeriodSelectActivity, "Back disabled on this screen", Toast.LENGTH_SHORT).show()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        loadPeriods()

        // Skip button → keep auto-assigned, go to ClassSelect
        binding.btnSkipPeriod.setOnClickListener {
            Log.d(TAG, "Teacher skipped period selection. Keeping auto-assigned spId=$autoAssignedSpId")
            navigateToClassSelect()
        }

        // Continue button → override with selected periods + duplicate for multi
        binding.btnContinuePeriod.setOnClickListener {
            if (!::adapter.isInitialized) {
                return@setOnClickListener
            }
            val selected = adapter.getSelectedPeriodIds()
            if (selected.isEmpty()) {
                Toast.makeText(this, "Please select at least one period, or tap Skip", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val firstSpId = selected.first()
                Log.d(TAG, "Teacher selected ${selected.size} period(s): $selected")

                // 1) Update existing attendance rows with FIRST selected period
                db.sessionDao().updateSessionSchoolPeriodId(sessionId, firstSpId)
                db.attendanceDao().updateAttendanceSchoolPeriodId(sessionId, firstSpId)
                Log.d(TAG, "Updated existing rows with spId=$firstSpId")

                // 2) For each ADDITIONAL period, duplicate all attendance rows
                if (selected.size > 1) {
                    val originalRows = db.attendanceDao().getAttendanceBySessionId(sessionId)
                    Log.d(TAG, "Found ${originalRows.size} attendance rows to duplicate for ${selected.size - 1} extra period(s)")

                    for (i in 1 until selected.size) {
                        val extraSpId = selected[i]
                        for (origAtt in originalRows) {
                            val duplicated = origAtt.copy(
                                atteId = java.util.UUID.randomUUID().toString(),
                                attSchoolPeriodId = extraSpId
                            )
                            db.attendanceDao().insertAttendance(duplicated)
                        }
                        Log.d(TAG, "Duplicated ${originalRows.size} rows for spId=$extraSpId")
                    }
                }

                Toast.makeText(
                    this@PeriodSelectActivity,
                    "Periods applied: ${selected.size} selected",
                    Toast.LENGTH_SHORT
                ).show()

                navigateToClassSelect()
            }
        }
    }

    private fun loadPeriods() {
        lifecycleScope.launch {
            // Get the session to find instId and current auto-assigned spId
            val session = db.sessionDao().getSessionById(sessionId)
            if (session == null) {
                Toast.makeText(this@PeriodSelectActivity, "Session not found", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            val instId = session.instId
            autoAssignedSpId = session.attSchoolPeriodId

            // Get all school periods for this institute
            val allPeriods = db.schoolPeriodDao().getAll().filter { it.instId == instId }

            if (allPeriods.isEmpty()) {
                android.app.AlertDialog.Builder(this@PeriodSelectActivity)
                    .setTitle("Setup Missing")
                    .setMessage("school period setup not done yet .please contact authority and setup..\n\ndo you want to procced")
                    .setCancelable(false)
                    .setPositiveButton("Yes") { dialog, _ ->
                        dialog.dismiss()
                        lifecycleScope.launch {
                            db.sessionDao().updateSessionSchoolPeriodId(sessionId, "999")
                            db.attendanceDao().updateAttendanceSchoolPeriodId(sessionId, "999")
                            navigateToClassSelect()
                        }
                    }
                    .setNegativeButton("No") { dialog, _ ->
                        dialog.dismiss()
                        getSharedPreferences("APP_STATE", MODE_PRIVATE).edit().clear().apply()
                        getSharedPreferences("AttendancePrefs", MODE_PRIVATE).edit().clear().apply()
                        val intent = Intent(this@PeriodSelectActivity, AttendanceActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                    .show()
                return@launch
            }

            // Show auto-assigned period info
            val autoAssignedPeriod = allPeriods.find { it.spId == autoAssignedSpId }
            val autoLabel = if (autoAssignedPeriod != null) {
                "Auto-assigned: ${autoAssignedPeriod.spTitle} (${autoAssignedPeriod.spIstTime} - ${autoAssignedPeriod.spEndTime})"
            } else {
                "Auto-assigned: —"
            }
            binding.tvAutoAssigned.text = autoLabel

            adapter = PeriodSelectAdapter(allPeriods, autoAssignedSpId) { spId, isChecked ->
                Log.d(TAG, "Period $spId checked=$isChecked")
            }

            binding.recyclerViewPeriods.layoutManager = LinearLayoutManager(this@PeriodSelectActivity)
            binding.recyclerViewPeriods.adapter = adapter
        }
    }

    private fun navigateToClassSelect() {
        val isMassBunk = intent.getBooleanExtra("IS_MASS_BUNK", false)
        val intent = Intent(this@PeriodSelectActivity, ClassSelectActivity::class.java).apply {
            putExtra("SESSION_ID", sessionId)
            putExtra("TEACHER_ID", teacherId)
            putExtra("IS_MASS_BUNK", isMassBunk)
        }
        startActivity(intent)
        finish()
    }

    override fun onPause() {
        super.onPause()
        getSharedPreferences("APP_STATE", MODE_PRIVATE).edit()
            .putString("CURRENT_SCREEN", "PERIOD_SELECT")
            .putString("SESSION_ID", sessionId)
            .apply()
    }
}
