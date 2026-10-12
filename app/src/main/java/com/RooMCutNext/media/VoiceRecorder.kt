package com.RooMCutNext.media

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * Voiceover microphone recorder (v1.1.4). Wraps [MediaRecorder] into a
 * start/stop pair producing an AAC/M4A file directly inside the project's
 * media directory — the same place MediaImporter copies picked files into,
 * so the recorded take becomes an ordinary MediaAsset + AudioClip with the
 * whole existing pipeline (metadata, volume, fades, undo, export).
 *
 * Not restartable: create a new instance for every take.
 */
class VoiceRecorder(private val context: Context, private val outputFile: File) {

    private var recorder: MediaRecorder? = null

    fun start() {
        check(recorder == null) { "recorder already started" }
        // minSdk 31: the scoped-storage-aware constructor is always available.
        val r = MediaRecorder(context)
        try {
            r.setOutputFile(outputFile.absolutePath)
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
            r.setAudioChannels(1)
            r.prepare()
            r.start()
            recorder = r
        } catch (e: Exception) {
            runCatching { r.release() }
            outputFile.delete()
            throw e
        }
    }

    /**
     * Finish the take. Returns false when nothing was recorded (never
     * started) or the recording was too short to produce a valid MP4 atom
     * structure — the partial file is deleted either way.
     */
    fun stop(): Boolean {
        val r = recorder ?: run {
            outputFile.delete()
            return false
        }
        recorder = null
        return try {
            r.stop()
            r.release()
            outputFile.exists() && outputFile.length() > 0L
        } catch (e: Exception) {
            // stop() throws when the take is shorter than the encoder's
            // priming (~0.5s): the file is an unusable fragment.
            runCatching { r.release() }
            outputFile.delete()
            false
        }
    }

    /** Abort without keeping anything. */
    fun cancel() {
        val r = recorder ?: run {
            outputFile.delete()
            return
        }
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
        outputFile.delete()
    }
}
