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
import kotlin.math.roundToInt

/**
 * Professional Algorithmic Reverb (v7.0)
 * Uses a Schroeder-Moorer model with Pre-Delay and High-Density Diffusion.
 */
@UnstableApi
class ReverbProcessor : AudioProcessor {
    var amount: Float = 0f 
        set(value) { field = value.coerceIn(0f, 1f); updateParams() }

    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded: Boolean = false

    // Pre-Delay
    private val preDelayL = FloatArray(2048)
    private val preDelayR = FloatArray(2048)
    private var pdIdx = 0
    private var pdSamples = 0

    // Schroeder Filters
    private val combsL = ArrayList<CombFilter>()
    private val combsR = ArrayList<CombFilter>()
    private val allpassesL = ArrayList<AllPassFilter>()
    private val allpassesR = ArrayList<AllPassFilter>()

    private val combTuning = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allpassTuning = intArrayOf(225, 341, 441, 556)

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 || 
           (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT && inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)) {
            this.inputAudioFormat = inputAudioFormat; return inputAudioFormat
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat

        val fs = inputAudioFormat.sampleRate.toFloat()
        pdSamples = (fs * 0.025f).toInt().coerceAtMost(2047) // 25ms Pre-delay

        initFilters(inputAudioFormat.sampleRate)
        return outputAudioFormat
    }

    private fun initFilters(sampleRate: Int) {
        combsL.clear(); combsR.clear()
        allpassesL.clear(); allpassesR.clear()
        val scale = sampleRate / 44100.0
        for (size in combTuning) {
            combsL.add(CombFilter((size * scale).roundToInt()))
            combsR.add(CombFilter((size * scale + 23).roundToInt()))
        }
        for (size in allpassTuning) {
            allpassesL.add(AllPassFilter((size * scale).roundToInt()))
            allpassesR.add(AllPassFilter((size * scale + 23).roundToInt()))
        }
        updateParams()
    }

    private fun updateParams() {
        val feedback = 0.7f + (amount * 0.25f)
        for (c in combsL) c.feedback = feedback
        for (c in combsR) c.feedback = feedback
    }

    override fun isActive() = inputAudioFormat.channelCount == 2 && amount > 0f

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

        var i = position
        while (i < limit) {
            var l: Float; var r: Float
            if (is16Bit) {
                l = inputBuffer.getShort(i).toFloat() / 32768f
                r = inputBuffer.getShort(i + bytesPerSample).toFloat() / 32768f
            } else {
                l = inputBuffer.getFloat(i); r = inputBuffer.getFloat(i + bytesPerSample)
            }

            // 1. Pre-Delay
            val ridx = (pdIdx - pdSamples + 2048) % 2048
            val pdL = preDelayL[ridx]; val pdR = preDelayR[ridx]
            preDelayL[pdIdx] = l; preDelayR[pdIdx] = r
            pdIdx = (pdIdx + 1) % 2048

            // 2. Process Reverb Tank
            val inMix = (pdL + pdR) * 0.015f
            var outL = 0f; var outR = 0f
            for (c in combsL) outL += c.process(inMix)
            for (c in combsR) outR += c.process(inMix)
            for (ap in allpassesL) outL = ap.process(outL)
            for (ap in allpassesR) outR = ap.process(outR)

            // 3. Dry/Wet Mix
            val wet = amount * 2.0f
            val finalL = l + outL * wet
            val finalR = r + outR * wet

            if (is16Bit) {
                buffer.putShort((finalL * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                buffer.putShort((finalR * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            } else {
                buffer.putFloat(finalL); buffer.putFloat(finalR)
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
        for (c in combsL) c.flush(); for (c in combsR) c.flush()
        for (ap in allpassesL) ap.flush(); for (ap in allpassesR) ap.flush()
        preDelayL.fill(0f); preDelayR.fill(0f); pdIdx = 0
    }
    
    override fun reset() {
        flush(); buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET; outputAudioFormat = AudioFormat.NOT_SET
    }

    private class CombFilter(val size: Int) {
        val buffer = FloatArray(size)
        var idx = 0
        var feedback = 0.5f
        fun process(input: Float): Float {
            val output = buffer[idx]
            buffer[idx] = input + output * feedback
            if (++idx >= size) idx = 0
            return output
        }
        fun flush() { buffer.fill(0f); idx = 0 }
    }

    private class AllPassFilter(val size: Int) {
        val buffer = FloatArray(size)
        var idx = 0
        val feedback = 0.5f
        fun process(input: Float): Float {
            val bufOut = buffer[idx]
            val output = -input + bufOut
            buffer[idx] = input + bufOut * feedback
            if (++idx >= size) idx = 0
            return output
        }
        fun flush() { buffer.fill(0f); idx = 0 }
    }
}
