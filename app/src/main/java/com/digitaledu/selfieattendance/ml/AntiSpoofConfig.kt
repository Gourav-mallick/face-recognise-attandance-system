package com.digitaledu.selfieattendance.ml

import android.util.Log
import org.json.JSONObject

/**
 * Centralized, runtime-configurable holder for every anti-spoofing
 * threshold, model parameter, and temporal-aggregation setting.
 *
 * **Pattern**: mirrors [FaceDetectionConfig]. All fields are `@Volatile`
 * so they can be hot-swapped from the server via [loadFromJson] without
 * restarting the app.
 *
 * **Usage**: every anti-spoofing class reads its parameters from this
 * object at call-time — no magic numbers scattered across the codebase.
 */
object AntiSpoofConfig {

    private const val TAG = "AntiSpoofConfig"

    // ─────────────────── Model metadata ─────────────────────────────

    /** ONNX model asset path for MiniFASNet V2-SE. */
    @Volatile var modelAssetPath: String = "models/minifasnet_v2_se.onnx"

    /** Model input width in pixels. Must match the training resolution. */
    const val INPUT_WIDTH: Int = 128

    /** Model input height in pixels. Must match the training resolution. */
    const val INPUT_HEIGHT: Int = 128

    /**
     * Model input color channel order.
     * `false` = BGR (MiniFASNet trained via OpenCV expects BGR).
     */
    @Volatile var inputIsRgb: Boolean = false

    /**
     * Output class index representing a LIVE / REAL face in MiniFASNet logits.
     * CelebA-Spoof / Silent-Face model output: Index 0 = REAL, Index 1 = SPOOF.
     */
    @Volatile var realClassIndex: Int = 0

    // ─────────────────── Face crop ───────────────────────────────────

    /**
     * Expansion factor around the YuNet bounding box.
     * `2.7` captures surrounding context (screen bezel, paper edges).
     */
    @Volatile var cropScale: Float = 2.7f

    // ─────────────────── Liveness thresholds ─────────────────────────

    /**
     * Softmax "real" probability above which a single frame is considered LIVE.
     */
    @Volatile var livenessThreshold: Float = 0.85f

    /**
     * Optional override threshold for registration flow.
     * If non-null, registration uses this instead of [livenessThreshold].
     * Registration should generally be stricter to prevent spoofed enrollment.
     */
    @Volatile var registrationThresholdOverride: Float? = null

    /**
     * Optional override threshold for attendance flow.
     * If non-null, attendance uses this instead of [livenessThreshold].
     */
    @Volatile var attendanceThresholdOverride: Float? = null

    /** Effective threshold for registration contexts. */
    val registrationThreshold: Float
        get() = registrationThresholdOverride ?: livenessThreshold

    /** Effective threshold for attendance/recognition contexts. */
    val attendanceThreshold: Float
        get() = attendanceThresholdOverride ?: livenessThreshold

    // ─────────────────── Temporal aggregation ────────────────────────

    /**
     * Number of consecutive frames in the sliding window.
     * Larger windows are more robust against single-frame noise but
     * increase the time before a decision is reached.
     */
    @Volatile var temporalWindowSize: Int = 5

    /**
     * Fraction of frames within the window that must individually pass
     * the liveness threshold for the overall decision to be LIVE.
     *
     * `0.80` means 4 out of 5 frames must pass.
     */
    @Volatile var requiredPassPercentage: Float = 0.80f

    /**
     * Aggregation strategy used to combine scores across the temporal window.
     *
     * Supported values:
     * - `PASS_COUNT` — count frames above threshold (default, simplest)
     * - `MEDIAN`     — take the median score across the window
     * - `AVERAGE`    — arithmetic mean of all frame scores
     * - `MIN_SCORE`  — most conservative: use the lowest score
     */
    @Volatile var aggregationStrategy: String = "PASS_COUNT"

    // ─────────────────── Quality pre-checks ─────────────────────────

    /**
     * Minimum average brightness (0–255 from Y-channel) below which
     * the anti-spoof model should not run (too dark for reliable inference).
     * In such cases the UI shows "Improve lighting".
     */
    @Volatile var minBrightness: Int = 40

    /**
     * Maximum average brightness (0–255) above which the frame is
     * considered overexposed (e.g., direct flashlight).
     */
    @Volatile var maxBrightness: Int = 240

    // ─────────────────── Performance / timing ────────────────────────

    /**
     * After attendance is successfully marked for a student, anti-spoof
     * cooldown period in ms before allowing the next student's scan.
     */
    @Volatile var attendanceCooldownMs: Long = 2_000L

    /**
     * Maximum retry count before temporarily blocking the scanner.
     */
    @Volatile var maxRetryCount: Int = 5

    // ─────────────────── Debug ───────────────────────────────────────

    /**
     * When `true`, [MiniFASNetEngine] and [TemporalLivenessBuffer] emit
     * detailed `Log.d` output including raw scores, inference timing,
     * and temporal window state. Set to `false` in production builds.
     */
    @Volatile var debugLogging: Boolean = false

    // ─────────────────── Audio Guidance Toggle ───────────────────────

    /**
     * Master toggle for voice/audio guidance throughout the application.
     *
     * - `true`  → Audio voice guidance enabled (default)
     * - `false` → All TTS voice audio output is muted/disabled
     */
    var enableAudioGuidance: Boolean
        get() = com.digitaledu.selfieattendance.utility.VoiceGuidance.isVoiceGuidanceEnabled
        set(value) {
            com.digitaledu.selfieattendance.utility.VoiceGuidance.isVoiceGuidanceEnabled = value
        }

    // ─────────────────── Server push support ─────────────────────────

    /**
     * Parse a JSON object and overwrite any matching fields.
     * Missing keys are silently skipped (partial update safe).
     *
     * Expected structure:
     * ```json
     * {
     *   "antiSpoofing": {
     *     "model": { "assetPath": "...", "cropScale": 2.7 },
     *     "thresholds": {
     *       "livenessThreshold": 0.90,
     *       "registrationThresholdOverride": 0.93,
     *       "attendanceThresholdOverride": null
     *     },
     *     "temporal": {
     *       "windowSize": 5,
     *       "requiredPassPercentage": 0.80,
     *       "aggregationStrategy": "PASS_COUNT"
     *     },
     *     "quality": { "minBrightness": 40, "maxBrightness": 240 },
     *     "debug": false
     *   }
     * }
     * ```
     */
    fun loadFromJson(jsonString: String) {
        try {
            val root = JSONObject(jsonString)
            val as_ = root.optJSONObject("antiSpoofing") ?: return

            // model
            as_.optJSONObject("model")?.let { m ->
                m.optStringNonEmpty("assetPath")?.let { modelAssetPath = it }
                m.optPositiveFloat("cropScale")?.let { cropScale = it }
            }

            // thresholds
            as_.optJSONObject("thresholds")?.let { t ->
                t.optPositiveFloat("livenessThreshold")?.let { livenessThreshold = it }
                if (t.has("registrationThresholdOverride")) {
                    registrationThresholdOverride =
                        t.optPositiveFloat("registrationThresholdOverride")
                }
                if (t.has("attendanceThresholdOverride")) {
                    attendanceThresholdOverride =
                        t.optPositiveFloat("attendanceThresholdOverride")
                }
            }

            // temporal
            as_.optJSONObject("temporal")?.let { tp ->
                tp.optPositiveInt("windowSize")?.let { temporalWindowSize = it }
                tp.optPositiveFloat("requiredPassPercentage")?.let { requiredPassPercentage = it }
                tp.optStringNonEmpty("aggregationStrategy")?.let { aggregationStrategy = it }
            }

            // quality
            as_.optJSONObject("quality")?.let { q ->
                q.optPositiveInt("minBrightness")?.let { minBrightness = it }
                q.optPositiveInt("maxBrightness")?.let { maxBrightness = it }
            }

            // debug
            if (as_.has("debug")) {
                debugLogging = as_.optBoolean("debug", false)
            }

            // audio guidance toggle
            as_.optJSONObject("audio")?.let { a ->
                if (a.has("enableAudio")) enableAudioGuidance = a.optBoolean("enableAudio", true)
                if (a.has("enableAudioGuidance")) enableAudioGuidance = a.optBoolean("enableAudioGuidance", true)
            }

            // timing
            as_.optJSONObject("timing")?.let { ti ->
                ti.optPositiveLong("attendanceCooldownMs")?.let { attendanceCooldownMs = it }
                ti.optPositiveInt("maxRetryCount")?.let { maxRetryCount = it }
            }

            Log.i(
                TAG,
                "✔ AntiSpoofConfig applied successfully:\n" +
                "  • modelAssetPath = $modelAssetPath\n" +
                "  • cropScale = $cropScale\n" +
                "  • livenessThreshold = $livenessThreshold\n" +
                "  • registrationThreshold = $registrationThreshold\n" +
                "  • attendanceThreshold = $attendanceThreshold\n" +
                "  • temporalWindowSize = $temporalWindowSize\n" +
                "  • requiredPassPercentage = $requiredPassPercentage\n" +
                "  • aggregationStrategy = $aggregationStrategy\n" +
                "  • debugLogging = $debugLogging"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse AntiSpoofConfig JSON — keeping defaults", e)
        }
    }

    // ─── small JSON-extension helpers (private) ────────────────────

    private fun JSONObject.optStringNonEmpty(key: String): String? =
        optString(key, "").takeIf { it.isNotBlank() }

    private fun JSONObject.optPositiveFloat(key: String): Float? =
        if (has(key)) optDouble(key, -1.0).toFloat().takeIf { it > 0f } else null

    private fun JSONObject.optPositiveInt(key: String): Int? =
        if (has(key)) optInt(key, -1).takeIf { it > 0 } else null

    private fun JSONObject.optPositiveLong(key: String): Long? =
        if (has(key)) optLong(key, -1L).takeIf { it > 0L } else null
}
