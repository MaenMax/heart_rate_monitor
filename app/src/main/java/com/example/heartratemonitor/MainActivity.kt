package com.example.heartratemonitor

import android.Manifest
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    
    private lateinit var previewView: PreviewView
    private lateinit var heartRateText: TextView
    private lateinit var statusText: TextView
    private lateinit var heartIcon: TextView
    private lateinit var pulseIndicator: View
    private lateinit var pulseDot: View
    
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var isFlashOn = false
    private var isMeasuring = false
    private var isFingerDetected = false
    
    // Heart rate detection variables
    private val redValues = mutableListOf<Float>()
    private var frameCount = 0
    private val SAMPLE_DURATION_SECONDS = 10
    private val FRAMES_PER_SECOND = 30
    private val REQUIRED_FRAMES = SAMPLE_DURATION_SECONDS * FRAMES_PER_SECOND
    
    // Finger detection
    private var baselineBrightness: Float = 0f
    private var brightnessReadings = mutableListOf<Float>()
    private val BASELINE_SAMPLE_SIZE = 30  // ~1 second at 30fps
    private var sustainedFingerContact = 0
    private val SUSTAINED_THRESHOLD = 30  // Need 30 consecutive changed frames (~1 second)
    private var isFingerCurrentlyOnCamera = false
    
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pulseAnimationJob: Job? = null
    
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
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
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
        redValues.clear()
        frameCount = 0
        heartRateText.text = "--"
        statusText.text = "Measuring... Keep your finger steady"
        
        // Turn on flash
        camera?.cameraControl?.enableTorch(true)
        isFlashOn = true
        
        // Show and animate pulse indicator
        showPulseIndicator()
        
        // Start timeout
        mainScope.launch {
            delay((SAMPLE_DURATION_SECONDS * 1000).toLong())
            if (isMeasuring) {
                calculateHeartRate()
            }
        }
    }
    
    private fun stopMeasuring() {
        isMeasuring = false
        isFingerDetected = false
        sustainedFingerContact = 0
        isFingerCurrentlyOnCamera = false
        
        // Don't change status text here - let the caller set appropriate message
        
        // Keep flash on for detection
        camera?.cameraControl?.enableTorch(true)
        isFlashOn = true
        
        // Hide pulse indicator
        hidePulseIndicator()
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
    
    private fun calculateHeartRate() {
        if (redValues.size < 150) {
            statusText.text = "Not enough data. Place finger again."
            heartRateText.text = "--"
            stopMeasuring()
            // Don't clear brightness readings - keep baseline
            return
        }
        
        // Apply smoothing filter to reduce noise
        val smoothedValues = smoothSignal(redValues)
        
        // Calculate heart rate using peak detection
        val peaks = detectPeaks(smoothedValues)
        
        if (peaks.size < 4) {
            statusText.text = "Could not detect heartbeat. Try again."
            heartRateText.text = "--"
            stopMeasuring()
            return
        }
        
        // Calculate intervals between peaks (ignore first interval as it may be partial)
        val intervals = mutableListOf<Float>()
        for (i in 1 until peaks.size) {
            val interval = (peaks[i] - peaks[i-1]).toFloat()
            // Filter out unrealistic intervals
            if (interval > 10 && interval < 90) {  // Between 40-180 BPM
                intervals.add(interval)
            }
        }
        
        if (intervals.isEmpty()) {
            statusText.text = "Could not detect consistent heartbeat."
            heartRateText.text = "--"
            stopMeasuring()
            return
        }
        
        // Use median instead of average for better outlier resistance
        intervals.sort()
        val medianInterval = if (intervals.size % 2 == 0) {
            (intervals[intervals.size / 2 - 1] + intervals[intervals.size / 2]) / 2
        } else {
            intervals[intervals.size / 2]
        }
        
        val heartRate = (60.0f * FRAMES_PER_SECOND / medianInterval).toInt()
        
        // Validate heart rate is in reasonable range
        if (heartRate in 45..180) {
            heartRateText.text = heartRate.toString()
            statusText.text = "✓ Heart rate: $heartRate BPM | Remove finger or measure again"
            animateHeartbeat()
        } else {
            heartRateText.text = "--"
            statusText.text = "Invalid reading: $heartRate BPM out of range."
        }
        
        stopMeasuring()
        // Don't clear baseline - keep it for next measurement
    }
    
    private fun smoothSignal(signal: List<Float>): List<Float> {
        // Simple moving average filter
        val windowSize = 3
        val smoothed = mutableListOf<Float>()
        
        for (i in signal.indices) {
            var sum = 0f
            var count = 0
            for (j in -windowSize..windowSize) {
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
        
        // Normalize the signal (remove DC component)
        val mean = values.average().toFloat()
        val normalized = values.map { it - mean }
        
        // Calculate standard deviation for adaptive threshold
        val variance = normalized.map { it * it }.average()
        val stdDev = kotlin.math.sqrt(variance).toFloat()
        
        // Find local maxima
        val peaks = mutableListOf<Int>()
        val threshold = stdDev * 0.3f  // Adaptive threshold based on signal strength
        val minPeakDistance = 18  // Minimum 18 frames between peaks (max 100 BPM at 30fps)
        val maxPeakDistance = 60  // Maximum 60 frames between peaks (min 30 BPM at 30fps)
        
        for (i in 2 until normalized.size - 2) {
            // Check if this is a local maximum (compare with neighbors)
            if (normalized[i] > threshold &&
                normalized[i] > normalized[i - 1] &&
                normalized[i] > normalized[i + 1] &&
                normalized[i] > normalized[i - 2] &&
                normalized[i] > normalized[i + 2]) {
                
                // Check minimum distance from last peak
                if (peaks.isEmpty()) {
                    peaks.add(i)
                } else {
                    val distance = i - peaks.last()
                    if (distance >= minPeakDistance) {
                        // Also check it's not too far (would indicate missed peaks)
                        if (distance <= maxPeakDistance) {
                            peaks.add(i)
                        }
                    }
                }
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
                camera?.cameraControl?.enableTorch(true)
                isFlashOn = true
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to start camera: ${e.message}", 
                    Toast.LENGTH_SHORT).show()
            }
            
        }, ContextCompat.getMainExecutor(this))
    }
    
    private inner class HeartRateAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(image: ImageProxy) {
            // Get the Y plane (luminance) from YUV format
            val buffer = image.planes[0].buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            
            // Calculate average intensity
            var sum = 0L
            val step = 10  // Sample every 10th pixel for performance
            for (i in data.indices step step) {
                sum += (data[i].toInt() and 0xFF)
            }
            val avgIntensity = sum.toFloat() / (data.size / step)
            
            // Finger detection logic
            if (!isMeasuring) {
                // Build baseline (camera with flash on, no finger)
                if (brightnessReadings.size < BASELINE_SAMPLE_SIZE) {
                    brightnessReadings.add(avgIntensity)
                    
                    // Check if values are stable and bright enough (no finger on camera)
                    if (brightnessReadings.size >= 10) {
                        val recent = brightnessReadings.takeLast(10)
                        val recentAvg = recent.average().toFloat()
                        val variance = recent.map { (it - recentAvg) * (it - recentAvg) }.average()
                        
                        // If variance is too high, someone is moving/touching - restart
                        if (variance > 200) {
                            brightnessReadings.clear()
                            runOnUiThread {
                                statusText.text = "Keep finger OFF! Moving detected - restarting..."
                            }
                            image.close()
                            return
                        }
                        
                        // If brightness is too low, finger might be covering camera - restart
                        if (recentAvg < 70) {
                            brightnessReadings.clear()
                            runOnUiThread {
                                statusText.text = "Too dark! Remove finger from camera!"
                            }
                            image.close()
                            return
                        }
                    }
                    
                    if (brightnessReadings.size == BASELINE_SAMPLE_SIZE) {
                        baselineBrightness = brightnessReadings.average().toFloat()
                        
                        // Final check: baseline should be reasonably bright (flash is on, no finger)
                        if (baselineBrightness < 70) {
                            brightnessReadings.clear()
                            runOnUiThread {
                                statusText.text = "Calibration failed! Remove finger and restart app"
                            }
                        } else {
                            runOnUiThread {
                                statusText.text = "Ready! NOW place your finger on camera"
                            }
                        }
                    } else {
                        val progress = (brightnessReadings.size * 100 / BASELINE_SAMPLE_SIZE)
                        runOnUiThread {
                            statusText.text = "Calibrating... $progress% (Keep finger OFF)"
                        }
                    }
                } else {
                    // Check if finger is covering camera
                    val brightnessDiff = avgIntensity - baselineBrightness
                    val absDiff = abs(brightnessDiff)
                    
                    // Finger is on camera if brightness differs significantly from baseline
                    val fingerIsOn = absDiff > 25  // More lenient threshold
                    
                    if (fingerIsOn) {
                        isFingerCurrentlyOnCamera = true
                        sustainedFingerContact++
                        
                        runOnUiThread {
                            statusText.text = "Detecting... ${sustainedFingerContact}/${SUSTAINED_THRESHOLD} | Base:${baselineBrightness.toInt()} Cur:${avgIntensity.toInt()} Diff:${absDiff.toInt()}"
                        }
                        
                        // Start measuring after sustained contact
                        if (sustainedFingerContact >= SUSTAINED_THRESHOLD && !isFingerDetected) {
                            isFingerDetected = true
                            runOnUiThread {
                                startMeasuring()
                            }
                        }
                    } else {
                        // Finger not on camera (back to baseline)
                        if (isFingerCurrentlyOnCamera) {
                            // Finger was just removed
                            isFingerCurrentlyOnCamera = false
                        }
                        sustainedFingerContact = 0
                        
                        // Show current values for debugging
                        runOnUiThread {
                            statusText.text = "Ready! (Base:${baselineBrightness.toInt()} Cur:${avgIntensity.toInt()} Diff:${absDiff.toInt()})"
                        }
                    }
                }
            } else if (frameCount < REQUIRED_FRAMES) {
                // Measuring mode - collect data
                redValues.add(avgIntensity)
                frameCount++
                
                // Update progress
                val progress = (frameCount * 100 / REQUIRED_FRAMES)
                runOnUiThread {
                    statusText.text = "Measuring... $progress%"
                }
                
                // Don't cancel if we're close to completion (>80%)
                if (progress < 80) {
                    // Check if finger removed (brightness returns to baseline)
                    val currentDiff = abs(avgIntensity - baselineBrightness)
                    if (currentDiff < 15) {
                        runOnUiThread {
                            statusText.text = "Finger removed. Place finger again."
                            stopMeasuring()
                        }
                    }
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
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        pulseAnimationJob?.cancel()
        mainScope.cancel()
        camera?.cameraControl?.enableTorch(false)
    }
}
