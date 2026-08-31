package com.digitaledu.selfieattendance

import android.app.Application
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.util.Log
import com.digitaledu.selfieattendance.db.dao.AppDatabase
import com.digitaledu.selfieattendance.ml.FaceDetectionConfig
import com.digitaledu.selfieattendance.utility.NetworkReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        Log.e("AUTO_SYNC", "MyApp.onCreate() → Application class loaded")

        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        registerReceiver(NetworkReceiver(), filter)

        Log.e("AUTO_SYNC", "NetworkReceiver registered in MyApp")

        // Load face detection config from Room DB (if previously synced).
        // If the DB has no config yet, FaceDetectionConfig keeps its
        // hardcoded defaults until the next sync.
        appScope.launch {
            try {
                val db = AppDatabase.getDatabase(this@MyApp)
                val json = db.programConfigDao()
                    .getValueByTitle("FaceDetectionThreshold")
                if (!json.isNullOrBlank()) {
                    FaceDetectionConfig.loadFromJson(json)
                    com.digitaledu.selfieattendance.ml.AntiSpoofConfig.loadFromJson(json)
                    Log.i("MyApp", "✔ FaceDetectionConfig & AntiSpoofConfig loaded from local DB")
                } else {
                    Log.i("MyApp", "No saved config found — using hardcoded defaults")
                }
            } catch (e: Exception) {
                Log.e("MyApp", "Failed to load config from DB — using defaults", e)
            }
        }
    }
}
