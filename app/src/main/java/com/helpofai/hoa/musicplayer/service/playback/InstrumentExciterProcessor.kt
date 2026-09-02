/*
 * Copyright (c) 2026 HOA Music Player Pro contributors.
 *
 * Licensed under the GNU General Public License v3
 */
package com.helpofai.hoa.musicplayer.service.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Professional Multi-Band Exciter & Mastering Limiter (v10.0)
 *
 * Built on Media3 BaseAudioProcessor for robust lifecycle and zero playback stalling:
 * - Separates audio into bands for harmonic reinforcement.
 * - Applies smooth brick-wall mastering limit.
 */
@UnstableApi
class InstrumentExciterProcessor : BaseAudioProcessor() {
    var clarity: Float = 0f
    var strength: Float = 0f

    // Multi-Band Filters
    private var lpL = 0f; private var lpR = 0f
    private var hpL = 0f; private var hpR = 0f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 ||
           (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)) {
            return AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outBuffer = replaceOutputBuffer(remaining)

        // Fast bit-perfect bypass when disabled
        if (clarity <= 0f && strength <= 0f) {
            outBuffer.put(inputBuffer)
            outBuffer.flip()
            return
        }

        val is16Bit = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        val bytesPerSample = if (is16Bit) 2 else 4

        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        var i = position
        while (i < limit) {
            var l: Float
            var r: Float
            if (is16Bit) {
                l = inputBuffer.getShort(i).toFloat() / 32768f
                r = inputBuffer.getShort(i + bytesPerSample).toFloat() / 32768f
            } else {
                l = inputBuffer.getFloat(i)
                r = inputBuffer.getFloat(i + bytesPerSample)
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
            outL = masterLimit(outL)
            outR = masterLimit(outR)

            if (is16Bit) {
                outBuffer.putShort((outL * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                outBuffer.putShort((outR * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            } else {
                outBuffer.putFloat(outL)
                outBuffer.putFloat(outR)
            }
            i += bytesPerSample * 2
        }

        inputBuffer.position(limit)
        outBuffer.flip()
    }

    private fun masterLimit(x: Float): Float {
        val a = abs(x)
        return if (a < 0.95f) x else (x / a) * (0.95f + (a - 0.95f) / (1f + (a - 0.95f) * (a - 0.95f)))
    }

    override fun onFlush() {
        lpL = 0f; lpR = 0f; hpL = 0f; hpR = 0f
    }

    override fun onReset() {
        onFlush()
    }
}
