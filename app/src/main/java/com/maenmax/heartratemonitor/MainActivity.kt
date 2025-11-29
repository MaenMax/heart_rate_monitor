package com.maenmax.heartratemonitor

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Outline
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieAnimationView
import com.maenmax.heartratemonitor.data.HeartRateDatabase
import com.maenmax.heartratemonitor.data.HeartRateMeasurement
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds

class MainActivity : AppCompatActivity() {
    
    private lateinit var previewView: PreviewView
    private lateinit var heartRateText: TextView
    private lateinit var statusText: TextView
    private lateinit var heartIcon: TextView
    private lateinit var pulseIndicator: View
    private lateinit var pulseDot: View
    private lateinit var muteButton: TextView
    private lateinit var historyButton: View
    private lateinit var ecgWaveformView: ECGWaveformView
    private lateinit var heartLottie: LottieAnimationView
    private lateinit var loadingAnimation: LottieAnimationView
    private lateinit var adView: AdView

    private lateinit var database: HeartRateDatabase
    private var lastConfidence: String = "Good"

    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var isFlashOn = false
    private var isMeasuring = false
    private var isFingerDetected = false
    
    // Unified heartbeat controller for perfect sync
    private var heartbeatController: HeartbeatController? = null
    private var heartbeatSoundPlayer: HeartbeatSoundPlayer? = null  // For completion sound only
    private var isSoundMuted = false

    // Heart rate processor for stable BPM calculation and beat detection
    private val heartRateProcessor = HeartRateProcessor()

    // Finger detector for robust finger verification
    private val fingerDetector = FingerDetector()

    // Heart rate detection variables
    private val redValues = mutableListOf<Float>()
    private var frameCount = 0
    private val SAMPLE_DURATION_SECONDS = 10
    private var measuredFramesPerSecond = 30f  // Will be calculated from actual timestamps
    private val REQUIRED_FRAMES = SAMPLE_DURATION_SECONDS * 30  // Target frames

    // Frame timing for accurate FPS measurement
    private var firstFrameTimestamp = 0L
    private var lastFrameTimestamp = 0L

    // Track red/green for finger detection
    private var lastRedIntensity = 0f
    private var lastGreenIntensity = 0f

    // Track brightness during measurement to detect finger removal
    private var measurementBrightnessSum = 0f
    private var measurementBrightnessCount = 0
    private var measurementRedSum = 0f
    private var measurementRedCount = 0
    
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pulseAnimationJob: Job? = null
    private var measurementTimeoutJob: Job? = null

    // Flag to prevent race condition between timeout and frame completion
    private var measurementCompleted = false

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        previewView = findViewById(R.id.previewView)
        heartRateText = findViewById(R.id.heartRateText)
        statusText = findViewById(R.id.statusText)
        heartIcon = findViewById(R.id.heartIcon)
        pulseIndicator = findViewById(R.id.pulseIndicator)
        pulseDot = findViewById(R.id.pulseDot)
        muteButton = findViewById(R.id.muteButton)
        historyButton = findViewById(R.id.historyButton)
        ecgWaveformView = findViewById(R.id.ecgWaveformView)
        heartLottie = findViewById(R.id.heartLottie)
        loadingAnimation = findViewById(R.id.loadingAnimation)

        // Initialize database
        database = HeartRateDatabase.getDatabase(this)

        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Make camera preview circular
        previewView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        previewView.clipToOutline = true
        
        // Mute button click listener
        muteButton.setOnClickListener {
            isSoundMuted = !isSoundMuted
            heartbeatController?.isMuted = isSoundMuted
            muteButton.text = if (isSoundMuted) "🔇" else "🔊"
        }

        // History button click listener
        historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // Initialize AdMob
        MobileAds.initialize(this) {}
        adView = findViewById(R.id.adView)
        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)

        // Initialize unified heartbeat controller (sound + animation + ECG in perfect sync)
        heartbeatController = HeartbeatController(heartIcon, ecgWaveformView)

        // Initialize completion sound player (for end of measurement)
        try {
            heartbeatSoundPlayer = HeartbeatSoundPlayer()
        } catch (e: Exception) {
            Toast.makeText(this, "Sound may not work on this device", Toast.LENGTH_SHORT).show()
        }
        
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }
    
    private fun startMeasuring() {
        if (isMeasuring) return

        isMeasuring = true
        measurementCompleted = false
        redValues.clear()
        frameCount = 0
        firstFrameTimestamp = 0L
        lastFrameTimestamp = 0L
        measurementBrightnessSum = 0f
        measurementBrightnessCount = 0
        measurementRedSum = 0f
        measurementRedCount = 0

        // Reset heart rate processor
        heartRateProcessor.reset()

        // Don't clear heart rate text - keep previous reading until new one is ready
        statusText.text = "Measuring... Keep your finger steady"

        // Turn on flash
        camera?.cameraControl?.enableTorch(true)
        isFlashOn = true

        // Show and animate pulse indicator
        showPulseIndicator()

        // Show ECG waveform and hide loading animation
        loadingAnimation.visibility = View.GONE
        ecgWaveformView.visibility = View.VISIBLE
        ecgWaveformView.clearData()
        ecgWaveformView.startAnimation()

        // Use heartIcon for beat animation (controlled by HeartbeatController)
        // Hide Lottie - we control the heart icon directly for perfect sync
        heartLottie.visibility = View.GONE
        heartIcon.visibility = View.VISIBLE

        // Start the heartbeat controller
        heartbeatController?.start()

        // Cancel any existing timeout job
        measurementTimeoutJob?.cancel()

        // Start timeout with race condition protection
        measurementTimeoutJob = mainScope.launch {
            delay((SAMPLE_DURATION_SECONDS * 1000).toLong())
            if (isMeasuring && !measurementCompleted) {
                measurementCompleted = true
                calculateHeartRate()
            }
        }
    }
    
    private fun stopMeasuring() {
        isMeasuring = false
        isFingerDetected = false

        // Cancel timeout to prevent race condition
        measurementTimeoutJob?.cancel()
        measurementTimeoutJob = null

        // Stop heartbeat controller
        heartbeatController?.stop()

        // Reset finger detector for next measurement (keep calibration)
        fingerDetector.resetForNextMeasurement()

        // Don't change status text here - let the caller set appropriate message

        // Keep flash on for detection
        camera?.cameraControl?.enableTorch(true)
        isFlashOn = true

        // Hide pulse indicator
        hidePulseIndicator()

        // Stop ECG waveform
        ecgWaveformView.stopAnimation()
        heartIcon.visibility = View.VISIBLE
    }
    
    private fun showPulseIndicator() {
        pulseIndicator.visibility = View.VISIBLE
        startPulseAnimation()
    }
    
    private fun hidePulseIndicator() {
        pulseIndicator.visibility = View.GONE
        pulseAnimationJob?.cancel()
    }
    
    private fun startPulseAnimation() {
        pulseAnimationJob?.cancel()
        pulseAnimationJob = mainScope.launch {
            while (isActive && isMeasuring) {
                pulseDot.alpha = 1.0f
                delay(400)
                pulseDot.alpha = 0.3f
                delay(400)
            }
        }
    }
    
    private fun calculateRealtimeHeartRate(): Int? {
        // Calculate real-time heart rate from current data
        if (redValues.size < 90) return null

        val smoothedValues = smoothSignal(redValues)
        val peaks = detectPeaks(smoothedValues)

        if (peaks.size >= 3) {
            // FPS-aware interval bounds: 40-200 BPM range
            val minInterval = (measuredFramesPerSecond * 0.3f)  // 200 BPM = 0.3s per beat
            val maxInterval = (measuredFramesPerSecond * 1.5f)  // 40 BPM = 1.5s per beat

            val intervals = mutableListOf<Float>()
            for (i in 1 until peaks.size) {
                val interval = (peaks[i] - peaks[i - 1]).toFloat()
                if (interval > minInterval && interval < maxInterval) {
                    intervals.add(interval)
                }
            }

            if (intervals.size >= 2) {
                // Filter outliers using IQR method
                val filteredIntervals = filterOutliers(intervals)
                if (filteredIntervals.isEmpty()) return null

                filteredIntervals.sort()
                val medianInterval = if (filteredIntervals.size % 2 == 0) {
                    (filteredIntervals[filteredIntervals.size / 2 - 1] + filteredIntervals[filteredIntervals.size / 2]) / 2
                } else {
                    filteredIntervals[filteredIntervals.size / 2]
                }

                val heartRate = (60.0f * measuredFramesPerSecond / medianInterval).toInt()
                if (heartRate in 40..200) {
                    return heartRate
                }
            }
        }

        return null
    }

    private fun filterOutliers(intervals: MutableList<Float>): MutableList<Float> {
        if (intervals.size < 4) return intervals

        val sorted = intervals.sorted()
        val q1 = sorted[sorted.size / 4]
        val q3 = sorted[sorted.size * 3 / 4]
        val iqr = q3 - q1
        val lowerBound = q1 - 1.5f * iqr
        val upperBound = q3 + 1.5f * iqr

        return intervals.filter { it >= lowerBound && it <= upperBound }.toMutableList()
    }

    private fun calculateSignalQuality(): Float {
        // Returns a quality score from 0.0 to 1.0
        if (redValues.size < 60) return 0f

        val recentValues = redValues.takeLast(60)
        val mean = recentValues.average().toFloat()
        val variance = recentValues.map { (it - mean) * (it - mean) }.average().toFloat()
        val stdDev = kotlin.math.sqrt(variance)

        // Good PPG signal should have moderate variance (not flat, not too noisy)
        // Typical good signal has stdDev between 1-10 for normalized values
        val normalizedStdDev = stdDev / mean * 100  // As percentage of mean

        return when {
            normalizedStdDev < 0.1f -> 0.1f  // Signal too flat - poor contact
            normalizedStdDev > 5f -> 0.3f    // Signal too noisy - movement
            normalizedStdDev in 0.5f..2f -> 1.0f  // Optimal range
            else -> 0.7f  // Acceptable
        }
    }
    
    private fun calculatePartialHeartRate(): Int? {
        // Calculate heart rate from partial data
        if (redValues.size < 30) {
            return null
        }

        val smoothedValues = smoothSignal(redValues)
        val peaks = detectPeaks(smoothedValues)

        if (peaks.size >= 2) {
            // FPS-aware interval bounds
            val minInterval = (measuredFramesPerSecond * 0.3f)  // 200 BPM
            val maxInterval = (measuredFramesPerSecond * 1.5f)  // 40 BPM

            val intervals = mutableListOf<Float>()
            for (i in 1 until peaks.size) {
                val interval = (peaks[i] - peaks[i - 1]).toFloat()
                if (interval > minInterval && interval < maxInterval) {
                    intervals.add(interval)
                }
            }

            if (intervals.isNotEmpty()) {
                val filteredIntervals = if (intervals.size >= 4) filterOutliers(intervals) else intervals
                if (filteredIntervals.isEmpty()) return null

                filteredIntervals.sort()
                val medianInterval = if (filteredIntervals.size % 2 == 0) {
                    (filteredIntervals[filteredIntervals.size / 2 - 1] + filteredIntervals[filteredIntervals.size / 2]) / 2
                } else {
                    filteredIntervals[filteredIntervals.size / 2]
                }

                val partialHeartRate = (60.0f * measuredFramesPerSecond / medianInterval).toInt()

                if (partialHeartRate in 40..200) {
                    return partialHeartRate
                }
            }
        }

        return null
    }
    
    private fun calculateHeartRate() {
        // Check signal quality first
        val signalQuality = calculateSignalQuality()

        // ALWAYS try to calculate and show heart rate - even with less data
        if (redValues.size < 90) {
            val partialHR = calculatePartialHeartRate()
            if (partialHR != null) {
                heartRateText.text = partialHR.toString()
                val qualityText = if (signalQuality < 0.5f) " (weak signal)" else " (short measurement)"
                statusText.text = "✓ Heart rate: $partialHR BPM$qualityText"
            } else {
                statusText.text = "Not enough data. Place finger again."
                heartRateText.text = "--"
            }
            stopMeasuring()
            return
        }

        // Apply smoothing filter to reduce noise
        val smoothedValues = smoothSignal(redValues)

        // Calculate heart rate using peak detection
        val peaks = detectPeaks(smoothedValues)

        if (peaks.size < 3) {
            val partialHR = calculatePartialHeartRate()
            if (partialHR != null) {
                heartRateText.text = partialHR.toString()
                statusText.text = "✓ Heart rate: $partialHR BPM (low confidence)"
            } else {
                statusText.text = "Could not detect heartbeat. Try again."
                heartRateText.text = "--"
            }
            stopMeasuring()
            return
        }

        // FPS-aware interval bounds
        val minInterval = (measuredFramesPerSecond * 0.3f)  // 200 BPM
        val maxInterval = (measuredFramesPerSecond * 1.5f)  // 40 BPM

        // Calculate intervals between peaks
        val intervals = mutableListOf<Float>()
        for (i in 1 until peaks.size) {
            val interval = (peaks[i] - peaks[i - 1]).toFloat()
            if (interval > minInterval && interval < maxInterval) {
                intervals.add(interval)
            }
        }

        if (intervals.isEmpty()) {
            val partialHR = calculatePartialHeartRate()
            if (partialHR != null) {
                heartRateText.text = partialHR.toString()
                statusText.text = "✓ Heart rate: $partialHR BPM"
            } else {
                statusText.text = "Could not detect consistent heartbeat."
                heartRateText.text = "--"
            }
            stopMeasuring()
            return
        }

        // Filter outliers for more accurate reading
        val filteredIntervals = if (intervals.size >= 4) filterOutliers(intervals) else intervals
        if (filteredIntervals.isEmpty()) {
            statusText.text = "Inconsistent heartbeat detected. Try again."
            heartRateText.text = "--"
            stopMeasuring()
            return
        }

        // Use median for accuracy
        filteredIntervals.sort()
        val medianInterval = if (filteredIntervals.size % 2 == 0) {
            (filteredIntervals[filteredIntervals.size / 2 - 1] + filteredIntervals[filteredIntervals.size / 2]) / 2
        } else {
            filteredIntervals[filteredIntervals.size / 2]
        }

        val heartRate = (60.0f * measuredFramesPerSecond / medianInterval).toInt()

        // Calculate confidence based on signal quality and interval consistency
        val intervalVariance = if (filteredIntervals.size > 1) {
            val mean = filteredIntervals.average().toFloat()
            filteredIntervals.map { (it - mean) * (it - mean) }.average().toFloat()
        } else 0f
        val intervalConsistency = if (intervalVariance < 25) 1.0f else if (intervalVariance < 100) 0.7f else 0.4f
        val confidence = (signalQuality + intervalConsistency) / 2

        // Determine confidence label
        val confidenceLabel = when {
            confidence >= 0.8f -> "High confidence"
            confidence >= 0.5f -> "Good"
            else -> "Low confidence"
        }
        lastConfidence = confidenceLabel

        // Show result with confidence
        if (heartRate in 40..200) {
            heartRateText.text = heartRate.toString()
            statusText.text = "✓ $heartRate BPM ($confidenceLabel) | Tap to measure again"
            animateHeartbeat()

            // Play completion sound (high-pitched beep-beep)
            if (!isSoundMuted) {
                heartbeatSoundPlayer?.playCompletionBeep()
            }

            // Save measurement to database
            saveMeasurement(heartRate, confidenceLabel)
        } else if (heartRate in 30..220) {
            heartRateText.text = heartRate.toString()
            statusText.text = "⚠ $heartRate BPM (unusual range, please retry)"

            // Play completion sound even for unusual readings
            if (!isSoundMuted) {
                heartbeatSoundPlayer?.playCompletionBeep()
            }

            saveMeasurement(heartRate, "Low confidence")
        } else {
            heartRateText.text = "--"
            statusText.text = "Invalid reading. Please try again."
        }

        stopMeasuring()
    }

    private fun saveMeasurement(bpm: Int, confidence: String) {
        lifecycleScope.launch {
            val measurement = HeartRateMeasurement(
                bpm = bpm,
                confidence = confidence,
                duration = SAMPLE_DURATION_SECONDS
            )
            database.heartRateDao().insertMeasurement(measurement)
        }
    }
    
    private fun smoothSignal(signal: List<Float>): List<Float> {
        // Safety check - return empty if signal is too short
        if (signal.size < 10) return signal.toList()

        try {
            // Make a copy to avoid concurrent modification
            val signalCopy = signal.toList()

            // Apply bandpass filter for heart rate detection (0.5 - 4 Hz for 30-240 BPM)
            // Step 1: Remove DC component and low-frequency drift (high-pass)
            val detrended = removeTrend(signalCopy)

            // Step 2: Apply low-pass filter to remove high-frequency noise
            val lowPassed = lowPassFilter(detrended, cutoffFrames = 5)  // ~6Hz cutoff at 30fps

            // Step 3: Apply smoothing for cleaner peaks
            val smoothed = movingAverageFilter(lowPassed, windowSize = 5)

            return smoothed
        } catch (e: Exception) {
            // Return original signal if processing fails
            return signal.toList()
        }
    }

    private fun removeTrend(signal: List<Float>): List<Float> {
        // High-pass filter using a large moving average subtraction
        // This removes breathing artifacts and slow drift
        val windowSize = (measuredFramesPerSecond * 2).toInt().coerceAtLeast(30)  // ~2 second window
        val trend = mutableListOf<Float>()

        for (i in signal.indices) {
            var sum = 0f
            var count = 0
            val halfWindow = windowSize / 2
            for (j in -halfWindow..halfWindow) {
                val index = i + j
                if (index in signal.indices) {
                    sum += signal[index]
                    count++
                }
            }
            trend.add(sum / count)
        }

        // Subtract trend to get high-passed signal
        return signal.mapIndexed { index, value -> value - trend[index] }
    }

    private fun lowPassFilter(signal: List<Float>, cutoffFrames: Int): List<Float> {
        // Simple low-pass using weighted moving average (approximates Butterworth)
        val result = mutableListOf<Float>()
        val weights = generateGaussianWeights(cutoffFrames)

        for (i in signal.indices) {
            var sum = 0f
            var weightSum = 0f
            for (j in weights.indices) {
                val offset = j - weights.size / 2
                val index = i + offset
                if (index in signal.indices) {
                    sum += signal[index] * weights[j]
                    weightSum += weights[j]
                }
            }
            result.add(if (weightSum > 0) sum / weightSum else signal[i])
        }

        return result
    }

    private fun generateGaussianWeights(size: Int): List<Float> {
        val sigma = size / 2f
        val weights = mutableListOf<Float>()
        for (i in 0 until size) {
            val x = i - size / 2f
            weights.add(kotlin.math.exp(-(x * x) / (2 * sigma * sigma)).toFloat())
        }
        return weights
    }

    private fun movingAverageFilter(signal: List<Float>, windowSize: Int): List<Float> {
        val smoothed = mutableListOf<Float>()
        val halfWindow = windowSize / 2

        for (i in signal.indices) {
            var sum = 0f
            var count = 0
            for (j in -halfWindow..halfWindow) {
                val index = i + j
                if (index in signal.indices) {
                    sum += signal[index]
                    count++
                }
            }
            smoothed.add(sum / count)
        }

        return smoothed
    }
    
    private fun detectPeaks(values: List<Float>): List<Int> {
        if (values.isEmpty()) return emptyList()

        // Signal should already be detrended, but ensure zero-mean
        val mean = values.average().toFloat()
        val normalized = values.map { it - mean }

        // Calculate standard deviation for adaptive threshold
        val variance = normalized.map { it * it }.average()
        val stdDev = kotlin.math.sqrt(variance).toFloat()

        // Calculate min/max peak distances based on actual measured FPS
        // Min distance: 200ms (max 300 BPM - allows for exercise)
        // Max distance: 2000ms (min 30 BPM - allows for athletes)
        val minPeakDistance = (measuredFramesPerSecond * 0.2f).toInt().coerceAtLeast(6)  // 200ms
        val maxPeakDistance = (measuredFramesPerSecond * 2.0f).toInt()  // 2 seconds

        // Find local maxima with improved algorithm
        val peaks = mutableListOf<Int>()
        val threshold = stdDev * 0.5f  // Increased threshold to reduce noise detection

        // Use a wider window for peak detection (3 samples each side)
        for (i in 3 until normalized.size - 3) {
            val current = normalized[i]

            // Must be above threshold
            if (current <= threshold) continue

            // Must be local maximum (higher than all neighbors in window)
            val isLocalMax = current > normalized[i - 1] &&
                    current > normalized[i + 1] &&
                    current > normalized[i - 2] &&
                    current > normalized[i + 2] &&
                    current > normalized[i - 3] &&
                    current > normalized[i + 3]

            if (!isLocalMax) continue

            // Check distance constraints
            if (peaks.isEmpty()) {
                peaks.add(i)
            } else {
                val distance = i - peaks.last()
                if (distance >= minPeakDistance && distance <= maxPeakDistance) {
                    peaks.add(i)
                } else if (distance > maxPeakDistance) {
                    // Gap too large - might have missed beats, still add this peak
                    peaks.add(i)
                }
                // If distance < minPeakDistance, skip (likely noise)
            }
        }

        return peaks
    }
    
    private fun animateHeartbeat() {
        val scaleUp = ObjectAnimator.ofFloat(heartIcon, View.SCALE_X, 1f, 1.2f).apply {
            duration = 100
        }
        val scaleDown = ObjectAnimator.ofFloat(heartIcon, View.SCALE_X, 1.2f, 1f).apply {
            duration = 100
        }
        val scaleUpY = ObjectAnimator.ofFloat(heartIcon, View.SCALE_Y, 1f, 1.2f).apply {
            duration = 100
        }
        val scaleDownY = ObjectAnimator.ofFloat(heartIcon, View.SCALE_Y, 1.2f, 1f).apply {
            duration = 100
        }
        
        scaleUp.start()
        scaleUpY.start()
        scaleUp.doOnEnd {
            scaleDown.start()
            scaleDownY.start()
        }
    }
    
    private fun ObjectAnimator.doOnEnd(action: () -> Unit) {
        this.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                action()
            }
            override fun onAnimationStart(animation: android.animation.Animator) {}
            override fun onAnimationCancel(animation: android.animation.Animator) {}
            override fun onAnimationRepeat(animation: android.animation.Animator) {}
        })
    }
    
    private fun startCamera() {
        // Check if device has flash
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        var hasFlash = false
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    break
                }
            }
        } catch (e: Exception) {
            // Assume no flash if we can't check
            hasFlash = false
        }

        if (!hasFlash) {
            Toast.makeText(
                this,
                "This device doesn't have a flash. Heart rate detection requires a flash.",
                Toast.LENGTH_LONG
            ).show()
            statusText.text = "No flash detected. App requires camera flash."
            heartRateText.text = "N/A"
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Image analysis for heart rate detection
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, HeartRateAnalyzer())
                }

            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )

                // Turn on flash immediately for finger detection
                camera?.cameraControl?.enableTorch(true)?.addListener({
                    // Verify flash actually turned on by checking after a short delay
                    mainScope.launch {
                        delay(500)
                        if (!isFlashOn) {
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Flash may not be working properly",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }, ContextCompat.getMainExecutor(this))
                isFlashOn = true
            } catch (e: Exception) {
                Toast.makeText(
                    this, "Failed to start camera: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }
    
    private inner class HeartRateAnalyzer : ImageAnalysis.Analyzer {
        private var consecutiveFingerOffFrames = 0
        private val FINGER_OFF_THRESHOLD = 5

        override fun analyze(image: ImageProxy) {
            val currentTimestamp = System.currentTimeMillis()

            // Extract RED and GREEN channels from YUV_420_888 format
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val yData = ByteArray(yBuffer.remaining())
            val uData = ByteArray(uBuffer.remaining())
            val vData = ByteArray(vBuffer.remaining())

            yBuffer.get(yData)
            uBuffer.get(uData)
            vBuffer.get(vData)

            val width = image.width
            val height = image.height
            val uvPixelStride = image.planes[1].pixelStride
            val uvRowStride = image.planes[1].rowStride

            // Calculate average RED and GREEN channel values
            var redSum = 0.0
            var greenSum = 0.0
            var sampleCount = 0
            val step = 8

            val startX = width / 4
            val endX = width * 3 / 4
            val startY = height / 4
            val endY = height * 3 / 4

            for (y in startY until endY step step) {
                for (x in startX until endX step step) {
                    val yIndex = y * width + x
                    val uvIndex = (y / 2) * uvRowStride + (x / 2) * uvPixelStride

                    if (yIndex < yData.size && uvIndex < vData.size && uvIndex < uData.size) {
                        val yValue = (yData[yIndex].toInt() and 0xFF).toFloat()
                        val uValue = (uData[uvIndex].toInt() and 0xFF).toFloat()
                        val vValue = (vData[uvIndex].toInt() and 0xFF).toFloat()

                        // Convert to RGB
                        // R = Y + 1.402 * (V - 128)
                        // G = Y - 0.344 * (U - 128) - 0.714 * (V - 128)
                        val red = yValue + 1.402f * (vValue - 128f)
                        val green = yValue - 0.344f * (uValue - 128f) - 0.714f * (vValue - 128f)

                        redSum += red.coerceIn(0f, 255f)
                        greenSum += green.coerceIn(0f, 255f)
                        sampleCount++
                    }
                }
            }

            val avgRedIntensity = if (sampleCount > 0) (redSum / sampleCount).toFloat() else 0f
            val avgGreenIntensity = if (sampleCount > 0) (greenSum / sampleCount).toFloat() else 0f

            // Calculate brightness (Y plane average)
            var brightnessSum = 0L
            val brightnessStep = 10
            for (i in yData.indices step brightnessStep) {
                brightnessSum += (yData[i].toInt() and 0xFF)
            }
            val avgBrightness = brightnessSum.toFloat() / (yData.size / brightnessStep)

            // Store for later use
            lastRedIntensity = avgRedIntensity
            lastGreenIntensity = avgGreenIntensity

            // Finger detection using new robust detector
            if (!isMeasuring) {
                val detectionState = fingerDetector.processFrame(avgRedIntensity, avgGreenIntensity, avgBrightness)

                runOnUiThread {
                    statusText.text = fingerDetector.getStatusMessage()
                }

                // Start measuring when finger is confirmed with pulse
                if (detectionState == FingerDetector.DetectionState.FINGER_CONFIRMED && !isFingerDetected) {
                    isFingerDetected = true
                    runOnUiThread {
                        startMeasuring()
                    }
                }
            } else if (frameCount < REQUIRED_FRAMES) {
                // ===== MEASURING MODE =====

                // Collect signal data
                redValues.add(avgRedIntensity)
                frameCount++

                // Track timestamps for FPS calculation
                if (firstFrameTimestamp == 0L) {
                    firstFrameTimestamp = currentTimestamp
                }
                lastFrameTimestamp = currentTimestamp

                // Update FPS measurement
                if (frameCount > 30) {
                    val elapsedSeconds = (lastFrameTimestamp - firstFrameTimestamp) / 1000f
                    if (elapsedSeconds > 0) {
                        measuredFramesPerSecond = frameCount / elapsedSeconds
                        heartRateProcessor.setFPS(measuredFramesPerSecond)
                    }
                }

                // Process sample for beat detection (sets flag if beat detected)
                heartRateProcessor.processSample(avgRedIntensity, currentTimestamp)

                // Check for beat on UI thread and trigger PERFECTLY synchronized animation/sound/ECG
                // Using HeartbeatController ensures all three fire in the SAME instant
                runOnUiThread {
                    if (heartRateProcessor.checkAndConsumeBeat()) {
                        heartbeatController?.triggerBeat()  // Atomic: Sound + Animation + ECG together!
                    }
                }

                // Update BPM display periodically
                if (frameCount >= 90 && frameCount % 30 == 0) {
                    val stableBPM = heartRateProcessor.calculateStableBPM(redValues)
                    if (stableBPM != null && heartRateProcessor.hasReliableBPM()) {
                        runOnUiThread {
                            heartRateText.text = stableBPM.toString()
                        }
                    }
                }

                // Track measurement averages for finger removal detection
                measurementBrightnessSum += avgBrightness
                measurementBrightnessCount++
                measurementRedSum += avgRedIntensity
                measurementRedCount++

                val avgMeasurementBrightness = measurementBrightnessSum / measurementBrightnessCount
                val avgMeasurementRed = measurementRedSum / measurementRedCount

                // Update progress display
                val progress = (frameCount * 100 / REQUIRED_FRAMES)
                val qualityIndicator = if (redValues.size >= 60) {
                    val quality = calculateSignalQuality()
                    when {
                        quality >= 0.8f -> "●●●"
                        quality >= 0.5f -> "●●○"
                        quality >= 0.3f -> "●○○"
                        else -> "○○○"
                    }
                } else "..."

                runOnUiThread {
                    statusText.text = "Measuring $progress% | Signal: $qualityIndicator"
                }

                // Check for finger removal using both brightness AND red channel
                val redDropped = avgRedIntensity < avgMeasurementRed * 0.7f  // Red dropped significantly
                val brightnessJumped = avgBrightness > avgMeasurementBrightness + 25  // Brightness jumped
                val redRatio = if (avgGreenIntensity > 0) avgRedIntensity / avgGreenIntensity else 0f
                val lostRedDominance = redRatio < 1.1f  // Lost red dominance (finger removed)

                val fingerLikelyRemoved = redDropped || brightnessJumped || lostRedDominance

                if (fingerLikelyRemoved) {
                    consecutiveFingerOffFrames++
                    if (consecutiveFingerOffFrames >= FINGER_OFF_THRESHOLD) {
                        val partialReading = calculatePartialHeartRate()
                        runOnUiThread {
                            if (partialReading != null) {
                                heartRateText.text = "~$partialReading"
                                statusText.text = "Finger removed. Partial: ~$partialReading BPM"
                            } else {
                                statusText.text = "Finger removed. Place finger again."
                            }
                            stopMeasuring()
                        }
                        consecutiveFingerOffFrames = 0
                    }
                } else {
                    consecutiveFingerOffFrames = 0
                }
            }

            image.close()
        }
    }
    
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required", 
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Cancel measurement if app goes to background
        if (isMeasuring) {
            val partialReading = calculatePartialHeartRate()
            if (partialReading != null) {
                heartRateText.text = "~$partialReading"
                statusText.text = "Paused. Partial: ~$partialReading BPM | Resume to retry"
            } else {
                statusText.text = "Measurement paused. Place finger to restart."
            }
            stopMeasuring()
        }

        // Turn off flash when app is paused to save battery
        camera?.cameraControl?.enableTorch(false)
        isFlashOn = false
    }

    override fun onResume() {
        super.onResume()
        // Reset state and recalibrate when app resumes
        if (camera != null) {
            // Turn flash back on
            camera?.cameraControl?.enableTorch(true)
            isFlashOn = true

            // Reset finger detector for fresh calibration
            fingerDetector.reset()
            isFingerDetected = false
            statusText.text = "Recalibrating... Keep finger OFF"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        pulseAnimationJob?.cancel()
        measurementTimeoutJob?.cancel()
        mainScope.cancel()
        heartbeatController?.release()  // Release unified heartbeat controller
        heartbeatSoundPlayer?.release()
        camera?.cameraControl?.enableTorch(false)
    }
}
