package com.RooMCutNext.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.1 track-level mutes — the contract of Project.videoTrackMuted /
 * Project.pipTrackMuted:
 *
 *  1. clipAudioMuted ORs the track switch with each clip's own mute — the
 *     single source of truth BOTH pipelines (preview composition and export)
 *     consume, so they can never disagree (WYSIWYG).
 *  2. The flags never touch the audio lanes: AudioClip.muted stays the only
 *     authority for background music.
 *  3. Backward/forward compatibility: old project JSON (no such fields)
 *     deserializes to false; new JSON round-trips.
 */
class TrackMuteTest {

    private fun clip(muted: Boolean = false) = Clip(
        id = "c1", assetId = "a1",
        sourceInUs = 0L, sourceOutUs = 1_000_000L, muted = muted,
    )

    private fun project(videoMuted: Boolean = false, pipMuted: Boolean = false, clipMuted: Boolean = false) =
        Project(
            id = "p1", name = "n",
            clips = listOf(clip(clipMuted)),
            videoTrackMuted = videoMuted,
            pipTrackMuted = pipMuted,
        )

    // ---------------- effective mute ----------------

    @Test
    fun `clipAudioMuted false when nothing requests mute`() {
        assertFalse(project().clipAudioMuted(clip()))
    }

    @Test
    fun `clipAudioMuted true when the track is muted`() {
        val p = project(videoMuted = true)
        assertTrue(p.clipAudioMuted(clip()))
    }

    @Test
    fun `clipAudioMuted true when the clip itself is muted`() {
        val p = project()
        assertTrue(p.clipAudioMuted(clip(muted = true)))
    }

    @Test
    fun `clipAudioMuted ORs track and clip mutes`() {
        // Track muted + clip unmuted, track unmuted + clip muted, both.
        assertTrue(project(videoMuted = true).clipAudioMuted(clip(false)))
        assertTrue(project(videoMuted = false).clipAudioMuted(clip(true)))
        assertTrue(project(videoMuted = true).clipAudioMuted(clip(true)))
    }

    @Test
    fun `track mute is non-destructive to per-clip flags`() {
        val p = project(videoMuted = true, clipMuted = false)
        assertFalse(p.clips.single().muted)
        assertTrue(p.clipAudioMuted(p.clips.single()))
        // Untoggling the track restores the clip's own state exactly.
        val off = p.copy(videoTrackMuted = false)
        assertFalse(off.clipAudioMuted(off.clips.single()))
    }

    @Test
    fun `pip track flag is independent from the video track flag`() {
        val p = project(pipMuted = true)
        assertFalse(p.videoTrackMuted)
        assertTrue(p.pipTrackMuted)
        assertFalse(p.clipAudioMuted(clip()))
    }

    // ---------------- serialization compatibility ----------------

    @Test
    fun `old project json without the flags deserializes to unmuted`() {
        val json = Json { ignoreUnknownKeys = true }
        // A v1.3.0-era project payload: no videoTrackMuted / pipTrackMuted.
        val legacy = """
            {"id":"p1","name":"n","fps":30,"width":1920,"height":1080,
             "clips":[{"id":"c1","assetId":"a1",
                       "sourceInUs":0,"sourceOutUs":1000000}],
             "schemaVersion":2}
        """.trimIndent()
        val p = json.decodeFromString<Project>(legacy)
        assertFalse(p.videoTrackMuted)
        assertFalse(p.pipTrackMuted)
        assertFalse(p.clipAudioMuted(p.clips.single()))
    }

    @Test
    fun `track mute flags round-trip through json`() {
        val json = Json { ignoreUnknownKeys = true }
        val p = project(videoMuted = true, pipMuted = true)
        val out = json.decodeFromString<Project>(json.encodeToString(p))
        assertTrue(out.videoTrackMuted)
        assertTrue(out.pipTrackMuted)
    }
}
