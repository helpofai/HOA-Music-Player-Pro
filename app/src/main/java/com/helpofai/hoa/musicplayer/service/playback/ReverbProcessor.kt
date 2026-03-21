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
            updateParams()
        }

    // Dynamic Room Size for "Long Distance" effect
    private var roomSize = 0.85f 
    private var damping = 0.2f 

    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded: Boolean = false

    // --- PRO REVERB STATE ---
    
    // 1. Pre-Delay Line (The secret to keeping Core Presence)
    private val preDelayL = FloatArray(4096)
    private val preDelayR = FloatArray(4096)
    private var preDelayIdx = 0
    private var preDelaySamples = 0

    // 2. High-Density Freeverb Core
    private val combsL = ArrayList<CombFilter>()
    private val combsR = ArrayList<CombFilter>()
    private val allpassesL = ArrayList<AllPassFilter>()
    private val allpassesR = ArrayList<AllPassFilter>()

    // Tuning (Professional High-Density Reverb)
    private val combTuning = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allpassTuning = intArrayOf(225, 341, 441, 556)

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 || 
           (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT && inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)) {
            return AudioFormat.NOT_SET
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat

        // 35ms Pre-Delay: Allows the initial transient (core presence) of the instrument to hit the ear 
        // completely dry BEFORE the reverberation starts. This creates massive distance without washing out detail.
        preDelaySamples = (inputAudioFormat.sampleRate * 0.035).toInt().coerceAtMost(4095)

        initFilters(inputAudioFormat.sampleRate)

        return outputAudioFormat
    }

    private fun initFilters(sampleRate: Int) {
        combsL.clear()
        combsR.clear()
        allpassesL.clear()
        allpassesR.clear()

        val scale = sampleRate / 44100.0

        for (i in 0 until 8) {
            val size = (combTuning[i] * scale).roundToInt()
            combsL.add(CombFilter(size))
            combsR.add(CombFilter(size + 23)) 
        }

        for (i in 0 until 4) {
            val size = (allpassTuning[i] * scale).roundToInt()
            allpassesL.add(AllPassFilter(size))
            allpassesR.add(AllPassFilter(size + 23))
        }
        
        updateParams()
    }

    private fun updateParams() {
        roomSize = 0.5f + (amount * 0.45f) // Up to 0.95 Room Size
        damping = 0.1f + (amount * 0.6f) // Increase damping with distance (Air Absorption)
        
        val feedback = roomSize * 0.28f + 0.7f
        val damp = damping * 0.4f
        for (comb in combsL) { comb.feedback = feedback; comb.damp = damp }
        for (comb in combsR) { comb.feedback = feedback; comb.damp = damp }
    }

    override fun isActive(): Boolean {
        return inputAudioFormat.channelCount == 2 && 
               (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT || inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) && 
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

        val is16Bit = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        val bytesPerSample = if(is16Bit) 2 else 4
        
        var i = position
        while (i < limit) {
            val inL: Float
            val inR: Float
            
            if(is16Bit) {
                inL = inputBuffer.getShort(i).toFloat() / 32768f
                inR = inputBuffer.getShort(i + bytesPerSample).toFloat() / 32768f
            } else {
                inL = inputBuffer.getFloat(i)
                inR = inputBuffer.getFloat(i + bytesPerSample)
            }

            // --- PRE-DELAY (Saves the Core Presence) ---
            val readIdx = (preDelayIdx - preDelaySamples + preDelayL.size) % preDelayL.size
            val pdL = preDelayL[readIdx]
            val pdR = preDelayR[readIdx]
            
            preDelayL[preDelayIdx] = inL
            preDelayR[preDelayIdx] = inR
            preDelayIdx = (preDelayIdx + 1) % preDelayL.size

            // Send the DELAYED signal to the reverb tank, NOT the immediate dry signal.
            val inputMix = (pdL + pdR) * 0.012f 

            var outL = 0f
            var outR = 0f

            // Accumulate Comb filters (Parallel)
            for (comb in combsL) outL += comb.process(inputMix)
            for (comb in combsR) outR += comb.process(inputMix)

            // Series All-Pass filters (Diffusion)
            for (ap in allpassesL) outL = ap.process(outL)
            for (ap in allpassesR) outR = ap.process(outR)

            // --- PRO DRY/WET MIX ---
            // Keep Dry at 1.0. This ensures instruments NEVER lose their upfront, punchy presence.
            // The Reverb comes 35ms behind them, creating a massive acoustic space in the background.
            val dryScale = 1.0f 
            val wetScale = amount * 2.5f 

            val finalL = (inL * dryScale) + (outL * wetScale)
            val finalR = (inR * dryScale) + (outR * wetScale)
            
            if(is16Bit) {
                buffer.putShort((finalL * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                buffer.putShort((finalR * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            } else {
                buffer.putFloat(finalL)
                buffer.putFloat(finalR)
            }
            
            i += bytesPerSample * 2
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
        for(c in combsL) { c.buffer.fill(0f); c.filterStore = 0f }
        for(c in combsR) { c.buffer.fill(0f); c.filterStore = 0f }
        for(a in allpassesL) a.buffer.fill(0f)
        for(a in allpassesR) a.buffer.fill(0f)
        preDelayL.fill(0f)
        preDelayR.fill(0f)
        preDelayIdx = 0
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
    }

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
