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
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Professional 3-Band Multiband Compressor
 *
 * Splits audio into low, mid, and high bands using LR-2 crossover filters,
 * independently compresses each band, then recombines.
 *
 * Crossover: 200Hz (low/mid), 4kHz (mid/high)
 * Each band: adjustable threshold, ratio, attack, release, makeup gain
 */
@UnstableApi
class MultibandCompressorProcessor : AudioProcessor {

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

    private var inputFormat: AudioFormat = AudioFormat.NOT_SET
    private var sampleRate = 44100
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    // ── Crossover Filters (LR-2 biquads) ──────────────────────
    // Low: LPF at 200Hz
    // Mid: HPF at 200Hz + LPF at 4kHz
    // High: HPF at 4kHz
    private var lp200_b0 = 0f; private var lp200_b1 = 0f; private var lp200_b2 = 0f
    private var lp200_a1 = 0f; private var lp200_a2 = 0f
    private val lp200L = BqState()
    private val lp200R = BqState()

    private var lp4k_b0 = 0f; private var lp4k_b1 = 0f; private var lp4k_b2 = 0f
    private var lp4k_a1 = 0f; private var lp4k_a2 = 0f
    private val lp4kL = BqState()
    private val lp4kR = BqState()

    // ── Per-band compressor state ─────────────────────────────
    // Per-channel compressor state (separate L/R for stable stereo)
    private class SingleCompState(
        var env: Float = 0f, var gain: Float = 1f,
        var counter: Int = 0, var targetGain: Float = 1f,
        var makeupCache: Float = 1f  // precomputed makeup gain
    )
    private class DualCompState(val l: SingleCompState, val r: SingleCompState)
    private val lowState = DualCompState(SingleCompState(), SingleCompState())
    private val midState = DualCompState(SingleCompState(), SingleCompState())
    private val highState = DualCompState(SingleCompState(), SingleCompState())

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

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 ||
            (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
             inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)) {
            this.inputFormat = inputAudioFormat; return inputAudioFormat
        }
        inputFormat = inputAudioFormat
        sampleRate = inputAudioFormat.sampleRate
        val fs = sampleRate.toFloat()

        // Design crossover filters
        val c200 = designLpf(200f, fs)
        lp200_b0 = c200[0]; lp200_b1 = c200[1]; lp200_b2 = c200[2]
        lp200_a1 = c200[3]; lp200_a2 = c200[4]

        val c4k = designLpf(4000f, fs)
        lp4k_b0 = c4k[0]; lp4k_b1 = c4k[1]; lp4k_b2 = c4k[2]
        lp4k_a1 = c4k[3]; lp4k_a2 = c4k[4]

        // Cache makeup gains (computed once per configure, not per sample)
        fun cacheMakeup(s: SingleCompState, db: Float) {
            s.makeupCache = 10f.pow((db / 20f).coerceAtLeast(-10f))
        }
        cacheMakeup(lowState.l, lowBand.makeupDb); cacheMakeup(lowState.r, lowBand.makeupDb)
        cacheMakeup(midState.l, midBand.makeupDb); cacheMakeup(midState.r, midBand.makeupDb)
        cacheMakeup(highState.l, highBand.makeupDb); cacheMakeup(highState.r, highBand.makeupDb)

        return inputAudioFormat
    }

    // Biquad: each lane (L/R) needs its own state storage
    private data class BqState(var x1: Float = 0f, var x2: Float = 0f,
                                var y1: Float = 0f, var y2: Float = 0f)

    private fun processBiquad(x: Float,
                               b0: Float, b1: Float, b2: Float,
                               a1: Float, a2: Float,
                               s: BqState): Float {
        val y = b0 * x + b1 * s.x1 + b2 * s.x2 - a1 * s.y1 - a2 * s.y2
        s.x2 = s.x1; s.x1 = x; s.y2 = s.y1; s.y1 = y
        return y
    }

    // Per-channel compressor processing with gain interpolation.
    // Full gain computation (sqrt, log10, pow) runs every 8 samples.
    // Between updates, the gain slides smoothly toward the target.
    private fun compressChannel(x: Float, band: BandConfig,
                                 att: Float, rel: Float,
                                 env: SingleCompState): Float {
        val absX = abs(x)
        // RMS envelope runs every sample (cheap)
        val delta = absX * absX - env.env
        env.env += att * delta
        if (env.env.isNaN() || env.env.isInfinite()) env.env = 0f

        // Fast-path: very quiet signal, gain already at unity
        if (env.env < 1e-6f && env.gain > 0.99f) {
            return x
        }

        // Full gain computation (expensive) — every 8 samples
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

        // Interpolate gain each sample (cheap)
        val coeff = if (env.targetGain < env.gain) att else rel
        env.gain += coeff * (env.targetGain - env.gain)
        if (env.gain.isNaN() || env.gain.isInfinite()) env.gain = 1f
        env.gain = env.gain.coerceIn(0.01f, 1f)

        // Apply gain + makeup (makeup computed once at load time)
        return x * env.gain * env.makeupCache
    }

    override fun isActive() = inputFormat.channelCount == 2 && enabled

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
        val fs = sampleRate.toFloat()

        // Precompute attack/release coefficients per band
        fun timeToCoeff(ms: Float): Float = 1f - exp(-1f / (ms * fs / 1000f))
        val lowAtt = timeToCoeff(lowBand.attackMs)
        val lowRel = timeToCoeff(lowBand.releaseMs)
        val midAtt = timeToCoeff(midBand.attackMs)
        val midRel = timeToCoeff(midBand.releaseMs)
        val highAtt = timeToCoeff(highBand.attackMs)
        val highRel = timeToCoeff(highBand.releaseMs)

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
            // LPF at 200Hz → low band (HPF complement = mids+highs)
            val lowL = processBiquad(l, lp200_b0, lp200_b1, lp200_b2, lp200_a1, lp200_a2, lp200L)
            val aboveLowL = l - lowL

            // LPF at 4kHz on above-200Hz → mid band (HPF complement = highs)
            val midL = processBiquad(aboveLowL, lp4k_b0, lp4k_b1, lp4k_b2, lp4k_a1, lp4k_a2, lp4kL)
            val highL = aboveLowL - midL

            val lowR = processBiquad(r, lp200_b0, lp200_b1, lp200_b2, lp200_a1, lp200_a2, lp200R)
            val aboveLowR = r - lowR
            val midR = processBiquad(aboveLowR, lp4k_b0, lp4k_b1, lp4k_b2, lp4k_a1, lp4k_a2, lp4kR)
            val highR = aboveLowR - midR

            // ── 2. COMPRESS EACH BAND (separate L/R) ────────────
            val compLowL = compressChannel(lowL, lowBand, lowAtt, lowRel, lowState.l)
            val compMidL = compressChannel(midL, midBand, midAtt, midRel, midState.l)
            val compHighL = compressChannel(highL, highBand, highAtt, highRel, highState.l)

            val compLowR = compressChannel(lowR, lowBand, lowAtt, lowRel, lowState.r)
            val compMidR = compressChannel(midR, midBand, midAtt, midRel, midState.r)
            val compHighR = compressChannel(highR, highBand, highAtt, highRel, highState.r)

            // ── 3. SUM ───────────────────────────────────────────
            var outL = compLowL + compMidL + compHighL
            var outR = compLowR + compMidR + compHighR

            // Master output gain
            val outGain = 10f.pow(outputGainDb / 20f)
            outL *= outGain; outR *= outGain

            outL = outL.coerceIn(-1.15f, 1.15f)
            outR = outR.coerceIn(-1.15f, 1.15f)

            if (is16Bit) {
                buffer.putShort((outL * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                buffer.putShort((outR * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            } else {
                buffer.putFloat(outL); buffer.putFloat(outR)
            }
            i += bytesPerSample * 2
        }

        inputBuffer.position(limit); buffer.flip(); outputBuffer = buffer
    }

    override fun getOutput(): ByteBuffer {
        val output = outputBuffer; outputBuffer = AudioProcessor.EMPTY_BUFFER; return output
    }
    override fun isEnded() = inputEnded && outputBuffer == AudioProcessor.EMPTY_BUFFER
    override fun queueEndOfStream() { inputEnded = true }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER; inputEnded = false
        lp200L.x1 = 0f; lp200L.x2 = 0f; lp200L.y1 = 0f; lp200L.y2 = 0f
        lp200R.x1 = 0f; lp200R.x2 = 0f; lp200R.y1 = 0f; lp200R.y2 = 0f
        lp4kL.x1 = 0f; lp4kL.x2 = 0f; lp4kL.y1 = 0f; lp4kL.y2 = 0f
        lp4kR.x1 = 0f; lp4kR.x2 = 0f; lp4kR.y1 = 0f; lp4kR.y2 = 0f
        lowState.l.env = 0f; lowState.l.gain = 1f; lowState.r.env = 0f; lowState.r.gain = 1f
        midState.l.env = 0f; midState.l.gain = 1f; midState.r.env = 0f; midState.r.gain = 1f
        highState.l.env = 0f; highState.l.gain = 1f; highState.r.env = 0f; highState.r.gain = 1f
    }

    override fun reset() {
        flush(); buffer = AudioProcessor.EMPTY_BUFFER
        inputFormat = AudioFormat.NOT_SET; sampleRate = 44100
    }
}
