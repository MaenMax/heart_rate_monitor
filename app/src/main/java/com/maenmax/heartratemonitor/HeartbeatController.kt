package com.maenmax.heartratemonitor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Unified heartbeat controller that synchronizes sound, animation, and ECG
 * to fire at EXACTLY the same moment.
 *
 * All three effects are triggered in a single atomic operation on the UI thread.
 */
class HeartbeatController(
    private val heartIcon: TextView,
    private val ecgView: ECGWaveformView
) {
    private val uiHandler = Handler(Looper.getMainLooper())

    // Sound - pre-generated and ready to play instantly
    private var audioTrack: AudioTrack? = null
    private var soundSamples: ShortArray? = null
    private var isSoundReady = false

    // State
    var isMuted = false
    private var isAnimating = false

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val BEEP_FREQ = 1000f
        private const val BEEP_DURATION_MS = 100
        private const val VOLUME = 0.8f
    }

    init {
        prepareSound()
    }

    /**
     * Pre-generate sound samples so playback is instant
     */
    private fun prepareSound() {
        val numSamples = (SAMPLE_RATE * BEEP_DURATION_MS / 1000.0).toInt()
        soundSamples = ShortArray(numSamples)

        val attackSamples = (SAMPLE_RATE * 0.005).toInt()
        val decaySamples = (SAMPLE_RATE * 0.050).toInt()
        val sustainEnd = numSamples - decaySamples

        for (i in 0 until numSamples) {
            val time = i.toDouble() / SAMPLE_RATE
            val sineValue = sin(2.0 * PI * BEEP_FREQ * time)

            val envelope = when {
                i < attackSamples -> i.toFloat() / attackSamples
                i < sustainEnd -> 1.0f
                else -> exp(-3.0 * ((i - sustainEnd).toFloat() / decaySamples)).toFloat()
            }

            val sample = (sineValue * envelope * VOLUME * Short.MAX_VALUE).toInt()
            soundSamples!![i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        createAudioTrack()
    }

    private fun createAudioTrack() {
        try {
            val samples = soundSamples ?: return

            audioTrack?.release()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)  // Lower latency
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            audioTrack?.write(samples, 0, samples.size)
            isSoundReady = true
        } catch (e: Exception) {
            isSoundReady = false
        }
    }

    /**
     * TRIGGER A SINGLE HEARTBEAT - All three effects fire NOW.
     * Must be called from UI thread.
     */
    fun triggerBeat() {
        if (!isAnimating) return

        // === ALL THREE HAPPEN RIGHT HERE, RIGHT NOW ===

        // 1. SOUND - Play immediately (already loaded in static mode)
        if (!isMuted && isSoundReady) {
            audioTrack?.let { track ->
                try {
                    track.stop()
                    track.reloadStaticData()
                    track.play()
                } catch (e: Exception) {
                    // Recreate if failed
                    createAudioTrack()
                }
            }
        }

        // 2. HEART ANIMATION - Direct property manipulation (no animator delay)
        pulseHeartDirect()

        // 3. ECG SPIKE - Direct call
        ecgView.onHeartbeat()
    }

    /**
     * Direct heart pulse without using ValueAnimator (which has scheduling delay)
     */
    private fun pulseHeartDirect() {
        // Immediately scale up
        heartIcon.scaleX = 1.25f
        heartIcon.scaleY = 1.25f
        heartIcon.setTextColor(0xFF8B0000.toInt())  // Dark red

        // Schedule scale down after 100ms
        uiHandler.postDelayed({
            heartIcon.scaleX = 1.1f
            heartIcon.scaleY = 1.1f
            heartIcon.setTextColor(0xFFFF5555.toInt())  // Back to normal red
        }, 100)

        // Final return to normal
        uiHandler.postDelayed({
            heartIcon.scaleX = 1.0f
            heartIcon.scaleY = 1.0f
        }, 200)
    }

    /**
     * Start accepting beats
     */
    fun start() {
        isAnimating = true
    }

    /**
     * Stop accepting beats
     */
    fun stop() {
        isAnimating = false
        // Reset heart icon
        heartIcon.scaleX = 1.0f
        heartIcon.scaleY = 1.0f
    }

    /**
     * Release resources
     */
    fun release() {
        isAnimating = false
        audioTrack?.release()
        audioTrack = null
        soundSamples = null
    }
}
