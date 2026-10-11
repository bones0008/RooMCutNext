package com.RooMCutNext.export.effects

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Time-varying audio gain: per-clip volume, fade-in and fade-out.
 *
 * A plain [BaseAudioProcessor] that scales PCM samples. Progress is derived
 * from the sample count (the audio clock), so it stays exact regardless of
 * buffering. Fades are computed on the item-local timeline time base.
 */
class GainAudioProcessor(
    private val volume: Float,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
    private val itemDurationUs: Long,
) : BaseAudioProcessor() {

    private var sampleRate = 0
    private var channels = 1
    private var framesProcessed = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        return inputAudioFormat
    }

    private fun frameTimeUs(): Long =
        if (sampleRate > 0) framesProcessed * 1_000_000L / sampleRate else 0L

    private fun gainAt(timeUs: Long): Float {
        var g = volume
        if (fadeInUs > 0 && timeUs < fadeInUs) {
            g *= (timeUs.toFloat() / fadeInUs).coerceIn(0f, 1f)
        }
        if (fadeOutUs > 0) {
            val fadeStart = itemDurationUs - fadeOutUs
            if (timeUs > fadeStart) {
                g *= ((itemDurationUs - timeUs).toFloat() / fadeOutUs).coerceIn(0f, 1f)
            }
        }
        return g.coerceIn(0f, 2f)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        // Media3 PCM buffers are little-endian.
        inputBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val output = replaceOutputBuffer(remaining).also {
            it.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        }

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                for (i in 0 until remaining / 2) {
                    val g = gainAt(frameTimeUs())
                    val v = inputBuffer.short
                    val out = (v * g.toDouble()).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    output.putShort(out.toShort())
                    if ((i + 1) % channels == 0) framesProcessed++
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                for (i in 0 until remaining / 4) {
                    val g = gainAt(frameTimeUs())
                    val v = inputBuffer.float
                    output.putFloat((v * g).coerceIn(-1f, 1f))
                    if ((i + 1) % channels == 0) framesProcessed++
                }
            }
            else -> {
                // Passthrough for unexpected encodings (should not happen after onConfigure).
                output.put(inputBuffer)
            }
        }
        output.flip()
    }

    override fun onFlush() {
        framesProcessed = 0L
    }
}
