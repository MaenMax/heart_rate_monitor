package com.maenmax.heartratemonitor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * ECG Waveform visualization that is PURELY driven by onHeartbeat() calls.
 * No internal timers - the waveform only spikes when a beat is detected.
 */
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

    // Data points for the waveform (values from -1 to 1, 0 is baseline)
    private val dataPoints = mutableListOf<Float>()
    private val maxDataPoints = 150  // Points visible on screen

    // Animation state
    private var isAnimating = false
    private var scrollPosition = 0

    // ECG beat pattern (QRS complex + T wave)
    // Values represent vertical displacement (-1 to 1)
    private val ecgBeatPattern = listOf(
        0f,                          // baseline
        0.05f, 0.08f, 0.05f,        // small P wave
        0f, 0f,                      // PR segment
        -0.15f,                      // Q dip
        1.0f,                        // R peak (main spike!)
        -0.4f,                       // S dip
        0f, 0f, 0f,                  // ST segment
        0.2f, 0.25f, 0.2f, 0.1f,    // T wave
        0f, 0f, 0f                   // return to baseline
    )

    // How many baseline points to add between beats (controls scroll speed)
    private val baselinePointsPerFrame = 2

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // Required for blur effect
    }

    fun startAnimation() {
        isAnimating = true
        // Initialize with baseline
        dataPoints.clear()
        for (i in 0 until maxDataPoints) {
            dataPoints.add(0f)
        }
        scheduleNextFrame()
    }

    fun stopAnimation() {
        isAnimating = false
    }

    fun clearData() {
        dataPoints.clear()
        scrollPosition = 0
        invalidate()
    }

    /**
     * Call this when a heartbeat is detected.
     * This adds the ECG spike pattern to the waveform.
     */
    fun onHeartbeat() {
        if (!isAnimating) return

        // Add ECG beat pattern
        for (point in ecgBeatPattern) {
            dataPoints.add(point)
        }

        // Trim old data
        while (dataPoints.size > maxDataPoints) {
            dataPoints.removeAt(0)
        }

        invalidate()
    }

    private fun scheduleNextFrame() {
        if (!isAnimating) return

        postDelayed({
            if (isAnimating) {
                // Add baseline points to keep waveform scrolling
                repeat(baselinePointsPerFrame) {
                    dataPoints.add(0f)
                    if (dataPoints.size > maxDataPoints) {
                        dataPoints.removeAt(0)
                    }
                }
                invalidate()
                scheduleNextFrame()
            }
        }, 33) // ~30 FPS
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2
        val amplitude = h * 0.35f  // How tall the spikes are

        // Draw grid
        drawGrid(canvas, w, h)

        if (dataPoints.isEmpty() || !isAnimating) {
            // Draw flat baseline when not animating
            canvas.drawLine(0f, centerY, w, centerY, waveformPaint.apply { alpha = 100 })
            waveformPaint.alpha = 255
            return
        }

        // Build waveform path from data points
        path.reset()
        glowPath.reset()

        val pointSpacing = w / maxDataPoints

        for (i in dataPoints.indices) {
            val x = i * pointSpacing
            val y = centerY - (dataPoints[i] * amplitude)

            if (i == 0) {
                path.moveTo(x, y)
                glowPath.moveTo(x, y)
            } else {
                path.lineTo(x, y)
                glowPath.lineTo(x, y)
            }
        }

        // Draw glow effect first (behind)
        canvas.drawPath(glowPath, glowPaint)

        // Draw main waveform
        canvas.drawPath(path, waveformPaint)
    }

    private fun drawGrid(canvas: Canvas, width: Float, height: Float) {
        // Vertical lines
        val vSpacing = width / 10
        for (i in 0..10) {
            val x = i * vSpacing
            canvas.drawLine(x, 0f, x, height, gridPaint)
        }

        // Horizontal lines
        val hSpacing = height / 4
        for (i in 0..4) {
            val y = i * hSpacing
            canvas.drawLine(0f, y, width, y, gridPaint)
        }
    }
}
