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
    private var vocalMidBpX1: Float = 0f
    private var vocalMidBpX2: Float = 0f
    private var vocalMidBpY1: Float = 0f
    private var vocalMidBpY2: Float = 0f
    
    // Filter state for Vocal Support (Band Pass) - Side Channel
    private var vocalSideBpX1: Float = 0f
    private var vocalSideBpX2: Float = 0f
    private var vocalSideBpY1: Float = 0f
    private var vocalSideBpY2: Float = 0f

    // Biquad coeffs
    private var b0: Float = 0f
    private var b1: Float = 0f
    private var b2: Float = 0f
    private var a1: Float = 0f
    private var a2: Float = 0f

    // DC Blocker state for Tube Saturation
    private var dcBlockLastIn: Float = 0f
    private var dcBlockLastOut: Float = 0f
    private val dcBlockR: Float = 0.995f


    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
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

        // 3. Vocal Presence BPF (3kHz, Q=0.5) - Slightly wider for "L/R support"
        val centerFreq = 3000.0
        val Q = 0.5 
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
        
        // Gains
        val vocalMidGain = clarity * 1.5f
        val vocalSideGain = clarity * 1.2f 
        val airGain = clarity * 2.5f

        var i = position
        while (i < limit) {
            val leftOriginal = inputBuffer.getFloat(i)
            val rightOriginal = inputBuffer.getFloat(i + 4)

            // Mid-Side decomposition
            var mid = (leftOriginal + rightOriginal) * 0.5f
            var side = (leftOriginal - rightOriginal) * 0.5f
            
            // --- 1. Diamond Clarity & Detail Engine ---
            if (clarity > 0f) {
                // --- ULTRA-DETAIL ENGINE (Side Channel Upward Compression) ---
                // This maximizes the "low bits" (quiet details) in the stereo field.
                // Boosts low-level ambient sounds, reverb tails, and subtle effects.
                val detailGain = clarity * 4.0f // Massive potential boost for quiet signals
                val sideAbs = kotlin.math.abs(side).coerceAtMost(1.0f)
                
                // Curve: Gain increases as signal gets quieter.
                // At 0 signal, Gain = 1 + detailGain. At 1 signal, Gain = 1.
                // Use quadratic curve for smooth transition.
                val lowLevelBoost = 1.0f + detailGain * (1.0f - sideAbs) * (1.0f - sideAbs)
                side *= lowLevelBoost


                // --- VOCAL MID (Center Presence) ---
                val vocalMidOut = b0 * mid + b1 * vocalMidBpX1 + b2 * vocalMidBpX2 - a1 * vocalMidBpY1 - a2 * vocalMidBpY2
                vocalMidBpX2 = vocalMidBpX1; vocalMidBpX1 = mid; vocalMidBpY2 = vocalMidBpY1; vocalMidBpY1 = vocalMidOut
                mid += vocalMidOut * vocalMidGain

                // --- VOCAL SIDE (L/R Support) ---
                val vocalSideOut = b0 * side + b1 * vocalSideBpX1 + b2 * vocalSideBpX2 - a1 * vocalSideBpY1 - a2 * vocalSideBpY2
                vocalSideBpX2 = vocalSideBpX1; vocalSideBpX1 = side; vocalSideBpY2 = vocalSideBpY1; vocalSideBpY1 = vocalSideOut
                
                val excitedVocalSide = tanh(vocalSideOut * 2.0f)
                side += excitedVocalSide * vocalSideGain

                // --- AIR (Edge Detail) ---
                val airLpf = airHpState + (1f - airHpAlpha) * (side - airHpState) 
                airHpState = airLpf
                val airHigh = side - airLpf
                val airHarmonics = tanh(airHigh * 4.0f)
                side += airHarmonics * airGain

                // --- TRUE TUBE WARMTH ---
                val tubeWarmth = 0.25f * clarity
                if (tubeWarmth > 0) {
                    val evenHarmonic = mid * mid 
                    val dcBlockedHarmonic = evenHarmonic - dcBlockLastIn + dcBlockR * dcBlockLastOut
                    dcBlockLastIn = evenHarmonic; dcBlockLastOut = dcBlockedHarmonic
                    mid += dcBlockedHarmonic * tubeWarmth
                }
            }

            // --- 2. Stereo Width ---
            val sideLow = sideLowState + sideXoverAlpha * (side - sideLowState)
            sideLowState = sideLow
            val sideHigh = side - sideLow

            val processedSide = if (stereoWidth >= 1.0f) {
                sideLow + (sideHigh * stereoWidth * 1.2f) 
            } else {
                side * stereoWidth
            }

            var leftProcessed = mid + processedSide
            var rightProcessed = mid - processedSide

            // --- 3. Binaural Crossfeed ---
            val shadowL = headShadowL + headShadowAlpha * (leftOriginal - headShadowL)
            val shadowR = headShadowR + headShadowAlpha * (rightOriginal - headShadowR)
            headShadowL = shadowL; headShadowR = shadowR
            
            if (clarity > 0f) {
                val feedAmount = 0.15f
                leftProcessed += shadowR * feedAmount
                rightProcessed += shadowL * feedAmount
            }

            // --- 4. Final Stage ---
            leftProcessed *= balanceLeftVol
            rightProcessed *= balanceRightVol

            // Soft Limiter
            leftProcessed = tanh(leftProcessed)
            rightProcessed = tanh(rightProcessed)

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
        headShadowL = 0f; headShadowR = 0f
        vocalMidBpX1 = 0f; vocalMidBpX2 = 0f; vocalMidBpY1 = 0f; vocalMidBpY2 = 0f
        vocalSideBpX1 = 0f; vocalSideBpX2 = 0f; vocalSideBpY1 = 0f; vocalSideBpY2 = 0f
        dcBlockLastIn = 0f; dcBlockLastOut = 0f
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
    }
}