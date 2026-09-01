package com.digitaledu.selfieattendance.ml

import android.graphics.PointF
import android.util.Log
import org.json.JSONObject

/**
 * Centralized, runtime-configurable holder for every face-detection,
 * recognition, quality-gate, alignment, and liveness threshold.
 *
 * **Defaults** are the same values that were previously hard-coded in
 * [YuNetSFaceEngine] and [ActiveLivenessVerifier]. When the server pushes
 * new values via the `ManageProgramConfig` API, [loadFromJson] overwrites
 * whichever keys are present; missing keys keep their defaults.
 *
 * Thread-safety: every field is `@Volatile`.  The [loadFromJson] method
 * performs a single pass of non-blocking writes.
 */
object FaceDetectionConfig {

    private const val TAG = "FaceDetectionConfig"

    // ─────────────────── YuNet detector thresholds ───────────────────

    /** ONNX model asset path for YuNet face detector. */
    @Volatile var detectorModel: String = "models/face_detection_yunet_2023mar.onnx"

    /** Width/height of the square input tensor passed to YuNet. */
    @Volatile var detectorInputSize: Int = 640

    /** Minimum detection confidence (geometric mean of cls × obj). */
    @Volatile var detectionThreshold: Float = 0.70f

    /** IoU threshold for Non-Maximum Suppression. */
    @Volatile var nmsThreshold: Float = 0.30f

    /** Max candidates before NMS. */
    @Volatile var topK: Int = 1000

    /** Minimum face bounding box size in pixels (px). Default 60 px for 640x480 analysis stream. */
    @Volatile var minFaceSize: Float = 60f

    /** Maximum face bounding box size in pixels (px). Default 500 px. */
    @Volatile var maxFaceSize: Float = 500f

    /** Feature-pyramid stride levels (model architecture; rarely changed). */
    @Volatile var strides: IntArray = intArrayOf(8, 16, 32)

    // ─────────────────── SFace recognizer thresholds ─────────────────

    /** ONNX model asset path for SFace recognizer. */
    @Volatile var recognizerModel: String = "models/face_recognition_sface_2021dec_int8.onnx"

    /** Side length of the aligned face patch fed to SFace (px). */
    @Volatile var recognizerInputSize: Int = 112

    /** Dimensionality of the output embedding vector. */
    @Volatile var embeddingDimensions: Int = 128

    /** Registration Cosine-similarity threshold for pre-registration duplicate checking. Default 0.50f. */
    @Volatile var registrationCosineThreshold: Float = 0.50f

    /** Recognition Cosine-similarity threshold for identity verification / attendance scanning. Default 0.50f. */
    @Volatile var recognitionCosineThreshold: Float = 0.50f

    /** Legacy / Global fallback threshold for identity verification. */
    var cosineThreshold: Float
        get() = recognitionCosineThreshold
        set(value) {
            recognitionCosineThreshold = value
            registrationCosineThreshold = value
        }

    // ─────────────────── Quality gates (strict — registration) ──────

    /** Minimum inter-pupillary distance in pixels (registration). */
    @Volatile var minEyeDistanceStrict: Float = 25f

    /** Max nose-to-eye-midpoint offset as fraction of eye distance (registration). */
    @Volatile var symmetryLimitStrict: Float = 0.16f

    /** Minimum Laplacian-variance sharpness score (registration). */
    @Volatile var minSharpnessStrict: Float = 50f

    // ─────────────────── Quality gates (normal — recognition) ────────

    /** Minimum inter-pupillary distance in pixels (recognition). */
    @Volatile var minEyeDistanceNormal: Float = 18f

    /** Max nose-to-eye-midpoint offset as fraction of eye distance (recognition). */
    @Volatile var symmetryLimitNormal: Float = 0.23f

    /** Minimum Laplacian-variance sharpness score (recognition). */
    @Volatile var minSharpnessNormal: Float = 30f

    // ─────────────────── SFace alignment template points ────────────

    /** Canonical 5-point alignment landmarks for the 112×112 SFace grid. */
    @Volatile var alignmentTemplatePoints: List<PointF> = listOf(
        PointF(38.2946f, 51.6963f),  // left eye
        PointF(73.5318f, 51.5014f),  // right eye
        PointF(56.0252f, 71.7366f),  // nose
        PointF(41.5493f, 92.3655f),  // left mouth
        PointF(70.7299f, 92.2041f)   // right mouth
    )

    // ─────────────────── Liveness thresholds ─────────────────────────

    /** ML Kit eye-open probability above which both eyes are "open". */
    @Volatile var eyeOpenThreshold: Float = 0.65f

    /** ML Kit eye-open probability below which both eyes are "closed". */
    @Volatile var eyeClosedThreshold: Float = 0.35f

    /** Maximum time (ms) allowed for the blink challenge to complete. */
    @Volatile var challengeTimeoutMs: Long = 12_000L

    /** If no face is detected for this many ms, the liveness state resets. */
    @Volatile var faceMissingResetMs: Long = 800L

    /** After a successful blink, the pass is valid for this many ms. */
    @Volatile var passValidityMs: Long = 5_000L

    // ─────────────────────────────────────────────────────────────────

    /**
     * Parse the nested JSON object stored in the ProgramConfig `value` field
     * and overwrite any matching fields.  Missing keys are silently skipped,
     * so partial updates from the server are safe.
     *
     * Expected structure:
     * ```json
     * {
     *   "faceRecognition": {
     *     "detector": {
     *       "inputSize": 640,
     *       "scoreThreshold": 0.85,
     *       "nmsThreshold": 0.30,
     *       "topK": 1000,
     *       "minFaceSize": 200,
     *       "maxFaceSize": 500
     *     },
     *     "recognizer": { "cosineThreshold": ..., ... },
     *     "quality": {
     *       "strict": { "minEyeDistance": ..., ... },
     *       "normal": { "minEyeDistance": ..., ... }
     *     },
     *     "alignment": {
     *       "templatePoints": {
     *         "leftEye":   { "x": ..., "y": ... },
     *         "rightEye":  { "x": ..., "y": ... },
     *         "nose":      { "x": ..., "y": ... },
     *         "leftMouth": { "x": ..., "y": ... },
     *         "rightMouth":{ "x": ..., "y": ... }
     *       }
     *     }
     *   },
     *   "liveness": { "eyeOpenThreshold": ..., ... }
     * }
     * ```
     */
    fun loadFromJson(jsonString: String) {
        try {
            val root = JSONObject(jsonString)

            // ── faceRecognition ──
            val fr = root.optJSONObject("faceRecognition") ?: return

            // detector (supports yuNetDetector, YuNetDetector, or legacy detector)
            val det = fr.optJSONObject("YuNetDetector")
                ?: fr.optJSONObject("yuNetDetector")
                ?: fr.optJSONObject("detector")

            det?.let { d ->
                d.optStringNonEmpty("model")?.let { detectorModel = it }
                (d.optPositiveInt("inputSize") ?: d.optPositiveInt("input_size"))?.let { detectorInputSize = it }
                (d.optPositiveFloat("scoreThreshold") ?: d.optPositiveFloat("score_threshold") ?: d.optPositiveFloat("detectionThreshold"))?.let { detectionThreshold = it }
                (d.optPositiveFloat("nmsThreshold") ?: d.optPositiveFloat("nms_threshold"))?.let { nmsThreshold = it }
                (d.optPositiveInt("topK") ?: d.optPositiveInt("top_k"))?.let { topK = it }
                (d.optPositiveFloat("minFaceSize") ?: d.optPositiveFloat("min_face_size"))?.let { minFaceSize = it }
                (d.optPositiveFloat("maxFaceSize") ?: d.optPositiveFloat("max_face_size"))?.let { maxFaceSize = it }
                d.optJSONArray("strides")?.let { arr ->
                    if (arr.length() > 0) {
                        strides = IntArray(arr.length()) { i -> arr.getInt(i) }
                    }
                }
            }

            // recognizer
            fr.optJSONObject("recognizer")?.let { rec ->
                rec.optStringNonEmpty("model")?.let { recognizerModel = it }
                rec.optPositiveInt("inputSize")?.let { recognizerInputSize = it }
                rec.optPositiveInt("embeddingDimensions")?.let { embeddingDimensions = it }

                val regThresh = rec.optPositiveFloat("registrationCosineThreshold")
                    ?: rec.optPositiveFloat("registration_cosine_threshold")
                val recThresh = rec.optPositiveFloat("recognitionCosineThreshold")
                    ?: rec.optPositiveFloat("recognition_cosine_threshold")
                val globalThresh = rec.optPositiveFloat("cosineThreshold")
                    ?: rec.optPositiveFloat("cosine_threshold")

                if (regThresh != null) {
                    registrationCosineThreshold = regThresh
                } else if (globalThresh != null) {
                    registrationCosineThreshold = globalThresh
                }

                if (recThresh != null) {
                    recognitionCosineThreshold = recThresh
                } else if (globalThresh != null) {
                    recognitionCosineThreshold = globalThresh
                }
            }

            // quality
            fr.optJSONObject("quality")?.let { qual ->
                qual.optJSONObject("strict")?.let { s ->
                    s.optPositiveFloat("minEyeDistance")?.let { minEyeDistanceStrict = it }
                    s.optPositiveFloat("symmetryLimit")?.let { symmetryLimitStrict = it }
                    s.optPositiveFloat("minSharpness")?.let { minSharpnessStrict = it }
                }
                qual.optJSONObject("normal")?.let { n ->
                    n.optPositiveFloat("minEyeDistance")?.let { minEyeDistanceNormal = it }
                    n.optPositiveFloat("symmetryLimit")?.let { symmetryLimitNormal = it }
                    n.optPositiveFloat("minSharpness")?.let { minSharpnessNormal = it }
                }
            }

            // alignment template points
            fr.optJSONObject("alignment")
                ?.optJSONObject("templatePoints")?.let { tp ->
                    val leftEye = tp.optPointF("leftEye")
                    val rightEye = tp.optPointF("rightEye")
                    val nose = tp.optPointF("nose")
                    val leftMouth = tp.optPointF("leftMouth")
                    val rightMouth = tp.optPointF("rightMouth")
                    if (leftEye != null && rightEye != null && nose != null &&
                        leftMouth != null && rightMouth != null
                    ) {
                        alignmentTemplatePoints = listOf(
                            leftEye, rightEye, nose, leftMouth, rightMouth
                        )
                    }
                }

            // ── liveness ──
            root.optJSONObject("liveness")?.let { lv ->
                lv.optPositiveFloat("eyeOpenThreshold")?.let { eyeOpenThreshold = it }
                lv.optPositiveFloat("eyeClosedThreshold")?.let { eyeClosedThreshold = it }
                lv.optPositiveLong("challengeTimeoutMs")?.let { challengeTimeoutMs = it }
                lv.optPositiveLong("faceMissingResetMs")?.let { faceMissingResetMs = it }
                lv.optPositiveLong("passValidityMs")?.let { passValidityMs = it }
            }

            Log.i(
                TAG,
                "✔ FaceDetectionConfig applied successfully:\n" +
                "  • cosineThreshold = $cosineThreshold\n" +
                "  • detectionThreshold = $detectionThreshold\n" +
                "  • nmsThreshold = $nmsThreshold\n" +
                "  • topK = $topK\n" +
                "  • detectorInputSize = $detectorInputSize\n" +
                "  • minFaceSize = $minFaceSize px\n" +
                "  • maxFaceSize = $maxFaceSize px\n" +
                "  • minEyeDistance (strict/normal) = $minEyeDistanceStrict / $minEyeDistanceNormal\n" +
                "  • symmetryLimit (strict/normal) = $symmetryLimitStrict / $symmetryLimitNormal\n" +
                "  • minSharpness (strict/normal) = $minSharpnessStrict / $minSharpnessNormal\n" +
                "  • liveness (eyeOpen/eyeClosed) = $eyeOpenThreshold / $eyeClosedThreshold"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse config JSON — keeping defaults", e)
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

    private fun JSONObject.optPointF(key: String): PointF? {
        val obj = optJSONObject(key) ?: return null
        val x = obj.optDouble("x", Double.NaN)
        val y = obj.optDouble("y", Double.NaN)
        return if (!x.isNaN() && !y.isNaN()) PointF(x.toFloat(), y.toFloat()) else null
    }
}
