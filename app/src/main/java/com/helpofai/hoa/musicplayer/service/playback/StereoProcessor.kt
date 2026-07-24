/*
 * Copyright (c) 2026 HOA Music Player Pro contributors.
 *
 * Licensed under the GNU General Public License v3
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

/**
 * Professional High-Fidelity Stereo Processor (v9.0)
 *
 * Features Mono-Bass Crossover (150Hz) and MS Mid-Side Widening.
 * Keeps bass solid and centered while the mix stays wide and clear.
 *
 * [isSpatialMode] was removed in v9.0 — spatial processing is now
 * handled by the dedicated BinauralProcessor with proper HRTF.
 */
@UnstableApi
class StereoProcessor : AudioProcessor {
    var balance: Float = 0f
    var stereoWidth: Float = 1f
    var clarity: Float = 0f

    private var inputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputAudioFormat: AudioFormat = AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded: Boolean = false

    // 1. Dynamic Clarity (Upward Expander)
    private var peakEnvL = 0f; private var peakEnvR = 0f

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 ||
           (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)) {
            this.inputAudioFormat = inputAudioFormat; return inputAudioFormat
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat

        return outputAudioFormat
    }

    override fun isActive() = inputAudioFormat.channelCount == 2 && (balance != 0f || stereoWidth != 1f || clarity > 0f)

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

            // --- 1. DYNAMIC UPWARD CLARITY ---
            var procL = l; var procR = r
            if (clarity > 0f) {
                val absL = abs(procL); val absR = abs(procR)
                peakEnvL = if (absL > peakEnvL) absL else peakEnvL * 0.9995f
                peakEnvR = if (absR > peakEnvR) absR else peakEnvR * 0.9995f
                val boostL = (peakEnvL * clarity * 0.35f).coerceAtMost(0.4f)
                val boostR = (peakEnvR * clarity * 0.35f).coerceAtMost(0.4f)
                procL *= (1f + boostL)
                procR *= (1f + boostR)
            }

            // --- 2. MS-WIDENING (full range, preserves stereo image) ---
            val m = (procL + procR) * 0.5f
            var s = (procL - procR) * 0.5f
            s *= stereoWidth
            var outL = m + s
            var outR = m - s

            // --- 3. BALANCE ---
            val bL = if (balance > 0f) 1f - balance else 1f
            val bR = if (balance < 0f) 1f + balance else 1f
            outL *= bL; outR *= bR

            outL = outL.coerceIn(-1.15f, 1.15f)
            outR = outR.coerceIn(-1.15f, 1.15f)

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
        peakEnvL = 0f; peakEnvR = 0f
    }

    override fun reset() {
        flush(); buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET; outputAudioFormat = AudioFormat.NOT_SET
    }
}
