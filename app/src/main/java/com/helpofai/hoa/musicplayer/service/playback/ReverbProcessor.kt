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

@UnstableApi
class ReverbProcessor : AudioProcessor {

    var amount: Float = 0f // 0.0 (Dry) to 1.0 (Wet)
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    private val roomSize = 0.5f // Fixed nice room size
    private val damping = 0.5f // Fixed nice damping

    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded: Boolean = false

    // Reverb Components
    private val combsL = ArrayList<CombFilter>()
    private val combsR = ArrayList<CombFilter>()
    private val allpassesL = ArrayList<AllPassFilter>()
    private val allpassesR = ArrayList<AllPassFilter>()

    // Tuning (based on Freeverb)
    private val combTuning = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allpassTuning = intArrayOf(225, 341, 441, 556)

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 || inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioFormat.NOT_SET
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat

        initFilters(inputAudioFormat.sampleRate)

        return outputAudioFormat
    }

    private fun initFilters(sampleRate: Int) {
        combsL.clear()
        combsR.clear()
        allpassesL.clear()
        allpassesR.clear()

        val scale = sampleRate / 44100.0

        // Use 4 combs and 2 allpasses for efficiency/smoothness tradeoff
        for (i in 0 until 4) {
            val size = (combTuning[i] * scale).roundToInt()
            combsL.add(CombFilter(size))
            combsR.add(CombFilter(size + 23)) // Slight stereo spread
        }

        for (i in 0 until 2) {
            val size = (allpassTuning[i] * scale).roundToInt()
            allpassesL.add(AllPassFilter(size))
            allpassesR.add(AllPassFilter(size + 23))
        }
        
        updateParams()
    }

    private fun updateParams() {
        val feedback = roomSize * 0.28f + 0.7f
        val damp = damping * 0.4f
        for (comb in combsL) { comb.feedback = feedback; comb.damp = damp }
        for (comb in combsR) { comb.feedback = feedback; comb.damp = damp }
    }

    override fun isActive(): Boolean {
        return inputAudioFormat.channelCount == 2 && 
               inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT && 
               amount > 0f
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

        // Processing
        // Simplified loop
        var i = position
        while (i < limit) {
            val inL = inputBuffer.getFloat(i)
            val inR = inputBuffer.getFloat(i + 4)

            // Input mix (mono sum for reverb input usually, or stereo)
            val inputMix = (inL + inR) * 0.015f // Gain down to prevent clipping

            var outL = 0f
            var outR = 0f

            // Accumulate Comb filters
            for (comb in combsL) outL += comb.process(inputMix)
            for (comb in combsR) outR += comb.process(inputMix)

            // Series All-Pass filters
            for (ap in allpassesL) outL = ap.process(outL)
            for (ap in allpassesR) outR = ap.process(outR)

            // Wet/Dry Mix
            val wet = amount
            val dry = 1f - (amount * 0.5f) // Don't drop dry too much

            // Boost wet signal for clarity
            buffer.putFloat(inL * dry + outL * wet * 1.5f)
            buffer.putFloat(inR * dry + outR * wet * 1.5f)
            
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
        // Clear filter buffers
        for(c in combsL) c.buffer.fill(0f)
        for(c in combsR) c.buffer.fill(0f)
        for(a in allpassesL) a.buffer.fill(0f)
        for(a in allpassesR) a.buffer.fill(0f)
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
    }

    // Inner classes for filters
    private class CombFilter(val size: Int) {
        val buffer = FloatArray(size)
        var bufIdx = 0
        var filterStore = 0f
        var feedback = 0.5f
        var damp = 0.5f

        fun process(input: Float): Float {
            val output = buffer[bufIdx]
            filterStore = (output * (1f - damp)) + (filterStore * damp)
            buffer[bufIdx] = input + (filterStore * feedback)
            if (++bufIdx >= size) bufIdx = 0
            return output
        }
    }

    private class AllPassFilter(val size: Int) {
        val buffer = FloatArray(size)
        var bufIdx = 0
        val feedback = 0.5f

        fun process(input: Float): Float {
            val bufOut = buffer[bufIdx]
            val output = -input + bufOut
            buffer[bufIdx] = input + (bufOut * feedback)
            if (++bufIdx >= size) bufIdx = 0
            return output
        }
    }
}