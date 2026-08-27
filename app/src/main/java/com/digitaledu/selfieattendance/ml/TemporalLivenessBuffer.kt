package com.digitaledu.selfieattendance.ml

import android.util.Log

/**
 * Sliding-window multi-frame liveness score aggregator.
 *
 * Instead of making a pass/fail decision on a single camera frame,
 * this buffer collects [windowSize] consecutive anti-spoofing scores
 * and applies a configurable aggregation strategy to produce a robust
 * liveness verdict.
 *
 * ## Why Multi-Frame?
 * A single frame can produce an outlier score due to motion blur,
 * lighting changes, or partial occlusion. By requiring multiple
 * consistent scores, replay attacks that might fool one frame are
 * reliably rejected over a window.
 *
 * ## Supported Strategies
 * - **PASS_COUNT** — count how many frames individually pass the threshold.
 *   If ≥ [requiredPassCount], the overall result is LIVE.
 * - **MEDIAN** — take the median score across the window and compare to threshold.
 * - **AVERAGE** — arithmetic mean of all scores compared to threshold.
 * - **MIN_SCORE** — most conservative: the lowest score in the window must pass.
 *
 * ## Thread Safety
 * All public methods are `@Synchronized`.
 *
 * ## Configuration
 * All parameters are read from [AntiSpoofConfig] at call-time.
 */
class TemporalLivenessBuffer {

    private val tag = "TemporalLivenessBuffer"

    /** Circular buffer of per-frame liveness scores. */
    private val scores = ArrayDeque<Float>()

    /** Number of frames that must pass for PASS_COUNT strategy. */
    private val requiredPassCount: Int
        get() {
            val windowSize = AntiSpoofConfig.temporalWindowSize
            return (windowSize * AntiSpoofConfig.requiredPassPercentage).toInt()
                .coerceAtLeast(1)
                .coerceAtMost(windowSize)
        }

    // ─────────────────── Public API ──────────────────────────────────

    /**
     * Result of the temporal liveness aggregation.
     *
     * @param passed      Whether the aggregated score meets the liveness criteria.
     * @param frameCount  Number of frames currently in the buffer.
     * @param windowSize  Target window size from config.
     * @param score       The aggregated score (meaning depends on strategy).
     * @param guidance    Human-readable UI message.
     */
    data class TemporalResult(
        val passed: Boolean,
        val frameCount: Int,
        val windowSize: Int,
        val score: Float,
        val guidance: String
    )

    /**
     * Add a new per-frame anti-spoofing score to the buffer.
     *
     * If the buffer exceeds [AntiSpoofConfig.temporalWindowSize],
     * the oldest score is evicted (sliding window).
     */
    @Synchronized
    fun addScore(score: Float) {
        val windowSize = AntiSpoofConfig.temporalWindowSize
        scores.addLast(score)
        while (scores.size > windowSize) {
            scores.removeFirst()
        }

        if (AntiSpoofConfig.debugLogging) {
            Log.d(tag, "Score added: ${"%.4f".format(score)} | Buffer: ${scores.size}/$windowSize | Scores: ${scores.map { "%.3f".format(it) }}")
        }
    }

    /**
     * Evaluate the temporal buffer and return a [TemporalResult].
     *
     * The buffer must be fully populated ([frameCount] == [windowSize])
     * before it can pass. This prevents premature decisions.
     *
     * @param threshold  Liveness threshold to compare scores against.
     *                   Defaults to [AntiSpoofConfig.livenessThreshold].
     */
    @Synchronized
    fun evaluate(
        threshold: Float = AntiSpoofConfig.livenessThreshold
    ): TemporalResult {
        val windowSize = AntiSpoofConfig.temporalWindowSize
        val frameCount = scores.size

        // Not enough frames yet — always fail
        if (frameCount < windowSize) {
            return TemporalResult(
                passed = false,
                frameCount = frameCount,
                windowSize = windowSize,
                score = if (scores.isEmpty()) 0f else scores.last(),
                guidance = "Verifying liveness... ($frameCount/$windowSize)"
            )
        }

        val scoreList = scores.toList()
        val aggregatedScore: Float
        val passed: Boolean

        when (AntiSpoofConfig.aggregationStrategy.uppercase()) {
            "MEDIAN" -> {
                val sorted = scoreList.sorted()
                aggregatedScore = if (sorted.size % 2 == 0) {
                    (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
                } else {
                    sorted[sorted.size / 2]
                }
                passed = aggregatedScore >= threshold
            }

            "AVERAGE" -> {
                aggregatedScore = scoreList.average().toFloat()
                passed = aggregatedScore >= threshold
            }

            "MIN_SCORE" -> {
                aggregatedScore = scoreList.min()
                passed = aggregatedScore >= threshold
            }

            else -> {
                // Default: PASS_COUNT
                val passCount = scoreList.count { it >= threshold }
                aggregatedScore = passCount.toFloat() / windowSize.toFloat()
                passed = passCount >= requiredPassCount
            }
        }

        val guidance = when {
            passed -> "Live face verified"
            aggregatedScore < 0.3f -> "Spoof detected — use a real face"
            aggregatedScore < threshold -> "Hold still — verifying liveness..."
            else -> "Verifying liveness..."
        }

        if (AntiSpoofConfig.debugLogging) {
            Log.d(
                tag,
                "Temporal result: passed=$passed, strategy=${AntiSpoofConfig.aggregationStrategy}, " +
                "aggregatedScore=${"%.4f".format(aggregatedScore)}, threshold=$threshold, " +
                "frameCount=$frameCount/$windowSize"
            )
        }

        return TemporalResult(
            passed = passed,
            frameCount = frameCount,
            windowSize = windowSize,
            score = aggregatedScore,
            guidance = guidance
        )
    }

    /**
     * Clear all buffered scores. Call this when:
     * - Face is lost
     * - Scanner resets after a successful match
     * - User identity changes
     */
    @Synchronized
    fun reset() {
        scores.clear()
        if (AntiSpoofConfig.debugLogging) {
            Log.d(tag, "Temporal buffer reset")
        }
    }

    /** Number of scores currently in the buffer. */
    @Synchronized
    fun size(): Int = scores.size
}
