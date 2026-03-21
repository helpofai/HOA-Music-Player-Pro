/*
 * Copyright (c) 2026 HOA Music Player Pro contributors.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package com.helpofai.hoa.musicplayer.service.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.tanh
import kotlin.math.abs

@UnstableApi
class BassBoostProcessor : AudioProcessor {

    var strength: Float = 0f 
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded: Boolean = false

    // --- ADVANCED KINETIC BASS STATE ---
    
    // 1. Dual-Stage Low Pass (Deep & Sub)
    private var lp1L: Float = 0f; private var lp1R: Float = 0f
    private var lp2L: Float = 0f; private var lp2R: Float = 0f
    private var alphaDeep: Float = 0.05f // ~90Hz
    private var alphaSub: Float = 0.02f  // ~40Hz

    // 2. Bass Transient Shaper (Punch)
    private var bassEnvL: Float = 0f; private var bassEnvR: Float = 0f
    
    // 3. Rumble Filter (20Hz HPF)
    private var rXL: Float = 0f; private var rXR: Float = 0f
    private var rYL: Float = 0f; private var rYR: Float = 0f
    private var rAlpha: Float = 0.99f

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 || 
           (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT && inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)) {
            return AudioFormat.NOT_SET
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat

        val sampleRate = inputAudioFormat.sampleRate.toDouble()
        val dt = 1.0 / sampleRate

        // Alpha for 90Hz (Deep Bass)
        alphaDeep = (dt / (1.0 / (2.0 * PI * 90.0) + dt)).toFloat()
        // Alpha for 45Hz (Sub Bass Thump)
        alphaSub = (dt / (1.0 / (2.0 * PI * 45.0) + dt)).toFloat()
        // Alpha for 20Hz HPF (Rumble)
        rAlpha = ( (1.0 / (2.0 * PI * 20.0)) / (1.0 / (2.0 * PI * 20.0) + dt) ).toFloat()

        return outputAudioFormat
    }

    override fun isActive(): Boolean {
        return inputAudioFormat.channelCount == 2 && 
               (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT || inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) && 
               strength > 0f
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

        val is16Bit = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        val bytesPerSample = if (is16Bit) 2 else 4
        
        // Massive Kinetic Boost Factor
        val boostAmt = strength * 12.0f 
        val subAmt = strength * 6.0f
        val punchAmt = strength * 0.8f

        var i = position
        while (i < limit) {
            var l: Float; var r: Float
            if (is16Bit) {
                l = inputBuffer.getShort(i).toFloat() / 32768f
                r = inputBuffer.getShort(i + bytesPerSample).toFloat() / 32768f
            } else {
                l = inputBuffer.getFloat(i); r = inputBuffer.getFloat(i + bytesPerSample)
            }

            // 1. Rumble Removal (Protect Speakers)
            val rYL = rAlpha * (rYL + l - rXL); rXL = l; l = rYL
            val rYR = rAlpha * (rYR + r - rXR); rXR = r; r = rYR

            // 2. Extract Bass Bands
            lp1L += alphaDeep * (l - lp1L); lp1R += alphaDeep * (r - lp1R) // Deep
            lp2L += alphaSub * (l - lp2L); lp2R += alphaSub * (r - lp2R)   // Sub

            // 3. KINETIC PUNCH (Bass Transient Shaping)
            // Detects the hit of the drum and makes it "Strong"
            val absL = abs(lp1L); val absR = abs(lp1R)
            bassEnvL += (if (absL > bassEnvL) 0.05f else 0.005f) * (absL - bassEnvL)
            bassEnvR += (if (absR > bassEnvR) 0.05f else 0.005f) * (absR - bassEnvR)
            
            val punchL = (absL - bassEnvL).coerceAtLeast(0f) * punchAmt
            val punchR = (absR - bassEnvR).coerceAtLeast(0f) * punchAmt

            // 4. PHANTOM HARMONICS (The "Growl")
            // Create rich harmonics for sub-bass to make it audible and "heavy"
            val harmL = lp2L * abs(lp2L) * 2.0f
            val harmR = lp2R * abs(lp2R) * 2.0f

            // 5. Final Mix Construction
            // Original + Deep Bass + Sub Harmonic + Kinetic Punch
            var outL = l + (lp1L * boostAmt) + (harmL * subAmt) + (l * punchL)
            var outR = r + (lp1R * boostAmt) + (harmR * subAmt) + (r * punchR)

            // 6. ASYMMETRIC ANALOG SATURATION
            // Gives bass that "Strong" hardware weight
            outL = tanh(outL * 1.1f)
            outR = tanh(outR * 1.1f)

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

    override fun isEnded(): Boolean = inputEnded && outputBuffer == AudioProcessor.EMPTY_BUFFER
    override fun queueEndOfStream() { inputEnded = true }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER; inputEnded = false
        lp1L = 0f; lp1R = 0f; lp2L = 0f; lp2R = 0f
        bassEnvL = 0f; bassEnvR = 0f
        rXL = 0f; rXR = 0f; rYL = 0f; rYR = 0f
    }

    override fun reset() {
        flush(); buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET; outputAudioFormat = AudioFormat.NOT_SET
    }
}
