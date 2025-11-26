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

class MainActivity : AppCompatActivity() {
    
    private lateinit var previewView: PreviewView
    private lateinit var heartRateText: TextView
    private lateinit var statusText: TextView
    private lateinit var measureButton: MaterialButton
    private lateinit var heartIcon: TextView
    
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var isFlashOn = false
    private var isMeasuring = false
    
    // Heart rate detection variables
    private val redValues = mutableListOf<Float>()
    private var frameCount = 0
    private val SAMPLE_DURATION_SECONDS = 10
    private val FRAMES_PER_SECOND = 30
    private val REQUIRED_FRAMES = SAMPLE_DURATION_SECONDS * FRAMES_PER_SECOND
    
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
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
        measureButton = findViewById(R.id.measureButton)
        heartIcon = findViewById(R.id.heartIcon)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        measureButton.setOnClickListener {
            if (isMeasuring) {
                stopMeasuring()
            } else {
                startMeasuring()
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
        isMeasuring = true
        redValues.clear()
        frameCount = 0
        heartRateText.text = "--"
        measureButton.text = "Measuring..."
        measureButton.isEnabled = false
        statusText.text = "Keep your finger steady on the camera"
        
        // Turn on flash
        camera?.cameraControl?.enableTorch(true)
        isFlashOn = true
        
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
        measureButton.text = "Start Measuring"
        measureButton.isEnabled = true
        statusText.text = "Place your finger on the camera"
        
        // Turn off flash
        camera?.cameraControl?.enableTorch(false)
        isFlashOn = false
    }
    
    private fun calculateHeartRate() {
        if (redValues.size < 60) {
            statusText.text = "Not enough data. Please try again."
            heartRateText.text = "--"
            stopMeasuring()
            return
        }
        
        // Calculate heart rate using peak detection
        val peaks = detectPeaks(redValues)
        
        if (peaks.size < 2) {
            statusText.text = "Could not detect heartbeat. Please try again."
            heartRateText.text = "--"
            stopMeasuring()
            return
        }
        
        // Calculate average time between peaks
        val intervals = mutableListOf<Float>()
        for (i in 1 until peaks.size) {
            intervals.add((peaks[i] - peaks[i-1]).toFloat())
        }
        
        val avgInterval = intervals.average().toFloat()
        val heartRate = (60.0f * FRAMES_PER_SECOND / avgInterval).toInt()
        
        // Validate heart rate is in reasonable range
        if (heartRate in 40..200) {
            heartRateText.text = heartRate.toString()
            statusText.text = "Heart rate measured successfully!"
            animateHeartbeat()
        } else {
            heartRateText.text = "--"
            statusText.text = "Invalid reading. Please try again."
        }
        
        stopMeasuring()
    }
    
    private fun detectPeaks(values: List<Float>): List<Int> {
        if (values.isEmpty()) return emptyList()
        
        // Normalize the signal
        val mean = values.average().toFloat()
        val normalized = values.map { it - mean }
        
        // Find local maxima
        val peaks = mutableListOf<Int>()
        val threshold = normalized.max() * 0.5f  // 50% of max value
        val minPeakDistance = 15  // Minimum frames between peaks (corresponds to max 120 BPM)
        
        for (i in 1 until normalized.size - 1) {
            if (normalized[i] > threshold &&
                normalized[i] > normalized[i - 1] &&
                normalized[i] > normalized[i + 1]) {
                
                // Check minimum distance from last peak
                if (peaks.isEmpty() || i - peaks.last() >= minPeakDistance) {
                    peaks.add(i)
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
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to start camera: ${e.message}", 
                    Toast.LENGTH_SHORT).show()
            }
            
        }, ContextCompat.getMainExecutor(this))
    }
    
    private inner class HeartRateAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(image: ImageProxy) {
            if (isMeasuring && frameCount < REQUIRED_FRAMES) {
                // Get the Y plane (luminance) from YUV format
                // The Y plane represents brightness, which changes with blood flow
                val buffer = image.planes[0].buffer
                val data = ByteArray(buffer.remaining())
                buffer.get(data)
                
                // Calculate average intensity
                // Blood volume changes affect the amount of light that passes through
                var sum = 0L
                val step = 10  // Sample every 10th pixel for performance
                for (i in data.indices step step) {
                    sum += (data[i].toInt() and 0xFF)
                }
                val avgIntensity = sum.toFloat() / (data.size / step)
                
                redValues.add(avgIntensity)
                frameCount++
                
                // Update progress
                val progress = (frameCount * 100 / REQUIRED_FRAMES)
                runOnUiThread {
                    statusText.text = "Measuring... $progress%"
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
        mainScope.cancel()
        camera?.cameraControl?.enableTorch(false)
    }
}
