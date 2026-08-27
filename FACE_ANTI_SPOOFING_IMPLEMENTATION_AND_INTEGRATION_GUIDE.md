# Anti-Spoofing Implementation & Integration Guide
## Production Anti-Proxy Architecture for YuNet + SFace Android Attendance System

---

## 1. Executive Senior Engineer Assessment

### 1.1 Root Cause Analysis of the Replay Attack Problem

Your current Android face recognition attendance pipeline operates as follows:

$$\text{CameraX} \longrightarrow \text{YuNet (Face Detection)} \longrightarrow \text{SFace (128-D Embedding Extraction)} \longrightarrow \text{Cosine Match} \longrightarrow \text{Attendance Marked}$$

#### Why Proxy Attendance (Video/Photo Replay) Succeeds
1. **SFace measures identity, NOT liveness:** SFace evaluates facial structure (eye-to-nose ratio, cheek contours, bone proportions). A 1080p video or photo on a smartphone/tablet screen displays the exact facial structure of the targeted student. SFace correctly measures a high cosine similarity ($\ge 0.42$) because the geometric facial features match.
2. **YuNet detects screens as valid faces:** YuNet locates human face landmarks (eyes, nose, mouth). Screen pixels displaying a face contain these exact landmarks. YuNet does not know whether light originated from human skin reflection or an OLED/LCD display emission.
3. **Single-Frame Vulnerability:** If the system makes a decision on a single video frame without verifying temporal continuity, depth, screen texture, or interactive responses, any screen replay attack will succeed.

### 1.2 The "Foolproof" Defense-in-Depth Solution

No single model guarantees 100% anti-spoofing in isolation. **A resilient, production-grade anti-spoofing solution requires Defense-in-Depth:**

```
                    ┌─────────────────────────┐
                    │     CameraX Stream      │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │   YuNet Face Detection  │
                    │  (BBox + 5 Landmarks)   │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │   Face Quality Gate     │
                    │  (Size, Blur, Lighting) │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │  MiniFASNet V2-SE ONNX  │
                    │ (Passive Texture Check) │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │   Temporal Windowing    │
                    │ (N-Frame Score Pooling) │
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │  Active Random Challenge│
                    │(Pose/Blink Verification)│
                    └────────────┬────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │   SFace ID Match Gate   │
                    │  (Identity Extraction)  │
                    └─────────────────────────┘
```

#### Key Pillars of Defense

| Defense Layer | Technology | Security Function | Prevents |
|---|---|---|---|
| **1. Quality Gate** | OpenCV / Bitmap math | Filters bad frames (tiny faces, blur, glare) | Pre-processing garbage & edge noise |
| **2. Passive Anti-Spoof** | **MiniFASNet V2-SE ONNX** | Deep frequency & texture feature extraction | Photo prints, screen replay, static displays |
| **3. Temporal Pooling** | Multi-frame sliding median | Requires $N$ consecutive real frames | Frame glitches, transient reflection bypass |
| **4. Active Challenge** | Head pose / Blink engine | Validates live real-time motion against random prompts | Pre-recorded videos, static photo replay |
| **5. Strict Flow Order** | Android execution logic | Enforces Liveness verification **BEFORE** SFace matching | Unauthorized identity leakage & proxy mark |

---

## 2. Model & Pipeline Technical Specifications

### 2.1 MiniFASNet V2-SE Model Profile

- **Model Architecture:** MiniFASNet V2 with Squeeze-and-Excitation (SE) blocks.
- **Model Size:** ~1.82 MB (FP32 ONNX) / ~600 KB (INT8 Quantized ONNX).
- **Execution Engine:** ONNX Runtime Android (`ai.onnxruntime:onnxruntime-android`).
- **Input Dimensions:** $128 \times 128 \times 3$ (RGB tensor, scaled face crop).
- **Crop Factor:** $2.7\times$ bounding box expansion around the YuNet face center to include background/screen border context (crucial for screen reflection and moiré detection).
- **Inference Time:** ~10-18 ms on mid-range Android devices.

### 2.2 Preprocessing Standard

```
YuNet Bounding Box (cx, cy, w, h)
            │
            ▼
Expand Box by 2.7x Scale Factor (include screen border)
            │
            ▼
Crop & Resize to 128x128 RGB
            │
            ▼
Normalize Pixels to [0.0, 1.0] or Standard ImageNet Normalization
            │
            ▼
Convert to NCHW Float Buffer: [1, 3, 128, 128]
```

---

## 3. Step-by-Step Implementation Blueprint

### 3.1 Step 1: Add Model Asset

Download `minifasnet_v2_se.onnx` (or `minifasnet_v2_se_int8.onnx`) and place it inside your Android assets directory:
```
app/src/main/assets/models/minifasnet_v2_se.onnx
```

### 3.2 Step 2: Implement `MiniFASNetEngine.kt`

Create the ONNX Runtime wrapper for MiniFASNet V2-SE in `com.digitaledu.selfieattendance.ml`:

```kotlin
package com.digitaledu.selfieattendance.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.exp

data class LivenessScore(
    val isReal: Boolean,
    val score: Float, // Probability of being real [0.0 - 1.0]
    val rawSpoofLogit: Float,
    val rawRealLogit: Float
)

/**
 * MiniFASNet V2-SE Passive Face Anti-Spoofing Engine.
 * Operates on ONNX Runtime Android to classify face crops as Genuine (Bona Fide) or Spoof.
 */
class MiniFASNetEngine(context: Context) : AutoCloseable {

    @Volatile
    private var closed = false

    private val environment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }

    private val session: OrtSession = environment.createSession(
        context.assets.open(MODEL_ASSET_PATH).use { it.readBytes() },
        options
    )

    private val inputName: String = session.inputNames.first()

    @Synchronized
    fun classifyLiveness(bitmap: Bitmap, faceBounds: RectF): LivenessScore {
        check(!closed) { "MiniFASNetEngine is closed" }

        // 1. Crop face with 2.7x context expansion
        val cropBitmap = extractExpandedFaceCrop(bitmap, faceBounds, CROP_SCALE)

        try {
            // 2. Resize to 128x128
            val resized = Bitmap.createScaledBitmap(cropBitmap, INPUT_SIZE, INPUT_SIZE, true)

            // 3. Preprocess to NCHW Float Array (RGB, normalized [0.0, 1.0])
            val inputBuffer = bitmapToNormalizedNchw(resized)

            val tensor = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(inputBuffer),
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            )

            tensor.use {
                session.run(mapOf(inputName to tensor)).use { output ->
                    val rawOutput = output[0].value
                    val logits = extractLogits(rawOutput)

                    val spoofLogit = logits[0]
                    val realLogit = logits[1]

                    // Softmax calculation
                    val maxLogit = maxOf(spoofLogit, realLogit)
                    val expSpoof = exp(spoofLogit - maxLogit)
                    val expReal = exp(realLogit - maxLogit)
                    val realProbability = expReal / (expSpoof + expReal)

                    val isReal = realProbability >= LIVENESS_THRESHOLD

                    Log.d(
                        TAG,
                        "Anti-Spoof evaluation: score=%.4f, rawReal=%.2f, rawSpoof=%.2f, passed=%b"
                            .format(realProbability, realLogit, spoofLogit, isReal)
                    )

                    if (resized !== cropBitmap) resized.recycle()

                    return LivenessScore(
                        isReal = isReal,
                        score = realProbability,
                        rawSpoofLogit = spoofLogit,
                        rawRealLogit = realLogit
                    )
                }
            }
        } finally {
            if (cropBitmap !== bitmap) cropBitmap.recycle()
        }
    }

    private fun extractExpandedFaceCrop(src: Bitmap, bounds: RectF, scale: Float): Bitmap {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val side = maxOf(bounds.width(), bounds.height()) * scale

        val left = cx - side / 2f
        val top = cy - side / 2f

        val srcRect = RectF(left, top, left + side, top + side)
        val dstRect = RectF(0f, 0f, side, side)

        val cropped = Bitmap.createBitmap(side.toInt().coerceAtLeast(1), side.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cropped)
        canvas.drawColor(Color.BLACK)

        val matrix = Matrix()
        matrix.setRectToRect(srcRect, dstRect, Matrix.ScaleToFit.FILL)
        canvas.drawBitmap(src, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        return cropped
    }

    private fun bitmapToNormalizedNchw(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val planeSize = width * height
        val output = FloatArray(planeSize * 3)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (Color.red(pixel) / 255.0f)
            val g = (Color.green(pixel) / 255.0f)
            val b = (Color.blue(pixel) / 255.0f)

            // RGB NCHW Layout
            output[i] = r
            output[planeSize + i] = g
            output[planeSize * 2 + i] = b
        }
        return output
    }

    private fun extractLogits(value: Any?): FloatArray {
        if (value is Array<*>) {
            val first = value[0]
            if (first is FloatArray) return first
        }
        if (value is FloatArray) return value
        throw IllegalArgumentException("Unexpected model output format: ${value?.javaClass}")
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        session.close()
        options.close()
    }

    companion object {
        private const val TAG = "MiniFASNetEngine"
        const val MODEL_ASSET_PATH = "models/minifasnet_v2_se.onnx"
        const val INPUT_SIZE = 128
        const val CROP_SCALE = 2.7f
        
        // Default Liveness Threshold (Calibrate on device fleet)
        var LIVENESS_THRESHOLD = 0.90f
    }
}
```

---

### 3.3 Step 3: Implement Multi-Frame Temporal Windowing (`TemporalLivenessBuffer.kt`)

To prevent single-frame glitches or brief screen reflection passes from tricking the system, enforce a sliding temporal window over $N=5$ consecutive frames.

```kotlin
package com.digitaledu.selfieattendance.ml

import java.util.ArrayDeque

/**
 * Sliding window buffer that aggregates passive liveness scores over consecutive frames.
 * Prevents transient false-positive passes from spoof displays.
 */
class TemporalLivenessBuffer(private val windowSize: Int = 5, private val requiredPassPercentage: Float = 0.8f) {

    private val scoreHistory = ArrayDeque<Float>()

    @Synchronized
    fun addScore(score: Float) {
        if (scoreHistory.size >= windowSize) {
            scoreHistory.removeFirst()
        }
        scoreHistory.addLast(score)
    }

    @Synchronized
    fun isTemporalLivenessPassed(threshold: Float): Boolean {
        if (scoreHistory.size < windowSize) return false

        val sortedScores = scoreHistory.sorted()
        val medianScore = sortedScores[sortedScores.size / 2]

        val passCount = scoreHistory.count { it >= threshold }
        val passRatio = passCount.toFloat() / scoreHistory.size

        return medianScore >= threshold && passRatio >= requiredPassPercentage
    }

    @Synchronized
    fun reset() {
        scoreHistory.clear()
    }

    @Synchronized
    fun getCurrentMedian(): Float {
        if (scoreHistory.isEmpty()) return 0f
        val sorted = scoreHistory.sorted()
        return sorted[sorted.size / 2]
    }
}
```

---

### 3.4 Step 4: Integrated Anti-Spoofing Orchestrator (`AntiSpoofPipelineManager.kt`)

Create a unified coordinator that links YuNet, Quality Gate, MiniFASNet Passive Anti-Spoofing, Active Liveness Challenge, and SFace Recognition:

```kotlin
package com.digitaledu.selfieattendance.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

data class AntiSpoofResult(
    val isLive: Boolean,
    val statusMessage: String,
    val passiveScore: Float,
    val activeChallengePassed: Boolean,
    val face: YuNetFace? = null,
    val embedding: FloatArray? = null
)

/**
 * Master Pipeline Manager enforcing Anti-Spoofing BEFORE SFace Recognition.
 */
class AntiSpoofPipelineManager(context: Context) : AutoCloseable {

    val yuNetEngine = YuNetSFaceEngine(context)
    val miniFASNetEngine = MiniFASNetEngine(context)
    val activeVerifier = ActiveLivenessVerifier()
    private val temporalBuffer = TemporalLivenessBuffer(windowSize = 5, requiredPassPercentage = 0.8f)

    @Synchronized
    fun processFrame(bitmap: Bitmap, requireActiveChallenge: Boolean = true): AntiSpoofResult {
        // 1. YuNet Face Detection
        val diagnostics = yuNetEngine.detectWithDiagnostics(bitmap)
        val faces = diagnostics.faces

        // Strict Single Face Enforcer (Prevents multi-face proxy tampering)
        if (faces.isEmpty()) {
            temporalBuffer.reset()
            return AntiSpoofResult(false, diagnostics.diagnosticReason, 0f, false)
        }
        if (faces.size > 1) {
            temporalBuffer.reset()
            return AntiSpoofResult(false, "Multiple faces detected! Attendance allows only 1 person.", 0f, false)
        }

        val face = faces.first()

        // 2. Face Quality Gate Check
        val quality = yuNetEngine.assessQualityDetailed(bitmap, face, strict = false)
        if (!quality.accepted) {
            temporalBuffer.reset()
            return AntiSpoofResult(false, quality.guidance, 0f, false, face)
        }

        // 3. Passive Anti-Spoofing (MiniFASNet V2-SE ONNX)
        val livenessScore = miniFASNetEngine.classifyLiveness(bitmap, face.bounds)
        temporalBuffer.addScore(livenessScore.score)

        val isPassivePassed = temporalBuffer.isTemporalLivenessPassed(MiniFASNetEngine.LIVENESS_THRESHOLD)
        val currentMedian = temporalBuffer.getCurrentMedian()

        if (!isPassivePassed) {
            return AntiSpoofResult(
                isLive = false,
                statusMessage = "Spoof / Screen replay detected (Score: %.2f)".format(currentMedian),
                passiveScore = currentMedian,
                activeChallengePassed = false,
                face = face
            )
        }

        // 4. Active Liveness Challenge (Blink / Head Movement)
        var isActivePassed = true
        if (requireActiveChallenge) {
            val activeResult = activeVerifier.update(bitmap)
            isActivePassed = activeResult.passed

            if (!isActivePassed) {
                return AntiSpoofResult(
                    isLive = false,
                    statusMessage = activeResult.guidance,
                    passiveScore = currentMedian,
                    activeChallengePassed = false,
                    face = face
                )
            }
        }

        // 5. SFace Embedding Extraction (Executed ONLY IF Anti-Spoofing passes)
        val embedding = yuNetEngine.embedding(bitmap, face)

        return AntiSpoofResult(
            isLive = true,
            statusMessage = "Anti-spoofing passed. Live user confirmed.",
            passiveScore = currentMedian,
            activeChallengePassed = isActivePassed,
            face = face,
            embedding = embedding
        )
    }

    @Synchronized
    fun resetPipeline() {
        temporalBuffer.reset()
        activeVerifier.reset()
    }

    @Synchronized
    override fun close() {
        yuNetEngine.close()
        miniFASNetEngine.close()
        activeVerifier.close()
    }
}
```

---

## 4. Integration with Existing Recognition Screens

### 4.1 Modifying Recognition Flow in `StudentScanFragment.kt` and `FaceRecogniseActivity.kt`

Currently, recognition screens extract SFace embeddings immediately upon detecting a face. **Update the analyzer loop to route through `AntiSpoofPipelineManager`:**

```kotlin
// Inside your CameraX Analyzer frame processor:

private val antiSpoofManager by lazy { AntiSpoofPipelineManager(requireContext()) }

private fun processCameraFrame(bitmap: Bitmap) {
    // Execute Anti-Spoofing Pipeline BEFORE matching identity
    val result = antiSpoofManager.processFrame(bitmap, requireActiveChallenge = true)

    if (!result.isLive) {
        // Display real-time security guidance feedback to user
        updateUiStatus(result.statusMessage, isWarning = true)
        return
    }

    // Anti-Spoofing passed! Retrieve validated embedding
    val liveEmbedding = result.embedding ?: return

    // Execute SFace Cosine Match against local/server database
    val matchedUser = findBestMatch(liveEmbedding)

    if (matchedUser != null) {
        markAttendanceSuccess(matchedUser)
    } else {
        updateUiStatus("Face not recognized in registered records", isWarning = true)
    }
}
```

---

## 5. Calibration & Verification Protocol

### 5.1 Attack Testing Matrix (APCER vs BPCER)

Before releasing to production, validate your threshold on an attack dataset containing:

| Attack Category | Test Sample | Minimum Rejection Target |
|---|---|---|
| **Phone Screen Replay** | 1080p video played on iPhone/Samsung | **> 99.5% Rejected** |
| **Tablet Display** | 4K video played on iPad Pro | **> 99.0% Rejected** |
| **Laptop Replay** | Video played on MacBook screen | **> 99.0% Rejected** |
| **Printed Photo** | Matte & Glossy paper photo | **> 99.9% Rejected** |
| **Bona Fide Student** | Live students under various lighting | **< 1.5% False Rejection** |

### 5.2 Threshold Tuning Guideline

- If students experience false rejections under dark classroom lighting: **Lower `LIVENESS_THRESHOLD` slightly (e.g., from 0.90 to 0.85).**
- If high-brightness iPad screens bypass detection: **Increase `LIVENESS_THRESHOLD` (e.g., to 0.92) or expand `CROP_SCALE` to 3.0x to capture screen bevel reflections.**

---



Step 1: Test with LIVENESS_THRESHOLD = 0.90
        → If real students get rejected in low light → lower to 0.85
        → If phone screen replay passes → raise to 0.93

Step 2: Test with CROP_SCALE = 2.7
        → If high-brightness OLED screens bypass → raise to 3.0 or 3.5
        → If face crops become too zoomed out → stay at 2.7

Step 3: Test with windowSize = 5
        → If spoofs occasionally sneak through → raise to 7-10
        → If attendance feels sluggish → lower to 3


## 6. Senior Engineer Summary Checklist

- [x] **Separation of Concerns:** YuNet detects, Quality Gate filters, MiniFASNet verifies liveness, Active Challenge tests motion, SFace identifies.
- [x] **Execution Hierarchy:** Liveness ALWAYS executes BEFORE SFace matching.
- [x] **Temporal Stability:** 5-frame sliding window eliminates single-frame bypasses.
- [x] **Single Face Enforcement:** Multiple faces in view immediately abort the session.
