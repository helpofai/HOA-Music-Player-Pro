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
import kotlin.math.abs

@UnstableApi
class StereoProcessor : AudioProcessor {
    
    var balance: Float = 0f 
    var stereoWidth: Float = 1f 
    var clarity: Float = 0f 
    var isSpatialMode: Boolean = false

    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded: Boolean = false

    // --- PRO MASTERING & HOLOPHONIC DSP STATE ---
    
    private var envFollowerL: Float = 0f
    private var envFollowerR: Float = 0f
    
    private var exciterHpL: Float = 0f
    private var exciterHpR: Float = 0f
    
    private var sideLpf: Float = 0f
    private var crossoverAlpha: Float = 0.05f 

    private val cfBufferL = FloatArray(64)
    private val cfBufferR = FloatArray(64)
    private var cfIdx = 0
    private var cfDelaySamples = 13 

    // --- 6-TAP BINAURAL MATRIX STATE ---
    private val spatialDelayL = FloatArray(8192)
    private val spatialDelayR = FloatArray(8192)
    private var spatialIdx = 0
    
    // Taps (in samples)
    private var tap1 = 0; private var tap2 = 0; private var tap3 = 0
    private var tap4 = 0; private var tap5 = 0; private var tap6 = 0

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 || 
           (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT && inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)) {
            return AudioFormat.NOT_SET
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat

        val sampleRate = inputAudioFormat.sampleRate.toDouble()
        
        crossoverAlpha = ( (1.0 / sampleRate) / (1.0 / (2.0 * PI * 200.0) + (1.0 / sampleRate)) ).toFloat()
        cfDelaySamples = (sampleRate * 0.0003).toInt().coerceIn(1, 63)

        // Holophonic Tap Distances (Prime numbers for reduced resonance)
        tap1 = (sampleRate * 0.011).toInt() // 11ms - Near Width
        tap2 = (sampleRate * 0.019).toInt() // 19ms - Depth
        tap3 = (sampleRate * 0.029).toInt() // 29ms - Distance
        tap4 = (sampleRate * 0.041).toInt() // 41ms - Rear Reflection
        tap5 = (sampleRate * 0.053).toInt() // 53ms - Far Envelopment
        tap6 = (sampleRate * 0.067).toInt() // 67ms - Stadium Boundary

        return outputAudioFormat
    }

    override fun isActive(): Boolean {
        return inputAudioFormat.channelCount == 2 && 
               (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT || inputAudioFormat.encoding == C.ENCODING_PCM_16BIT)
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
        val frameSize = bytesPerSample * 2

        var i = position
        while (i < limit) {
            var l: Float; var r: Float
            if (is16Bit) {
                l = inputBuffer.getShort(i).toFloat() / 32768f
                r = inputBuffer.getShort(i + bytesPerSample).toFloat() / 32768f
            } else {
                l = inputBuffer.getFloat(i); r = inputBuffer.getFloat(i + bytesPerSample)
            }

            // --- 1. TRANSIENT SHAPER (Core Punch) ---
            if (clarity > 0.1f) {
                val absL = abs(l); val absR = abs(r)
                envFollowerL += (if (absL > envFollowerL) 0.02f else 0.002f) * (absL - envFollowerL)
                envFollowerR += (if (absR > envFollowerR) 0.02f else 0.002f) * (absR - envFollowerR)
                l *= 1.0f + (clarity * 0.5f * (absL - envFollowerL).coerceAtLeast(0f))
                r *= 1.0f + (clarity * 0.5f * (absR - envFollowerR).coerceAtLeast(0f))
            }

            // --- 2. HARMONIC EXCITER (Studio Detail) ---
            if (clarity > 0.2f) {
                exciterHpL += 0.2f * (l - exciterHpL); exciterHpR += 0.2f * (r - exciterHpR)
                l += tanh((l - exciterHpL) * (1.0f + clarity * 4.0f)) * clarity * 0.4f
                r += tanh((r - exciterHpR) * (1.0f + clarity * 4.0f)) * clarity * 0.4f
            }

            // --- 3. BINAURAL CROSSFEED (Natural Room Spread) ---
            cfBufferL[cfIdx] = l; cfBufferR[cfIdx] = r
            val readCfIdx = (cfIdx - cfDelaySamples + 64) % 64
            l += cfBufferR[readCfIdx] * 0.12f; r += cfBufferL[readCfIdx] * 0.12f
            cfIdx = (cfIdx + 1) % 64

            // --- 4. MASTERING MS-WIDENING ---
            val mid = (l + r) * 0.5f; val side = (l - r) * 0.5f
            val sLow = sideLpf + crossoverAlpha * (side - sideLpf); sideLpf = sLow
            val sHigh = side - sLow
            val dynWidth = if (stereoWidth > 1.0f) 1.0f + (stereoWidth - 1.0f) * 1.8f else stereoWidth
            val sideProcessed = (sLow * 0.7f) + (sHigh * dynWidth)
            
            var leftFinal = mid + sideProcessed; var rightFinal = mid - sideProcessed

            // --- 5. 6-TAP BINAURAL HOLOPHONIC MATRIX ---
            if (isSpatialMode) {
                // Save signal
                spatialDelayL[spatialIdx] = leftFinal; spatialDelayR[spatialIdx] = rightFinal
                
                // Read Taps
                val t1L = spatialDelayL[(spatialIdx - tap1 + 8192) % 8192]; val t1R = spatialDelayR[(spatialIdx - tap1 + 8192) % 8192]
                val t2L = spatialDelayL[(spatialIdx - tap2 + 8192) % 8192]; val t2R = spatialDelayR[(spatialIdx - tap2 + 8192) % 8192]
                val t3L = spatialDelayL[(spatialIdx - tap3 + 8192) % 8192]; val t3R = spatialDelayR[(spatialIdx - tap3 + 8192) % 8192]
                val t4L = spatialDelayL[(spatialIdx - tap4 + 8192) % 8192]; val t4R = spatialDelayR[(spatialIdx - tap4 + 8192) % 8192]
                val t5L = spatialDelayL[(spatialIdx - tap5 + 8192) % 8192]; val t5R = spatialDelayR[(spatialIdx - tap5 + 8192) % 8192]
                val t6L = spatialDelayL[(spatialIdx - tap6 + 8192) % 8192]; val t6R = spatialDelayR[(spatialIdx - tap6 + 8192) % 8192]
                
                spatialIdx = (spatialIdx + 1) % 8192

                // Build 3D Holophonic Space
                // Width (T1, T2)
                leftFinal += (t1R * 0.25f) + (t2L * 0.15f)
                rightFinal += (t1L * 0.25f) + (t2R * 0.15f)
                
                // Depth & Phase Inversion for "Behind" sensation (T3, T4)
                leftFinal += (t3R * 0.12f) - (t4L * 0.10f) 
                rightFinal += (t3L * 0.12f) - (t4R * 0.10f)
                
                // Far Stadium Ambience (T5, T6)
                leftFinal += (t5L * 0.08f) + (t6R * 0.06f)
                rightFinal += (t5R * 0.08f) + (t6L * 0.06f)
                
                // Final Gain Compensation (Massive space needs careful scaling)
                leftFinal *= 0.62f; rightFinal *= 0.62f
            }

            // --- 6. ANALOG SOFT LIMITER ---
            leftFinal = tanh(leftFinal); rightFinal = tanh(rightFinal)

            // Balance
            val bL = if (balance > 0f) 1f - balance else 1f
            val bR = if (balance < 0f) 1f + balance else 1f
            leftFinal *= bL; rightFinal *= bR

            if (is16Bit) {
                buffer.putShort((leftFinal * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                buffer.putShort((rightFinal * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            } else {
                buffer.putFloat(leftFinal); buffer.putFloat(rightFinal)
            }
            i += frameSize
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
        envFollowerL = 0f; envFollowerR = 0f; exciterHpL = 0f; exciterHpR = 0f; sideLpf = 0f
        cfBufferL.fill(0f); cfBufferR.fill(0f); spatialDelayL.fill(0f); spatialDelayR.fill(0f)
        spatialIdx = 0; cfIdx = 0
    }

    override fun reset() {
        flush(); buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET; outputAudioFormat = AudioFormat.NOT_SET
    }
}
