package com.RooMCutNext.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream

/**
 * Generates small helper media files inside the project's media directory:
 *
 *  - [silenceWav]: a PCM WAV containing pure silence, used as padding items
 *    inside audio sequences (Media3 sequences are strictly concatenated, so a
 *    clip starting at t>0 needs a leading silence item).
 *
 *  - [blackPng]: a solid black still image used as the video background when
 *    the timeline has no video clips (text/audio-only project) or when text /
 *    audio extend past the last video clip. Generated at the project's aspect
 *    ratio so the frame size matches the video track exactly.
 *
 * Files are cached on disk and reused across rebuilds; they are tiny
 * (an all-black PNG compresses to a few KB regardless of resolution).
 */
object GeneratedMedia {

    private const val SILENCE_SAMPLE_RATE = 8000
    private const val SILENCE_CHANNELS = 1
    private const val SILENCE_BITS = 16

    private fun genDir(mediaDir: File): File =
        File(mediaDir, "gen").apply { mkdirs() }

    /**
     * Return (creating if needed) a silent WAV of at least [durationUs],
     * rounded up to 10ms. Cached per duration.
     */
    fun silenceWav(mediaDir: File, durationUs: Long): File {
        val ms = ((durationUs / 1000L + 9L) / 10L) * 10L // round up to 10ms
        val safeMs = ms.coerceIn(10L, 30L * 60L * 1000L)
        val file = File(genDir(mediaDir), "silence_${safeMs}ms.wav")
        if (file.exists() && file.length() > 44L) return file
        synchronized(this) {
            if (file.exists() && file.length() > 44L) return file
            val samples = (safeMs * SILENCE_SAMPLE_RATE / 1000L).coerceAtLeast(1L)
            val dataBytes = samples * SILENCE_CHANNELS * (SILENCE_BITS / 8)
            val byteRate = SILENCE_SAMPLE_RATE * SILENCE_CHANNELS * (SILENCE_BITS / 8)
            FileOutputStream(file).use { out ->
                fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
                fun le32(v: Long) {
                    out.write((v and 0xFF).toInt()); out.write(((v shr 8) and 0xFF).toInt())
                    out.write(((v shr 16) and 0xFF).toInt()); out.write(((v shr 24) and 0xFF).toInt())
                }
                fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII).forEach { out.write(it.toInt()) }

                ascii("RIFF"); le32(36 + dataBytes); ascii("WAVE")
                ascii("fmt "); le32(16); le16(1) // PCM
                le16(SILENCE_CHANNELS); le32(SILENCE_SAMPLE_RATE.toLong())
                le32(byteRate.toLong()); le16(SILENCE_CHANNELS * (SILENCE_BITS / 8)); le16(SILENCE_BITS)
                ascii("data"); le32(dataBytes)

                // zero-filled data: write in chunks to avoid one huge allocation
                val chunk = ByteArray(8192)
                var remaining = dataBytes
                while (remaining > 0) {
                    val n = minOf(remaining, chunk.size.toLong()).toInt()
                    out.write(chunk, 0, n)
                    remaining -= n
                }
            }
        }
        return file
    }

    /**
     * Return (creating if needed) a solid black PNG at the given aspect.
     * Cached per width-height pair.
     */
    fun blackPng(mediaDir: File, width: Int, height: Int): File {
        val w = width.coerceIn(2, 4096)
        val h = height.coerceIn(2, 4096)
        val file = File(genDir(mediaDir), "black_${w}x${h}.png")
        if (file.exists() && file.length() > 0L) return file
        synchronized(this) {
            if (file.exists() && file.length() > 0L) return file
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = android.graphics.Color.BLACK })
            FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            bmp.recycle()
        }
        return file
    }

    /** Actual duration of the silence file for [durationUs] (may be rounded up). */
    fun silenceDurationUs(durationUs: Long): Long {
        val ms = ((durationUs / 1000L + 9L) / 10L) * 10L
        return (ms.coerceIn(10L, 30L * 60L * 1000L) * 1000L)
    }
}
