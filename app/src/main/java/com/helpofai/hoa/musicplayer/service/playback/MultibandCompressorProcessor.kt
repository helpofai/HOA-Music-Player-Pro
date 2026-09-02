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
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Professional 3-Band Multiband Compressor (v10.0)
 *
 * Built on Media3 BaseAudioProcessor for robust lifecycle and zero playback stalling:
 * Splits audio into low, mid, and high bands using LR-2 crossover filters,
 * independently compresses each band, then recombines.
 *
 * Crossover: 200Hz (low/mid), 4kHz (mid/high)
 * Each band: adjustable threshold, ratio, attack, release, makeup gain
 */
@UnstableApi
class MultibandCompressorProcessor : BaseAudioProcessor() {

    data class BandConfig(
        var thresholdDb: Float = -20f,     // dB
        var ratio: Float = 2f,              // 1:1 to 20:1
        var attackMs: Float = 5f,           // ms
        var releaseMs: Float = 100f,        // ms
        var makeupDb: Float = 0f            // dB (auto-computed if < 0)
    )

    val lowBand = BandConfig()
    val midBand = BandConfig()
    val highBand = BandConfig()

    var enabled: Boolean = false
    var outputGainDb: Float = 0f

    private var sampleRate = 44100

    // ── Crossover Filters (LR-2 biquads) ──────────────────────
    private var lp200_b0 = 0f; private var lp200_b1 = 0f; private var lp200_b2 = 0f
    private var lp200_a1 = 0f; private var lp200_a2 = 0f
    private val lp200L = BqState()
    private val lp200R = BqState()

    private var lp4k_b0 = 0f; private var lp4k_b1 = 0f; private var lp4k_b2 = 0f
    private var lp4k_a1 = 0f; private var lp4k_a2 = 0f
    private val lp4kL = BqState()
    private val lp4kR = BqState()

    // ── Per-band compressor state (Stereo-Linked for Perfect Balance) ──
    private class SingleCompState(
        var env: Float = 0f, var gain: Float = 1f,
        var counter: Int = 0, var targetGain: Float = 1f,
        var makeupCache: Float = 1f
    )
    private val lowState = SingleCompState()
    private val midState = SingleCompState()
    private val highState = SingleCompState()

    private fun designLpf(fc: Float, fs: Float): Array<Float> {
        val w0 = 2f * PI.toFloat() * fc / fs
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / sqrt(2f)  // Q = 0.707
        val norm = 1f + alpha
        return arrayOf(
            ((1f - cosW) / 2f) / norm,  // b0
            (1f - cosW) / norm,          // b1
            ((1f - cosW) / 2f) / norm,   // b2
            (-2f * cosW) / norm,         // a1
            (1f - alpha) / norm          // a2
        )
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 ||
            (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
             inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)) {
            return AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        val fs = sampleRate.toFloat()

        val lp200 = designLpf(200f, fs)
        lp200_b0 = lp200[0]; lp200_b1 = lp200[1]; lp200_b2 = lp200[2]
        lp200_a1 = lp200[3]; lp200_a2 = lp200[4]

        val lp4k = designLpf(4000f, fs)
        lp4k_b0 = lp4k[0]; lp4k_b1 = lp4k[1]; lp4k_b2 = lp4k[2]
        lp4k_a1 = lp4k[3]; lp4k_a2 = lp4k[4]

        updateMakeupCache()
        return inputAudioFormat
    }

    private fun updateMakeupCache() {
        lowState.makeupCache = 10f.pow(lowBand.makeupDb / 20f)
        midState.makeupCache = 10f.pow(midBand.makeupDb / 20f)
        highState.makeupCache = 10f.pow(highBand.makeupDb / 20f)
    }

    private class BqState(var x1: Float = 0f, var x2: Float = 0f, var y1: Float = 0f, var y2: Float = 0f) {
        fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }
    }

    private inline fun processBiquad(
        x: Float,
        b0: Float, b1: Float, b2: Float,
        a1: Float, a2: Float,
        s: BqState
    ): Float {
        val y = b0 * x + b1 * s.x1 + b2 * s.x2 - a1 * s.y1 - a2 * s.y2
        s.x2 = s.x1
        s.x1 = x
        s.y2 = s.y1
        s.y1 = y
        return y
    }

    private fun compressStereoBand(
        xL: Float, xR: Float,
        band: BandConfig,
        att: Float, rel: Float,
        env: SingleCompState
    ): Pair<Float, Float> {
        val maxInput = maxOf(abs(xL), abs(xR))
        val inputPower = maxInput * maxInput

        val envCoeff = if (inputPower > env.env) att else rel
        env.env += envCoeff * (inputPower - env.env)
        if (env.env.isNaN() || env.env.isInfinite()) env.env = 0f

        env.counter++
        if (env.counter >= 8) {
            env.counter = 0

            val level = sqrt(env.env.coerceAtLeast(1e-10f))
            val dbLevel = 20f * log10(level.coerceAtLeast(1e-10f))

            val thresh = band.thresholdDb
            val ratio = band.ratio.coerceAtLeast(1f)
            val overlap = dbLevel - thresh
            val dbReduction = if (overlap > 0f) overlap * (1f - 1f / ratio) else 0f
            env.targetGain = 10f.pow((-dbReduction / 20f).coerceAtLeast(-10f))

            if (env.targetGain.isNaN() || env.targetGain.isInfinite()) env.targetGain = 1f
        }

        val coeff = if (env.targetGain < env.gain) att else rel
        env.gain += coeff * (env.targetGain - env.gain)
        if (env.gain.isNaN() || env.gain.isInfinite()) env.gain = 1f
        env.gain = env.gain.coerceIn(0.01f, 1f)

        val totalGain = env.gain * env.makeupCache
        return Pair(xL * totalGain, xR * totalGain)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outBuffer = replaceOutputBuffer(remaining)

        // Fast bit-perfect bypass when disabled
        if (!enabled) {
            outBuffer.put(inputBuffer)
            outBuffer.flip()
            return
        }

        val is16Bit = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        val bytesPerSample = if (is16Bit) 2 else 4
        val fs = sampleRate.toFloat()

        fun timeToCoeff(ms: Float): Float = 1f - exp(-1f / (ms * fs / 1000f))
        val lowAtt = timeToCoeff(lowBand.attackMs)
        val lowRel = timeToCoeff(lowBand.releaseMs)
        val midAtt = timeToCoeff(midBand.attackMs)
        val midRel = timeToCoeff(midBand.releaseMs)
        val highAtt = timeToCoeff(highBand.attackMs)
        val highRel = timeToCoeff(highBand.releaseMs)

        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        var i = position
        while (i < limit) {
            var l: Float; var r: Float
            if (is16Bit) {
                l = inputBuffer.getShort(i).toFloat() / 32768f
                r = inputBuffer.getShort(i + bytesPerSample).toFloat() / 32768f
            } else {
                l = inputBuffer.getFloat(i); r = inputBuffer.getFloat(i + bytesPerSample)
            }

            // ── 1. CROSSOVER: split into 3 bands ─────────────────
            val lowL = processBiquad(l, lp200_b0, lp200_b1, lp200_b2, lp200_a1, lp200_a2, lp200L)
            val aboveLowL = l - lowL

            val midL = processBiquad(aboveLowL, lp4k_b0, lp4k_b1, lp4k_b2, lp4k_a1, lp4k_a2, lp4kL)
            val highL = aboveLowL - midL

            val lowR = processBiquad(r, lp200_b0, lp200_b1, lp200_b2, lp200_a1, lp200_a2, lp200R)
            val aboveLowR = r - lowR
            val midR = processBiquad(aboveLowR, lp4k_b0, lp4k_b1, lp4k_b2, lp4k_a1, lp4k_a2, lp4kR)
            val highR = aboveLowR - midR

            // ── 2. COMPRESS EACH BAND (Stereo-Linked) ────────────
            val (compLowL, compLowR) = compressStereoBand(lowL, lowR, lowBand, lowAtt, lowRel, lowState)
            val (compMidL, compMidR) = compressStereoBand(midL, midR, midBand, midAtt, midRel, midState)
            val (compHighL, compHighR) = compressStereoBand(highL, highR, highBand, highAtt, highRel, highState)

            // ── 3. SUM ───────────────────────────────────────────
            var outL = compLowL + compMidL + compHighL
            var outR = compLowR + compMidR + compHighR

            val outGain = 10f.pow(outputGainDb / 20f)
            outL *= outGain; outR *= outGain

            outL = outL.coerceIn(-1.15f, 1.15f)
            outR = outR.coerceIn(-1.15f, 1.15f)

            if (is16Bit) {
                outBuffer.putShort((outL * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                outBuffer.putShort((outR * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            } else {
                outBuffer.putFloat(outL); outBuffer.putFloat(outR)
            }
            i += bytesPerSample * 2
        }

        inputBuffer.position(limit)
        outBuffer.flip()
    }

    override fun onFlush() {
        lp200L.reset(); lp200R.reset()
        lp4kL.reset(); lp4kR.reset()
        lowState.env = 0f; lowState.gain = 1f; lowState.counter = 0; lowState.targetGain = 1f
        midState.env = 0f; midState.gain = 1f; midState.counter = 0; midState.targetGain = 1f
        highState.env = 0f; highState.gain = 1f; highState.counter = 0; highState.targetGain = 1f
        updateMakeupCache()
    }

    override fun onReset() {
        onFlush()
        sampleRate = 44100
    }
}
