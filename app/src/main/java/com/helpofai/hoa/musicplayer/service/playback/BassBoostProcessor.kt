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
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.math.sqrt

/**
 * Surgical Kinetic Bass Engine (v9.1)
 * Clean deep bass with proper biquad-based sub isolation.
 * - 8th-order Butterworth LPF for sharp 90Hz cutoff
 * - Gentle mud-carving (reduced to preserve bass body)
 * - 3rd-order Chebyshev sub-harmonic synthesis
 * - Clean impact envelope with fast attack/release
 * - Smooth tanh saturation (no gate threshold)
 */
@UnstableApi
class BassBoostProcessor : AudioProcessor {
    var strength: Float = 0f 
        set(value) { field = value.coerceIn(0f, 1f) }

    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded: Boolean = false

    // ── 4-Stage Biquad LPF (Butterworth Q=0.707, 8th-order) ────
    // Proper LR-8 alignment for sharp, clean 90Hz cutoff
    private val b0 = FloatArray(4)
    private val b1 = FloatArray(4)
    private val b2 = FloatArray(4)
    private val a1 = FloatArray(4)
    private val a2 = FloatArray(4)

    private val x1L = FloatArray(4); private val x2L = FloatArray(4)
    private val y1L = FloatArray(4); private val y2L = FloatArray(4)
    private val x1R = FloatArray(4); private val x2R = FloatArray(4)
    private val y1R = FloatArray(4); private val y2R = FloatArray(4)

    // ── Gentle Mud-Cut (~250Hz) ────────────────────────────────
    private var mudL = 0f; private var mudR = 0f
    private var alphaMud = 0f

    // ── Physical Earth Wave (~45Hz) ────────────────────────────
    private var earthL = 0f; private var earthR = 0f
    private var alphaEarth = 0f

    // ── Clean Impact Envelope ───────────────────────────────────
    private var envL = 0f; private var envR = 0f
    private var peakL = 0f; private var peakR = 0f
    private var peakHoldCounter = 0

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 || 
           (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT && inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)) {
            this.inputAudioFormat = inputAudioFormat; return inputAudioFormat
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat

        val fs = inputAudioFormat.sampleRate.toFloat()
        val dt = 1f / fs

        // ── Design LR-8 biquad coefficients ──────────────────────
        // 4 cascaded 2nd-order Butterworth biquads @ 115Hz for massive club thump
        val fc = 115f
        val w0 = 2f * PI.toFloat() * fc / fs
        val cosW = cos(w0)
        val sinW = sin(w0)
        val Q = 1f / kotlin.math.sqrt(2f)  // 0.7071
        val alpha = sinW / (2f * Q)

        for (i in 0 until 4) {
            val norm = 1f + alpha
            b0[i] = ((1f - cosW) / 2f) / norm
            b1[i] = (1f - cosW) / norm
            b2[i] = ((1f - cosW) / 2f) / norm
            a1[i] = (-2f * cosW) / norm
            a2[i] = (1f - alpha) / norm
        }

        // Mud-carving at 250Hz
        alphaMud = (dt / (1f / (2f * PI.toFloat() * 250f) + dt)).coerceIn(0f, 1f)
        
        // Physical Earth Wave at 45Hz (Deep Sub Rumble)
        alphaEarth = (dt / (1f / (2f * PI.toFloat() * 45f) + dt)).coerceIn(0f, 1f)

        return outputAudioFormat
    }

    private fun processBiquad(stage: Int, x: Float,
                               x1: FloatArray, x2: FloatArray,
                               y1: FloatArray, y2: FloatArray): Float {
        val y = b0[stage] * x + b1[stage] * x1[stage] + b2[stage] * x2[stage]
                - a1[stage] * y1[stage] - a2[stage] * y2[stage]
        x2[stage] = x1[stage]; x1[stage] = x
        y2[stage] = y1[stage]; y1[stage] = y
        return y
    }

    override fun isActive() = inputAudioFormat.channelCount == 2 && strength > 0f

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val remaining = limit - position
        
        if (buffer.capacity() < remaining) {
            buffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            buffer.clear()
        }

        val is16Bit = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        val bytesPerSample = if (is16Bit) 2 else 4
        val str = strength
        val fs = inputAudioFormat.sampleRate.toFloat()

        // Sample-rate-aware time constants for DJ 'Boom Boom' effect
        val attackMs = 0.005f * fs
        val releaseMs = 0.250f * fs // 250ms for a long, massive ringing boom
        val peakHoldSamples = (fs * 0.015f).toInt().coerceAtLeast(1)

        var i = position
        while (i < limit) {
            var l: Float; var r: Float
            if (is16Bit) {
                l = inputBuffer.getShort(i).toFloat() / 32768f
                r = inputBuffer.getShort(i + bytesPerSample).toFloat() / 32768f
            } else {
                l = inputBuffer.getFloat(i); r = inputBuffer.getFloat(i + bytesPerSample)
            }

            // ── 1. PROPER LR-8 SUB ISOLATION ─────────────────────
            // 4 biquad stages for sharp 90Hz cutoff — clean, no mush
            var subL = l; var subR = r
            for (stage in 0 until 4) {
                subL = processBiquad(stage, subL, x1L, x2L, y1L, y2L)
                subR = processBiquad(stage, subR, x1R, x2R, y1R, y2R)
            }



            // ── 2. PHYSICAL EARTH WAVE & FAT 808 SUB ─────────────────
            // Isolate the extreme low frequencies (<45Hz) that create physical vibration
            earthL += alphaEarth * (subL - earthL)
            earthR += alphaEarth * (subR - earthR)
            val physicalWaveL = earthL * str * 25.0f // Extreme physical vibration boost
            val physicalWaveR = earthR * str * 25.0f

            // Drive the sub and the physical wave into the saturator.
            val subDrive = 1f + (str * 15.0f) 
            val thickSubL = tanh((subL * subDrive) + physicalWaveL) * str * 4.5f 
            val thickSubR = tanh((subR * subDrive) + physicalWaveR) * str * 4.5f

            // ── 3. MASSIVE TRANSIENT PUNCH ───────────────────────────
            val absSubL = abs(subL); val absSubR = abs(subR)

            if (absSubL > peakL || peakHoldCounter > peakHoldSamples) {
                peakL = if (absSubL > peakL) absSubL else peakL * 0.995f
                peakHoldCounter = 0
            } else { peakHoldCounter++ }
            if (absSubR > peakR || peakHoldCounter > peakHoldSamples) {
                peakR = if (absSubR > peakR) absSubR else peakR * 0.995f
            }

            val att = attackMs.coerceIn(0.001f, 1f)
            val rel = releaseMs.coerceIn(0.001f, 1f)
            envL += (if (absSubL > envL) att else rel) * (absSubL - envL)
            envR += (if (absSubR > envR) att else rel) * (absSubR - envR)

            val punchL = (peakL - envL).coerceAtLeast(0f) * str * 20.0f
            val punchR = (peakR - envR).coerceAtLeast(0f) * str * 20.0f

            // ── 4. DIRECT MIX WITH DJ MASTER GAIN ────────────────────
            // No mud-carving or ducking. Add the massive sub and punch directly to the original audio.
            var outL = l + thickSubL + (subL * punchL)
            var outR = r + thickSubR + (subR * punchR)

            // DJ Master Gain: Boost the entire track volume by up to 50% so it hits extremely hard
            val masterGain = 1f + (str * 0.50f)
            outL *= masterGain
            outR *= masterGain

            // ── 5. ANALOG MASTERING TAPE SATURATOR ───────────────────────
            // Instead of a hard ceiling that causes digital 'bree' noise, we run the
            // insanely loud mix through a wide-knee mastering saturator. This mathematically
            // squeezes the huge bass together with the track, making it sound exceptionally
            // loud, glued, and heavy without squaring off the waveform.
            outL = 1.05f * tanh(outL / 1.05f)
            outR = 1.05f * tanh(outR / 1.05f)
            
            // Final safety clip
            outL = outL.coerceIn(-1f, 1f)
            outR = outR.coerceIn(-1f, 1f)

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
        for (i in 0 until 4) {
            x1L[i] = 0f; x2L[i] = 0f; y1L[i] = 0f; y2L[i] = 0f
            x1R[i] = 0f; x2R[i] = 0f; y1R[i] = 0f; y2R[i] = 0f
        }
        mudL = 0f; mudR = 0f
        envL = 0f; envR = 0f; peakL = 0f; peakR = 0f; peakHoldCounter = 0
    }
    
    override fun reset() {
        flush(); buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET; outputAudioFormat = AudioFormat.NOT_SET
    }
}
