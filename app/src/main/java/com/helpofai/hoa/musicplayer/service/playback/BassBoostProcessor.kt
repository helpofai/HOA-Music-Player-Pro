package com.helpofai.hoa.musicplayer.service.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
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

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 || inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioFormat.NOT_SET
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat

        // Calculate alpha for ~90Hz Low Pass Filter (Deep Bass focus)
        val cutOffFreq = 90.0
        val sampleRate = inputAudioFormat.sampleRate.toDouble()
        val dt = 1.0 / sampleRate
        val rc = 1.0 / (2.0 * PI * cutOffFreq)
        alpha = (dt / (rc + dt)).toFloat()

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

        // Boost factor: scale strength to a much higher gain (e.g. max +18dB boost effect)
        // We really want that "thump".
        val boostAmount = strength * 10.0f 

        var i = position
        while (i < limit) {
            val leftIn = inputBuffer.getFloat(i)
            val rightIn = inputBuffer.getFloat(i + 4)

            // Simple Low Pass Filter
            // y[n] = y[n-1] + alpha * (x[n] - y[n-1])
            val leftLow = lastOutputL + alpha * (leftIn - lastOutputL)
            val rightLow = lastOutputR + alpha * (rightIn - lastOutputR)

            lastOutputL = leftLow
            lastOutputR = rightLow

            // Mix: Original + Bass * Boost
            val leftOut = leftIn + (leftLow * boostAmount)
            val rightOut = rightIn + (rightLow * boostAmount)

            // Soft Clip to prevent harsh digital clipping (makes sound smoother/cleaner)
            // Using tanh gives a nice analog saturation feel at high gain
            buffer.putFloat(tanh(leftOut))
            buffer.putFloat(tanh(rightOut))
            
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
        lastOutputL = 0f
        lastOutputR = 0f
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        inputAudioFormat = AudioFormat.NOT_SET
        outputAudioFormat = AudioFormat.NOT_SET
    }
}