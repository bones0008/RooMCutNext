package com.RooMCutNext.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.3 — HeadSyncPolicy, the in-window clock steering that replaces the
 * v1.5.2 "let the head run ahead" behavior (the mid-window effect-vanish:
 * head progress hit 1 early, parked at full-B over a still-mid-window
 * playhead, and the cooldown-free ENDED rewind seek-stormed on top).
 */
class HeadSyncPolicyTest {

    @Test
    fun `ahead drift engages the trim factor`() {
        assertEquals(1f, HeadSyncPolicy.speedFactor(aheadUs = 50_000L, wasTrimming = false), 1e-6f)
        assertEquals(
            HeadSyncPolicy.AHEAD_TRIM_FACTOR,
            HeadSyncPolicy.speedFactor(aheadUs = 100_000L, wasTrimming = false),
            1e-6f,
        )
    }

    @Test
    fun `trim hysteresis holds until the drift bleeds back inside the release band`() {
        // Engaged at 100ms; 50ms (above the 40ms release) keeps trimming.
        assertTrue(HeadSyncPolicy.speedFactor(100_000L, wasTrimming = true) < 1f)
        assertTrue(HeadSyncPolicy.speedFactor(50_000L, wasTrimming = true) < 1f)
        // 30ms — inside the release band — restores full speed.
        assertEquals(1f, HeadSyncPolicy.speedFactor(30_000L, wasTrimming = true), 1e-6f)
    }

    @Test
    fun `behind drift never trims`() {
        // Negative ahead = the head lags the playhead (the v1.2.9 doctrine:
        // leave alone).
        assertEquals(1f, HeadSyncPolicy.speedFactor(aheadUs = -400_000L, wasTrimming = false), 1e-6f)
        assertEquals(1f, HeadSyncPolicy.speedFactor(aheadUs = -400_000L, wasTrimming = true), 1e-6f)
    }

    @Test
    fun `park engages at the park threshold and holds until the unpark band`() {
        // 400ms: below the 500ms park threshold.
        assertFalse(HeadSyncPolicy.shouldPark(400_000L, wasParked = false))
        // 600ms: parks.
        assertTrue(HeadSyncPolicy.shouldPark(600_000L, wasParked = false))
        // Parked at 300ms (above the 250ms unpark): stays parked.
        assertTrue(HeadSyncPolicy.shouldPark(300_000L, wasParked = true))
        // 200ms: unparks.
        assertFalse(HeadSyncPolicy.shouldPark(200_000L, wasParked = true))
    }

    @Test
    fun `policy invariants for the controller wiring`() {
        // Park threshold above trim trigger: the escalation ladder is
        // trim-then-park, never park-first.
        assertTrue(HeadSyncPolicy.AHEAD_PARK_US > HeadSyncPolicy.AHEAD_TRIM_TRIGGER_US)
        assertTrue(HeadSyncPolicy.AHEAD_UNPARK_US > HeadSyncPolicy.AHEAD_TRIM_RELEASE_US)
        // The trim factor is a gentle bleed, not a stall.
        assertTrue(HeadSyncPolicy.AHEAD_TRIM_FACTOR in 0.9f..0.999f)
    }
}
