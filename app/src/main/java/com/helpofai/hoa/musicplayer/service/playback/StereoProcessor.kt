package com.helpofai.hoa.musicplayer.service.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

@UnstableApi
class StereoProcessor : AudioProcessor {
    
    var balance: Float = 0f // -1.0 (Left) to 1.0 (Right)
        set(value) {
            field = value.coerceIn(-1f, 1f)
        }

    var stereoWidth: Float = 1f // 0.0 (Mono) to 2.0 (Wide), 1.0 = Normal
        set(value) {
            field = value.coerceIn(0f, 2f)
        }

    var clarity: Float = 0f // 0.0 to 1.0 (Diamond Clear)
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded: Boolean = false

    // Filter state for Side channel (Low Pass) - Crossover
    private var sideLowState: Float = 0f
    private var sideXoverAlpha: Float = 0.1f

    // Filter state for Binaural Crossfeed (Head Shadow simulation)
    private var headShadowL: Float = 0f
    private var headShadowR: Float = 0f
    private var headShadowAlpha: Float = 0.08f // ~400Hz cutoff for head shadow

    // Filter state for Air (High Pass) - Side Channel
    private var airHpState: Float = 0f
    private var airHpAlpha: Float = 0.1f

    // Filter state for Vocal Presence (Band Pass) - Mid Channel
    // Simple 2-pole Resonant Bandpass
    private var vocalBpX1: Float = 0f
    private var vocalBpX2: Float = 0f
    private var vocalBpY1: Float = 0f
    private var vocalBpY2: Float = 0f
    // Biquad coeffs
    private var b0: Float = 0f
    private var b1: Float = 0f
    private var b2: Float = 0f
    private var a1: Float = 0f
    private var a2: Float = 0f


    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        // Support 32-bit Float PCM
        if (inputAudioFormat.channelCount != 2 || inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioFormat.NOT_SET
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat

        val sampleRate = inputAudioFormat.sampleRate.toDouble()

        // 1. Crossover LPF (~300Hz)
        val xoverFreq = 300.0
        val xoverDt = 1.0 / sampleRate
        val xoverRc = 1.0 / (2.0 * PI * xoverFreq)
        sideXoverAlpha = (xoverDt / (xoverRc + xoverDt)).toFloat()

        // 2. Air HPF (~10kHz)
        val airFreq = 10000.0
        val airRc = 1.0 / (2.0 * PI * airFreq)
        val airDt = 1.0 / sampleRate
        airHpAlpha = (airRc / (airRc + airDt)).toFloat()

        // 3. Vocal Presence BPF (3kHz, Q=0.7)
        // Standard Biquad Bandpass calculation
        val centerFreq = 3000.0
        val Q = 0.7 // Wider band for more presence
        val w0 = 2.0 * PI * centerFreq / sampleRate
        val alpha = sin(w0) / (2.0 * Q)
        val norm = 1.0 + alpha
        
        b0 = (alpha / norm).toFloat()
        b1 = 0f
        b2 = (-alpha / norm).toFloat()
        a1 = (-2.0 * cos(w0) / norm).toFloat()
        a2 = ((1.0 - alpha) / norm).toFloat()

        return outputAudioFormat
    }

    override fun isActive(): Boolean {
        return inputAudioFormat.channelCount == 2 && inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
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

        val balanceLeftVol = if (balance > 0f) 1f - balance else 1f
        val balanceRightVol = if (balance < 0f) 1f + balance else 1f
        
        // Clarity Gains (Upgraded for "Clearly Present L/R")
        val vocalGain = clarity * 1.5f // Stronger Vocal Presence
        val airGain = clarity * 2.5f // Much stronger Air/Detail for Side channel

        var i = position
        while (i < limit) {
            val leftOriginal = inputBuffer.getFloat(i)
            val rightOriginal = inputBuffer.getFloat(i + 4)

            // Mid-Side decomposition
            var mid = (leftOriginal + rightOriginal) * 0.5f
            var side = (leftOriginal - rightOriginal) * 0.5f
            
            // --- 1. Diamond Clarity Processing ---
            if (clarity > 0f) {
                // Vocal Presence (Mid Channel) - Biquad Bandpass
                val vocalOut = b0 * mid + b1 * vocalBpX1 + b2 * vocalBpX2 - a1 * vocalBpY1 - a2 * vocalBpY2
                // Update state
                vocalBpX2 = vocalBpX1
                vocalBpX1 = mid
                vocalBpY2 = vocalBpY1
                vocalBpY1 = vocalOut
                
                // Add presence to Mid
                mid += vocalOut * vocalGain

                // Air (Side Channel) - HPF
                // Use simple 1-pole LPF to derive HPF
                val airLpf = airHpState + (1f - airHpAlpha) * (side - airHpState) 
                airHpState = airLpf
                val airHigh = side - airLpf
                
                // Harmonic Exciter: Generate harmonics using saturation
                // Drive the high freq content to create "sparkle" and detail
                val airHarmonics = tanh(airHigh * 2.0f)
                
                // Add air harmonics to Side
                side += airHarmonics * airGain

                // --- ANALOG TUBE SATURATION (The "Real" Feel) ---
                // Adds Even-Order Harmonics (Warmth) to the Mid channel (Vocals/Bass)
                // f(x) = x + alpha * (x^2)
                // We use a safe approximation to avoid DC offset issues or extreme distortion
                val tubeWarmth = 0.15f * clarity // Amount of warmth linked to clarity
                if (tubeWarmth > 0) {
                    val evenHarmonic = mid * mid 
                    // Mix standard signal with its warmth, preserving phase
                    if (mid >= 0) {
                        mid += evenHarmonic * tubeWarmth
                    } else {
                        mid -= evenHarmonic * tubeWarmth // Maintain symmetry to avoid DC drift, but creates 'thick' sound
                    }
                }
            }

            // --- 2. Stereo Width Processing ---
            
            // Frequency Dependent Widening (Bass Mono)
            // Low Pass Filter on Side component (Crossover)
            val sideLow = sideLowState + sideXoverAlpha * (side - sideLowState)
            sideLowState = sideLow
            
            val sideHigh = side - sideLow

            // Apply width
            // We boost the sideHigh slightly more when widening to enhance "Small effects"
            val processedSide = if (stereoWidth >= 1.0f) {
                // Progressive widening: wider sounds get wider
                sideLow + (sideHigh * stereoWidth * 1.2f) 
            } else {
                side * stereoWidth
            }

            var leftProcessed = mid + processedSide
            var rightProcessed = mid - processedSide

            // --- 3. Binaural Crossfeed (Speaker Simulation) ---
            // Simulates sound bending around the head (Head Shadow).
            // Reduces headphone fatigue and creates a natural "Phantom Center".
            // We use the "Shadow" signal (Low Passed) to mimic head obstruction.
            val shadowL = headShadowL + headShadowAlpha * (leftOriginal - headShadowL)
            val shadowR = headShadowR + headShadowAlpha * (rightOriginal - headShadowR)
            headShadowL = shadowL
            headShadowR = shadowR
            
            // Mix Shadow into Opposite Channel (delayed slightly by filter phase lag)
            // Only apply if Clarity is active (High-Res Mode) to keep standard mode pure
            if (clarity > 0f) {
                val feedAmount = 0.15f // Natural crossfeed level (~ -16dB)
                leftProcessed += shadowR * feedAmount
                rightProcessed += shadowL * feedAmount
            }

            // --- 4. Balance ---
            leftProcessed *= balanceLeftVol
            rightProcessed *= balanceRightVol

            buffer.putFloat(leftProcessed)
            buffer.putFloat(rightProcessed)
            
            i += 8
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

    override fun isEnded(): Boolean {
        return inputEnded && outputBuffer == AudioProcessor.EMPTY_BUFFER
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        sideLowState = 0f
        airHpState = 0f
        headShadowL = 0f
        headShadowR = 0f
        vocalBpX1 = 0f; vocalBpX2 = 0f; vocalBpY1 = 0f; vocalBpY2 = 0f
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
    }
}