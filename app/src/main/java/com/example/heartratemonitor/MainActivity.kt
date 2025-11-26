package com.example.heartratemonitor

import android.Manifest
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Outline
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ToneGenerator
import android.media.AudioManager
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
    private lateinit var muteButton: TextView
    
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var isFlashOn = false
    private var isMeasuring = false
    private var isFingerDetected = false
    
    // Sound effects
    private var toneGenerator: ToneGenerator? = null
    private var lastHeartbeatTime = 0L
    private val MIN_HEARTBEAT_INTERVAL = 500L  // Minimum 500ms between heartbeats (max 120 BPM) - prevent rapid firing
    private var isSoundMuted = false
    
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
    
    // Interruption detection (to prevent false cancellations)
    private var consecutiveFingerOffFrames = 0
    private val FINGER_OFF_THRESHOLD = 10  // Need 10 consecutive frames (~0.3 second) to confirm finger is really off
    
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
        muteButton = findViewById(R.id.muteButton)
        
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
            muteButton.text = if (isSoundMuted) "🔇" else "🔊"
        }
        
        // Initialize sound generator with higher volume
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)  // 90% volume, notification stream
        } catch (e: Exception) {
            // Try alternative stream if it fails
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            } catch (e2: Exception) {
                Toast.makeText(this, "Sound may not work on this device", Toast.LENGTH_SHORT).show()
            }
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
        redValues.clear()
        frameCount = 0
        // Don't clear heart rate text - keep previous reading until new one is ready
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
        consecutiveFingerOffFrames = 0  // Reset interruption counter
        
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
    
    private fun calculateRealtimeHeartRate(): Int? {
        // Calculate real-time heart rate from current data
        if (redValues.size < 90) return null
        
        val smoothedValues = smoothSignal(redValues)
        val peaks = detectPeaks(smoothedValues)
        
        if (peaks.size >= 3) {
            val intervals = mutableListOf<Float>()
            for (i in 1 until peaks.size) {
                val interval = (peaks[i] - peaks[i-1]).toFloat()
                if (interval > 10 && interval < 90) {
                    intervals.add(interval)
                }
            }
            
            if (intervals.size >= 2) {
                intervals.sort()
                val medianInterval = if (intervals.size % 2 == 0) {
                    (intervals[intervals.size / 2 - 1] + intervals[intervals.size / 2]) / 2
                } else {
                    intervals[intervals.size / 2]
                }
                
                val heartRate = (60.0f * FRAMES_PER_SECOND / medianInterval).toInt()
                if (heartRate in 45..180) {
                    return heartRate
                }
            }
        }
        
        return null
    }
    
    private fun calculatePartialHeartRate(): Int? {
        // Calculate heart rate from partial data
        if (redValues.size < 30) {
            // Not enough data even for partial reading
            return null
        }
        
        val smoothedValues = smoothSignal(redValues)
        val peaks = detectPeaks(smoothedValues)
        
        if (peaks.size >= 2) {
            val intervals = mutableListOf<Float>()
            for (i in 1 until peaks.size) {
                val interval = (peaks[i] - peaks[i-1]).toFloat()
                if (interval > 10 && interval < 90) {
                    intervals.add(interval)
                }
            }
            
            if (intervals.isNotEmpty()) {
                intervals.sort()
                val medianInterval = if (intervals.size % 2 == 0) {
                    (intervals[intervals.size / 2 - 1] + intervals[intervals.size / 2]) / 2
                } else {
                    intervals[intervals.size / 2]
                }
                
                val partialHeartRate = (60.0f * FRAMES_PER_SECOND / medianInterval).toInt()
                
                if (partialHeartRate in 45..180) {
                    return partialHeartRate
                }
            }
        }
        
        return null
    }
    
    private fun calculateHeartRate() {
        // ALWAYS try to calculate and show heart rate - even with less data
        if (redValues.size < 90) {
            // Try partial calculation
            val partialHR = calculatePartialHeartRate()
            if (partialHR != null) {
                heartRateText.text = partialHR.toString()
                statusText.text = "✓ Heart rate: $partialHR BPM (short measurement)"
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
            // Still try to calculate something
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
        
        // Calculate intervals between peaks
        val intervals = mutableListOf<Float>()
        for (i in 1 until peaks.size) {
            val interval = (peaks[i] - peaks[i-1]).toFloat()
            // Filter out unrealistic intervals
            if (interval > 10 && interval < 90) {
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
        
        // Use median for accuracy
        intervals.sort()
        val medianInterval = if (intervals.size % 2 == 0) {
            (intervals[intervals.size / 2 - 1] + intervals[intervals.size / 2]) / 2
        } else {
            intervals[intervals.size / 2]
        }
        
        val heartRate = (60.0f * FRAMES_PER_SECOND / medianInterval).toInt()
        
        // ALWAYS SHOW THE NUMBER - even if outside ideal range
        if (heartRate in 45..180) {
            heartRateText.text = heartRate.toString()
            statusText.text = "✓ Heart rate: $heartRate BPM | Remove finger or measure again"
            animateHeartbeat()
        } else if (heartRate in 30..220) {
            // Still show it but warn user
            heartRateText.text = heartRate.toString()
            statusText.text = "⚠ Heart rate: $heartRate BPM (unusual, please retry)"
        } else {
            heartRateText.text = "--"
            statusText.text = "Invalid reading: $heartRate BPM out of range."
        }
        
        stopMeasuring()
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
                
                // CRITICAL: Detect heartbeat for sound and animation - FAST response!
                if (redValues.size >= 8) {
                    val currentTime = System.currentTimeMillis()
                    
                    // Use smaller windows for faster response (reduces lag)
                    val current = redValues.last()  // Most recent frame
                    val last2 = redValues.takeLast(2).average().toFloat()
                    val last4 = redValues.takeLast(4).average().toFloat()
                    val last8 = redValues.takeLast(8).average().toFloat()
                    
                    // Detect peak: current values higher than recent past
                    // Immediate response to brightness increase
                    val isPeak = current > last2 && last2 > last4
                    val strength = abs(current - last8)
                    
                    // Trigger on peaks with minimal delay
                    if (isPeak && strength > 1.0f && currentTime - lastHeartbeatTime > MIN_HEARTBEAT_INTERVAL) {
                        lastHeartbeatTime = currentTime
                        // IMMEDIATE trigger - sound and animation
                        runOnUiThread {
                            onHeartbeatDetected()
                        }
                    }
                }
                
                // REAL-TIME HEART RATE DISPLAY - Update frequently!
                // Show heart rate as soon as we have enough data and keep updating
                if (frameCount >= 90 && frameCount % 30 == 0) {  // Every second after 3 seconds
                    val currentHR = calculateRealtimeHeartRate()
                    if (currentHR != null) {
                        runOnUiThread {
                            heartRateText.text = currentHR.toString()
                        }
                    }
                }
                
                // Update progress
                val progress = (frameCount * 100 / REQUIRED_FRAMES)
                runOnUiThread {
                    statusText.text = "Measuring... $progress%"
                }
                
                // Don't cancel if we're close to completion (>80%)
                if (progress < 80) {
                    // Check if finger removed (brightness returns close to baseline)
                    // Use more lenient threshold and require sustained removal to avoid false cancellations
                    val currentDiff = abs(avgIntensity - baselineBrightness)
                    
                    if (currentDiff < 10) {
                        // Brightness is close to baseline - might be finger removal
                        consecutiveFingerOffFrames++
                        
                        // Only cancel after sustained removal (1 second of frames)
                        if (consecutiveFingerOffFrames >= FINGER_OFF_THRESHOLD) {
                            // Calculate partial heart rate before canceling
                            val partialReading = calculatePartialHeartRate()
                            runOnUiThread {
                                if (partialReading != null) {
                                    heartRateText.text = "~$partialReading"
                                    statusText.text = "Interrupted! Partial: ~$partialReading BPM | Place finger to retry"
                                } else {
                                    statusText.text = "Interrupted. Not enough data. Place finger again."
                                }
                                stopMeasuring()
                            }
                            consecutiveFingerOffFrames = 0
                        }
                    } else {
                        // Finger is still on camera, reset counter
                        consecutiveFingerOffFrames = 0
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
    
    private fun onHeartbeatDetected() {
        // Synchronized heartbeat effects - sound + animation together
        playHeartbeatSound()
        animateHeartPulse()
    }
    
    private fun playHeartbeatSound() {
        if (isSoundMuted) return
        
        try {
            // Force stop any previous tone
            toneGenerator?.stopTone()
            // Play a short beep tone (similar to medical devices)
            // Using DTMF tone '8' which is a higher pitch beep
            toneGenerator?.startTone(ToneGenerator.TONE_DTMF_8, 80)  // 80ms duration, louder
        } catch (e: Exception) {
            // If tone generator fails, try to recreate it
            try {
                toneGenerator?.release()
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)  // 80% volume
                toneGenerator?.startTone(ToneGenerator.TONE_DTMF_8, 80)
            } catch (e2: Exception) {
                // Sound completely failed
            }
        }
    }
    
    private fun animateHeartPulse() {
        // Animate both color AND scale for dramatic, synchronized effect with sound
        // Duration matches the beep sound (50ms beep + 150ms fade = 200ms total)
        val colorAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f, 0f)
        colorAnimator.duration = 200  // Synchronized with sound timing
        
        colorAnimator.addUpdateListener { animator ->
            val fraction = animator.animatedValue as Float
            
            // Very dramatic color change for clear visibility
            // Start: #FF6B6B (bright red), Peak: #8B0000 (dark red)
            val startColor = 0xFF6B6B
            val peakColor = 0x8B0000
            
            val r1 = (startColor shr 16) and 0xFF
            val g1 = (startColor shr 8) and 0xFF
            val b1 = startColor and 0xFF
            
            val r2 = (peakColor shr 16) and 0xFF
            val g2 = (peakColor shr 8) and 0xFF
            val b2 = peakColor and 0xFF
            
            val r = (r1 + (r2 - r1) * fraction).toInt()
            val g = (g1 + (g2 - g1) * fraction).toInt()
            val b = (b1 + (b2 - b1) * fraction).toInt()
            
            val color = android.graphics.Color.rgb(r, g, b)
            heartIcon.setTextColor(color)
            
            // Pulse the scale - quick expansion and contraction
            val scale = 1f + (fraction * 0.2f)  // Scale up to 20% bigger for more visible effect
            heartIcon.scaleX = scale
            heartIcon.scaleY = scale
        }
        
        colorAnimator.start()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        pulseAnimationJob?.cancel()
        mainScope.cancel()
        toneGenerator?.release()
        camera?.cameraControl?.enableTorch(false)
    }
}
