package com.example.heartratemonitor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

class ECGWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val waveformPaint = Paint().apply {
        color = Color.parseColor("#00FF88")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val glowPaint = Paint().apply {
        color = Color.parseColor("#00FF88")
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
        maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
        alpha = 80
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#1A3A2A")
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val path = Path()
    private val glowPath = Path()

    // Data points for the waveform
    private val dataPoints = mutableListOf<Float>()
    private val maxDataPoints = 200

    // Animation
    private var offset = 0f
    private var isAnimating = false
    private var lastBeatTime = 0L
    private var beatInterval = 1000L // Default 60 BPM

    // ECG pattern points (normalized 0-1)
    private val ecgPattern = floatArrayOf(
        0f, 0f, 0f, 0f, 0f,           // Baseline
        0.02f, 0.05f, 0.02f, 0f,      // P wave
        0f, 0f,                        // PR segment
        -0.1f, 0.9f, -0.3f,           // QRS complex
        0f, 0f, 0f,                    // ST segment
        0.15f, 0.2f, 0.15f, 0.05f,    // T wave
        0f, 0f, 0f, 0f, 0f            // Baseline
    )

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // Required for blur effect
    }

    fun startAnimation() {
        isAnimating = true
        invalidate()
    }

    fun stopAnimation() {
        isAnimating = false
    }

    fun setBPM(bpm: Int) {
        if (bpm > 0) {
            beatInterval = (60000L / bpm)
        }
    }

    fun onHeartbeat() {
        lastBeatTime = System.currentTimeMillis()
        // Add ECG pattern to data
        addECGBeat()
    }

    fun addDataPoint(value: Float) {
        dataPoints.add(value)
        if (dataPoints.size > maxDataPoints) {
            dataPoints.removeAt(0)
        }
        if (isAnimating) {
            invalidate()
        }
    }

    private fun addECGBeat() {
        // Add ECG pattern points
        for (point in ecgPattern) {
            dataPoints.add(point)
            if (dataPoints.size > maxDataPoints) {
                dataPoints.removeAt(0)
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2

        // Draw grid
        drawGrid(canvas, width, height)

        if (dataPoints.isEmpty()) {
            // Draw flat line when no data
            if (isAnimating) {
                drawIdleWaveform(canvas, width, height)
            }
            return
        }

        // Build waveform path
        path.reset()
        glowPath.reset()

        val pointSpacing = width / maxDataPoints
        var firstPoint = true

        for (i in dataPoints.indices) {
            val x = i * pointSpacing
            val y = centerY - (dataPoints[i] * height * 0.4f)

            if (firstPoint) {
                path.moveTo(x, y)
                glowPath.moveTo(x, y)
                firstPoint = false
            } else {
                path.lineTo(x, y)
                glowPath.lineTo(x, y)
            }
        }

        // Draw glow effect first
        canvas.drawPath(glowPath, glowPaint)

        // Draw main waveform
        canvas.drawPath(path, waveformPaint)

        // Auto-add baseline points when animating
        if (isAnimating) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBeatTime > beatInterval * 0.8f) {
                // Add baseline points between beats
                dataPoints.add(0f)
                if (dataPoints.size > maxDataPoints) {
                    dataPoints.removeAt(0)
                }
            }
            postInvalidateDelayed(33) // ~30 FPS
        }
    }

    private fun drawGrid(canvas: Canvas, width: Float, height: Float) {
        // Vertical lines
        val vSpacing = width / 10
        for (i in 0..10) {
            val x = i * vSpacing
            canvas.drawLine(x, 0f, x, height, gridPaint)
        }

        // Horizontal lines
        val hSpacing = height / 6
        for (i in 0..6) {
            val y = i * hSpacing
            canvas.drawLine(0f, y, width, y, gridPaint)
        }
    }

    private fun drawIdleWaveform(canvas: Canvas, width: Float, height: Float) {
        val centerY = height / 2

        // Draw subtle animated baseline
        path.reset()
        path.moveTo(0f, centerY)

        for (i in 0..width.toInt() step 4) {
            val x = i.toFloat()
            val wave = sin((x + offset) * 0.02f) * 2f
            path.lineTo(x, centerY + wave)
        }

        canvas.drawPath(path, waveformPaint.apply { alpha = 100 })
        waveformPaint.alpha = 255

        offset += 2f
        if (offset > 1000f) offset = 0f

        postInvalidateDelayed(33)
    }

    fun clearData() {
        dataPoints.clear()
        invalidate()
    }
}
