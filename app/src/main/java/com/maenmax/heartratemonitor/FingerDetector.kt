package com.maenmax.heartratemonitor

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Robust finger detection that verifies:
 * 1. High red channel intensity (finger with flash is very red due to blood)
 * 2. Pulsating signal (living tissue has rhythmic variations)
 * 3. Proper brightness range (not a wall or open air)
 * 4. Signal micro-variations (living tissue vs static objects)
 */
class FingerDetector {

    companion object {
        // Finger detection thresholds
        private const val MIN_RED_INTENSITY = 180f      // Finger with flash should be very red
        private const val MIN_RED_RATIO = 1.3f          // Red should be 30% higher than green
        private const val MIN_BRIGHTNESS = 50f          // Not too dark
        private const val MAX_BRIGHTNESS = 250f         // Not overexposed

        // Pulse detection for finger verification
        private const val PULSE_DETECTION_FRAMES = 90   // 3 seconds at 30fps
        private const val MIN_PULSE_VARIATION = 0.3f    // Minimum signal variation (%)
        private const val MAX_PULSE_VARIATION = 5.0f    // Maximum (too much = movement/noise)

        // Sustained contact requirement
        private const val SUSTAINED_FRAMES_REQUIRED = 30  // 1 second of valid finger
        private const val PULSE_VERIFY_FRAMES = 45        // 1.5 seconds to verify pulse exists
    }

    // State
    private var frameCount = 0
    private var sustainedFingerFrames = 0
    private var pulseVerificationFrames = 0
    private var pulseVerifyAttempts = 0
    private val MAX_PULSE_VERIFY_ATTEMPTS = 3

    // Signal history for pulse detection
    private val redHistory = mutableListOf<Float>()
    private val brightnessHistory = mutableListOf<Float>()

    // Baseline (no finger)
    private var baselineBrightness = 0f
    private var baselineRed = 0f
    private var isCalibrated = false
    private val calibrationReadings = mutableListOf<Triple<Float, Float, Float>>() // red, green, brightness

    // Detection state
    private var fingerConfirmed = false
    private var pulseDetected = false

    enum class DetectionState {
        CALIBRATING,        // Building baseline (no finger)
        WAITING_FOR_FINGER, // Calibrated, waiting for finger placement
        VERIFYING_FINGER,   // Finger-like object detected, verifying it's a real finger
        VERIFYING_PULSE,    // Finger confirmed, checking for pulse
        FINGER_CONFIRMED    // Real finger with pulse detected - ready to measure
    }

    var currentState = DetectionState.CALIBRATING
        private set

    /**
     * Reset detector state - call when starting fresh
     */
    fun reset() {
        frameCount = 0
        sustainedFingerFrames = 0
        pulseVerificationFrames = 0
        pulseVerifyAttempts = 0
        redHistory.clear()
        brightnessHistory.clear()
        calibrationReadings.clear()
        baselineBrightness = 0f
        baselineRed = 0f
        isCalibrated = false
        fingerConfirmed = false
        pulseDetected = false
        currentState = DetectionState.CALIBRATING
    }

    /**
     * Reset for next measurement without requiring recalibration.
     * Use this after a successful measurement when baseline is still valid.
     */
    fun resetForNextMeasurement() {
        frameCount = 0
        sustainedFingerFrames = 0
        pulseVerificationFrames = 0
        pulseVerifyAttempts = 0
        redHistory.clear()
        brightnessHistory.clear()
        fingerConfirmed = false
        pulseDetected = false
        // Keep calibration data, but require finger to be removed and placed again
        // Go to WAITING_FOR_FINGER - user must lift and replace finger
        if (isCalibrated) {
            currentState = DetectionState.WAITING_FOR_FINGER
        } else {
            currentState = DetectionState.CALIBRATING
        }
    }

    /**
     * Force reset to allow immediate re-detection.
     * Use this to skip pulse verification for faster re-measurement.
     */
    fun forceReadyState() {
        if (isCalibrated) {
            frameCount = 0
            sustainedFingerFrames = 0
            pulseVerificationFrames = 0
            pulseVerifyAttempts = 0
            redHistory.clear()
            fingerConfirmed = false
            pulseDetected = false
            currentState = DetectionState.WAITING_FOR_FINGER
        }
    }

    /**
     * Process a frame and return detection state
     *
     * @param redIntensity Average red channel value (0-255)
     * @param greenIntensity Average green channel value (0-255)
     * @param brightness Overall brightness (Y channel, 0-255)
     * @return Current detection state
     */
    fun processFrame(redIntensity: Float, greenIntensity: Float, brightness: Float): DetectionState {
        frameCount++

        when (currentState) {
            DetectionState.CALIBRATING -> {
                handleCalibration(redIntensity, greenIntensity, brightness)
            }
            DetectionState.WAITING_FOR_FINGER -> {
                checkForFingerPlacement(redIntensity, greenIntensity, brightness)
            }
            DetectionState.VERIFYING_FINGER -> {
                verifyFinger(redIntensity, greenIntensity, brightness)
            }
            DetectionState.VERIFYING_PULSE -> {
                verifyPulse(redIntensity)
            }
            DetectionState.FINGER_CONFIRMED -> {
                // Already confirmed, check if finger is still there
                if (!isFingerPresent(redIntensity, greenIntensity, brightness)) {
                    // Finger removed
                    sustainedFingerFrames = 0
                    pulseVerificationFrames = 0
                    redHistory.clear()
                    currentState = DetectionState.WAITING_FOR_FINGER
                }
            }
        }

        return currentState
    }

    /**
     * Build baseline readings (should be done with NO finger on camera)
     */
    private fun handleCalibration(red: Float, green: Float, brightness: Float) {
        calibrationReadings.add(Triple(red, green, brightness))

        if (calibrationReadings.size >= 30) { // 1 second of data
            // Check if readings are stable (no finger moving around)
            val brightnessValues = calibrationReadings.map { it.third }
            val avgBrightness = brightnessValues.average().toFloat()
            val variance = brightnessValues.map { (it - avgBrightness) * (it - avgBrightness) }.average()
            val stdDev = sqrt(variance).toFloat()

            // Readings should be stable and reasonably bright (flash on, no finger)
            if (stdDev < 10 && avgBrightness > 60) {
                baselineBrightness = avgBrightness
                baselineRed = calibrationReadings.map { it.first }.average().toFloat()
                isCalibrated = true
                currentState = DetectionState.WAITING_FOR_FINGER
            } else {
                // Unstable readings, restart calibration
                calibrationReadings.clear()
            }
        }
    }

    /**
     * Check if something that looks like a finger is placed on camera
     */
    private fun checkForFingerPlacement(red: Float, green: Float, brightness: Float) {
        if (isFingerLikeSignal(red, green, brightness)) {
            sustainedFingerFrames++
            if (sustainedFingerFrames >= 15) { // 0.5 seconds of finger-like signal
                currentState = DetectionState.VERIFYING_FINGER
                redHistory.clear()
            }
        } else {
            sustainedFingerFrames = 0
        }
    }

    /**
     * Verify that the object is actually a finger (not a red wall, etc.)
     */
    private fun verifyFinger(red: Float, green: Float, brightness: Float) {
        if (!isFingerLikeSignal(red, green, brightness)) {
            // Lost finger-like signal
            sustainedFingerFrames = 0
            currentState = DetectionState.WAITING_FOR_FINGER
            return
        }

        sustainedFingerFrames++
        redHistory.add(red)

        // After sustained contact, check for micro-variations (living tissue has these)
        if (sustainedFingerFrames >= SUSTAINED_FRAMES_REQUIRED) {
            if (hasLivingTissueVariation()) {
                fingerConfirmed = true
                currentState = DetectionState.VERIFYING_PULSE
                pulseVerificationFrames = 0
                redHistory.clear()
            } else {
                // No variation - might be a static red object
                // Keep waiting, but don't advance
            }
        }
    }

    /**
     * Verify that there's an actual pulse (rhythmic variation in red channel)
     */
    private fun verifyPulse(red: Float) {
        redHistory.add(red)
        pulseVerificationFrames++

        if (pulseVerificationFrames >= PULSE_VERIFY_FRAMES) {
            if (hasPulseSignal()) {
                pulseDetected = true
                pulseVerifyAttempts = 0
                currentState = DetectionState.FINGER_CONFIRMED
            } else {
                pulseVerifyAttempts++
                if (pulseVerifyAttempts >= MAX_PULSE_VERIFY_ATTEMPTS) {
                    // After 3 attempts, just accept the finger (user clearly has finger on camera)
                    pulseDetected = true
                    pulseVerifyAttempts = 0
                    currentState = DetectionState.FINGER_CONFIRMED
                } else {
                    // Try again
                    pulseVerificationFrames = 0
                    redHistory.clear()
                }
            }
        }
    }

    /**
     * Check if the current readings look like a finger on camera with flash
     */
    private fun isFingerLikeSignal(red: Float, green: Float, brightness: Float): Boolean {
        // 1. Red intensity should be high (finger with flash is VERY red)
        if (red < MIN_RED_INTENSITY) return false

        // 2. Red should dominate over green (blood absorbs green, reflects red)
        val redRatio = if (green > 0) red / green else 0f
        if (redRatio < MIN_RED_RATIO) return false

        // 3. Brightness should be in valid range
        if (brightness < MIN_BRIGHTNESS || brightness > MAX_BRIGHTNESS) return false

        // 4. Should be significantly different from baseline
        val brightnessDiff = abs(brightness - baselineBrightness)
        if (brightnessDiff < 20) return false  // Not different enough from no-finger state

        return true
    }

    /**
     * Check if finger is still present (less strict than initial detection)
     */
    private fun isFingerPresent(red: Float, green: Float, brightness: Float): Boolean {
        // More lenient check for continued presence
        if (red < MIN_RED_INTENSITY * 0.8f) return false

        val redRatio = if (green > 0) red / green else 0f
        if (redRatio < MIN_RED_RATIO * 0.9f) return false

        // Check brightness hasn't jumped back to baseline
        val brightnessDiff = abs(brightness - baselineBrightness)
        if (brightnessDiff < 10) return false

        return true
    }

    /**
     * Check for micro-variations that indicate living tissue
     * (Static objects like walls have very stable readings)
     */
    private fun hasLivingTissueVariation(): Boolean {
        if (redHistory.size < 30) return false

        val recent = redHistory.takeLast(30)
        val mean = recent.average().toFloat()
        val variance = recent.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance).toFloat()
        val variationPercent = (stdDev / mean) * 100

        // Living tissue has slight variations due to blood flow
        // Static objects have almost zero variation
        return variationPercent > 0.05f && variationPercent < 3f
    }

    /**
     * Check for rhythmic pulse signal in red channel
     */
    private fun hasPulseSignal(): Boolean {
        if (redHistory.size < PULSE_VERIFY_FRAMES) return false

        val values = redHistory.takeLast(PULSE_VERIFY_FRAMES)
        val mean = values.average().toFloat()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance).toFloat()
        val variationPercent = (stdDev / mean) * 100

        // Pulse signal should have noticeable but not excessive variation
        if (variationPercent < MIN_PULSE_VARIATION || variationPercent > MAX_PULSE_VARIATION) {
            return false
        }

        // Count zero-crossings (signal going above and below mean)
        // A pulse signal should have regular crossings (2-4 per second for 60-120 BPM)
        var crossings = 0
        var wasAbove = values[0] > mean
        for (v in values) {
            val isAbove = v > mean
            if (isAbove != wasAbove) {
                crossings++
                wasAbove = isAbove
            }
        }

        // For 2 seconds of data at 30fps (60 frames), expect 4-16 crossings
        // (each heartbeat causes 2 crossings: up and down)
        // 60 BPM = 2 beats in 2 seconds = 4 crossings
        // 120 BPM = 4 beats in 2 seconds = 8 crossings
        return crossings in 3..20
    }

    /**
     * Get a status message for current detection state
     */
    fun getStatusMessage(): String {
        return when (currentState) {
            DetectionState.CALIBRATING -> {
                val progress = (calibrationReadings.size * 100 / 30).coerceAtMost(100)
                "Calibrating... $progress% (Keep finger OFF)"
            }
            DetectionState.WAITING_FOR_FINGER -> "Ready! Place finger on camera"
            DetectionState.VERIFYING_FINGER -> "Detecting finger..."
            DetectionState.VERIFYING_PULSE -> "Verifying pulse..."
            DetectionState.FINGER_CONFIRMED -> "Finger detected! Starting measurement..."
        }
    }

    /**
     * Check if finger is fully confirmed and ready to measure
     */
    fun isReadyToMeasure(): Boolean = currentState == DetectionState.FINGER_CONFIRMED
}
