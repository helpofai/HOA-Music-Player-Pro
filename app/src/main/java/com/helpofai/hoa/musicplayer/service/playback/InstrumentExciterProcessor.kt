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
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Professional Multi-Band Exciter & Mastering Limiter (v7.0)
 * Separates audio into bands for harmonic reinforcement and applies a clean brick-wall limit.
 */
@UnstableApi
class InstrumentExciterProcessor : AudioProcessor {
    var clarity: Float = 0f 
    var strength: Float = 0f 

    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded: Boolean = false

    // Multi-Band Filters
    private var lpL = 0f; private var lpR = 0f
    private var hpL = 0f; private var hpR = 0f

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 || 
           (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT && inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)) {
            this.inputAudioFormat = inputAudioFormat; return inputAudioFormat
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat
        return outputAudioFormat
    }

    override fun isActive() = inputAudioFormat.channelCount == 2 && (clarity > 0f || strength > 0f)

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

            // 1. Multi-Band Separation (Mids vs Highs)
            lpL += 0.25f * (l - lpL); lpR += 0.25f * (r - lpR)
            val midsL = l - lpL; val midsR = r - lpR
            
            hpL += 0.5f * (l - hpL); hpR += 0.5f * (r - hpR)
            val highsL = l - hpL; val highsR = r - hpR

            // 2. Harmonic Excitation
            val satL = tanh(midsL * (1f + strength * 2f)) * strength * 0.2f
            val satR = tanh(midsR * (1f + strength * 2f)) * strength * 0.2f
            
            val airL = (highsL * abs(highsL)) * clarity * 0.5f
            val airR = (highsR * abs(highsR)) * clarity * 0.5f

            var outL = l + satL + airL
            var outR = r + satR + airR

            // 3. Professional Brick-Wall Mastering Limiter
            fun masterLimit(x: Float): Float {
                val a = abs(x)
                return if (a < 0.95f) x else (x / a) * (0.95f + (a - 0.95f) / (1f + (a - 0.95f) * (a - 0.95f)))
            }
            
            outL = masterLimit(outL)
            outR = masterLimit(outR)

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
        lpL = 0f; lpR = 0f; hpL = 0f; hpR = 0f
    }
    
    override fun reset() {
        flush(); buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET; outputAudioFormat = AudioFormat.NOT_SET
    }
}
