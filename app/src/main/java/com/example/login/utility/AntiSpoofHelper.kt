package com.example.login.utility

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class AntiSpoofHelper(context: Context) {

    private val interpreter: Interpreter

    // Your model expects 256x256
    private val INPUT_SIZE = 256

    // Based on your logs: class index 4 becomes higher for replay/video
    private val SPOOF_INDEX = 4

    init {
        val model = loadModelFile(context.assets, "antispoof.tflite")
        interpreter = Interpreter(model)

        val inTensor = interpreter.getInputTensor(0)
        val outTensor = interpreter.getOutputTensor(0)

        Log.d("PAD", "INPUT shape=${inTensor.shape().contentToString()} type=${inTensor.dataType()}")
        Log.d("PAD", "OUTPUT shape=${outTensor.shape().contentToString()} type=${outTensor.dataType()}")

        // If model was dynamic (not in your case), we'd resize. Safe to keep:
        val inShape = inTensor.shape()
        if (inShape.any { it <= 0 }) {
            interpreter.resizeInput(0, intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3))
        }

        interpreter.allocateTensors()
    }

    private fun loadModelFile(assetManager: AssetManager, filename: String): MappedByteBuffer {
        val fileDescriptor = assetManager.openFd(filename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    data class SpoofResult(
        val probs: FloatArray,
        val spoofScore: Float,
        val liveScore: Float,
        val maxIdx: Int,
        val maxVal: Float
    )

    /**
     * Returns:
     * - probs[0..7] (model output)
     * - spoofScore = probs[SPOOF_INDEX]
     * - liveScore  = 1 - spoofScore
     *
     * IMPORTANT:
     * Your output already sums to ~1.0 in logs, so we DO NOT softmax again.
     */
    fun analyze(faceBitmap: Bitmap): SpoofResult {
        val input = preprocessNHWC(faceBitmap, INPUT_SIZE, INPUT_SIZE)

        val output = Array(1) { FloatArray(8) } // model output [1,8]
        interpreter.run(input, output)

        val raw = output[0]

        // Decide whether raw looks like probabilities or logits
        val sum = raw.sum()
        val needsSoftmax =
            raw.any { it < 0f || it > 1.0f } || kotlin.math.abs(sum - 1f) > 0.05f

        val probs = if (needsSoftmax) softmax(raw) else raw

        val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: -1
        val maxVal = if (maxIdx >= 0) probs[maxIdx] else 0f

        val spoofScore = probs[SPOOF_INDEX].coerceIn(0f, 1f)
        val liveScore = (1f - spoofScore).coerceIn(0f, 1f)

        Log.d(
            "PAD",
            "needsSoftmax=$needsSoftmax rawSum=${"%.3f".format(sum)} " +
                    "rawMax=${"%.3f".format(raw.maxOrNull() ?: 0f)} " +
                    "probs=${probs.contentToString()} spoof=${"%.3f".format(spoofScore)} " +
                    "maxIdx=$maxIdx maxVal=${"%.3f".format(maxVal)}"
        )

        return SpoofResult(
            probs = probs,
            spoofScore = spoofScore,
            liveScore = liveScore,
            maxIdx = maxIdx,
            maxVal = maxVal
        )
    }


    private fun softmax(x: FloatArray): FloatArray {
        val max = x.maxOrNull() ?: 0f
        val exps = FloatArray(x.size)
        var sum = 0.0

        for (i in x.indices) {
            val e = kotlin.math.exp((x[i] - max).toDouble())
            exps[i] = e.toFloat()
            sum += e
        }

        val out = FloatArray(x.size)
        val denom = sum.toFloat().coerceAtLeast(1e-9f)
        for (i in exps.indices) out[i] = exps[i] / denom
        return out
    }

    /**
     * NHWC float input: [1][H][W][3]
     * Normalization: 0..1 (matches what you used, keep same)
     */
    private fun preprocessNHWC(bitmap: Bitmap, w: Int, h: Int): Array<Array<Array<FloatArray>>> {
        val resized = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val input = Array(1) { Array(h) { Array(w) { FloatArray(3) } } }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = resized.getPixel(x, y)
                input[0][y][x][0] = Color.red(p) / 255f
                input[0][y][x][1] = Color.green(p) / 255f
                input[0][y][x][2] = Color.blue(p) / 255f
            }
        }
        return input
    }
}
