package com.example.login.utility

import android.content.Context
import android.util.Log

object ThresholdManager {
    private const val TAG = "ThresholdManager"
    private const val PREFS_NAME = "LoginPrefs"
    private const val KEY_THRESHOLD = "FaceDetectionThreshold"
    const val DEFAULT_THRESHOLD = 0.60f

    fun getThreshold(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val threshold = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        Log.d(TAG, "Current active face detection threshold: $threshold")
        return threshold
    }

    fun saveThreshold(context: Context, threshold: Float) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_THRESHOLD, threshold).apply()
        Log.i(TAG, "Successfully updated and saved face detection threshold: $threshold")
    }
}
