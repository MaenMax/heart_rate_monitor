package com.maenmax.heartratemonitor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Generates and plays a realistic medical device heartbeat beep sound.
 * The sound is a pure sine wave with attack/decay envelope, similar to
 * hospital cardiac monitors.
 *
 * Uses AudioTrack in STREAM mode for reliable repeated playback.
 */
class HeartbeatSoundPlayer {

    private var beepSamples: ShortArray? = null
    private var completionBeepSamples: ShortArray? = null
    private val audioAttributes: AudioAttributes
    private val audioFormat: AudioFormat
    private var isPlaying = false

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val BEEP_FREQUENCY = 1000f  // 1kHz - classic medical monitor frequency
        private const val BEEP_DURATION_MS = 100  // 100ms beep duration
        private const val VOLUME = 0.8f  // 80% volume

        // Completion sound settings - higher pitch, longer, two-tone
        private const val COMPLETION_FREQ_1 = 1400f  // First tone - higher pitch
        private const val COMPLETION_FREQ_2 = 1800f  // Second tone - even higher
        private const val COMPLETION_TONE_DURATION_MS = 150  // Each tone duration
    }

    init {
        audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        generateBeepSound()
        generateCompletionSound()
    }

    /**
     * Generates a medical-grade beep sound with:
     * - 1kHz pure sine wave (standard medical monitor frequency)
     * - Quick attack (5ms)
     * - Gradual decay envelope for pleasant sound
     * - No harsh clicks at start/end
     */
    private fun generateBeepSound() {
        val numSamples = (SAMPLE_RATE * BEEP_DURATION_MS / 1000.0).toInt()
        beepSamples = ShortArray(numSamples)

        val attackSamples = (SAMPLE_RATE * 0.005).toInt()  // 5ms attack
        val decaySamples = (SAMPLE_RATE * 0.050).toInt()   // 50ms decay
        val sustainEnd = numSamples - decaySamples

        for (i in 0 until numSamples) {
            // Generate sine wave
            val time = i.toDouble() / SAMPLE_RATE
            val sineValue = sin(2.0 * PI * BEEP_FREQUENCY * time)

            // Apply envelope
            val envelope = when {
                // Attack phase - quick ramp up
                i < attackSamples -> {
                    i.toFloat() / attackSamples
                }
                // Sustain phase
                i < sustainEnd -> {
                    1.0f
                }
                // Decay phase - exponential decay for natural sound
                else -> {
                    val decayProgress = (i - sustainEnd).toFloat() / decaySamples
                    exp(-3.0 * decayProgress).toFloat()
                }
            }

            // Apply envelope and volume, convert to 16-bit PCM
            val sample = (sineValue * envelope * VOLUME * Short.MAX_VALUE).toInt()
            beepSamples!![i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /**
     * Generates a two-tone completion sound (beep-beep) at higher pitch
     * to signal measurement is complete.
     */
    private fun generateCompletionSound() {
        val samplesPerTone = (SAMPLE_RATE * COMPLETION_TONE_DURATION_MS / 1000.0).toInt()
        val gapSamples = (SAMPLE_RATE * 0.08).toInt()  // 80ms gap between tones
        val totalSamples = samplesPerTone * 2 + gapSamples

        completionBeepSamples = ShortArray(totalSamples)

        // Generate first tone (1400 Hz)
        for (i in 0 until samplesPerTone) {
            val time = i.toDouble() / SAMPLE_RATE
            val sineValue = sin(2.0 * PI * COMPLETION_FREQ_1 * time)
            val envelope = calculateEnvelope(i, samplesPerTone)
            val sample = (sineValue * envelope * VOLUME * Short.MAX_VALUE).toInt()
            completionBeepSamples!![i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        // Gap (silence)
        for (i in samplesPerTone until samplesPerTone + gapSamples) {
            completionBeepSamples!![i] = 0
        }

        // Generate second tone (1800 Hz) - higher pitch
        val secondToneStart = samplesPerTone + gapSamples
        for (i in 0 until samplesPerTone) {
            val time = i.toDouble() / SAMPLE_RATE
            val sineValue = sin(2.0 * PI * COMPLETION_FREQ_2 * time)
            val envelope = calculateEnvelope(i, samplesPerTone)
            val sample = (sineValue * envelope * VOLUME * Short.MAX_VALUE).toInt()
            completionBeepSamples!![secondToneStart + i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun calculateEnvelope(sampleIndex: Int, totalSamples: Int): Float {
        val attackSamples = (SAMPLE_RATE * 0.005).toInt()  // 5ms attack
        val decaySamples = (SAMPLE_RATE * 0.030).toInt()   // 30ms decay
        val sustainEnd = totalSamples - decaySamples

        return when {
            sampleIndex < attackSamples -> sampleIndex.toFloat() / attackSamples
            sampleIndex < sustainEnd -> 1.0f
            else -> {
                val decayProgress = (sampleIndex - sustainEnd).toFloat() / decaySamples
                exp(-3.0 * decayProgress).toFloat()
            }
        }
    }

    /**
     * Plays the heartbeat beep sound.
     * Creates a new AudioTrack each time for reliable playback.
     */
    fun playBeep() {
        if (isPlaying) return  // Prevent overlapping beeps

        val samples = beepSamples ?: return

        Thread {
            isPlaying = true
            var audioTrack: AudioTrack? = null
            try {
                val bufferSize = samples.size * 2  // 2 bytes per short

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                audioTrack.write(samples, 0, samples.size)
                audioTrack.play()

                // Wait for playback to complete
                Thread.sleep(BEEP_DURATION_MS.toLong() + 20)

                audioTrack.stop()
            } catch (e: Exception) {
                // Silently fail - sound is not critical
            } finally {
                try {
                    audioTrack?.release()
                } catch (e: Exception) {
                    // Ignore
                }
                isPlaying = false
            }
        }.start()
    }

    /**
     * Plays the completion beep sound (two-tone, higher pitch).
     * Call this when measurement is complete.
     */
    fun playCompletionBeep() {
        val samples = completionBeepSamples ?: return

        Thread {
            // Wait for any current beep to finish
            while (isPlaying) {
                Thread.sleep(10)
            }

            isPlaying = true
            var audioTrack: AudioTrack? = null
            try {
                val bufferSize = samples.size * 2

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                audioTrack.write(samples, 0, samples.size)
                audioTrack.play()

                // Wait for playback to complete
                val totalDuration = COMPLETION_TONE_DURATION_MS * 2 + 80 + 50  // Two tones + gap + buffer
                Thread.sleep(totalDuration.toLong())

                audioTrack.stop()
            } catch (e: Exception) {
                // Silently fail
            } finally {
                try {
                    audioTrack?.release()
                } catch (e: Exception) {
                    // Ignore
                }
                isPlaying = false
            }
        }.start()
    }

    /**
     * Releases audio resources. Call when done with the player.
     */
    fun release() {
        beepSamples = null
        completionBeepSamples = null
    }
}
