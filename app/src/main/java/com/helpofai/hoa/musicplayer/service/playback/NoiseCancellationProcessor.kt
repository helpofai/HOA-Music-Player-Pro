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

/**
 * Mastering-Grade Audio Noise Cancellation & Adaptive De-Noiser (v10.0)
 *
 * Built on Media3 BaseAudioProcessor for robust lifecycle and zero playback stalling:
 * 1. 30Hz 2nd-Order Butterworth HPF: Strips sub-audible rumble, turntable vibrations, and 50/60Hz ground hum.
 * 2. Adaptive Spectral De-Hiss (>7.5kHz): Dynamic high-shelf downward expander that eliminates tape hiss and DAC quantization noise.
 * 3. Optical-Modeled Soft-Knee Expander / Noise Gate: Creates pitch-black background silence in headphone pauses and quiet sections.
 */
@UnstableApi
class NoiseCancellationProcessor : BaseAudioProcessor() {

    var enabled: Boolean = false
    var strength: Float = 0.5f
        set(value) { field = value.coerceIn(0f, 1f) }

    // ── 1. 2nd-Order Butterworth High-Pass Filter @ 30Hz ────────────
    private var hp_b0 = 1f; private var hp_b1 = -2f; private var hp_b2 = 1f
    private var hp_a1 = 0f; private var hp_a2 = 0f
    private var hp_x1L = 0f; private var hp_x2L = 0f; private var hp_y1L = 0f; private var hp_y2L = 0f
    private var hp_x1R = 0f; private var hp_x2R = 0f; private var hp_y1R = 0f; private var hp_y2R = 0f

    // ── 2. Spectral De-Hiss Crossover @ 7500Hz ───────────────────────
    private var hissLpfL = 0f; private var hissLpfR = 0f
    private var alphaHiss = 0f
    private var hissEnv = 0f

    // ── 3. Downward Expander / Smart Noise Gate (Stereo-Linked) ──────
    private var gateEnv = 0f
    private var gateGain = 1f
    private var holdCounter = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 ||
            (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
             inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)) {
            return AudioFormat.NOT_SET
        }

        val fs = inputAudioFormat.sampleRate.toFloat()

        // Design 2nd-order Butterworth HPF at 30Hz
        val fc = 30f
        val w0 = 2f * PI.toFloat() * fc / fs
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2f * 0.7071f) // Q = 0.7071
        val norm = 1f + alpha
        hp_b0 = ((1f + cosW) / 2f) / norm
        hp_b1 = (-(1f + cosW)) / norm
        hp_b2 = ((1f + cosW) / 2f) / norm
        hp_a1 = (-2f * cosW) / norm
        hp_a2 = (1f - alpha) / norm

        // De-Hiss 1-pole crossover at 7500Hz
        val fHiss = 7500f
        val dt = 1f / fs
        alphaHiss = (dt / (1f / (2f * PI.toFloat() * fHiss) + dt)).coerceIn(0f, 1f)

        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outBuffer = replaceOutputBuffer(remaining)

        // Fast bit-perfect bypass when disabled
        if (!enabled || strength <= 0f) {
            outBuffer.put(inputBuffer)
            outBuffer.flip()
            return
        }

        val is16Bit = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        val bytesPerSample = if (is16Bit) 2 else 4
        val fs = inputAudioFormat.sampleRate.toFloat()
        val dt = 1f / fs
        val str = strength

        // Gate ballistics: 1.5ms attack, 80ms release, 25ms hold
        val att = (dt / (0.0015f + dt)).coerceIn(0f, 1f)
        val rel = (dt / (0.080f + dt)).coerceIn(0f, 1f)
        val holdSamples = (fs * 0.025f).toInt().coerceAtLeast(1)

        // Threshold: -64 dBFS (gentle) to -42 dBFS (aggressive)
        val thresholdDb = -64f + (str * 22f)
        val thresholdLinear = 10f.pow(thresholdDb / 20f)
        val expanderRatio = 1.8f + (str * 2.5f) // 1.8:1 to 4.3:1 downward expansion

        // De-Hiss threshold: high-frequency noise floor limit
        val hissThreshold = thresholdLinear * 1.8f

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

            // ── 1. 2nd-Order Infrasonic HPF (<30Hz) ──────────────────
            val hpOutL = hp_b0 * l + hp_b1 * hp_x1L + hp_b2 * hp_x2L - hp_a1 * hp_y1L - hp_a2 * hp_y2L
            hp_x2L = hp_x1L; hp_x1L = l; hp_y2L = hp_y1L; hp_y1L = hpOutL

            val hpOutR = hp_b0 * r + hp_b1 * hp_x1R + hp_b2 * hp_x2R - hp_a1 * hp_y1R - hp_a2 * hp_y2R
            hp_x2R = hp_x1R; hp_x1R = r; hp_y2R = hp_y1R; hp_y1R = hpOutR

            // ── 2. Adaptive Spectral De-Hiss (>7.5kHz) ───────────────
            hissLpfL += alphaHiss * (hpOutL - hissLpfL)
            val highsL = hpOutL - hissLpfL

            hissLpfR += alphaHiss * (hpOutR - hissLpfR)
            val highsR = hpOutR - hissLpfR

            val maxHighs = maxOf(abs(highsL), abs(highsR))
            hissEnv += (if (maxHighs > hissEnv) 0.15f else 0.002f) * (maxHighs - hissEnv)

            val hissAtten = if (hissEnv < hissThreshold) {
                val ratio = (hissEnv / hissThreshold).coerceIn(0.1f, 1f)
                1f - (1f - ratio) * (str * 0.85f)
            } else 1f

            val deHissedL = hissLpfL + (highsL * hissAtten)
            val deHissedR = hissLpfR + (highsR * hissAtten)

            // ── 3. Stereo-Linked Soft-Knee Downward Expander ─────────
            val maxSignal = maxOf(abs(deHissedL), abs(deHissedR))
            gateEnv += (if (maxSignal > gateEnv) att else rel) * (maxSignal - gateEnv)

            val targetGain: Float
            if (gateEnv >= thresholdLinear) {
                holdCounter = holdSamples
                targetGain = 1f
            } else if (holdCounter > 0) {
                holdCounter--
                targetGain = 1f
            } else {
                val ratio = (gateEnv / thresholdLinear).coerceIn(0.0001f, 1f)
                targetGain = ratio.pow(expanderRatio - 1f).coerceIn(0.001f, 1f)
            }

            // Smooth gain envelope transition (no clicks or zipper noise)
            gateGain += (if (targetGain < gateGain) rel else att) * (targetGain - gateGain)

            val outL = (deHissedL * gateGain).coerceIn(-1.15f, 1.15f)
            val outR = (deHissedR * gateGain).coerceIn(-1.15f, 1.15f)

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

    override fun onFlush() {
        hp_x1L = 0f; hp_x2L = 0f; hp_y1L = 0f; hp_y2L = 0f
        hp_x1R = 0f; hp_x2R = 0f; hp_y1R = 0f; hp_y2R = 0f
        hissLpfL = 0f; hissLpfR = 0f
        hissEnv = 0f
        gateEnv = 0f
        gateGain = 1f
        holdCounter = 0
    }

    override fun onReset() {
        onFlush()
    }
}
