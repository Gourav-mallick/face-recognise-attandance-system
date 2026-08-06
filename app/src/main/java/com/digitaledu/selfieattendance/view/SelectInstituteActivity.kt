package com.digitaledu.selfieattendance.view

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.digitaledu.selfieattendance.api.ApiClient
import com.digitaledu.selfieattendance.api.ApiService
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.db.entity.Class
import com.digitaledu.selfieattendance.db.entity.Student
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.digitaledu.selfieattendance.db.entity.Course
import com.digitaledu.selfieattendance.db.entity.CoursePeriod
import com.digitaledu.selfieattendance.db.entity.Subject
import com.digitaledu.selfieattendance.db.entity.Teacher
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.telephony.TelephonyManager
import java.util.*
import android.os.Build
import android.provider.Settings
import android.view.View
import com.digitaledu.selfieattendance.R
import com.digitaledu.selfieattendance.repository.DataSyncRepository
import com.digitaledu.selfieattendance.utility.CheckNetworkAndInternetUtils
import com.digitaledu.selfieattendance.utility.TripleDESUtility
import kotlinx.coroutines.delay


class SelectInstituteActivity : AppCompatActivity() {

    private lateinit var instituteSelectionLayout: LinearLayout
 //   private lateinit var edtUsername: EditText
  //  private lateinit var edtPassword: EditText
    private lateinit var btnSync: Button
    private lateinit var progressBar: ProgressBar

    private val selectedInstitutes = mutableSetOf<String>()
    private val TAG = "SELECT_INSTITUTE"
    private val allInstitutesMap = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_institute)

        instituteSelectionLayout = findViewById(R.id.instituteSelectionLayout)
        btnSync = findViewById(R.id.btnLogin)
        progressBar = findViewById(R.id.progressBar)

        // 🔹 Get shared preferences
        val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
        val baseUrl = prefs.getString("baseUrl", "") ?: ""
        val HASH = prefs.getString("hash", "")

        // 🔹 Get data from intent
        val schoolIds = intent.getStringArrayListExtra("schoolIds") ?: arrayListOf()
        val schoolShortNames = intent.getStringArrayListExtra("schoolShortNames") ?: arrayListOf()

        if (schoolIds.isEmpty()) {
            // Load from local database
            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@SelectInstituteActivity)
                val allInsts = db.instituteDao().getAll()
                withContext(Dispatchers.Main) {
                    val ids = ArrayList<String>()
                    val names = ArrayList<String>()
                    allInsts.forEach {
                        ids.add(it.id)
                        names.add(it.shortName)
                    }
                    buildInstituteList(ids, names)
                }
            }
        } else {
            buildInstituteList(schoolIds, schoolShortNames)
        }

        // 🔹 Sync button click
        btnSync.setOnClickListener {
            // Show progress and disable button
            progressBar.visibility = ProgressBar.VISIBLE
            btnSync.isEnabled = false

            // ✅ NEW: Check network connectivity first
            if (!CheckNetworkAndInternetUtils.isNetworkAvailable(this)) {
                showToast("No network connection. Please check your network.")
                progressBar.visibility = ProgressBar.GONE
                btnSync.isEnabled = true
                return@setOnClickListener
            }

            if (selectedInstitutes.isEmpty()) {
                showToast("Please select at least one institute")
                progressBar.visibility = ProgressBar.GONE
                btnSync.isEnabled = true
                return@setOnClickListener
            }

            //  Save selected institute IDs to SharedPreferences
            val instIds = selectedInstitutes.joinToString(",")

            // Normalize baseUrl with triple slashes
            val normalizedBaseUrl = if (baseUrl.endsWith("/")) {
                baseUrl.removeSuffix("/") + "///"
            } else {
                "$baseUrl///"
            }

            // 🔹 Build query data
            val rParam = "api/v1/StudentEnrollment/GetStudList"
            val dataParam = "{\"studListParamData\":{\"actionType\":\"FingerPrint\",\"school_id\":\"$instIds\"}}"

            val fullUrl = "${normalizedBaseUrl}sims-services/digitalsims/?r=$rParam&data=$dataParam"
            Log.d(TAG, "REQUEST_URL: $fullUrl")
            Log.d(TAG, "SYNC_CALL: instId=$instIds")

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val hasInternet = CheckNetworkAndInternetUtils.hasInternetAccess()
                    if (!hasInternet) {
                        withContext(Dispatchers.Main) {
                            progressBar.visibility = ProgressBar.GONE
                            btnSync.isEnabled = true
                            showToast("Unable to connect to the server. Please try again.")
                        }
                        return@launch
                    }
                    val retrofit = ApiClient.getClient(normalizedBaseUrl, HASH)
                    val apiService = retrofit.create(ApiService::class.java)
                    val db = AppDatabase.getDatabase(this@SelectInstituteActivity)
                    val repository = DataSyncRepository(this@SelectInstituteActivity)

                    var allOk = true

                    // 🔥 NEW: Sync each institute one-by-one
                    selectedInstitutes.forEach { instId ->
                        // 🔥 Classes
                        repository.fetchAndSaveClasses(apiService, db, instId)

                        // 🔥 Students
                        val st = repository.fetchAndSaveStudents(apiService, db, instId)
                        if (!st) allOk = false

                        // 🔥 Teachers
                        val tt = repository.fetchAndSaveTeachers(apiService, db, instId)
                        if (!tt) allOk = false

                        // 🔥 Schedules
                        val sc = repository.fetchAndSaveStudentSchedulingData(apiService, db, instId)
                        if (!sc) allOk = false

                        // periods details
                        val pd = repository.fetchAndSaveSchoolPeriods(apiService, db, instId)
                        if (!pd) allOk = false

                        // 🔥 Face Detection & Recognition Thresholds (ManageProgramConfig API)
                        repository.fetchAndSaveFaceDetectionConfig(apiService, db, instId)

                        // Global attendance period-selection rules (ManageProgramConfig API)
                        repository.fetchAndSaveGlobalAttendanceConfig(apiService, db, instId)

                        // 🔥 Attendance Codes (schoolAttCodeToMarkAtt API)
                        val ac = repository.fetchAndSaveAttendanceCodes(apiService, db, instId)
                        if (!ac) {
                            Log.w(TAG, "Attendance codes not configured on server for institute: $instId")
                        }
                    }


                    // 🔥 Subject Instances do NOT depend on institute
                    val subj = repository.syncSubjectInstances(apiService, db)
                    if (!subj) allOk = false

                    // 🔥 Device config also per selected block
                    val device = fetchDeviceDataToServer(apiService, db, normalizedBaseUrl, selectedInstitutes.first())
                    if (!device) allOk = true

                    delay(2000)
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = ProgressBar.GONE
                        btnSync.isEnabled = true

                        if (allOk) {
                            val selectedNames = selectedInstitutes.map { allInstitutesMap[it] ?: "" }.joinToString(",")
                            prefs.edit()
                                .putString("selectedInstituteIds", instIds)
                                .putString("selectedInstituteNames", selectedNames)
                                .apply()
                            Log.d(TAG, "Saved selected institutes: $instIds")

                            showToast("Sync completed successfully!")

                            // Navigate to AttendanceActivity
                            val intent = Intent(this@SelectInstituteActivity, AttendanceActivity::class.java)
                            startActivity(intent)
                            finish()
                        } else {
                            showToast("Some data failed to sync. Please try again.")
                        }
                    }

                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = ProgressBar.GONE
                        btnSync.isEnabled = true
                        showToast("Something went wrong. Please try again later.")
                    }
                    Log.e(TAG, "SYNC_EXCEPTION: ${e.message}", e)
                }
            }
        }
    }

    private fun buildInstituteList(schoolIds: List<String>, schoolShortNames: List<String>) {
        val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
        val savedSelectedIds = prefs.getString("selectedInstituteIds", "") ?: ""
        val savedSelectedSet = savedSelectedIds.split(",").filter { it.isNotEmpty() }.toSet()

        instituteSelectionLayout.removeAllViews()
        val allInstituteViews = mutableListOf<View>()

        allInstitutesMap.clear()
        for (i in schoolIds.indices) {
            allInstitutesMap[schoolIds[i]] = schoolShortNames[i]

            val view = layoutInflater.inflate(R.layout.item_institute, instituteSelectionLayout, false)
            val chkSelect = view.findViewById<CheckBox>(R.id.cbSelectInstitute)
            val tvSchoolShortName = view.findViewById<TextView>(R.id.tvSchoolShortName)
            val tvSchoolId = view.findViewById<TextView>(R.id.tvSchoolId)

            tvSchoolShortName.text = schoolShortNames[i]
            tvSchoolId.text = "Institute ID: ${schoolIds[i]}"

            val isSaved = savedSelectedSet.contains(schoolIds[i])
            chkSelect.isChecked = isSaved
            if (isSaved) {
                selectedInstitutes.add(schoolIds[i])
            }

            chkSelect.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedInstitutes.add(schoolIds[i])
                else selectedInstitutes.remove(schoolIds[i])
            }

            instituteSelectionLayout.addView(view)
            allInstituteViews.add(view)
        }

        // Setup SearchView filtering
        val searchInstitute = findViewById<SearchView>(R.id.searchInstitute)
        searchInstitute.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText?.trim()?.lowercase(Locale.getDefault()) ?: ""
                for (view in allInstituteViews) {
                    val name = view.findViewById<TextView>(R.id.tvSchoolShortName).text.toString().lowercase(Locale.getDefault())
                    val idText = view.findViewById<TextView>(R.id.tvSchoolId).text.toString().lowercase(Locale.getDefault())

                    // Match if query is part of name or ID
                    view.visibility = if (name.contains(query) || idText.contains(query)) View.VISIBLE else View.GONE
                }
                return true
            }
        })
    }

    //   Safe toast helper that works from any thread
    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this@SelectInstituteActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    // get students and save to db
    private suspend fun fetchAndSaveStudents(
        apiService: ApiService,
        db: AppDatabase,
        baseUrl: String,
        instIds: String
    ) :Boolean  {
        val rParam = "api/v1/StudentEnrollment/GetStudList"
        val dataParam = "{\"studListParamData\":{\"actionType\":\"FingerPrint\",\"school_id\":\"$instIds\"}}"

        val response = apiService.getStudents(rParam, dataParam)
        if (response.isSuccessful && response.body() != null) {
            val jsonString = response.body()!!.string()
            val json = JSONObject(jsonString)
            val collection = json.optJSONObject("collection")
            val responseObj = collection?.optJSONObject("response")
            val dataArray = responseObj?.optJSONArray("studentData") ?: JSONArray()

            val studentsList = mutableListOf<Student>()
            val classList = mutableListOf<Class>()

            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                val studentId = obj.optString("studentId", "")
                val studentName = obj.optString("studentName", "")
                val classId = obj.optString("classId", "")
                val classShortName = obj.optString("classShortName", "")
                val instId = obj.optString("instId", "")
                studentsList.add(Student(studentId, studentName, classId, instId))
                classList.add(Class(classId, classShortName))
            }
            db.studentsDao().insertAll(studentsList)
            db.classDao().insertAll(classList)
            Log.d(TAG, "Inserted ${studentsList} students.")
            Log.d(TAG, "Inserted ${classList} classes.")
            return true
        } else {
            Log.e(TAG, "STUDENT_API_FAILED: ${response.errorBody()?.string()}")
            return false
        }
    }




//Get teachers and save to db
    private suspend fun fetchAndSaveTeachers(
        apiService: ApiService,
        db: AppDatabase,
        normalizedBaseUrl: String,
        instIds: String
    ) :Boolean  {
        val rParam = "api/v1/User/GetUserRegisteredDetails"
        val dataParam = "{\"userRegParamData\":{\"userType\":\"staff\",\"registrationType\":\"FingerPrint\",\"school_id\":\"$instIds\"}}"

        val fullUrl = "${normalizedBaseUrl}sims-services/digitalsims/?r=$rParam&data=$dataParam"
        Log.d(TAG, "REQUEST_TEACHER_URL: $fullUrl")

        val response = apiService.getTeachers(rParam, dataParam)
        if (response.isSuccessful && response.body() != null) {
            val jsonString = response.body()!!.string()
            val json = JSONObject(jsonString)
            val collection = json.optJSONObject("collection")
            val responseObj = collection?.optJSONObject("response")
            val dataArray = responseObj?.optJSONArray("userRegisteredData") ?: JSONArray()

            val teachersList = mutableListOf<Teacher>()
            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)

                val staffProfile= obj.optString("staffProfile", "")
                if (staffProfile.equals("teacher", ignoreCase = true)) {
                    // Only process if userProfile is "teacher"
                    val staffId = obj.optString("staffId", "")
                    val staffName = obj.optString("staffName", "")
                    val instId = obj.optString("instId", "")
                    teachersList.add(Teacher(staffId, staffName, instId))
                }

            }
            db.teachersDao().insertAll(teachersList)
            Log.d(TAG, "Inserted teachers ${teachersList} .")
            return true
        } else {
            Log.e(TAG, "TEACHER_API_FAILED: ${response.errorBody()?.string()}")
            return false
        }
    }



    //get subject instances data and normalize into different table like coursePero
    private suspend fun syncSubjectInstances(
        apiService: ApiService,
         db: AppDatabase,
        normalizedBaseUrl: String,
        instIds: String
    ) :Boolean  {
        val rParam = "api/v1/CoursePeriod/SubjectInstances"
        val dataParam = "{\"cpParamData\":{\"actionType\":\"markCpAttendance2\"}}"

        val fullUrl = "${normalizedBaseUrl}sims-services/digitalsims/?r=$rParam&data=$dataParam"
        Log.d(TAG, "REQUEST_subjectInstance_URL: $fullUrl")



        val response = apiService.getSubjectInstances(rParam, dataParam)
        if (response.isSuccessful && response.body() != null) {
                    val jsonString = response.body()!!.string()
                    Log.d(TAG, "SUBJECT_INSTANCE_RESPONSE: $jsonString")

                    val json = JSONObject(jsonString)
                    val collection = json.optJSONObject("collection")
                    val responseObj = collection?.optJSONObject("response")
                    val dataArray = responseObj?.optJSONArray("subjectInstancesData")

                    if (dataArray == null || dataArray.length() == 0) {
                        Log.w(TAG, "No subject instance data found.")
                        Toast.makeText(this@SelectInstituteActivity, "No subject instance data found.", Toast.LENGTH_LONG).show()
                        return false
                    }


                    val coursePeriodList = mutableListOf<CoursePeriod>()
                    val courseList = mutableListOf<Course>()
                    val subjectList = mutableListOf<Subject>()
                   // val classList = mutableListOf<Class>()

                    for (i in 0 until dataArray.length()) {
                        val obj = dataArray.getJSONObject(i)

                        val cpId = obj.optString("cpIds")
                        val courseId = obj.optString("courseIds")
                        val subjectId = obj.optString("subjectIds")
                        val subjectTitle = obj.optString("subjectTitles")
                        val courseTitle = obj.optString("courseTitles")
                        val classId = obj.optString("classIds")
                        val classShortName = obj.optString("classShortNames")
                        val mpId = obj.optString("mpId")
                        val mpLongTitle = obj.optString("mpLongTitle")
                        val teacherId = obj.optString("teacherIds").replace(",", "").trim()
                        val subjectTypeRaw = obj.optString("subjectType", "").trim()
                        val subjectType = if (subjectTypeRaw.isNotEmpty()) subjectTypeRaw else null

                        // Normalize data
                        subjectList.add(Subject(subjectId, subjectTitle, subjectType))
                        courseList.add(Course(courseId, subjectId, courseTitle, courseTitle, subjectType))
                       // classList.add(Class(classId, classShortName))
                        coursePeriodList.add(CoursePeriod( id = 0,cpId, courseId, classId, teacherId, mpId,mpLongTitle))
                    }

                    // Save all in DB
                    db.subjectDao().insertAll(subjectList)
                    db.courseDao().insertAll(courseList)
                   // db.classDao().insertAll(classList)
                    db.coursePeriodDao().insertAll(coursePeriodList)


                    Log.d(TAG, "DB_INSERT_SUCCESS: ${coursePeriodList} records saved")
                    Log.d(TAG, "DB_INSERT_Subjects: ${subjectList} records saved")
                    Log.d(TAG, "DB_INSERT_course: ${courseList} records saved")
                  //  Log.d(TAG, "DB_INSERT_class: ${classList} records saved")
                    return true
        } else {
                    Log.e(TAG, "API Error: ${response.errorBody()?.string()}")
                    return false
        }


    }



    private suspend fun fetchDeviceDataToServer(
         apiService: ApiService,
         db: AppDatabase,
         normalizedBaseUrl: String,
         instIds: String
     ): Boolean {
         try {
             val rParam = "api/v1/Hardware/DeviceUtilityMgmt"
             val dataParam = getDeviceUtilityQueryParams(this)

             val fullUrl = "${normalizedBaseUrl}sims-services/digitalsims/?r=$rParam&data=$dataParam"
             Log.d(TAG, "HARDWARE_REQUEST_URL: $fullUrl")

             val response = apiService.getDeveiceDataToserver(rParam, dataParam)
             if (response.isSuccessful && response.body() != null) {
                 val jsonString = response.body()!!.string()

                 val json = JSONObject(jsonString)
                 Log.d(TAG, "HARDWARE_RESPONSE: $jsonString")
                 val collection = json.optJSONObject("collection")
                 Log.d(TAG, "HARDWARE_COLLECTION_RESPONSE: $collection")
                 val responseObj = collection?.optJSONObject("response")
                 Log.d(TAG, "RESPONSE: $responseObj")
                 val hwMgmtData = responseObj?.optJSONObject("hwMgmtData")
                 Log.d(TAG, "HW_MGMT_DATA: $hwMgmtData")

                 if (hwMgmtData != null) {
                     val status = hwMgmtData.optString("status")
                     val cfg = hwMgmtData.optJSONObject("cfg")
                     val deviceDetails = hwMgmtData.optJSONObject("deviceDetails")

                     val passcode = cfg?.optString("passCode")
                     val faciCode = cfg?.optString("faciCode")
                     val instType = cfg?.optString("instType")
                     val deconfigstr = cfg?.optString("deconfigstr")

                     if (!deconfigstr.isNullOrEmpty()) {
                         val decryptedStr = TripleDESUtility().getDecryptedStr(deconfigstr)
                         Log.d(TAG, "Decrypted Config: $decryptedStr")

                         // Example: "CODE,ABCD12,0345"
                         val elements = decryptedStr.split(",")
                         if (elements.size >= 2) {
                             val passCode = elements[1].trim()
                             val faciCode = elements.getOrNull(2)?.trim() ?: ""
                             val hexPassCode = convertAsciiToHex(passCode)

                             val prefs = getSharedPreferences("LoginPrefs", MODE_PRIVATE)
                             prefs.edit()
                                 .putString("cpass", hexPassCode)
                                 .putString("passCode", passCode)
                                 .putString("faciCode", faciCode)
                                 .apply()

                             Log.d(TAG, "Saved passCode: $passCode → HEX: $hexPassCode")

                             val verify = prefs.getString("cpass", null)
                             Log.d(TAG, "VERIFY_PREF_AFTER_SAVE: $verify")

                         } else {
                             Log.e(TAG, "Invalid decrypted config format: $decryptedStr")
                             withContext(Dispatchers.Main) {
                                 Toast.makeText(this@SelectInstituteActivity, "Device config format is invalid.", Toast.LENGTH_LONG).show()
                             }
                         }
                     } else {
                         withContext(Dispatchers.Main) {
                             Toast.makeText(this@SelectInstituteActivity, "Device configuration details not found on server.", Toast.LENGTH_LONG).show()
                         }
                         return false
                     }
                 } else {
                     Log.e(TAG, "No hwMgmtData found in response!")
                     withContext(Dispatchers.Main) {
                         Toast.makeText(this@SelectInstituteActivity, "Device management details not found on server.", Toast.LENGTH_LONG).show()
                     }
                     return false
                 }
                 return true
             } else {
                 Log.e(TAG, "DEVICE_API_FAILED: ${response.errorBody()?.string()}")
                 withContext(Dispatchers.Main) {
                     Toast.makeText(this@SelectInstituteActivity, "Device API failed: Server returned error ${response.code()}", Toast.LENGTH_LONG).show()
                 }
                 return false
             }
         } catch (e: Exception) {
             Log.e(TAG, "DEVICE_API_EXCEPTION: ${e.message}", e)
             withContext(Dispatchers.Main) {
                 Toast.makeText(this@SelectInstituteActivity, "Device API connection failed: ${e.localizedMessage ?: "Unknown network error"}", Toast.LENGTH_LONG).show()
             }
             return false
         }
     }



    fun convertAsciiToHex(input: String): String {
        return input.map { it.code.toString(16).uppercase() }.joinToString("")
    }



    @SuppressLint("HardwareIds", "MissingPermission")
    fun getDeviceUtilityQueryParams(context: Context): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val currentDate = sdf.format(Date())

        val telephonyManager = context.getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        val imei = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) telephonyManager.imei ?: "N/A"
            else telephonyManager.deviceId ?: "N/A"
        } catch (e: Exception) { "N/A" }

        val serialNo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Build.getSerial()
            else Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }

        val bm = context.getSystemService(BATTERY_SERVICE) as? BatteryManager
        val batteryLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && bm != null)
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) else -1

        val connectivityManager = context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val connectivity = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                when {
                    capabilities == null -> "NO_CONNECTION"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                    else -> "UNKNOWN"
                }
            } else {
                val info = connectivityManager.activeNetworkInfo
                when {
                    info == null || !info.isConnected -> "NO_CONNECTION"
                    info.type == ConnectivityManager.TYPE_WIFI -> "WIFI"
                    info.type == ConnectivityManager.TYPE_MOBILE -> "MOBILE"
                    else -> "UNKNOWN"
                }
            }
        } catch (e: Exception) { "UNKNOWN" }

        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) { "1.0" }

        //  Return proper JSON string just like server expects
        return """
        {
          "deviceUtilityParamData": {
            "device_srno": "$serialNo",
            "imei_no": "$imei",
            "app_id": "${context.packageName}",
            "app_name": "periodSync",
            "app_version": "$appVersion",
            "battery_level": "$batteryLevel",
            "last_recharge_date": "$currentDate",
            "validity_date": "$currentDate",
            "connectivity": "$connectivity",
            "last_sync": "${dateFormat.format(Date())}",
            "gps_corordinates": "",
            "device_place": "TestLab",
            "status_info": "rec_to_sync:0,student_reg_cnt:0,staff_reg_cnt:0,sub_instance_cnt:0,enrolled_student_cnt:0,schedule_student_cnt:0,institute_code:0",
            "req_type": "CFG"
          }
        }
    """.trimIndent()
    }

}
