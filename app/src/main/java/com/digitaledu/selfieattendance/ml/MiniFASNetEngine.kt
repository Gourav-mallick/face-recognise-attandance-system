package com.digitaledu.selfieattendance.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * ONNX Runtime wrapper for **MiniFASNet V2-SE** passive anti-spoofing.
 *
 * This engine classifies whether the face visible in a camera frame is
 * a live human or a spoof (printed photo, phone screen, tablet replay).
 *
 * ## Model Metadata (version-locked)
 * - **Model file**: `minifasnet_v2_se.onnx` (FP32, 1.82 MB)
 * - **Architecture**: MiniFASNet V2 with Squeeze-and-Excitation blocks
 * - **Input**: `[1, 3, 128, 128]` — batch=1, channels=RGB, 128×128 pixels
 * - **Normalization**: pixel / 255.0 → [0.0, 1.0]
 * - **Crop**: 2.7× context expansion around face bounding box center
 * - **Output**: `[1, 2]` — logits `[spoof, real]`
 * - **Interpretation**: Softmax → `real_probability`; compare against threshold
 *
 * ## Thread Safety
 * All inference methods are `@Synchronized`.
 * The ONNX session is created once and reused for the lifetime of the engine.
 *
 * ## Lifecycle
 * Call [close] when the camera screen is destroyed to release native resources.
 */
class MiniFASNetEngine(context: Context) : AutoCloseable {

    private val tag = "MiniFASNetEngine"

    /** ONNX Runtime environment (shared singleton). */
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()

    /** ONNX inference session, loaded once from assets. */
    private val session: OrtSession?

    /** Whether the engine initialized successfully. */
    val isAvailable: Boolean
        get() = session != null

    /** Reusable float buffer sized for one 128×128 RGB frame (3 × 128 × 128 = 49152). */
    private val inputBuffer = FloatBuffer.allocate(3 * AntiSpoofConfig.INPUT_WIDTH * AntiSpoofConfig.INPUT_HEIGHT)

    init {
        session = try {
            val modelBytes = context.assets.open(AntiSpoofConfig.modelAssetPath).use { it.readBytes() }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            ortEnv.createSession(modelBytes, opts).also {
                Log.i(tag, "✔ MiniFASNet V2-SE loaded (${AntiSpoofConfig.modelAssetPath})")
            }
        } catch (e: Exception) {
            Log.e(tag, "✘ Failed to load MiniFASNet V2-SE model", e)
            null
        }
    }

    // ─────────────────── Public API ──────────────────────────────────

    /**
     * Result of a single-frame anti-spoofing classification.
     *
     * @param status  HIGH-LEVEL verdict: [Status.REAL], [Status.SPOOF], or [Status.UNCERTAIN].
     * @param score   The softmax probability that the face is real (0.0–1.0).
     * @param inferenceMs  Wall-clock time spent inside ONNX inference (ms).
     * @param guidance  Human-readable message suitable for UI display.
     */
    data class AntiSpoofResult(
        val status: Status,
        val score: Float,
        val inferenceMs: Long,
        val guidance: String
    )

    enum class Status {
        /** Model classifies the face as a live human. */
        REAL,
        /** Model classifies the face as a spoof (photo/screen/printout). */
        SPOOF,
        /** Model is unavailable or inference failed — fail-safe (blocks SFace). */
        UNCERTAIN
    }

    /**
     * Classify a detected face as REAL or SPOOF.
     *
     * @param frame        The full camera frame bitmap (upright, mirrored if front camera).
     * @param faceBounds   Bounding box from YuNet detection (in frame coordinates).
     * @param threshold    Softmax cutoff; scores ≥ threshold → REAL.
     *                     Defaults to [AntiSpoofConfig.livenessThreshold].
     * @return [AntiSpoofResult] with status, score, timing, and guidance text.
     */
    @Synchronized
    fun classifyLiveness(
        frame: Bitmap,
        faceBounds: RectF,
        threshold: Float = AntiSpoofConfig.livenessThreshold
    ): AntiSpoofResult {
        if (session == null) {
            return AntiSpoofResult(
                status = Status.UNCERTAIN,
                score = 0f,
                inferenceMs = 0,
                guidance = "Anti-spoof model unavailable"
            )
        }

        return try {
            val startMs = System.currentTimeMillis()

            // Step 1: Crop with context expansion
            val cropped = cropWithScale(frame, faceBounds, AntiSpoofConfig.cropScale)

            // Step 2: Resize to model input dimensions
            val resized = Bitmap.createScaledBitmap(
                cropped,
                AntiSpoofConfig.INPUT_WIDTH,
                AntiSpoofConfig.INPUT_HEIGHT,
                true
            )
            if (resized !== cropped) cropped.recycle()

            // Step 3: Convert to float tensor [1, 3, 128, 128] in CHW, RGB, /255
            val tensor = bitmapToTensor(resized)
            resized.recycle()

            // Step 4: Run inference
            val inputName = session.inputNames.first()
            val output = session.run(mapOf(inputName to tensor))
            tensor.close()

            // Step 5: Parse output [spoof, real] logits → softmax
            val rawOutput = (output[0].value as Array<FloatArray>)[0]
            output.close()

            val realProb = softmax(rawOutput)
            val inferenceMs = System.currentTimeMillis() - startMs

            val status = if (realProb >= threshold) Status.REAL else Status.SPOOF
            val guidance = when (status) {
                Status.REAL -> "Live face verified"
                Status.SPOOF -> "Spoof detected — use a real face"
                Status.UNCERTAIN -> "Unable to verify liveness"
            }

            if (AntiSpoofConfig.debugLogging) {
                Log.d(
                    tag,
                    "MiniFASNet → status=$status, realProb=${String.format("%.4f", realProb)}, " +
                    "threshold=$threshold, rawLogits=[${rawOutput.joinToString()}], " +
                    "inferenceMs=${inferenceMs}ms"
                )
            }

            AntiSpoofResult(
                status = status,
                score = realProb,
                inferenceMs = inferenceMs,
                guidance = guidance
            )
        } catch (e: Exception) {
            Log.e(tag, "MiniFASNet inference failed", e)
            AntiSpoofResult(
                status = Status.UNCERTAIN,
                score = 0f,
                inferenceMs = 0,
                guidance = "Liveness check error — retrying"
            )
        }
    }

    // ─────────────────── Preprocessing ───────────────────────────────

    /**
     * Crop around the face center with a context expansion factor.
     * The expanded crop captures surrounding context (screen bezels,
     * paper edges) that helps the model distinguish real from spoof.
     */
    private fun cropWithScale(
        bmp: Bitmap,
        rect: RectF,
        scale: Float
    ): Bitmap {
        val cx = rect.centerX().toInt()
        val cy = rect.centerY().toInt()
        val halfW = (rect.width() * scale / 2f).toInt()
        val halfH = (rect.height() * scale / 2f).toInt()
        val x = max(0, cx - halfW)
        val y = max(0, cy - halfH)
        val w = min(bmp.width - x, halfW * 2)
        val h = min(bmp.height - y, halfH * 2)
        if (w <= 0 || h <= 0) {
            // Fallback: use full bitmap if crop is degenerate
            return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height)
        }
        return Bitmap.createBitmap(bmp, x, y, w, h)
    }

    /**
     * Convert a 128×128 ARGB_8888 bitmap to an ONNX tensor.
     *
     * Layout: `[1, 3, H, W]` — batch=1, channels-first (CHW).
     * Color order: BGR if [AntiSpoofConfig.inputIsRgb] is false, RGB otherwise.
     * Normalization: pixel / 255.0 → [0.0, 1.0].
     */
    private fun bitmapToTensor(bitmap: Bitmap): OnnxTensor {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        inputBuffer.clear()

        val isRgb = AntiSpoofConfig.inputIsRgb
        val channel0Shift = if (isRgb) 16 else 0  // R if RGB, B if BGR
        val channel1Shift = 8                     // G
        val channel2Shift = if (isRgb) 0 else 16  // B if RGB, R if BGR

        // Channel 0 (R or B)
        for (pixel in pixels) {
            inputBuffer.put(((pixel shr channel0Shift) and 0xFF) / 255f)
        }
        // Channel 1 (G)
        for (pixel in pixels) {
            inputBuffer.put(((pixel shr channel1Shift) and 0xFF) / 255f)
        }
        // Channel 2 (B or R)
        for (pixel in pixels) {
            inputBuffer.put(((pixel shr channel2Shift) and 0xFF) / 255f)
        }

        inputBuffer.flip()

        val shape = longArrayOf(1, 3, h.toLong(), w.toLong())
        return OnnxTensor.createTensor(ortEnv, inputBuffer, shape)
    }

    /**
     * Apply softmax to the 2-class output logits and return the
     * probability of the real face class specified by [AntiSpoofConfig.realClassIndex].
     *
     * CelebA-Spoof / Silent-Face: Index 0 = Real, Index 1 = Spoof.
     */
    private fun softmax(logits: FloatArray): Float {
        require(logits.size == 2) { "Expected 2-class output, got ${logits.size}" }
        val maxLogit = maxOf(logits[0], logits[1])
        val exp0 = exp((logits[0] - maxLogit).toDouble()).toFloat()
        val exp1 = exp((logits[1] - maxLogit).toDouble()).toFloat()
        val sumExp = exp0 + exp1
        val probs = floatArrayOf(exp0 / sumExp, exp1 / sumExp)

        val realIdx = AntiSpoofConfig.realClassIndex.coerceIn(0, 1)
        return probs[realIdx]
    }

    // ─────────────────── Lifecycle ───────────────────────────────────

    override fun close() {
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(tag, "Error closing MiniFASNet session", e)
        }
    }
}
