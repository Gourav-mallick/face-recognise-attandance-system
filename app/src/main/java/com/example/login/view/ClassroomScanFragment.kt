package com.example.login.view

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.login.R
import androidx.appcompat.app.AlertDialog
import android.widget.EditText
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.example.login.api.ApiClient
import com.example.login.db.dao.AppDatabase
import com.example.login.repository.DataSyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.login.api.ApiService
import com.example.login.db.entity.Session
import com.example.login.BuildConfig
import java.net.URLEncoder
import org.json.JSONObject
import android.util.Log
import android.widget.LinearLayout
import android.widget.ImageView
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class ClassroomScanFragment : Fragment() {

    private lateinit var tvSyncStatus: LinearLayout
    private lateinit var tvVersion:TextView


//    private val updateReceiver = object : BroadcastReceiver() {
//        override fun onReceive(context: Context?, intent: Intent?) {
//            if (intent?.action == "UPDATE_UNSUBMITTED_COUNT") {
//                refreshUnsubmittedSessions()
//            }
//        }
//    }


    companion object {
        fun newInstance() = ClassroomScanFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = inflater.inflate(R.layout.fragment_classroom_scan, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvSyncStatus = view.findViewById(R.id.tvSyncStatus)
        //val tvUnsubmittedCount = view.findViewById<TextView>(R.id.tvUnsubmittedCount)
        val prefs = requireContext().getSharedPreferences("SyncPrefs", Context.MODE_PRIVATE)
        val tvLastSync = view.findViewById<TextView>(R.id.tvLastSync)

        val lastSync = prefs.getString("last_sync_time", null)
/*
        if (lastSync != null) {
            tvLastSync.text = "Last Sync: $lastSync"
        }

 */
        val versionName = BuildConfig.VERSION_NAME
        val tvVersion =view.findViewById<TextView>(R.id.tvVersion)
        tvVersion.text = "Version $versionName"

        val tvVersionSettings = view.findViewById<TextView>(R.id.tvVersionSettings)
        tvVersionSettings?.text = "Version $versionName"


        val inputFormat = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", java.util.Locale.getDefault())
        val outputFormat = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault())

        val formattedTime = try {
            inputFormat.parse(lastSync)?.let { outputFormat.format(it) } ?: lastSync
        } catch (e: Exception) {
            lastSync // fallback
        }

        tvLastSync.text = "Last Sync: $formattedTime"


        // inside onViewCreated
        val tvManualDataSync = view.findViewById<View>(R.id.tvManualDataSync)
        tvManualDataSync.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Sync Data")
                .setMessage("Do you want to sync data from the server and update your local database?")
                .setPositiveButton("Yes") { _, _ ->
                    //showAuthDialogForSync()
                    showProgressAndSync()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

//TODO for logout
//        val ivMenu = view.findViewById<View>(R.id.ivMenu)
//
//        ivMenu.setOnClickListener { anchor ->
//
//            val popup = android.widget.PopupMenu(requireContext(), anchor)
//            popup.menuInflater.inflate(R.menu.menu_header, popup.menu)
//
//            popup.setOnMenuItemClickListener { item ->
//                when (item.itemId) {
//
//                    R.id.menu_logout -> {
//                        showLogoutDialog()
//                        true
//                    }
//
//                    else -> false
//                }
//            }
//
//            popup.show()
//        }


        //face recognize enrollment
        val tvFaceRegistration=view.findViewById<View>(R.id.tvFaceRegister)
        tvFaceRegistration.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Registration User")
                .setMessage("Do you want to Registration User Face?")
                .setPositiveButton("Yes") { _, _ ->
                    showAuthDialog {
                        val intent = Intent(requireContext(), FaceRegistrationActivity::class.java)
                        startActivity(intent)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        val btnStartClass = view.findViewById<View>(R.id.tvStartClass)
        btnStartClass.setOnClickListener {
            simulateClassroomCard()
        }

        //Todo when give previlages permission
       // applyFeaturePrivileges(btnStartClass, tvFaceRegistration)

        val btnFaceVerify = view.findViewById<View>(R.id.btnFaceVerify)
        btnFaceVerify.setOnClickListener {
           val intent = Intent(requireContext(), FaceRecogniseActivity::class.java)
            startActivity(intent)
        }



    // Listen for broadcast updates
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val time = intent?.getStringExtra("time") ?: return
                tvLastSync.text = "Last Sync: $time"
            }
        }
        val filter = IntentFilter("SYNC_UPDATE")
        @Suppress("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(receiver, filter)
        }


        // show offline hours
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val lastUptime = prefs.getLong("last_sync_uptime", 0L)
                val lastSyncStr = prefs.getString("last_sync_time", null)

                if (lastUptime > 0 && lastSyncStr != null) {
                    val diffMillis = SystemClock.elapsedRealtime() - lastUptime
                    val diffHours = (diffMillis / (1000 * 60 * 60)).toInt()

                    if (diffHours >= 24) {
                        tvLastSync.text = "⚠️ Time expired — please sync"
                        tvLastSync.setTextColor(android.graphics.Color.RED)
                    } else {
                        //  tvSyncStatus.text = "Working offline for $diffHours hrs"
                        // tvSyncStatus.setTextColor(android.graphics.Color.WHITE)
                    }
                } else {
                    // tvSyncStatus.text = "Last Sync: --"
                }
                delay(60_000)
            }


        }

        // 🕓 Show count of unsubmitted (active) sessions
        // refreshUnsubmittedSessions()


        // 🔹 Disable back press (both button and gesture)
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    Toast.makeText(
                        requireContext(),
                        "Back is disabled on this screen",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )


        tvSyncStatus.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Confirm Sync")
                .setMessage("Do you want to send pending attendance to the server?")
                .setPositiveButton("Yes") { _, _ ->
                    val intent = Intent(requireContext(), SyncAttendanceToServer::class.java)
                    startActivity(intent)

                }
                .setNegativeButton("No", null)
                .show()
        }

        val cardCounter = view.findViewById<View>(R.id.cardCounter)
        cardCounter?.setOnClickListener {
            val intent = Intent(requireContext(), UnregisteredUsersActivity::class.java)
            startActivity(intent)
        }

        // --- BOTTOM NAVIGATION & SETTINGS CLICK ACTIONS ---
        val tabHome = view.findViewById<View>(R.id.tabHome)
        val tabSync = view.findViewById<View>(R.id.tabSync)
        val tabRegister = view.findViewById<View>(R.id.tabRegister)
        val tabSettings = view.findViewById<View>(R.id.tabSettings)

        tabHome.setOnClickListener {
            selectTab(view, "Home")
        }
        tabSync.setOnClickListener {
            tvSyncStatus.performClick()
        }
        tabRegister.setOnClickListener {
            tvFaceRegistration.performClick()
        }
        tabSettings.setOnClickListener {
            showAuthDialog {
                val intent = Intent(requireContext(), SettingsActivity::class.java)
                startActivity(intent)
            }
        }

        // Settings items
        view.findViewById<View>(R.id.btnContactSupport)?.setOnClickListener {
            Toast.makeText(requireContext(), "Support Contact: support@university.edu", Toast.LENGTH_LONG).show()
        }
        view.findViewById<View>(R.id.btnTerms)?.setOnClickListener {
            Toast.makeText(requireContext(), "Terms of Service opened", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.btnPrivacy)?.setOnClickListener {
            Toast.makeText(requireContext(), "Privacy Policy opened", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.btnLogout)?.setOnClickListener {
            showLogoutDialog()
        }
        view.findViewById<View>(R.id.btnEditProfile)?.setOnClickListener {
            Toast.makeText(requireContext(), "Profile editing is managed by Administrator", Toast.LENGTH_LONG).show()
        }
        // view.findViewById<View>(R.id.btnViewDetails)?.setOnClickListener {
        //     Toast.makeText(requireContext(), "Class CS101 in Room 302 is scheduled from 09:00 AM to 10:30 AM", Toast.LENGTH_LONG).show()
        // }
    }

    private fun selectTab(view: View, tabName: String) {
        val tabHomePill = view.findViewById<View>(R.id.tabHomePill) ?: return
        val tabHomeIcon = view.findViewById<ImageView>(R.id.tabHomeIcon) ?: return
        val tabHomeText = view.findViewById<TextView>(R.id.tabHomeText) ?: return

        val tabSyncPill = view.findViewById<View>(R.id.tabSyncPill) ?: return
        val tabSyncIcon = view.findViewById<ImageView>(R.id.tabSyncIcon) ?: return
        val tabSyncText = view.findViewById<TextView>(R.id.tabSyncText) ?: return

        val tabRegisterPill = view.findViewById<View>(R.id.tabRegisterPill) ?: return
        val tabRegisterIcon = view.findViewById<ImageView>(R.id.tabRegisterIcon) ?: return
        val tabRegisterText = view.findViewById<TextView>(R.id.tabRegisterText) ?: return

        val tabSettingsPill = view.findViewById<View>(R.id.tabSettingsPill) ?: return
        val tabSettingsIcon = view.findViewById<ImageView>(R.id.tabSettingsIcon) ?: return
        val tabSettingsText = view.findViewById<TextView>(R.id.tabSettingsText) ?: return

        val layoutHomeContainer = view.findViewById<View>(R.id.layoutHomeContainer) ?: return
        val layoutSettingsContainer = view.findViewById<View>(R.id.layoutSettingsContainer) ?: return

        // Reset all to default gray/unselected
        tabHomePill.setBackgroundResource(0)
        tabHomeIcon.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#6B7280")))
        tabHomeText.setTextColor(android.graphics.Color.parseColor("#6B7280"))
        tabHomeText.setTypeface(null, android.graphics.Typeface.NORMAL)

        tabSyncPill.setBackgroundResource(0)
        tabSyncIcon.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#6B7280")))
        tabSyncText.setTextColor(android.graphics.Color.parseColor("#6B7280"))
        tabSyncText.setTypeface(null, android.graphics.Typeface.NORMAL)

        tabRegisterPill.setBackgroundResource(0)
        tabRegisterIcon.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#6B7280")))
        tabRegisterText.setTextColor(android.graphics.Color.parseColor("#6B7280"))
        tabRegisterText.setTypeface(null, android.graphics.Typeface.NORMAL)

        tabSettingsPill.setBackgroundResource(0)
        tabSettingsIcon.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#6B7280")))
        tabSettingsText.setTextColor(android.graphics.Color.parseColor("#6B7280"))
        tabSettingsText.setTypeface(null, android.graphics.Typeface.NORMAL)

        when (tabName) {
            "Home" -> {
                tabHomePill.setBackgroundResource(R.drawable.bg_nav_selected_pill)
                tabHomeIcon.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1E88E5")))
                tabHomeText.setTextColor(android.graphics.Color.parseColor("#1E88E5"))
                tabHomeText.setTypeface(null, android.graphics.Typeface.BOLD)

                layoutHomeContainer.visibility = View.VISIBLE
                layoutSettingsContainer.visibility = View.GONE
            }
            "Settings" -> {
                tabSettingsPill.setBackgroundResource(R.drawable.bg_nav_selected_pill)
                tabSettingsIcon.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1E88E5")))
                tabSettingsText.setTextColor(android.graphics.Color.parseColor("#1E88E5"))
                tabSettingsText.setTypeface(null, android.graphics.Typeface.BOLD)

                layoutHomeContainer.visibility = View.GONE
                layoutSettingsContainer.visibility = View.VISIBLE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUserCounters()
    }

    private fun updateUserCounters() {
        val view = view ?: return
        val tvUnregisteredCount = view.findViewById<TextView>(R.id.tvUnregisteredCount) ?: return
        val tvTotalCount = view.findViewById<TextView>(R.id.tvTotalCount) ?: return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(requireContext())
                val totalStudents = db.studentsDao().getTotalStudentsCount()
                val totalTeachers = db.teachersDao().getTotalTeachersCount()
                val unregisteredStudents = db.studentsDao().getUnregisteredStudentsCount()
                val unregisteredTeachers = db.teachersDao().getUnregisteredTeachersCount()

                val totalUsers = totalStudents + totalTeachers
                val totalUnregistered = unregisteredStudents + unregisteredTeachers

                withContext(Dispatchers.Main) {
                    tvTotalCount.text = totalUsers.toString()
                    tvUnregisteredCount.text = totalUnregistered.toString()
                }
            } catch (e: Exception) {
                Log.e("ClassroomScanFragment", "Error updating counters", e)
            }
        }
    }



    private fun simulateClassroomCard() {
        val activity = requireActivity() as AttendanceActivity
        activity.simulateClassroomScan("1", "Room 1")
    }



    override fun onDestroyView() {
        super.onDestroyView()
        try {
           // requireContext().unregisterReceiver(updateReceiver)
        } catch (_: Exception) {
        }
    }

    private fun showAuthDialogForSync() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_auth_sync, null)
        val edtUsername = dialogView.findViewById<EditText>(R.id.edtUsername)
        val edtPassword = dialogView.findViewById<EditText>(R.id.edtPassword)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val btnSubmit = dialogView.findViewById<Button>(R.id.btnSubmit)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSubmit.setOnClickListener {
            val enteredUser = edtUsername.text.toString().trim()
            val enteredPass = edtPassword.text.toString().trim()
            val prefs = requireContext().getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
            val savedUser = prefs.getString("username", "")
            val savedPass = prefs.getString("password", "")

            if (enteredUser == savedUser && enteredPass == savedPass) {
                dialog.dismiss()
                showProgressAndSync()
            } else {
                Toast.makeText(requireContext(), "Invalid credentials!", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }


    private fun showAuthDialog(onSuccess: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_auth_sync, null)
        val edtUsername = dialogView.findViewById<EditText>(R.id.edtUsername)
        val edtPassword = dialogView.findViewById<EditText>(R.id.edtPassword)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val btnSubmit = dialogView.findViewById<Button>(R.id.btnSubmit)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSubmit.setOnClickListener {
            val enteredUser = edtUsername.text.toString().trim()
            val enteredPass = edtPassword.text.toString().trim()
            val prefs = requireContext().getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
            val savedUser = prefs.getString("username", "")
            val savedPass = prefs.getString("password", "")

            if (enteredUser == savedUser && enteredPass == savedPass) {
                dialog.dismiss()
                onSuccess()
            } else {
                Toast.makeText(requireContext(), "Invalid credentials!", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }



    private fun showProgressAndSync() {
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle("Syncing Data")
            .setMessage("Please wait while data is being synced...")
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            // Simulate loading delay for 3 seconds (visual feedback)
            delay(3000)

            val prefs = requireContext().getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
            val baseUrl = prefs.getString("baseUrl", "") ?: ""
            val instIds = prefs.getString("selectedInstituteIds", "") ?: ""
            val HASH = "trr36pdthb9xbhcppyqkgbpkq"

            if (baseUrl.isBlank() || instIds.isBlank()) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(
                        requireContext(),
                        "Missing institute or URL info",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            val normalizedBaseUrl = if (baseUrl.endsWith("/")) {
                baseUrl.removeSuffix("/") + "///"
            } else {
                "$baseUrl///"
            }

            val instIdList = instIds.split(",")
            try {
                val retrofit = ApiClient.getClient(normalizedBaseUrl, HASH)
                val apiService = retrofit.create(ApiService::class.java)
                val db = AppDatabase.getDatabase(requireContext())
                val repository = DataSyncRepository(requireContext())

                var allOk = true

                for (instId in instIdList) {

                    val st = repository.fetchAndSaveStudents(apiService, db, instId)
                    if (!st) allOk = false

                    val tt = repository.fetchAndSaveTeachers(apiService, db, instId)
                    if (!tt) allOk = false

                    val sc = repository.fetchAndSaveStudentSchedulingData(apiService, db, instId)
                    if (!sc) allOk = false
                }

                // Subjects do not depend on institute, sync once
                val subj = repository.syncSubjectInstances(apiService, db)
                if (!subj) allOk = false

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (allOk) {
                        updateUserCounters()
                        Toast.makeText(
                            requireContext(),
                            " Sync Successful , Data synced and updated in local database.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            " Some data failed to sync. Try again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(
                        requireContext(),
                        " Server timeout. Please try again later.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

//    private fun refreshUnsubmittedSessions() {
//        val tvUnsubmittedCount = view?.findViewById<TextView>(R.id.tvUnsubmittedCount) ?: return
//
//        viewLifecycleOwner.lifecycleScope.launch {
//            try {
//                val db = AppDatabase.getDatabase(requireContext())
//                val openSessions = db.sessionDao().getAllSessions()
//                    .filter { it.endTime.isNullOrEmpty() }
//
//                if (openSessions.isNotEmpty()) {
//                    tvUnsubmittedCount.visibility = View.VISIBLE
//                    tvUnsubmittedCount.text = "🕓 Unsubmitted Sessions: ${openSessions.size}"
//
//                    // 🔹 Add click listener to show details
//                    tvUnsubmittedCount.setOnClickListener {
//                        showUnsubmittedSessionPopup(openSessions)
//                    }
//                } else {
//                    tvUnsubmittedCount.visibility = View.GONE
//                }
//            } catch (e: Exception) {
//                e.printStackTrace()
//            }
//        }
//    }


    private fun showUnsubmittedSessionPopup(openSessions: List<Session>) {
        if (openSessions.isEmpty()) return

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Unsubmitted Sessions")

        // Use coroutine to safely call suspend DAO method
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(requireContext())
            val message = StringBuilder()

            for ((index, session) in openSessions.withIndex()) {
                val teacherId = session.teacherId ?: "N/A"
                val teacherName = withContext(Dispatchers.IO) {
                    db.teachersDao().getTeacherNameById(teacherId)
                } ?: "Unknown"

                message.append("${index + 1}. ID-$teacherId : $teacherName\n\n")
            }

            withContext(Dispatchers.Main) {
                builder.setMessage(message.toString().trim())
                builder.setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                builder.show()
            }
        }
    }





    private fun applyFeaturePrivileges(
        btnStartClass: View,
        btnFaceRegister: View
    ) {
        val TAG = "PRIVILEGE_API"
        val prefs = requireContext().getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)

        val baseUrl = prefs.getString("baseUrl", "") ?: ""
        val userId = prefs.getString("loggedStaffId", "") ?: ""
        val userType = prefs.getString("loggedUserType", "admin") ?: "admin"

        Log.d(TAG, "STEP-0: baseUrl=$baseUrl, userId=$userId, userType=$userType")

        if (baseUrl.isBlank() || userId.isBlank()) {
            Log.e(TAG, "STEP-0-FAIL: baseUrl or userId missing -> hiding both buttons")
            btnStartClass.visibility = View.GONE
            btnFaceRegister.visibility = View.GONE
            return
        }

        val normalizedBaseUrl = if (baseUrl.endsWith("/")) {
            baseUrl.removeSuffix("/") + "///"
        } else {
            "$baseUrl///"
        }

        Log.d(TAG, "STEP-1: normalizedBaseUrl=$normalizedBaseUrl")

        val HASH = "trr36pdthb9xbhcppyqkgbpkq"
        val rParam = "api/v1/User/GetUserAssignedAccessPrivileges"

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "STEP-2: Creating retrofit client...")

                val retrofit = ApiClient.getClient(normalizedBaseUrl, HASH)
                val service = retrofit.create(ApiService::class.java)

                // Build JSON as per backend
                val dataObj = JSONObject().apply {
                    put(
                        "userPrivilegeParamData",
                        JSONObject().apply {
                            put("userType", userType)
                            put("userId", userId)
                            put("schoolId", "")
                            put("featureId", "")
                            put("syear", "")
                        }
                    )
                }

                val rawData = dataObj.toString()
                val encodedData = URLEncoder.encode(rawData, "UTF-8")

                Log.d(TAG, "STEP-3: REQUEST r=$rParam")
                Log.d(TAG, "STEP-3: REQUEST rawData=$rawData")
                Log.d(TAG, "STEP-3: REQUEST encodedDataLength=${encodedData.length}")

                val resp = service.getUserAssignedAccessPrivileges(
                    r = rParam,
                    data = encodedData
                )

                Log.d(TAG, "STEP-4: HTTP code=${resp.code()} message=${resp.message()}")

                if (!resp.isSuccessful || resp.body() == null) {
                    val errorBody = resp.errorBody()?.string()
                    Log.e(TAG, "STEP-4-FAIL: API failed. errorBody=$errorBody")

                    withContext(Dispatchers.Main) {
                        btnStartClass.visibility = View.GONE
                        btnFaceRegister.visibility = View.GONE
                    }
                    return@launch
                }

                // IMPORTANT: body.string() can be read only once
                val rawResponse = resp.body()!!.string()

                // Print full response (it can be long)
                Log.d(TAG, "STEP-5: RAW_RESPONSE_START")
                Log.d(TAG, rawResponse)
                Log.d(TAG, "STEP-5: RAW_RESPONSE_END")

                val json = JSONObject(rawResponse)
                val responseObj = json.optJSONObject("collection")?.optJSONObject("response")

                if (responseObj == null) {
                    Log.e(TAG, "STEP-6-FAIL: responseObj is null -> hiding buttons")

                    withContext(Dispatchers.Main) {
                        btnStartClass.visibility = View.GONE
                        btnFaceRegister.visibility = View.GONE
                    }
                    return@launch
                }

                val status = responseObj.optString("dataServiceStatus", "FAIL")
                Log.d(TAG, "STEP-6: dataServiceStatus=$status")

                if (!status.equals("SUCCESS", ignoreCase = true)) {
                    Log.e(TAG, "STEP-6-FAIL: status not SUCCESS -> hiding buttons")

                    withContext(Dispatchers.Main) {
                        btnStartClass.visibility = View.GONE
                        btnFaceRegister.visibility = View.GONE
                    }
                    return@launch
                }

                val arr = responseObj.optJSONArray("privilegesDataArr")
                Log.d(TAG, "STEP-7: privilegesDataArr count=${arr?.length() ?: 0}")

                // Debug each privilege row (to find correct shortName)
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val shortName = obj.optString("featureShortName", "")
                        val name = obj.optString("featureName", "")
                        val canSee = obj.optString("canSee", "N")
                        Log.d(TAG, "ROW[$i]: featureShortName=$shortName, featureName=$name, canSee=$canSee")
                    }
                }

                // Now match your two features
                val canSeeFace = getCanSeeByFeatureShortName(arr, "Manage Personal Info")
                val canSeeStartClass = getCanSeeByFeatureShortName(arr, "Manage Personal Info")

                Log.d(TAG, "STEP-8: FINAL canSeeFace=$canSeeFace (faceRegistration)")
                Log.d(TAG, "STEP-8: FINAL canSeeStartClass=$canSeeStartClass (subject-list)")

                withContext(Dispatchers.Main) {
                    btnFaceRegister.visibility = if (canSeeFace) View.VISIBLE else View.GONE
                    btnStartClass.visibility = if (canSeeStartClass) View.VISIBLE else View.GONE

                    Log.d(
                        TAG,
                        "STEP-9: UI APPLIED -> Face=${btnFaceRegister.visibility}, StartClass=${btnStartClass.visibility}"
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "EXCEPTION: ${e.message}", e)

                withContext(Dispatchers.Main) {
                    btnStartClass.visibility = View.GONE
                    btnFaceRegister.visibility = View.GONE
                }
            }
        }
    }

    private fun getCanSeeByFeatureShortName(arr: org.json.JSONArray?, shortName: String): Boolean {
        val TAG = "PRIVILEGE_API"
        if (arr == null) {
            Log.e(TAG, "getCanSeeByFeatureShortName: arr is null for shortName=$shortName")
            return false
        }

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val featureShortName = obj.optString("featureShortName", "")
            val canSee = obj.optString("canSee", "N")

            if (featureShortName.equals(shortName, ignoreCase = true)) {
                val result = canSee.equals("Y", ignoreCase = true)
                Log.d(TAG, "MATCH FOUND: shortName=$shortName -> canSee=$canSee -> result=$result")
                return result
            }
        }

        Log.e(TAG, "NO MATCH: shortName=$shortName not found in privilegesDataArr")
        return false
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Yes") { _, _ ->
              //  performLogout()
                Toast.makeText(
                    requireContext(),
                    " logout.",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performLogout() {

        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(requireContext())

            // Clear only user runtime data
//            db.sessionDao().deleteAll()
//            db.attendanceDao().deleteAll()
//            db.activeClassCycleDao().deleteAll()

            // Clear preferences
            requireContext().getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE)
                .edit().clear().apply()

            requireContext().getSharedPreferences("APP_STATE", Context.MODE_PRIVATE)
                .edit().clear().apply()

            Toast.makeText(requireContext(), "Logged out", Toast.LENGTH_SHORT).show()

            // Go to Login screen
            val intent = Intent(requireContext(), LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
    }


}

