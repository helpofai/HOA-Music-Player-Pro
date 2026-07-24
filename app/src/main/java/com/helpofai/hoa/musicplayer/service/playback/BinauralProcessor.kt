/*
 * Copyright (c) 2026 HOA Music Player Pro contributors.
 *
 * Licensed under the GNU General Public License v3
 */
package com.helpofai.hoa.musicplayer.service.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Professional Binaural Spatial Audio Processor — Real HRTF Model (v2.0)
 *
 * Uses a parametric spherical-head model for physically-plausible HRTF:
 *
 *   - ITD (Interaural Time Difference) via Woodworth formula
 *   - ILD (Interaural Level Difference) via frequency-dependent cross-over
 *     that shadows high frequencies more than low (realistic head diffraction)
 *   - Elevation via biquad notch filters that shift with elevation angle
 *   - Natural crossfeed for reduced headphone "in-head" localization
 *
 * This replaces the old StereoProcessor delay-matrix "8D" mode.
 */
@UnstableApi
class BinauralProcessor : AudioProcessor {

    /** Overall spatial strength 0–1. Controls wet/dry crossfade. */
    var spatialStrength: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f) }

    /** Azimuth in degrees (0 = front, 90 = right, 180 = rear, 270 = left). */
    var azimuth: Float = 0f
        set(value) { field = ((value % 360f) + 360f) % 360f }

    /** Elevation in degrees (-90 = below, 0 = ear level, +90 = above).
     *  Setting this recalculates the elevation notch filter coefficients. */
    var elevation: Float = 0f
        set(value) {
            field = value.coerceIn(-90f, 90f)
            if (sampleRate > 0) updateElevationFilters()
        }

    /** Natural crossfeed 0–0.5. Reduces "in-head" feel on headphones. */
    var crossfeed: Float = 0.15f
        set(value) { field = value.coerceIn(0f, 0.5f) }

    // ── Audio format state ───────────────────────────────────────────
    private var inputFormat: AudioFormat = AudioFormat.NOT_SET
    private var sampleRate = 44100
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    // ── ITD fractional delay line ────────────────────────────────────
    // Max ITD at 90° ≈ 0.66ms → at 48kHz that's ~32 samples. 64 is safe.
    private val delayLineL = FloatArray(64)
    private val delayLineR = FloatArray(64)
    private var delayWp = 0

    // ── ILD crossover (one-pole at ~1500Hz separates low/high bands) ─
    private var hpL = 0f; private var hpR = 0f
    private var alphaHp = 0f

    // ── Elevation notch 1 (biquad direct-form I) ─────────────────────
    private var n1_x1L = 0f; private var n1_x2L = 0f
    private var n1_y1L = 0f; private var n1_y2L = 0f
    private var n1_x1R = 0f; private var n1_x2R = 0f
    private var n1_y1R = 0f; private var n1_y2R = 0f
    private var n1_b0 = 0f; private var n1_b1 = 0f; private var n1_b2 = 0f
    private var n1_a1 = 0f; private var n1_a2 = 0f

    // ── Elevation notch 2 (second biquad for higher notch) ───────────
    private var n2_x1L = 0f; private var n2_x2L = 0f
    private var n2_y1L = 0f; private var n2_y2L = 0f
    private var n2_x1R = 0f; private var n2_x2R = 0f
    private var n2_y1R = 0f; private var n2_y2R = 0f
    private var n2_b0 = 0f; private var n2_b1 = 0f; private var n2_b2 = 0f
    private var n2_a1 = 0f; private var n2_a2 = 0f

    // ── Elevation blend ──────────────────────────────────────────────
    private var elevBlend = 0f

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 ||
            (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
             inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)) {
            this.inputFormat = inputAudioFormat; return inputAudioFormat
        }
        inputFormat = inputAudioFormat
        sampleRate = inputAudioFormat.sampleRate
        val fs = sampleRate.toFloat()

        // ILD crossover ~1500Hz
        alphaHp = 1f / (1f + 2f * PI.toFloat() * 1500f / fs)

        updateElevationFilters()
        return inputAudioFormat
    }

    private fun updateElevationFilters() {
        val fs = sampleRate.toFloat()
        val el = elevation
        val elevRad = abs(el) * PI.toFloat() / 180f

        // ── Notch 1: pinna notch — shifts with elevation ─────────
        //   front (0°):   ~8kHz,  above (90°):  ~10kHz,  behind: ~4kHz
        val notch1Freq = 4000f + 6000f * (1f - elevRad / (PI.toFloat() / 2f))
        val w1 = 2f * PI.toFloat() * notch1Freq / fs
        val alpha1 = sin(w1) / (2f * 2.5f)
        val A1 = 10f.pow(-6f / 40f)
        val norm1 = 1f + alpha1 / A1
        n1_b0 = (1f + alpha1 * A1) / norm1
        n1_b1 = (-2f * cos(w1)) / norm1
        n1_b2 = (1f - alpha1 * A1) / norm1
        n1_a1 = (-2f * cos(w1)) / norm1
        n1_a2 = (1f + alpha1 / A1) / norm1

        // ── Notch 2: second pinna resonance ──────────────────────
        //   Present mostly for above-ear signals (el > 30°)
        val notch2Freq = 10000f + 3000f * max(0f, el / 90f)
        val w2 = 2f * PI.toFloat() * notch2Freq / fs
        val alpha2 = sin(w2) / (2f * 3f)
        val A2 = 10f.pow(-4f / 40f)
        val norm2 = 1f + alpha2 / A2
        n2_b0 = (1f + alpha2 * A2) / norm2
        n2_b1 = (-2f * cos(w2)) / norm2
        n2_b2 = (1f - alpha2 * A2) / norm2
        n2_a1 = (-2f * cos(w2)) / norm2
        n2_a2 = (1f + alpha2 / A2) / norm2

        // Blend factor: elevation notches only matter above ~10°
        elevBlend = min(1f, max(0f, (abs(el) - 5f) / 30f))
    }

    override fun isActive(): Boolean {
        return inputFormat.channelCount == 2 && spatialStrength > 0f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val remaining = limit - position

        if (buffer.capacity() < remaining) {
            buffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }

        val is16Bit = inputFormat.encoding == C.ENCODING_PCM_16BIT
        val bytesPerSample = if (is16Bit) 2 else 4
        val str = spatialStrength

        // Precompute angle-dependent parameters (constant for this buffer)
        val azRad = azimuth * PI.toFloat() / 180f
        val earFactor = abs(sin(azRad))  // 0=front, 1=side

        // ILD gain per band
        val lowGainIpsi = 1f - 0.10f * earFactor      // low freqs: max -1dB
        val lowGainContra = 1f - 0.20f * earFactor     // low freqs contra: max -2dB
        val highGainIpsi = 1f - 0.15f * earFactor      // high freqs: max -1.5dB
        val highGainContra = 1f - 0.75f * earFactor    // high freqs contra: max -7.5dB

        // ITD: Woodworth formula (seconds)
        val rawItd = (0.0875f / 343f) * (sin(azRad) + azRad)
        val itdSamp = rawItd * sampleRate
        val itdInt = itdSamp.roundToInt().coerceIn(-60, 60)
        val itdFrac = itdSamp - itdInt

        var i = position
        while (i < limit) {
            var l: Float; var r: Float
            if (is16Bit) {
                l = inputBuffer.getShort(i).toFloat() / 32768f
                r = inputBuffer.getShort(i + bytesPerSample).toFloat() / 32768f
            } else {
                l = inputBuffer.getFloat(i)
                r = inputBuffer.getFloat(i + bytesPerSample)
            }

            // ── Step 1: Write to delay line ──────────────────────────
            delayLineL[delayWp] = l
            delayLineR[delayWp] = r

            // Read delayed sample for the ear that receives the ITD
            val readIdx = (delayWp - itdInt + 64) % 64
            val readPrev = (readIdx - 1 + 64) % 64

            // ITD_delayed ear gets the delayed version; the other ear gets direct
            val delayedL: Float
            val delayedR: Float
            if (itdInt >= 0) {
                // Sound is from left → right ear is delayed
                delayedL = l
                delayedR = delayLineR[readIdx] * (1f - abs(itdFrac)) +
                           delayLineR[readPrev] * abs(itdFrac)
            } else {
                // Sound is from right → left ear is delayed
                delayedL = delayLineL[readIdx] * (1f - abs(itdFrac)) +
                           delayLineL[readPrev] * abs(itdFrac)
                delayedR = r
            }
            delayWp = (delayWp + 1) % 64

            // ── Step 2: ILD — frequency-dependent head shadowing ─────
            // Split into low (~diffracting) and high (~shadowed) bands
            hpL += alphaHp * (delayedL - hpL)
            hpR += alphaHp * (delayedR - hpR)
            val lowL = delayedL - hpL
            val lowR = delayedR - hpR

            // Apply angle-dependent gain to each band
            val ildL = lowL * lowGainIpsi + hpL * highGainIpsi
            val ildR = lowR * lowGainContra + hpR * highGainContra

            // ── Step 3: Elevation spectral notches (zero allocation) ─
            var spatialL: Float; var spatialR: Float
            if (elevBlend > 0.01f) {
                // Notch 1 — inline biquad, no arrays
                val n1Ly = n1_b0 * ildL + n1_b1 * n1_x1L + n1_b2 * n1_x2L
                        - n1_a1 * n1_y1L - n1_a2 * n1_y2L
                n1_x2L = n1_x1L; n1_x1L = ildL; n1_y2L = n1_y1L; n1_y1L = n1Ly
                val n1Ry = n1_b0 * ildR + n1_b1 * n1_x1R + n1_b2 * n1_x2R
                        - n1_a1 * n1_y1R - n1_a2 * n1_y2R
                n1_x2R = n1_x1R; n1_x1R = ildR; n1_y2R = n1_y1R; n1_y1R = n1Ry

                val nb = elevBlend * 0.4f
                spatialL = ildL * (1f - nb) + n1Ly * nb
                spatialR = ildR * (1f - nb) + n1Ry * nb

                // Notch 2 (for high elevation)
                if (elevation > 15f) {
                    val n2Ly = n2_b0 * spatialL + n2_b1 * n2_x1L + n2_b2 * n2_x2L
                            - n2_a1 * n2_y1L - n2_a2 * n2_y2L
                    n2_x2L = n2_x1L; n2_x1L = spatialL; n2_y2L = n2_y1L; n2_y1L = n2Ly
                    val n2Ry = n2_b0 * spatialR + n2_b1 * n2_x1R + n2_b2 * n2_x2R
                            - n2_a1 * n2_y1R - n2_a2 * n2_y2R
                    n2_x2R = n2_x1R; n2_x1R = spatialR; n2_y2R = n2_y1R; n2_y1R = n2Ry

                    val n2b = elevBlend * 0.3f
                    spatialL = spatialL * (1f - n2b) + n2Ly * n2b
                    spatialR = spatialR * (1f - n2b) + n2Ry * n2b
                }
            } else {
                spatialL = ildL
                spatialR = ildR
            }

            // ── Step 4: Natural crossfeed ────────────────────────────
            val cf = crossfeed * str
            val cfL = spatialL + spatialR * cf * 0.12f
            val cfR = spatialR + spatialL * cf * 0.12f

            // ── Step 5: Wet/dry blend ────────────────────────────────
            val outL = (l * (1f - str) + cfL * str).coerceIn(-1.15f, 1.15f)
            val outR = (r * (1f - str) + cfR * str).coerceIn(-1.15f, 1.15f)

            if (is16Bit) {
                buffer.putShort((outL * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                buffer.putShort((outR * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            } else {
                buffer.putFloat(outL)
                buffer.putFloat(outR)
            }
            i += bytesPerSample * 2
        }

        inputBuffer.position(limit)
        buffer.flip()
        outputBuffer = buffer
    }

    override fun getOutput(): ByteBuffer {
        val output = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return output
    }

    override fun isEnded() = inputEnded && outputBuffer == AudioProcessor.EMPTY_BUFFER
    override fun queueEndOfStream() { inputEnded = true }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        delayLineL.fill(0f); delayLineR.fill(0f); delayWp = 0
        hpL = 0f; hpR = 0f
        n1_x1L = 0f; n1_x2L = 0f; n1_y1L = 0f; n1_y2L = 0f
        n1_x1R = 0f; n1_x2R = 0f; n1_y1R = 0f; n1_y2R = 0f
        n2_x1L = 0f; n2_x2L = 0f; n2_y1L = 0f; n2_y2L = 0f
        n2_x1R = 0f; n2_x2R = 0f; n2_y1R = 0f; n2_y2R = 0f
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        inputFormat = AudioFormat.NOT_SET
        sampleRate = 44100
    }
}
