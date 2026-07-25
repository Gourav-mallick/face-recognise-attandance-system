package com.example.login.utility

import android.content.Context
import android.util.Log

object ThresholdManager {
    private const val TAG = "ThresholdManager"
    private const val PREFS_NAME = "LoginPrefs"
    private const val KEY_THRESHOLD = "FaceDetectionThreshold"
    public const val DEFAULT_THRESHOLD = 0.60f

    fun getThreshold(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)
        Log.d(TAG, "Retrieved threshold: $value")
        return value
    }

    fun saveThreshold(context: Context, threshold: Float) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_THRESHOLD, threshold).apply()
        Log.d(TAG, "Set active threshold to $threshold")
    }
}
