package com.example.login.utility

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small TextToSpeech wrapper for camera flows.
 *
 * Camera guidance is de-duplicated by [key], so a frame analyzer can submit the
 * current instruction repeatedly without making TalkBack-style audio chatter.
 * Important outcomes use [announce] and interrupt the current instruction.
 */
class VoiceGuidance(context: Context) : TextToSpeech.OnInitListener, AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = mutableMapOf<String, () -> Unit>()
    private val textToSpeech = TextToSpeech(context.applicationContext, this)

    private var ready = false
    private var closed = false
    private var lastGuidanceKey: String? = null
    private var guidanceMutedUntil = 0L
    private var pending: PendingSpeech? = null

    override fun onInit(status: Int) {
        if (closed) return
        if (status != TextToSpeech.SUCCESS) {
            pending?.onComplete?.let { callback ->
                mainHandler.post { callback() }
            }
            pending = null
            return
        }

        val preferred = textToSpeech.setLanguage(Locale.getDefault())
        val languageResult = if (
            preferred == TextToSpeech.LANG_MISSING_DATA ||
            preferred == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            textToSpeech.setLanguage(Locale.US)
        } else {
            preferred
        }
        ready = languageResult != TextToSpeech.LANG_MISSING_DATA &&
            languageResult != TextToSpeech.LANG_NOT_SUPPORTED
        textToSpeech.setSpeechRate(0.92f)
        textToSpeech.setPitch(1.0f)
        textToSpeech.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    complete(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    complete(utteranceId)
                }
            }
        )

        pending?.also {
            pending = null
            speakInternal(it.message, it.onComplete)
        }
    }

    /** Speak only when the liveness/quality stage changes. */
    fun guide(message: String, key: String = message) {
        if (
            closed ||
            SystemClock.elapsedRealtime() < guidanceMutedUntil ||
            key == lastGuidanceKey
        ) return
        lastGuidanceKey = key
        enqueue(message, null)
    }

    /** Interrupt guidance to announce a recognition or attendance outcome. */
    fun announce(message: String, key: String = message, onComplete: (() -> Unit)? = null) {
        if (closed) {
            onComplete?.invoke()
            return
        }
        guidanceMutedUntil = SystemClock.elapsedRealtime() + OUTCOME_GUIDANCE_PAUSE_MS
        lastGuidanceKey = "outcome:$key"
        enqueue(message, onComplete)
    }

    /**
     * Announces an outcome before navigation. The timeout keeps navigation from
     * becoming dependent on the device having a working TTS engine.
     */
    fun announceThen(
        message: String,
        key: String = message,
        timeoutMs: Long = 2_800L,
        action: () -> Unit
    ) {
        val completed = AtomicBoolean(false)
        val runOnce = {
            if (completed.compareAndSet(false, true)) action()
        }
        announce(message, key, runOnce)
        mainHandler.postDelayed(runOnce, timeoutMs)
    }

    fun resetGuidance() {
        lastGuidanceKey = null
    }

    private fun enqueue(message: String, onComplete: (() -> Unit)?) {
        if (!ready) {
            pending = PendingSpeech(message, onComplete)
            return
        }
        speakInternal(message, onComplete)
    }

    private fun speakInternal(message: String, onComplete: (() -> Unit)?) {
        val utteranceId = UUID.randomUUID().toString()
        if (onComplete != null) synchronized(callbacks) {
            callbacks[utteranceId] = onComplete
        }
        val result = textToSpeech.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )
        if (result == TextToSpeech.ERROR) {
            Log.w(TAG, "Unable to speak voice guidance")
            complete(utteranceId)
        }
    }

    private fun complete(utteranceId: String?) {
        if (utteranceId == null) return
        val callback = synchronized(callbacks) { callbacks.remove(utteranceId) } ?: return
        mainHandler.post(callback)
    }

    override fun close() {
        closed = true
        pending = null
        synchronized(callbacks) {
            callbacks.clear()
        }
        textToSpeech.stop()
        textToSpeech.shutdown()
    }

    private data class PendingSpeech(
        val message: String,
        val onComplete: (() -> Unit)?
    )

    private companion object {
        const val TAG = "VoiceGuidance"
        const val OUTCOME_GUIDANCE_PAUSE_MS = 2_200L
    }
}
