package com.digitaledu.selfieattendance.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class YuNetFace(
    val bounds: RectF,
    val landmarks: List<PointF>,
    val confidence: Float
)

data class FaceQuality(
    val accepted: Boolean,
    val guidance: String,
    val sharpness: Float
)

data class DetailedFaceQuality(
    val accepted: Boolean,
    val guidance: String,
    val eyeDistance: Float,
    val sharpness: Float,
    val isSymmetric: Boolean
)

data class DetectionDiagnostics(
    val faces: List<YuNetFace>,
    val maxRawScore: Float,
    val totalRawCandidatesCount: Int,
    val rejectedSizeCandidatesCount: Int,
    val lastRejectedFaceSizePx: Float,
    val diagnosticReason: String
)

/**
 * YuNet detector + five-point alignment + SFace embedding pipeline.
 *
 * Input frames must already be rotated upright and mirrored when using the front camera.
 * The public methods are synchronized because one ORT session must not be run concurrently
 * by CameraX analyzer callbacks.
 */
class YuNetSFaceEngine(context: Context) : AutoCloseable {
    @Volatile
    private var closed = false

    private val environment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val detector = environment.createSession(
        context.assets.open(DETECTOR_ASSET).use { it.readBytes() },
        options
    )
    private val recognizer = environment.createSession(
        context.assets.open(RECOGNIZER_ASSET).use { it.readBytes() },
        options
    )
    private val detectorInput = detector.inputNames.first()
    private val recognizerInput = recognizer.inputNames.first()
    private val detectorShape = (detector.inputInfo.getValue(detectorInput).info as TensorInfo).shape
    private val detectorHeight = detectorShape.getOrNull(2)?.takeIf { it > 0 }?.toInt()
        ?: DEFAULT_DETECTOR_SIZE
    private val detectorWidth = detectorShape.getOrNull(3)?.takeIf { it > 0 }?.toInt()
        ?: DEFAULT_DETECTOR_SIZE

    @Synchronized
    fun detect(bitmap: Bitmap, scoreThreshold: Float = FaceDetectionConfig.detectionThreshold): List<YuNetFace> {
        return detectWithDiagnostics(bitmap, scoreThreshold).faces
    }

    @Synchronized
    fun detectWithDiagnostics(
        bitmap: Bitmap,
        scoreThreshold: Float = FaceDetectionConfig.detectionThreshold
    ): DetectionDiagnostics {
        check(!closed) { "YuNet/SFace engine is closed" }

        val modelReqWidth = detectorShape.getOrNull(3)?.takeIf { it > 0 }?.toInt()
        val modelReqHeight = detectorShape.getOrNull(2)?.takeIf { it > 0 }?.toInt()

        val targetWidth = if (modelReqWidth != null && modelReqWidth > 0) modelReqWidth else (FaceDetectionConfig.detectorInputSize.takeIf { it > 0 } ?: detectorWidth)
        val targetHeight = if (modelReqHeight != null && modelReqHeight > 0) modelReqHeight else (FaceDetectionConfig.detectorInputSize.takeIf { it > 0 } ?: detectorHeight)

        Log.d(
            "YuNetSFaceEngine",
            "detect() start: targetSize=${targetWidth}x${targetHeight}, frameSize=${bitmap.width}x${bitmap.height}, " +
            "minFaceSize=${FaceDetectionConfig.minFaceSize}px, maxFaceSize=${FaceDetectionConfig.maxFaceSize}px, " +
            "topK=${FaceDetectionConfig.topK}, scoreThreshold=$scoreThreshold, nmsThreshold=${FaceDetectionConfig.nmsThreshold}"
        )

        val resized = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)

        var maxRawScore = 0f
        var rawCount = 0
        var sizeRejectedCount = 0
        var lastRejectedSizePx = 0f

        try {
            val input = bitmapToNchw(resized, rgbOrder = false)
            val candidates = mutableListOf<YuNetFace>()

            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, targetHeight.toLong(), targetWidth.toLong())
            ).use { tensor ->
                detector.run(mapOf(detectorInput to tensor)).use { output ->
                    for (stride in STRIDES) {
                        val suffix = stride.toString()
                        val cls = outputFloats(output, "cls_$suffix")
                        val obj = outputFloats(output, "obj_$suffix")
                        val boxes = outputFloats(output, "bbox_$suffix")
                        val keypoints = outputFloats(output, "kps_$suffix")
                        val columns = targetWidth / stride
                        val count = min(cls.size, obj.size)

                        for (index in 0 until count) {
                            val score = sqrt(max(0f, cls[index] * obj[index]))
                            if (score > maxRawScore) maxRawScore = score
                            if (score < scoreThreshold) continue

                            rawCount++

                            val row = index / columns
                            val col = index % columns
                            val boxOffset = index * 4
                            val keypointOffset = index * 10
                            if (boxOffset + 3 >= boxes.size || keypointOffset + 9 >= keypoints.size) continue

                            val centerX = (boxes[boxOffset] + col) * stride
                            val centerY = (boxes[boxOffset + 1] + row) * stride
                            val width = exp(boxes[boxOffset + 2].coerceIn(-10f, 10f)) * stride
                            val height = exp(boxes[boxOffset + 3].coerceIn(-10f, 10f)) * stride

                            val scaleX = bitmap.width.toFloat() / targetWidth
                            val scaleY = bitmap.height.toFloat() / targetHeight

                            val rect = RectF(
                                (centerX - width / 2f) * scaleX,
                                (centerY - height / 2f) * scaleY,
                                (centerX + width / 2f) * scaleX,
                                (centerY + height / 2f) * scaleY
                            )

                            val canvasFaceSizePx = max(width, height)
                            val passesSizeGate = canvasFaceSizePx >= FaceDetectionConfig.minFaceSize && canvasFaceSizePx <= FaceDetectionConfig.maxFaceSize

                            if (!passesSizeGate) {
                                sizeRejectedCount++
                                lastRejectedSizePx = canvasFaceSizePx
                                continue
                            }

                            val landmarks = List(5) { point ->
                                PointF(
                                    (keypoints[keypointOffset + point * 2] + col) * stride * scaleX,
                                    (keypoints[keypointOffset + point * 2 + 1] + row) * stride * scaleY
                                )
                            }
                            candidates += YuNetFace(
                                rect,
                                landmarks,
                                score
                            )
                        }
                    }
                }
            }

            val topKCandidates = candidates
                .sortedByDescending { it.confidence }
                .take(FaceDetectionConfig.topK)

            val finalResult = nonMaximumSuppression(topKCandidates)

            val reason = when {
                finalResult.isNotEmpty() -> "Face detected successfully"
                sizeRejectedCount > 0 -> "Candidate face size (${String.format(java.util.Locale.US, "%.1f", lastRejectedSizePx)}px) outside bounds [min=${FaceDetectionConfig.minFaceSize.toInt()}px, max=${FaceDetectionConfig.maxFaceSize.toInt()}px]"
                rawCount == 0 -> "Low confidence score (Max candidate score ${String.format(java.util.Locale.US, "%.2f", maxRawScore)} < threshold ${String.format(java.util.Locale.US, "%.2f", scoreThreshold)})"
                else -> "No valid face candidates after NMS"
            }

            return DetectionDiagnostics(
                faces = finalResult,
                maxRawScore = maxRawScore,
                totalRawCandidatesCount = rawCount,
                rejectedSizeCandidatesCount = sizeRejectedCount,
                lastRejectedFaceSizePx = lastRejectedSizePx,
                diagnosticReason = reason
            )
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    fun assessQuality(bitmap: Bitmap, face: YuNetFace, strict: Boolean): FaceQuality {
        if (face.landmarks.size != 5) return FaceQuality(false, "Keep your whole face visible", 0f)
        val leftEye = face.landmarks[0]
        val rightEye = face.landmarks[1]
        val nose = face.landmarks[2]
        val leftMouth = face.landmarks[3]
        val rightMouth = face.landmarks[4]
        val eyeDistance = distance(leftEye, rightEye)
        val minimumEyeDistance = if (strict) FaceDetectionConfig.minEyeDistanceStrict else FaceDetectionConfig.minEyeDistanceNormal
        if (eyeDistance < minimumEyeDistance) return FaceQuality(false, "Move closer to the camera", 0f)

        val eyeMidX = (leftEye.x + rightEye.x) / 2f
        val mouthMidX = (leftMouth.x + rightMouth.x) / 2f
        val symmetryLimit = eyeDistance * if (strict) FaceDetectionConfig.symmetryLimitStrict else FaceDetectionConfig.symmetryLimitNormal
        if (abs(nose.x - eyeMidX) > symmetryLimit || abs(nose.x - mouthMidX) > symmetryLimit) {
            return FaceQuality(false, "Look straight at the screen", 0f)
        }
        if (face.bounds.left < 0 || face.bounds.top < 0 ||
            face.bounds.right > bitmap.width || face.bounds.bottom > bitmap.height
        ) return FaceQuality(false, "Keep your whole face inside the guide", 0f)

        val aligned = align(bitmap, face.landmarks)
        val sharpness = laplacianVariance(aligned)
        aligned.recycle()
        val minimumSharpness = if (strict) FaceDetectionConfig.minSharpnessStrict else FaceDetectionConfig.minSharpnessNormal
        if (sharpness < minimumSharpness) {
            return FaceQuality(false, "Hold still — image is blurry", sharpness)
        }
        return FaceQuality(true, "Landmarks locked — processing", sharpness)
    }

    fun assessQualityDetailed(bitmap: Bitmap, face: YuNetFace, strict: Boolean): DetailedFaceQuality {
        if (face.landmarks.size != 5) return DetailedFaceQuality(false, "Face landmarks incomplete (< 5 points)", 0f, 0f, false)
        val leftEye = face.landmarks[0]
        val rightEye = face.landmarks[1]
        val nose = face.landmarks[2]
        val leftMouth = face.landmarks[3]
        val rightMouth = face.landmarks[4]
        val eyeDistance = distance(leftEye, rightEye)
        val minimumEyeDistance = if (strict) FaceDetectionConfig.minEyeDistanceStrict else FaceDetectionConfig.minEyeDistanceNormal

        val eyeMidX = (leftEye.x + rightEye.x) / 2f
        val mouthMidX = (leftMouth.x + rightMouth.x) / 2f
        val symmetryLimit = eyeDistance * if (strict) FaceDetectionConfig.symmetryLimitStrict else FaceDetectionConfig.symmetryLimitNormal
        val isSymmetric = abs(nose.x - eyeMidX) <= symmetryLimit && abs(nose.x - mouthMidX) <= symmetryLimit

        if (eyeDistance < minimumEyeDistance) {
            return DetailedFaceQuality(
                false,
                "Face too far / Eye distance small (${String.format(java.util.Locale.US, "%.1f", eyeDistance)}px < Min ${minimumEyeDistance.toInt()}px)",
                eyeDistance,
                0f,
                isSymmetric
            )
        }
        if (!isSymmetric) {
            return DetailedFaceQuality(
                false,
                "Head turned sideways / Asymmetric pose",
                eyeDistance,
                0f,
                false
            )
        }
        if (face.bounds.left < 0 || face.bounds.top < 0 ||
            face.bounds.right > bitmap.width || face.bounds.bottom > bitmap.height
        ) {
            return DetailedFaceQuality(
                false,
                "Partial face outside image frame",
                eyeDistance,
                0f,
                isSymmetric
            )
        }

        val aligned = align(bitmap, face.landmarks)
        val sharpness = laplacianVariance(aligned)
        aligned.recycle()
        val minimumSharpness = if (strict) FaceDetectionConfig.minSharpnessStrict else FaceDetectionConfig.minSharpnessNormal

        if (sharpness < minimumSharpness) {
            return DetailedFaceQuality(
                false,
                "Image is blurry (Sharpness ${String.format(java.util.Locale.US, "%.1f", sharpness)} < Min ${minimumSharpness.toInt()})",
                eyeDistance,
                sharpness,
                isSymmetric
            )
        }
        return DetailedFaceQuality(true, "Passed Quality Gate", eyeDistance, sharpness, true)
    }

    fun align(bitmap: Bitmap, landmarks: List<PointF>): Bitmap {
        require(landmarks.size == 5) { "Five landmarks are required for SFace alignment" }
        val transform = solveSimilarity(landmarks, FaceDetectionConfig.alignmentTemplatePoints)
        val output = Bitmap.createBitmap(SFACE_SIZE, SFACE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, transform, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return output
    }

    @Synchronized
    fun embeddingFromAligned(alignedFace: Bitmap): FloatArray {
        check(!closed) { "YuNet/SFace engine is closed" }

        Log.d("YuNetSFaceEngine", "SFace Config: inputSize=$SFACE_SIZE, embeddingDimensions=$SFACE_DIMENSIONS, cosineThreshold=${FaceDetectionConfig.cosineThreshold}")

        val resized = if (alignedFace.width == SFACE_SIZE && alignedFace.height == SFACE_SIZE) {
            alignedFace
        } else {
            Bitmap.createScaledBitmap(alignedFace, SFACE_SIZE, SFACE_SIZE, true)
        }
        try {
            // This mirrors OpenCV FaceRecognizerSF: blobFromImage(..., scale=1,
            // mean=0, swapRB=true). Normalization is part of the SFace ONNX graph.
            val input = bitmapToNchw(resized, rgbOrder = true)
            val raw: FloatArray
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, SFACE_SIZE.toLong(), SFACE_SIZE.toLong())
            ).use { tensor ->
                recognizer.run(mapOf(recognizerInput to tensor)).use { output ->
                    raw = flatten(output[0].value)
                }
            }
            return l2Normalize(raw)
        } finally {
            if (resized !== alignedFace) resized.recycle()
        }
    }

    fun embedding(bitmap: Bitmap, face: YuNetFace): FloatArray {
        val aligned = align(bitmap, face.landmarks)
        return try {
            embeddingFromAligned(aligned)
        } finally {
            aligned.recycle()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        detector.close()
        recognizer.close()
        options.close()
    }

    private fun bitmapToNchw(bitmap: Bitmap, rgbOrder: Boolean): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val plane = width * height
        val output = FloatArray(plane * 3)
        pixels.forEachIndexed { index, pixel ->
            val red = Color.red(pixel).toFloat()
            val green = Color.green(pixel).toFloat()
            val blue = Color.blue(pixel).toFloat()
            output[index] = if (rgbOrder) red else blue
            output[plane + index] = green
            output[plane * 2 + index] = if (rgbOrder) blue else red
        }
        return output
    }

    private fun outputFloats(result: OrtSession.Result, name: String): FloatArray {
        val value = result.get(name).orElseThrow { IllegalStateException("YuNet output '$name' missing") }
        return flatten(value.value)
    }

    private fun flatten(value: Any?): FloatArray {
        val values = ArrayList<Float>()
        fun visit(item: Any?) {
            when (item) {
                is Float -> values += item
                is Number -> values += item.toFloat()
                is FloatArray -> item.forEach { values += it }
                is Array<*> -> item.forEach(::visit)
                else -> if (item != null && item.javaClass.isArray) {
                    for (index in 0 until java.lang.reflect.Array.getLength(item)) {
                        visit(java.lang.reflect.Array.get(item, index))
                    }
                }
            }
        }
        visit(value)
        return values.toFloatArray()
    }

    private fun nonMaximumSuppression(faces: List<YuNetFace>): List<YuNetFace> {
        val sorted = faces.sortedByDescending { it.confidence }.toMutableList()
        val kept = mutableListOf<YuNetFace>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept += best
            sorted.removeAll { intersectionOverUnion(best.bounds, it.bounds) > FaceDetectionConfig.nmsThreshold }
        }
        return kept
    }

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun solveSimilarity(source: List<PointF>, target: List<PointF>): Matrix {
        // Least-squares similarity fit:
        // u = a*x - b*y + tx, v = b*x + a*y + ty.
        // Unlike a free affine transform this cannot shear or distort the face.
        val normal = Array(4) { DoubleArray(4) }
        val rhs = DoubleArray(4)
        source.indices.forEach { i ->
            val x = source[i].x.toDouble()
            val y = source[i].y.toDouble()
            val u = target[i].x.toDouble()
            val v = target[i].y.toDouble()
            val rows = arrayOf(
                doubleArrayOf(x, -y, 1.0, 0.0) to u,
                doubleArrayOf(y, x, 0.0, 1.0) to v
            )
            rows.forEach { (row, expected) ->
                for (r in 0..3) {
                    rhs[r] += row[r] * expected
                    for (c in 0..3) normal[r][c] += row[r] * row[c]
                }
            }
        }
        val solved = gaussianElimination(normal, rhs)
        return Matrix().apply {
            setValues(
                floatArrayOf(
                    solved[0].toFloat(), -solved[1].toFloat(), solved[2].toFloat(),
                    solved[1].toFloat(), solved[0].toFloat(), solved[3].toFloat(),
                    0f, 0f, 1f
                )
            )
        }
    }

    private fun gaussianElimination(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray {
        val size = vector.size
        for (pivot in 0 until size) {
            var best = pivot
            for (row in pivot + 1 until size) {
                if (abs(matrix[row][pivot]) > abs(matrix[best][pivot])) best = row
            }
            val rowSwap = matrix[pivot]
            matrix[pivot] = matrix[best]
            matrix[best] = rowSwap
            val valueSwap = vector[pivot]
            vector[pivot] = vector[best]
            vector[best] = valueSwap
            val divisor = matrix[pivot][pivot]
            require(abs(divisor) > 1e-10) { "Degenerate face landmarks" }
            for (column in pivot until size) matrix[pivot][column] /= divisor
            vector[pivot] /= divisor
            for (row in 0 until size) {
                if (row == pivot) continue
                val factor = matrix[row][pivot]
                for (column in pivot until size) matrix[row][column] -= factor * matrix[pivot][column]
                vector[row] -= factor * vector[pivot]
            }
        }
        return vector
    }

    private fun laplacianVariance(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val gray = FloatArray(width * height)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        pixels.forEachIndexed { index, color ->
            gray[index] = 0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)
        }
        var sum = 0.0
        var squared = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val laplacian = 4f * gray[index] - gray[index - 1] - gray[index + 1] -
                    gray[index - width] - gray[index + width]
                sum += laplacian
                squared += laplacian * laplacian
                count++
            }
        }
        if (count == 0) return 0f
        val mean = sum / count
        return (squared / count - mean * mean).toFloat()
    }

    companion object {
        // ── Compile-time fallback defaults (kept for reference / const usage) ──
        const val DETECTOR_ASSET = "models/face_detection_yunet_2023mar.onnx"
        const val RECOGNIZER_ASSET = "models/face_recognition_sface_2021dec_int8.onnx"
        const val SFACE_DIMENSIONS = 128
        /** Hardcoded default; runtime value is [FaceDetectionConfig.cosineThreshold]. */
        const val COSINE_THRESHOLD = 0.42f
        private const val DEFAULT_DETECTOR_SIZE = 640
        private const val SFACE_SIZE = 112
        private const val DETECTION_THRESHOLD = 0.85f
        private const val NMS_THRESHOLD = 0.3f
        private val STRIDES = intArrayOf(8, 16, 32)
        private val SFACE_TEMPLATE = listOf(
            PointF(38.2946f, 51.6963f),
            PointF(73.5318f, 51.5014f),
            PointF(56.0252f, 71.7366f),
            PointF(41.5493f, 92.3655f),
            PointF(70.7299f, 92.2041f)
        )

        fun cosineSimilarity(first: FloatArray, second: FloatArray): Float {
            if (first.size != second.size || first.isEmpty()) return -1f
            var dot = 0f
            var firstNorm = 0f
            var secondNorm = 0f
            for (index in first.indices) {
                dot += first[index] * second[index]
                firstNorm += first[index] * first[index]
                secondNorm += second[index] * second[index]
            }
            val denominator = sqrt(firstNorm) * sqrt(secondNorm)
            return if (denominator <= 1e-8f) -1f else dot / denominator
        }

        fun l2Normalize(values: FloatArray): FloatArray {
            val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
            if (norm <= 1e-8f) return values
            return FloatArray(values.size) { values[it] / norm }
        }

        private fun distance(a: PointF, b: PointF): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            return sqrt(dx * dx + dy * dy)
        }
    }
}
