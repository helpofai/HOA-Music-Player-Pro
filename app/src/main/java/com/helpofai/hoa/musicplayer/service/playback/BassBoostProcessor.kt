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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tanh

@UnstableApi
class BassBoostProcessor : AudioProcessor {

    var strength: Float = 0f // 0.0 (Off) to 1.0 (Max)
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded: Boolean = false

    // Filter state
    private var lastOutputL: Float = 0f
    private var lastOutputR: Float = 0f
    private var alpha: Float = 0.05f

    // Rumble Filter state (20Hz HPF)
    private var rumbleXL: Float = 0f
    private var rumbleXR: Float = 0f
    private var rumbleYL: Float = 0f
    private var rumbleYR: Float = 0f
    private var rumbleAlpha: Float = 0.99f

    // DC Blocker state for Phantom Bass
    private var dcBlockLastInL: Float = 0f
    private var dcBlockLastInR: Float = 0f
    private var dcBlockLastOutL: Float = 0f
    private var dcBlockLastOutR: Float = 0f
    private val dcBlockR: Float = 0.995f

    // Transient Shaper state
    private var envFollowerL: Float = 0f
    private var envFollowerR: Float = 0f
    private val attackAlpha: Float = 0.1f // Fast attack
    private val releaseAlpha: Float = 0.001f // Slow release

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 || inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioFormat.NOT_SET
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat

        val sampleRate = inputAudioFormat.sampleRate.toDouble()

        // Calculate alpha for ~100Hz Low Pass Filter (Tightened from 90Hz)
        val cutOffFreq = 100.0
        val dt = 1.0 / sampleRate
        val rc = 1.0 / (2.0 * PI * cutOffFreq)
        alpha = (dt / (rc + dt)).toFloat()

        // Calculate alpha for 20Hz High Pass Rumble Filter
        val rumbleFreq = 20.0
        val rumbleRc = 1.0 / (2.0 * PI * rumbleFreq)
        rumbleAlpha = (rumbleRc / (rumbleRc + dt)).toFloat()

        return outputAudioFormat
    }

    override fun isActive(): Boolean {
        return inputAudioFormat.channelCount == 2 && 
               inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT && 
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

        // Boost factor: High energy scaling
        val boostAmount = strength * 8.0f 

        var i = position
        while (i < limit) {
            val leftIn = inputBuffer.getFloat(i)
            val rightIn = inputBuffer.getFloat(i + 4)

            // 1. Rumble Filter (20Hz HPF)
            val rYL = rumbleAlpha * (rumbleYL + leftIn - rumbleXL)
            val rYR = rumbleAlpha * (rumbleYR + rightIn - rumbleXR)
            rumbleXL = leftIn; rumbleXR = rightIn; rumbleYL = rYL; rumbleYR = rYR
            
            val cleanL = rYL
            val cleanR = rYR

            // 2. Bass Extraction (100Hz LPF)
            val leftLow = lastOutputL + alpha * (cleanL - lastOutputL)
            val rightLow = lastOutputR + alpha * (cleanR - lastOutputR)
            lastOutputL = leftLow; lastOutputR = rightLow

            // 3. --- BASS TRANSIENT PUNCH (The "Hard" Bass effect) ---
            // Emphasizes the attack of bass notes (drums/kicks)
            val absL = abs(leftLow); val absR = abs(rightLow)
            
            // Envelope following
            envFollowerL = if (absL > envFollowerL) 
                envFollowerL + attackAlpha * (absL - envFollowerL)
            else 
                envFollowerL + releaseAlpha * (absL - envFollowerL)
                
            envFollowerR = if (absR > envFollowerR) 
                envFollowerR + attackAlpha * (absR - envFollowerR)
            else 
                envFollowerR + releaseAlpha * (absR - envFollowerR)

            // Dynamic Transient Gain: If signal is rising fast, boost it more
            val punchGainL = 1.0f + max(0f, absL - envFollowerL) * 5.0f * strength
            val punchGainR = 1.0f + max(0f, absR - envFollowerR) * 5.0f * strength
            
            val punchedBassL = leftLow * punchGainL
            val punchedBassR = rightLow * punchGainR

            // 4. --- ADVANCED PHANTOM BASS ---
            // 2nd Harmonic Generation (Octave Up)
            val h2L = punchedBassL * punchedBassL
            val h2R = punchedBassR * punchedBassR
            
            // DC Blocking for harmonics
            val dcL = h2L - dcBlockLastInL + dcBlockR * dcBlockLastOutL
            val dcR = h2R - dcBlockLastInR + dcBlockR * dcBlockLastOutR
            dcBlockLastInL = h2L; dcBlockLastInR = h2R; dcBlockLastOutL = dcL; dcBlockLastOutR = dcR

            // 5. Final Mix
            // We mix the "clean" input with Punched Physical Bass and Phantom Harmonics
            val physicalMix = 1.2f // Slight extra weight on physical bass
            val phantomMix = 0.8f // Harmonic weight
            
            var leftOut = cleanL + (punchedBassL * boostAmount * physicalMix) + (dcL * boostAmount * phantomMix)
            var rightOut = cleanR + (punchedBassR * boostAmount * physicalMix) + (dcR * boostAmount * phantomMix)

            // 6. --- HARD BASS COMPRESSION & LIMITING ---
            // Soft-knee compression logic using tanh saturation
            // This prevents "farting" sounds and keeps the bass "solid" and "hard"
            leftOut = tanh(leftOut * (1.0f + strength * 0.5f))
            rightOut = tanh(rightOut * (1.0f + strength * 0.5f))

            buffer.putFloat(leftOut)
            buffer.putFloat(rightOut)
            
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
        lastOutputL = 0f; lastOutputR = 0f
        rumbleXL = 0f; rumbleXR = 0f; rumbleYL = 0f; rumbleYR = 0f
        dcBlockLastInL = 0f; dcBlockLastInR = 0f; dcBlockLastOutL = 0f; dcBlockLastOutR = 0f
        envFollowerL = 0f; envFollowerR = 0f
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
    }
}