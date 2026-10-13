package com.RooMCutNext.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0: the PiP corrective-seek discipline as a PURE policy — the
 * regression test for the "three audio sources" stutter metronome.
 *
 * The bug (v1.3.3 field reports): with BGM + main-clip audio + PiP audio
 * all active, a PiP's audio pipeline opens SLOWER than the single-source
 * 100-250ms latency the v1.2.10 threshold assumed. The playing hard-realign
 * gate sat at 400ms — inside the multi-audio restart-latency range — so the
 * drift parked permanently above it and the 500ms cooldown re-fired the seek
 * forever: seek -> decoder flush + keyframe re-decode + audio reopen ->
 * latency re-incurred -> still over threshold -> next cooldown expiry seeks
 * again. The user saw "PiP stutters, its audio stutters", or the seek bursts
 * starved the body pipeline ("everything freezes, sound keeps going"), and
 * the second play was fine because the warm pipeline re-opened under the
 * threshold.
 *
 * The fix mirrors the v1.2.10 head decision (headHardRealignDriftUs): the
 * playing gate moves to 1.5s — beyond any realistic (re)start latency, so a
 * constant sub-1.5s lag (which has no visual reference for PiP content)
 * settles instead of being fought, while a genuinely broken alignment
 * (a mid-window user seek, seconds off) still realigns — once.
 */
class PipSyncPolicyTest {

    // ---- paused (scrub) tracking stays tight ----

    @Test
    fun `paused drift under a frame is left alone`() {
        assertFalse(
            PipSyncPolicy.shouldCorrectiveSeek(
                driftAbsUs = 19_000L, isPlaying = false,
                nowMs = 1_000L, lastSeekMs = 0L,
            ),
        )
    }

    @Test
    fun `paused drift over a frame seeks immediately regardless of cooldown`() {
        // Scrub tracking has no cooldown by design: seeks are cheap while
        // nothing renders, and frame-accurate tracking is the contract.
        assertTrue(
            PipSyncPolicy.shouldCorrectiveSeek(
                driftAbsUs = 21_000L, isPlaying = false,
                nowMs = 1_000L, lastSeekMs = 999L, // just seeked — still seeks
            ),
        )
    }

    // ---- THE metronome regression (v1.4.0's actual bug) ----

    @Test
    fun `constant multi-audio start latency drift is never seeked while playing`() {
        // Simulate the field condition: PiP opened ~600ms late (three audio
        // sources inflating the audio-pipeline open), so its position runs a
        // CONSTANT 600ms behind the timeline while playing. 30Hz sync ticks
        // across 5 seconds: the OLD 400ms gate seeked ~10x (every cooldown
        // expiry); the policy must seek ZERO times — a constant lag with no
        // visual reference is left alone, not fought.
        var seeks = 0
        var lastSeekMs = 0L
        var nowMs = 0L
        while (nowMs < 5_000L) {
            if (PipSyncPolicy.shouldCorrectiveSeek(
                    driftAbsUs = 600_000L, isPlaying = true,
                    nowMs = nowMs, lastSeekMs = lastSeekMs,
                )
            ) {
                seeks++
                lastSeekMs = nowMs
            }
            nowMs += 33L // ~30Hz sync tick
        }
        assertEquals("constant sub-threshold drift must not re-arm the gate", 0, seeks)
    }

    @Test
    fun `genuinely broken alignment realigns exactly once then waits out the cooldown`() {
        // A user seek landed mid-window: drift 2.2s, past the 1.5s gate.
        // First tick realigns; ticks inside the 500ms cooldown do NOT;
        // after the cooldown the drift has settled (the seek landed), so
        // still exactly one seek total.
        var seeks = 0
        var lastSeekMs = 0L
        var nowMs = 0L
        var driftUs = 2_200_000L
        while (nowMs < 2_000L) {
            if (PipSyncPolicy.shouldCorrectiveSeek(
                    driftAbsUs = driftUs, isPlaying = true,
                    nowMs = nowMs, lastSeekMs = lastSeekMs,
                )
            ) {
                seeks++
                lastSeekMs = nowMs
                driftUs = 50_000L // the realign landed; drift settled
            }
            nowMs += 33L
        }
        assertEquals(1, seeks)
    }

    @Test
    fun `playing drift under the hard gate is left alone`() {
        assertFalse(
            PipSyncPolicy.shouldCorrectiveSeek(
                driftAbsUs = 1_499_000L, isPlaying = true,
                nowMs = 10_000L, lastSeekMs = 0L,
            ),
        )
    }

    @Test
    fun `playing drift past the hard gate respects the cooldown`() {
        // Over the gate but the cooldown has NOT expired: hold.
        assertFalse(
            PipSyncPolicy.shouldCorrectiveSeek(
                driftAbsUs = 1_600_000L, isPlaying = true,
                nowMs = 400L, lastSeekMs = 100L, // 300ms since last seek < 500ms
            ),
        )
        // Cooldown expired: seek.
        assertTrue(
            PipSyncPolicy.shouldCorrectiveSeek(
                driftAbsUs = 1_600_000L, isPlaying = true,
                nowMs = 700L, lastSeekMs = 100L, // 600ms since last seek
            ),
        )
    }

    // ---- ENDED rewind (v1.2.7 replay semantics, unchanged) ----

    @Test
    fun `ended player rewinds when the playhead is back before its source span`() {
        assertTrue(PipSyncPolicy.endedRewind(localUs = 1_000_000L, sourceSpanUs = 3_000_000L))
    }

    @Test
    fun `ended player holds its last frame near or past the span end`() {
        // Within the hold margin of the end: hold (the window outlives the source).
        assertFalse(PipSyncPolicy.endedRewind(localUs = 2_950_000L, sourceSpanUs = 3_000_000L))
        // Past the end: hold.
        assertFalse(PipSyncPolicy.endedRewind(localUs = 3_500_000L, sourceSpanUs = 3_000_000L))
    }
}
