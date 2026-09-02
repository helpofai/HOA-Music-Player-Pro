/*
 * Copyright (c) 2026 HOA Music Player Pro contributors.
 *
 * Licensed under the GNU General Public License v3
 */
package com.helpofai.hoa.musicplayer.service.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Mastering-Grade Apex Kinetic Bass Engine (v10.2)
 *
 * Built on Media3 BaseAudioProcessor for robust lifecycle and zero playback stalling:
 * 1. 8th-Order Linkwitz-Riley (48 dB/oct) Zero-Leakage Sub-Band Isolation (<110Hz).
 * 2. Seismic Earth-Wave Kinetic Resonator (<45Hz).
 * 3. Warm Harmonic Sub-Synthesis with soft-clipping tape drive.
 * 4. Stereo-Linked Dynamic Transient Punch (Kick Drums & 808s).
 * 5. C1-Continuous Soft-Knee Mastering Limiter.
 *
 * Slider curve: uses sqrt(strength) so 50% slider ≈ old 100% quality,
 * and 100% slider delivers noticeably stronger, deeper bass.
 */
@UnstableApi
class BassBoostProcessor : BaseAudioProcessor() {
    var strength: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f) }

    // ── 8th-Order Linkwitz-Riley Filter Coefficients (4 cascaded 2nd-order Butterworth biquads) ──
    private val b0 = FloatArray(4)
    private val b1 = FloatArray(4)
    private val b2 = FloatArray(4)
    private val a1 = FloatArray(4)
    private val a2 = FloatArray(4)

    // Filter states for Left and Right channels
    private val x1L = FloatArray(4); private val x2L = FloatArray(4)
    private val y1L = FloatArray(4); private val y2L = FloatArray(4)

    private val x1R = FloatArray(4); private val x2R = FloatArray(4)
    private val y1R = FloatArray(4); private val y2R = FloatArray(4)

    // Seismic Earth Wave (<45Hz) single-pole integrator
    private var earthL = 0f
    private var earthR = 0f
    private var alphaEarth = 0f

    // Dynamic Transient Punch Tracker (Stereo-Linked)
    private var envMaster = 0f
    private var peakMaster = 0f
    private var peakHoldCounter = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 ||
            (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
             inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)) {
            return AudioFormat.NOT_SET
        }

        val sampleRate = inputAudioFormat.sampleRate
        val cutoff = 110.0
        val fs = sampleRate.toDouble()
        val omega = 2.0 * PI * cutoff / fs
        val sn = sin(omega)
        val cs = cos(omega)

        // 8th-order LR = 4 cascaded identical 2nd-order Butterworth LPFs with Q = 0.7071
        val q = 0.70710678
        val alpha = sn / (2.0 * q)

        val b0_val = ((1.0 - cs) / 2.0).toFloat()
        val b1_val = (1.0 - cs).toFloat()
        val b2_val = ((1.0 - cs) / 2.0).toFloat()
        val a0_val = (1.0 + alpha).toFloat()
        val a1_val = (-2.0 * cs).toFloat()
        val a2_val = (1.0 - alpha).toFloat()

        for (i in 0 until 4) {
            b0[i] = b0_val / a0_val
            b1[i] = b1_val / a0_val
            b2[i] = b2_val / a0_val
            a1[i] = a1_val / a0_val
            a2[i] = a2_val / a0_val
        }

        // Earth wave one-pole filter at 45Hz
        alphaEarth = (2.0 * PI * 45.0 / fs).toFloat().coerceIn(0.001f, 0.5f)

        return inputAudioFormat
    }

    private inline fun processBiquad(
        stage: Int, x: Float,
        x1: FloatArray, x2: FloatArray,
        y1: FloatArray, y2: FloatArray
    ): Float {
        val y = b0[stage] * x + b1[stage] * x1[stage] + b2[stage] * x2[stage] - a1[stage] * y1[stage] - a2[stage] * y2[stage]
        x2[stage] = x1[stage]
        x1[stage] = x
        y2[stage] = y1[stage]
        y1[stage] = y
        return y
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outBuffer = replaceOutputBuffer(remaining)

        // Fast bit-perfect bypass when disabled
        if (strength <= 0f) {
            outBuffer.put(inputBuffer)
            outBuffer.flip()
            return
        }

        val is16Bit = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        val bytesPerSample = if (is16Bit) 2 else 4

        // ── NON-LINEAR SLIDER CURVE ─────────────────────────────────────
        // sqrt curve: slider 50% → str=0.707 (≈old 100% quality)
        //             slider 100% → str=1.0 (new maximum, deeper & harder)
        val str = sqrt(strength)

        val fs = inputAudioFormat.sampleRate.toFloat()

        val dt = 1f / fs
        val att = (dt / (0.005f + dt)).coerceIn(0f, 1f)   // 5ms attack
        val rel = (dt / (0.150f + dt)).coerceIn(0f, 1f)   // 150ms release
        val peakHoldSamples = (fs * 0.015f).toInt().coerceAtLeast(1) // 15ms peak hold

        // Headroom: slightly tighter scale to keep vocals clear at 100%
        val headroomScale = 1f / (1f + (str * 0.22f))

        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        var i = position
        while (i < limit) {
            val l: Float
            val r: Float
            if (is16Bit) {
                l = inputBuffer.getShort(i).toFloat() / 32768f
                r = inputBuffer.getShort(i + bytesPerSample).toFloat() / 32768f
            } else {
                l = inputBuffer.getFloat(i)
                r = inputBuffer.getFloat(i + bytesPerSample)
            }

            // ── 1. LR-8 SURGICAL SUB ISOLATION ────────────────────────
            var subL = l
            var subR = r
            for (stage in 0 until 4) {
                subL = processBiquad(stage, subL, x1L, x2L, y1L, y2L)
                subR = processBiquad(stage, subR, x1R, x2R, y1R, y2R)
            }

            // ── 2. SEISMIC EARTH WAVE (<45Hz) ─────────────────────────
            earthL += alphaEarth * (subL - earthL)
            earthR += alphaEarth * (subR - earthR)
            val physicalWaveL = earthL * (str * 1.6f)
            val physicalWaveR = earthR * (str * 1.6f)

            // ── 3. WARM HARMONIC SUB SYNTHESIS ────────────────────────
            val subDrive = 1f + (str * 1.8f)
            val thickSubL = tanh((subL * subDrive) + physicalWaveL) * (str * 1.35f)
            val thickSubR = tanh((subR * subDrive) + physicalWaveR) * (str * 1.35f)

            // ── 4. DYNAMIC TRANSIENT PUNCH (KICK DRUMS & 808s) ────────
            val maxSub = maxOf(abs(subL), abs(subR))

            if (maxSub > peakMaster || peakHoldCounter > peakHoldSamples) {
                peakMaster = if (maxSub > peakMaster) maxSub else peakMaster * 0.995f
                peakHoldCounter = 0
            } else {
                peakHoldCounter++
            }

            envMaster += (if (maxSub > envMaster) att else rel) * (maxSub - envMaster)
            val punch = (peakMaster - envMaster).coerceAtLeast(0f) * (str * 1.8f)

            // ── 5. COHERENT GAIN-STAGED SUMMATION ──────────────────────
            var outL = (l + thickSubL + (subL * punch)) * headroomScale
            var outR = (r + thickSubR + (subR * punch)) * headroomScale

            // ── 6. C1-CONTINUOUS SOFT-KNEE MASTERING LIMITER ───────────
            outL = softMasterLimiter(outL)
            outR = softMasterLimiter(outR)

            if (is16Bit) {
                outBuffer.putShort((outL * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                outBuffer.putShort((outR * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            } else {
                outBuffer.putFloat(outL)
                outBuffer.putFloat(outR)
            }
            i += bytesPerSample * 2
        }

        inputBuffer.position(limit)
        outBuffer.flip()
    }

    private fun softMasterLimiter(x: Float): Float {
        val threshold = 0.85f
        val absX = abs(x)
        if (absX <= threshold) {
            return x
        }
        val sign = if (x >= 0f) 1f else -1f
        val excess = absX - threshold
        val margin = 1f - threshold
        val compressed = threshold + margin * tanh(excess / margin)
        return sign * compressed.coerceIn(-1f, 1f)
    }

    override fun onFlush() {
        for (i in 0 until 4) {
            x1L[i] = 0f; x2L[i] = 0f; y1L[i] = 0f; y2L[i] = 0f
            x1R[i] = 0f; x2R[i] = 0f; y1R[i] = 0f; y2R[i] = 0f
        }
        earthL = 0f; earthR = 0f
        envMaster = 0f; peakMaster = 0f; peakHoldCounter = 0
    }

    override fun onReset() {
        onFlush()
    }
}
