package com.digitaledu.selfieattendance.utility

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
            !isVoiceGuidanceEnabled ||
            SystemClock.elapsedRealtime() < guidanceMutedUntil ||
            key == lastGuidanceKey
        ) return
        lastGuidanceKey = key
        enqueue(shortGuidance(message), null)
    }

    /** Interrupt guidance to announce a recognition or attendance outcome. */
    fun announce(message: String, key: String = message, onComplete: (() -> Unit)? = null) {
        if (closed || !isVoiceGuidanceEnabled) {
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

    private fun shortGuidance(message: String): String {
        val normalized = message.lowercase(Locale.US)
        return when {
            "blink" in normalized -> "Blink once"
            "open your eyes" in normalized || "eyes open" in normalized -> "Open eyes"
            "move closer" in normalized -> "Move closer"
            "look straight" in normalized -> "Look at camera"
            "inside the oval" in normalized ||
                "inside the guide" in normalized ||
                "whole face" in normalized -> "Face in oval"
            "hold still" in normalized ||
                "blurry" in normalized ||
                "landmarks locked" in normalized -> "Hold still"
            "unable" in normalized || "unavailable" in normalized -> "Try again"
            else -> message
        }
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
        mainHandler.removeCallbacksAndMessages(null)
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

    companion object {
        const val TAG = "VoiceGuidance"
        const val OUTCOME_GUIDANCE_PAUSE_MS = 2_200L

        /**
         * Global master key for voice/audio guidance.
         *
         * Set `VoiceGuidance.isVoiceGuidanceEnabled = false` (or `AntiSpoofConfig.enableAudioGuidance = false`)
         * to disable all application audio/voice guidance output.
         *
         * Set `true` to enable application audio guidance.
         */
        @Volatile var isVoiceGuidanceEnabled: Boolean = false

        /**
         * Converts database-style names such as "GOURAV KUMAR" or
         * "G O U R A V" into natural TTS text without changing the UI value.
         */
        fun speakableName(rawName: String): String {
            val cleaned = rawName
                .trim()
                .replace(Regex("[._-]+"), " ")
                .replace(Regex("\\s+"), " ")
            if (cleaned.isBlank()) return "User"

            val output = mutableListOf<String>()
            val separatedLetters = StringBuilder()

            fun flushSeparatedLetters() {
                if (separatedLetters.isEmpty()) return
                output += separatedLetters.toString().replaceFirstChar { character ->
                    character.titlecase(Locale.getDefault())
                }
                separatedLetters.clear()
            }

            cleaned.split(" ").forEach { token ->
                if (token.length == 1 && token[0].isLetter()) {
                    separatedLetters.append(token[0].lowercaseChar())
                } else {
                    flushSeparatedLetters()
                    output += token.toNaturalCase()
                }
            }
            flushSeparatedLetters()
            return output.joinToString(" ")
        }

        private fun String.toNaturalCase(): String {
            val letters = filter(Char::isLetter)
            if (letters.isEmpty() || letters.any(Char::isLowerCase)) return this
            return lowercase(Locale.getDefault()).replaceFirstChar { character ->
                character.titlecase(Locale.getDefault())
            }
        }
    }
}
