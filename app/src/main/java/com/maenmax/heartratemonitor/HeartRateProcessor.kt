package com.maenmax.heartratemonitor

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Robust heart rate processor with stabilization, outlier rejection,
 * and true beat detection for synchronized animation.
 */
class HeartRateProcessor {

    // Configuration
    companion object {
        private const val MIN_BPM = 45
        private const val MAX_BPM = 180
        private const val MAX_BPM_CHANGE_PER_SECOND = 15  // Max 15 BPM change per second
        private const val BPM_STABILITY_WINDOW = 5  // Need 5 consistent readings
        private const val MIN_PEAKS_FOR_CALCULATION = 4
        private const val OUTLIER_THRESHOLD_PERCENT = 0.25f  // 25% deviation = outlier
    }

    // State
    private var stableBPM: Int = 0
    private var lastBPMUpdateTime: Long = 0
    private val recentBPMReadings = mutableListOf<Int>()
    private var lastPeakTime: Long = 0
    private var measuredFPS: Float = 30f

    // Beat detection state - PEAK-based detection for sync with visible blood volume changes
    private var beatDetectedFlag = false
    private val recentSamples = mutableListOf<Float>()  // Rolling window of recent samples
    private val SAMPLE_WINDOW = 15  // ~500ms at 30fps - enough to see a full beat
    private var lastBeatTimestamp = 0L
    private var runningMin = Float.MAX_VALUE
    private var runningMax = Float.MIN_VALUE

    // Callback for when a real beat is detected
    var onBeatDetected: (() -> Unit)? = null

    /**
     * Reset all state - call when starting a new measurement
     */
    fun reset() {
        stableBPM = 0
        lastBPMUpdateTime = 0
        recentBPMReadings.clear()
        lastPeakTime = 0
        beatDetectedFlag = false
        recentSamples.clear()
        lastBeatTimestamp = 0L
        runningMin = Float.MAX_VALUE
        runningMax = Float.MIN_VALUE
    }

    /**
     * Set the measured frames per second for accurate timing
     */
    fun setFPS(fps: Float) {
        measuredFPS = fps.coerceIn(15f, 60f)
    }

    /**
     * Process a new signal sample and detect beats in real-time.
     * Uses PEAK DETECTION to sync with the actual visible blood volume change.
     *
     * The beat is triggered at the PEAK of the red intensity wave - this is
     * exactly when you see the color change (blood volume maximum).
     *
     * @param signalValue The current red channel intensity
     * @param timestamp Current time in milliseconds
     * @return true if a beat was detected on this sample
     */
    fun processSample(signalValue: Float, timestamp: Long): Boolean {
        // Add to rolling window
        recentSamples.add(signalValue)
        if (recentSamples.size > SAMPLE_WINDOW) {
            recentSamples.removeAt(0)
        }

        // Need enough samples to detect a peak
        if (recentSamples.size < SAMPLE_WINDOW) return false

        // Update running min/max for signal range
        if (signalValue > runningMax) runningMax = signalValue
        if (signalValue < runningMin) runningMin = signalValue

        // Slowly adapt the range
        val range = runningMax - runningMin
        if (range > 0.5f) {
            runningMax -= range * 0.001f
            runningMin += range * 0.001f
        }

        // Need minimum signal variation
        if (range < 0.8f) return false

        // Check if the sample in the MIDDLE of our window is a LOCAL MAXIMUM
        // This means we're detecting the peak with a small delay, but it's accurate
        val midIndex = SAMPLE_WINDOW / 2
        val midSample = recentSamples[midIndex]

        // The middle sample must be higher than all surrounding samples (true peak)
        var isPeak = true
        for (i in 0 until SAMPLE_WINDOW) {
            if (i != midIndex && recentSamples[i] >= midSample) {
                isPeak = false
                break
            }
        }

        if (!isPeak) return false

        // Peak must be in upper 40% of signal range (not noise at the bottom)
        val threshold = runningMin + range * 0.6f
        if (midSample < threshold) return false

        // Timing check - minimum 450ms between beats (max ~133 BPM)
        val timeSinceLastBeat = timestamp - lastBeatTimestamp
        if (timeSinceLastBeat < 450) return false

        // Valid beat detected at peak!
        lastBeatTimestamp = timestamp
        lastPeakTime = timestamp
        beatDetectedFlag = true
        return true
    }

    /**
     * Check if a beat was detected and consume the flag.
     * Call this from UI thread to ensure synchronized animation/sound.
     *
     * @return true if a beat was detected since last check
     */
    @Synchronized
    fun checkAndConsumeBeat(): Boolean {
        if (beatDetectedFlag) {
            beatDetectedFlag = false
            return true
        }
        return false
    }

    /**
     * Calculate heart rate from collected signal data with full stabilization.
     *
     * @param redValues List of red channel values
     * @return Stabilized BPM or null if not reliable
     */
    fun calculateStableBPM(redValues: List<Float>): Int? {
        if (redValues.size < 90) return stableBPM.takeIf { it > 0 }

        // Apply signal processing
        val smoothed = smoothSignal(redValues)
        val peaks = detectPeaks(smoothed)

        if (peaks.size < MIN_PEAKS_FOR_CALCULATION) {
            return stableBPM.takeIf { it > 0 }
        }

        // Calculate intervals between peaks
        val intervals = mutableListOf<Float>()
        for (i in 1 until peaks.size) {
            val interval = (peaks[i] - peaks[i - 1]).toFloat()
            // Filter physiologically impossible intervals
            val bpmFromInterval = 60f * measuredFPS / interval
            if (bpmFromInterval in MIN_BPM.toFloat()..MAX_BPM.toFloat()) {
                intervals.add(interval)
            }
        }

        if (intervals.size < 3) {
            return stableBPM.takeIf { it > 0 }
        }

        // Remove outliers using median-based filtering
        val filteredIntervals = removeOutliers(intervals)
        if (filteredIntervals.isEmpty()) {
            return stableBPM.takeIf { it > 0 }
        }

        // Calculate BPM from median interval
        val medianInterval = median(filteredIntervals)
        val rawBPM = (60f * measuredFPS / medianInterval).toInt()

        // Validate and stabilize
        return stabilizeBPM(rawBPM)
    }

    /**
     * Stabilize BPM reading with rate limiting and consistency checking
     */
    private fun stabilizeBPM(rawBPM: Int): Int? {
        val currentTime = System.currentTimeMillis()

        // First reading - accept if in valid range
        if (stableBPM == 0) {
            if (rawBPM in MIN_BPM..MAX_BPM) {
                recentBPMReadings.add(rawBPM)
                if (recentBPMReadings.size >= 3) {
                    // Check if readings are consistent
                    val avg = recentBPMReadings.average().toInt()
                    val maxDev = recentBPMReadings.maxOf { abs(it - avg) }
                    if (maxDev <= 10) {
                        stableBPM = avg
                        lastBPMUpdateTime = currentTime
                        return stableBPM
                    }
                }
            }
            return null
        }

        // Check if new reading is physiologically plausible
        val timeDelta = (currentTime - lastBPMUpdateTime) / 1000f
        val maxAllowedChange = (MAX_BPM_CHANGE_PER_SECOND * timeDelta).toInt().coerceAtLeast(5)
        val bpmDifference = abs(rawBPM - stableBPM)

        // If change is too large, it's likely noise - ignore it
        if (bpmDifference > maxAllowedChange * 2) {
            return stableBPM
        }

        // Add to recent readings
        recentBPMReadings.add(rawBPM)
        if (recentBPMReadings.size > BPM_STABILITY_WINDOW) {
            recentBPMReadings.removeAt(0)
        }

        // Only update if we have consistent readings
        if (recentBPMReadings.size >= 3) {
            val avg = recentBPMReadings.average().toInt()
            val maxDev = recentBPMReadings.maxOf { abs(it - avg) }

            // Readings are consistent - update stable BPM with rate limiting
            if (maxDev <= 8) {
                val targetBPM = avg
                val allowedChange = maxAllowedChange.coerceAtMost(bpmDifference)

                stableBPM = when {
                    targetBPM > stableBPM -> (stableBPM + allowedChange).coerceAtMost(targetBPM)
                    targetBPM < stableBPM -> (stableBPM - allowedChange).coerceAtLeast(targetBPM)
                    else -> stableBPM
                }

                lastBPMUpdateTime = currentTime
            }
        }

        return stableBPM.takeIf { it > 0 }
    }

    /**
     * Get the current stable BPM (may be 0 if not yet established)
     */
    fun getStableBPM(): Int = stableBPM

    /**
     * Check if we have a reliable BPM reading
     */
    fun hasReliableBPM(): Boolean = stableBPM in MIN_BPM..MAX_BPM && recentBPMReadings.size >= 3

    // Signal processing functions

    private fun smoothSignal(signal: List<Float>): List<Float> {
        // Remove DC component and trend
        val detrended = removeTrend(signal)
        // Apply low-pass filter
        return movingAverage(detrended, 5)
    }

    private fun removeTrend(signal: List<Float>): List<Float> {
        val windowSize = (measuredFPS * 2).toInt().coerceAtLeast(30)
        val trend = movingAverage(signal, windowSize)
        return signal.mapIndexed { i, v -> v - trend.getOrElse(i) { trend.lastOrNull() ?: 0f } }
    }

    private fun movingAverage(signal: List<Float>, windowSize: Int): List<Float> {
        val result = mutableListOf<Float>()
        val halfWindow = windowSize / 2

        for (i in signal.indices) {
            var sum = 0f
            var count = 0
            for (j in -halfWindow..halfWindow) {
                val idx = i + j
                if (idx in signal.indices) {
                    sum += signal[idx]
                    count++
                }
            }
            result.add(sum / count)
        }
        return result
    }

    private fun detectPeaks(values: List<Float>): List<Int> {
        if (values.size < 10) return emptyList()

        val mean = values.average().toFloat()
        val normalized = values.map { it - mean }
        val stdDev = sqrt(normalized.map { it * it }.average()).toFloat()

        val peaks = mutableListOf<Int>()
        val minDistance = (measuredFPS * 0.33f).toInt().coerceAtLeast(10)  // Min 333ms between peaks
        val threshold = stdDev * 0.4f

        for (i in 4 until normalized.size - 4) {
            val current = normalized[i]
            if (current <= threshold) continue

            // Check if local maximum
            val isMax = (i-3..i+3).all { j ->
                j == i || normalized.getOrElse(j) { Float.MAX_VALUE } < current
            }

            if (isMax) {
                if (peaks.isEmpty() || (i - peaks.last()) >= minDistance) {
                    peaks.add(i)
                }
            }
        }

        return peaks
    }

    private fun removeOutliers(intervals: List<Float>): List<Float> {
        if (intervals.size < 4) return intervals

        val med = median(intervals)
        return intervals.filter {
            abs(it - med) / med <= OUTLIER_THRESHOLD_PERCENT
        }
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        } else {
            sorted[sorted.size / 2]
        }
    }
}
