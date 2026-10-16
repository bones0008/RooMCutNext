# RooMCutNext

**Native Android single-layer timeline video editor** — Kotlin + Jetpack Compose + Media3.

[中文](README.md) | **[English](README.en.md)**

A complete editing tool built from an empty project to exporting MP4: import media → arrange on timeline → precise cutting → preview → export.

---

## Contents

1. [Feature List](#feature-list)
2. [Install the APK](#install-the-apk)
3. [User Guide](#user-guide)
4. [Architecture](#architecture)
5. [Handling Mixed 30FPS / 24FPS Footage](#frame-rate-strategy)
6. [Known Limitations & Roadmap](#known-limitations)
7. [License (AGPL-3.0)](#license-agpl-30)
8. [Third-Party Components & Licenses](#third-party-components--licenses)

---

## Feature List

Mapped against the original V1 requirements (✅ = implemented, ⚠️ = simplified, ❌ = deferred):

### 0. Project Management (Home)
- ✅ Project card cover = **auto-captured frame #20 of the timeline** (v1.0.11): refreshed every time you return to the project list; the cover updates after cutting / reordering / speed changes (frame #20 because the first frame is often black); empty-timeline / audio-only / failed-extraction projects fall back to a film icon
- ✅ Two-step project creation (v1.0.11): pick landscape/portrait first → then name the project (editable; blank defaults to "Project N")
- ✅ Rename / duplicate / delete (with confirmation) / sorted by most recently edited
- ✅ App icon update (v1.0.11): black background + blue play arrow

### 1. Media Management
- ✅ Pick videos/photos from the system gallery (Photo Picker, multi-select)
- ✅ Pick from file managers (SAF; supports MKV / WebM and more)
- ✅ MP4 / MOV / MKV / WebM (device decoder permitting) + JPG / PNG / WebP / HEIC
- ✅ Images as standalone timeline clips (default 3 s, stretchable to 60 s)
- ✅ Media info: resolution / FPS / duration / codec / audio / file size (Info panel)
- ✅ Media is copied into the app's private directory on import (eliminates URI-permission expiry corrupting projects)

### 2. Single-Layer Timeline (Core)
- ✅ Add / delete / drag-reorder / tap-select / highlight / duration labels
- ✅ Time ruler (adaptive ticks, **one number every 5 s at normal zoom** (v1.0.9); stretches to 10/15/30 s when zoomed out, falls back to per-second when zoomed in for trim reference)
- ✅ **Fixed playhead + scrolling timeline (LibreCut-style, v1.0.4)**: the playhead stays centered; clips move right-to-left during playback; dragging any blank area of the timeline scrubs with live video
- ✅ **Tap blank to deselect (v1.0.5)**: tapping the ruler/blank area no longer seeks — it only clears the selection; positioning is done by press-and-drag only
- ✅ Two-finger pinch zoom (6–480 px/s, playhead-anchored) + zoom buttons + fit-to-screen
- ✅ Free clip placement via drag (v1.0.9): drop a clip anywhere, gaps allowed; live drop preview + haptic feedback
- ✅ **Fixed timeline height + vertical scrolling (v1.5.4)**: the track stack is now **bottom-anchored** — the video/audio tracks are pinned at the bottom of the timeline area and text/PiP layers grow upward, so no number of text layers can push the video track under the toolbar (on portrait projects the old top-anchored layout saturated at 2 text + 1 PiP + video + audio and clipped everything past it). When the stack outgrows the visible area it automatically becomes vertically scrollable (drag blank space or track backgrounds up/down; swipe up to reveal lower content); the ruler stays pinned at the top; a selected/newly-added layer that is off-screen auto-scrolls minimally into view; the timeline's total height is fixed in both portrait and landscape projects. The vertical layout math (region geometry / anchoring / scroll clamping / selection reveal) is extracted into pure functions pinned by unit tests
- ✅ **Transition preview smoothness triple fix (v1.5.5)**: the transition head player is now **session-persistent** (media3 playlist-mode in-place effect-chain swap reusing one EGL context and the compiled shader cache — window changes no longer tear down and rebuild the whole pipeline, killing both the end-of-transition hitch and the accumulate-over-plays degradation); the post-window fade-out tail now **interpolates per display frame** (it used to step alpha at the 33ms sync cadence — the visible "end-of-transition jump"); the head's **audio ducks with the tail** (removing the short echo where the head overlapped the body's own clip audio at the boundary)
- ✅ **LIVE fade-out tail (v1.5.6)**: the preview head clip now runs PAST the transition window end (600ms tail + margin), so the fade-out fades the incoming clip's LIVE frames instead of a frozen boundary frame — the frozen frame used to drift up to 400ms behind the body underneath, and that growing content offset read as the end-of-transition "jump" no alpha smoothing could remove; the offset is now the body's render lag (~1-2 frames), smooth by construction (the transition shader pins p>=1 to fully-opaque B, and export is untouched). Also fixes the v1.5.5 persistent-slot **stale-frame flash** (a reused TextureView briefly showed the PREVIOUS window's last frame on entries without premount lead — the view now stays transparent until the new stream's first frame renders), lengthens the tail to 600ms of pure masking coverage, hard-mutes the head's audio at the boundary (the body's own clip-B audio takes over cleanly), and adds a head mount→first-frame latency readout to the hidden DIAG overlay for field diagnosis of the "degrades over plays" report
- ✅ **Four-layer root fix for preview degradation over plays (v1.5.7)**: the field retest (DIAG readout: head mount→first-frame latency at 16474ms) traced to cumulative damage inside media3 1.11.1's input-stream switching machinery — (1) **pre-stop sink scrub**: a same-position seek before every head retarget forces videoSink.flush, dropping ALL in-flight frames the old stop() used to strand in the output queue against the fresh clock (the stranding was what snowballed first-frame latency to the 16-second scale — the "next clip appears then freezes" symptom); (2) **drain gate**: automatic premount retargets defer while the outgoing item still has live content, and the tail extension is capped by the NEXT window's geometry so short clips are never cut mid-flight; (3) **value-stable effect chain**: style/progress were already per-frame uniforms, so the transition shader is now ONE session-stable instance mutated in place — media3's list comparison no longer triggers the release+recompile of the whole shader chain on every retarget (previously compiled TWICE per window); (4) **bounded recycle**: the persistent head player is safely rebuilt after serving 12 transition windows, hard-capping any residual fallout from media3's own sampling-shader release race (upstream TODO b/417680219)
- ✅ **Audio import lands at the playhead (v1.5.5)**: imported audio no longer appends after the last clip — it inserts from the playhead position (overlaps are fine, lanes pack automatically; multiple files queue sequentially); **zoom removed from the transition panel** (field report: the effect reads as dead on device, and a zoom belongs at a clip's ending, not between two clips; the enum entries stay for old-project compatibility but the panel no longer offers them)

### 3. Cutting
- ✅ Trim: drag the rounded handles at both ends of the selected clip with live duration display; **source-frame accurate** (snap-once algorithm keeps left/right durations conserved)
- ✅ Split: frame-accurate cut at the playhead producing two clips; **audio splits the same way (v1.0.9)** — whichever track (video/audio/**PiP**) is under the playhead gets split, one undo step; **PiP splitting (v1.5.5)**: layers now carry a source offset (sourceInUs), so the right half plays the source from the cut point (the halves join sequentially, exactly like splitting a video clip), and dragging the left edge advances the source window instead of restarting the material from its head
- ✅ Delete **leaves a hole** (v1.0.8): deleting a middle clip leaves a black gap (text can be layered on top); deleting a tail clip closes the gap

### 4. Gaps / Ripple (redone in v1.0.8, free placement in v1.0.9)
- ✅ **Free placement by dragging (v1.0.9)**: drop a clip anywhere; a black gap is automatically left between clips (live drop preview and gap duration shown while dragging; snaps shut when close); gap blocks display their duration (e.g. "2.0s")
- ✅ **Deleting a middle clip leaves a black gap** (shown as a black block on the timeline); later clips stay put; text can be layered over the gap
- ✅ Trim / speed changes still ripple (later clips shift forward), matching mainstream NLE behavior
- ✅ Drag-reorder: content moves, gaps stay in place; compact timelines stay compact after reordering
- ✅ Gaps render as true black (preview matches export); older projects (no gap field) auto-migrate to the compact layout

### 5. Clip Operations
- ✅ Move / delete / trim / split / duplicate / **replace media** (editing params preserved) / info
- ✅ **Split / delete / duplicate in the timecode row** (v1.0.10): three always-visible buttons next to the timecode; the bottom toolbar keeps only frequent entries like "Add media · Text · Audio" up front
- ✅ **Tool panels never cover the video (v1.1.1)**: every tool panel (speed/rotate/crop/color/text/audio/info/locate/export) lives below the preview inside the timeline area — color grading stays visible while dragging sliders; panels scroll vertically; the header shows the clip index being edited + close button; tapping toolbar buttons switches tools directly, tapping the active one collapses it

### 6. Playback
- ✅ Play / pause / previous frame / next frame / jump to start / jump to end / playhead dragging
- ⚠️ Playback speed: per-clip speed (see §12); no global playback speed

### 7. Precise Positioning
- ✅ Transport clock `MM:SS:FF` (frame count, v1.0.5): frame code follows project FPS (24/25/30/50/60); the info panel shows `HH:MM:SS:FF` SMPTE timecode
- ✅ Locate panel: type `1:23.5` or `83.5` to jump
- ✅ Frame stepping follows **the current clip's source FPS** (a 24FPS clip steps at 1/24 s)

### 8. Timeline Zoom
- ✅ Two-finger pinch / Zoom In / Out / Fit buttons

### 9. Video Preview
- ✅ Letterbox fit / centered / rotate-mirror-crop preview in real time (WYSIWYG)
- ✅ **Save current frame (v1.1.4)**: camera button at the top-right of the preview saves the current picture (with grading/filters/text) as JPG into Pictures/RooMCutNext, black bars auto-cropped — for covers, stills, or frame-by-frame grading checks

### 10–11. Audio
- ✅ **Voice-over recording (v1.1.4)**: record inside the Audio panel — starts at the playhead with synchronized playback for narration; on stop it lands on the timeline at the recording start; volume/fades/drag-align/undo/export all work; backgrounding finalizes and keeps the take
- ✅ Independent audio tracks: music/narration/ambience with unlimited layers; drag to move, drag ends to trim, split at the playhead
- ✅ Original sound / per-clip volume 0–200% / mute (remove audio track)
- ✅ Fade in / fade out (0–2000 ms, visualized on the timeline waveform strip)

### 12. Speed
- ✅ 0.25x–4x, presets + custom slider, **pitch preserved**, timeline length updates live

### 13. Rotate / Mirror
- ✅ 90° / 180° / horizontal flip / vertical flip

### 14. Crop
- ✅ None / 16:9 / 9:16 / 1:1 / 4:3 / 3:4 + custom horizontal/vertical margins

### 15. Color Grading (redone in v1.1)
- ✅ **Filters (LUTs)**: 8 built-in styles (Warm Sun / Cool / Cine / Vintage / Japanese / Punch / B&W / Sepia) with intensity slider (0–100%)
- ✅ **Import .cube LUTs** (3D LUTs exported by Premiere / DaVinci), long-press to delete
- ✅ **Basic grading**: brightness / contrast / saturation / **temperature / tint** / **independent R/G/B channels** (nine sliders, live preview)
- ✅ Grading chain: filter first → basic parameters after (the brightness slider can fix an over-dark filter, matching CapCut's logic)
- ✅ **Apply-to-all (v1.1.2)**: tap "Apply to all clips" to copy LUT + intensity + all basic parameters to every clip on the timeline (video and images); grading-only change, single undo step
- ✅ Undo/redo/export/autosave all inherited

### 15.5. Canvas & Multi-Layer Composition (v1.2.2 fixes)
- ✅ **Picture-in-picture actually renders**: fixed the v1.2.0 root cause of PiP not appearing in preview/export — Media3 1.11.1's CompositionPlayer defaults to a single-input video graph (a second video sequence fails internally), and the compositor always drew the first sequence on top (main video covering the PiP). Now: the player switches to a multi-input video graph when multiple video sequences exist; sequences stack bottom-up as "main → transition head → PiP layers", later PiP layers on top
- ✅ **Portrait-canvas letterbox**: landscape media in a portrait project scales proportionally into the canvas with black side bars (preview matches export); mixed orientations each fit proportionally, no stretching
- ✅ **Recoverable compatibility preview**: the degraded state auto-retries full effects as the timeline structure changes; after deleting all clips the timeline keeps a black editable canvas (while text/audio/PiP remain) instead of requiring project recreation
- ✅ **PiP-only import**: the PiP panel gains "Import new media" — new files insert directly as PiP layers, never forced onto the main timeline (stickers/small-window media decoupled from the main timeline)

### 16. Transitions (v1.2.1 → v1.5.0 → v1.5.1 → v1.5.2 → v1.5.3)
- ✅ **Crossfade**: select the later clip → toolbar "Transition" → duration slider or presets (none / 0.3 / 0.5 / 1 / 2 s); media is inserted early into the previous clip's tail, the two overlap and crossfade, total timeline length shrinks accordingly
- ✅ **Full transition library (v1.5.0)**: all the common editorial transitions, four groups —
  - Basic: crossfade / fade-to-black / fade-to-white;
  - Slide: slide-in from left / right / top / bottom (the new clip enters from off-screen);
  - Wipe: directional wipes (L/R/T/B) / iris open / iris close / clock wipe;
  - Effects: zoom-in / zoom-out / rotate / blur / mosaic / flash / blinds
- ✅ Implementation: the earlier clip stays fully opaque underneath; the later clip's head performs the transition via a custom GL shader (time-driven per-pixel alpha/color) — no black flashes, no double-transparency artifacts; preview and export mount the **same** TransitionEffect shader (WYSIWYG)
- ✅ **v1.5.1 fixed "preview shows everything as crossfade"**: the same shader's output hits different alpha-compositing conventions on the two pipelines — export goes through DefaultVideoCompositor (`GL_SRC_ALPHA` straight blending), while the preview head player writes directly to the TextureView's SurfaceTexture (Android composites with **premultiplied alpha**). v1.5.0 output straight alpha, which the preview side misread as `rgb + (1−α)·base`, degrading every position-static transition (flash/clock wipe/wipe/zoom…) into the same "ghost dissolve". The preview mount point now outputs premultiplied (`rgb·α`); the export side stays straight-alpha
- ✅ **v1.5.2 fixed "transitions still all-dissolve while playing + stale deleted-transition resurrection"**: v1.5.1's premultiplication fix was directionally right, but on affected devices the shader never mounted at all — the preview controller's fallback mechanism was pierced by two state-machine defects:
  - **First-frame flag repeatedly wiped during preroll**: driveHead's out-of-window early-return branch reset the first-frame flag every sync tick (~33 ms), so a correctly rendered first frame during preroll was erased before entering the window; the grace check only runs in-window, so entering the window always concluded "never rendered"
  - **Arithmetic contradiction: 450 ms grace > 400 ms preroll**: a comment claimed the fallback "lands inside preroll, invisible"; it actually landed 50 ms after window entry, exactly triggering the mid-fade flicker rebuild the comment described avoiding
  - Combined: playing *through* a transition (rather than seeking directly into the window) almost always triggered session-level fallback, degrading every style to view-layer dissolve gradients; the direct-seek path dodged the reset, making the bug deceptively "position-dependent"
  - Fix: the first-frame flag lifecycle is now "set false on mount, set true on first render, nothing else may touch it"; grace split into cold/hot tiers (2500 ms for the session's first effects mount — covering EGL context + shader compilation + decoder cold start; 1200 ms afterwards); preroll 400→600 ms; the numeric invariants are pinned in HeadEffectsPolicy unit tests (preventing a v1.2.8-style "comment vs arithmetic contradiction" regression)
  - **Deleted-transition residue** (same release): reconcileHead used to only hide mounted heads whose window no longer contained the playhead, while driveHead in the same sync tick pulled alpha back to 1 from a **stale** spec — after deleting a transition, every tick did "hide then resurrect" until re-entering the project. Now a mounted spec vanishing from the current spec list (transition deleted, or style/duration/mute/volume changed) fully releases the player; only byte-identical specs keep the "hidden for reuse" drag optimization
- ✅ **v1.5.3 fixed the five field-reported preview symptom groups** (root causes verified at media3 source level):
  - **Bright line at the bottom during transitions (slide-in-from-bottom / wipe-down immune)**: the overlay layer's content rect came out 1px SHORTER than the PlayerView's video surface — media3's AspectRatioFrameLayout has a 1% deformation tolerance (within it the video fills the whole view), while the old layout code truncated unconditionally, leaking a 1px row of the outgoing clip under the head. CanvasFitMath now replicates the library's branch map exactly (tolerance band included), so the head covers the body video pixel-for-pixel
  - **Stutter + first-play flash-black (smooth only after 7-8 plays)**: the cold pipeline (codec + EGL + shader compile) cannot finish inside the 600ms preroll. While playing, the next window's head is now PRE-MOUNTED 2.5s ahead and warms up while parked; the pre-mount also pre-initializes the incoming clip's codec, softening the body's item-boundary switch at the window end (the other half of the flash-black)
  - **Iris/clock effect vanishing halfway to a full second clip**: dual-clock drift — when the body stalls the playhead freezes while the head keeps advancing, hitting its span end early and parking at full-B; the cooldown-free ENDED rewind then seek-stormed every sync tick. Now: clock steering (trim speed at +80ms ahead, park at +500ms) plus a rewind cooldown
  - **Mosaic reading as dissolve**: block density is now 24·40^(p³) — a visibly coarse grid through the first ~2/3, refining late, instead of near-sharp by mid-window
  - **Zoom-out reading as no effect**: now enters at 2.4× magnification with a quadratic settle and a whole-window linear coverage ramp — the settle is visible the entire time
- ✅ Automatic constraint clamping: a transition cannot exceed the neighboring clips' available length (previous clip's remaining body / this clip's 0.1 s minimum); the panel shows the "effective" value; unavailable when a black gap separates the two clips (close the gap first); audio fades follow the head/tail
- ✅ Timeline visualization: two overlapping blocks (CapCut-style) + orange transition marker (with effective duration and style abbreviation); transitions auto-recompute on undo/trim
- ⚠️ Known approximation: **image** clips as the transition "from" side still preview as a dissolve gradient (ImageView has no effects pipeline; export renders the proper style)

### 16.5. Picture-in-Picture / Stickers (v1.2)
- ✅ **PiP**: toolbar "PiP" → choose in-project media (video/photo) → inserted as an independent layer at the playhead; adjustable position (horizontal/vertical), size (10%–100% of canvas width), opacity; multiple overlapping layers; PiP video's original audio auto-mixes
- ✅ **Stickers**: import transparent-background PNGs via "Add media" → insert via "PiP" as a sticker, alpha channel fully preserved
- ✅ Independent track rendering (above the video track, below the text track): drag to retime, drag ends to change duration (video layers capped at media duration), tap to select; preview and export share the pipeline (WYSIWYG)

### 16.6. Mosaic (v1.2.1)
- ✅ **Region pixelation**: select a clip → toolbar "Mosaic" → "+ Add region" → drag sliders to cover faces / plates / watermarks; up to 4 regions per clip (one GL channel each)
- ✅ Five sliders with live preview: horizontal/vertical position, width, height, block size (blocks stay square in pixel space, unaffected by aspect ratio)
- ✅ Custom GL shader (sampled-coordinate region quantization); region coordinates are normalized to the clip's own picture; undo/redo/export fully inherited

### 17. Text
- ✅ Add / edit / font size / color (7 colors) / outline / shadow / bold / rotation / position / start-end times
- ✅ **Font selection (v1.1)**: 5 built-in subsetted Chinese fonts (sans / serif / LXGW WenKai / Smiley Sans / ZCOOL KuaiLe, only 6.4 MB total) + **import TTF/OTF from the phone** (long-press to delete); each option in the font panel renders its own name in its own font
- ✅ Preview and export fonts are identical (same CanvasOverlay render path); rare glyphs missing from a subset auto-fall back to the system font
- ✅ **Double-tap a text clip to open the editor (v1.0.7)**: single tap selects, double-tap edits (content, color, size, position)
- ✅ Text tracks display above the video track (LibreCut-style overlay), multi-layer, drag to retime, drag edges to retime duration
- ✅ Outline and shadow render identically in preview and export (same CanvasOverlay path)

### 18. Undo / Redo
- ✅ Snapshot history stack (100 steps) storing **editing operations, not video state**; slider drags don't create history mid-gesture (committed on release)

### 19. Project Persistence
- ✅ Project = JSON timeline data (`project.json`); media is never re-encoded
- ✅ 500 ms debounced autosave on every edit + atomic write (tmp+rename)

### 20–21. Export
- ✅ 480P / 720P / 1080P / original resolution
- ✅ H.264 + AAC → MP4 (Media3 Transformer hardware encoding)
- ✅ Foreground-service background export + notification progress bar + ETA + cancel + failure reporting
- ✅ On completion, saved to the gallery's `Movies/RooMCutNext`, one-tap open / share

### 22. Crash Recovery
- ✅ Autosave (see §19) → relaunch restores the last edit after process death/power loss
- ✅ Temporary export files auto-cleaned
- ⚠️ Interrupted-export resumption: ❌ (just re-export; the source project is unharmed)

---

## Install the APK

The easiest way (no development environment needed):

1. Open this repository's [Releases](https://github.com/bones0008/RooMCutNext/releases) page, go to the latest version (currently **v1.5.7**) and download `app-release.apk`
2. Transfer the APK to your phone (WeChat / cloud drive / USB all work)
3. Tap the file on the phone and allow "install from unknown sources" when prompted
4. After installation, the "RooMCutNext" icon appears on the home screen

> Signing keys are never distributed with the repository: official Release APKs are signed with a key stored outside the repo.
> The v1.5.7 official APK **keeps the original signing key** (rotation is deferred until just before an app-store listing), so upgrades from any v1.5.x official APK install directly over the top with project data intact; v1.0.4 or earlier requires a one-time uninstall first. When the rotation eventually happens it will be prominently noted in the release notes (a one-time uninstall/reinstall will be needed then).
> Building from source: debug builds work out of the box after cloning; configure release signing via `local.properties` (git-ignored) keys `roocmcut.keystore` / `roocmcut.storePassword`, or env vars `ROOCMCUT_KEYSTORE` / `ROOCMCUT_STORE_PASSWORD` — builds fall back to debug signing when unconfigured.

**System requirements: Android 12 (API 31) or newer.**

---

## User Guide

**Basic flow: create a project (orientation → name) → tap + to import → edit on the timeline → export**

| Action | Gesture |
|---|---|
| Double-tap a text clip | Opens the text editor (single tap only selects) |
| Play / pause | The big center button |
| Frame stepping | ⏪ / ⏩ buttons (steps by the current clip's source FPS) |
| Scrub | **Press and drag the timeline (ruler/blank area)**; video follows live |
| Precise jump | Toolbar "Locate" → type `1:23.5` |
| Select a clip | Tap it |
| Deselect | Tap blank timeline area |
| Trim | Drag the rounded handles at both ends of the selected clip |
| Split | Move the playhead to the target frame → "Split" in the timecode row (whichever track is under the playhead gets split) |
| Duplicate | Select → "Duplicate" in the timecode row (next to Split/Delete, always visible since v1.0.10) |
| Reorder / open gaps | **Press and drag a clip to the target position and release**: it lands where dropped; dragging far apart leaves a black gap (dragging close snaps shut), with live drop preview and gap duration |
| Delete | Select → "Delete" in the timecode row (middle clips leave a black gap; tail clips close the gap) |
| Speed / rotate / crop / grading / audio | Select a clip → the corresponding toolbar button (audio is second to the right of "Add media") |
| Add text | Bottom toolbar "Text" (first to the right of "Add media", v1.0.10; inserts 3 s at the playhead) |
| Edit text | Tap the text clip on the timeline to open the editor |
| Timeline zoom | Two-finger pinch, or the zoom buttons |
| Undo / redo | ↶ ↷ at the top |

---

## Architecture

```
app/src/main/java/com.RooMCutNext/
├── core/                  Pure-Kotlin editing kernel (no Android deps, unit-testable)
│   ├── model/             Clip / MediaAsset / TextOverlay / Project (all immutable)
│   ├── ops/TimelineOps    All editing algorithms: split/trim/delete/move/frame-step (snap-once discipline)
│   ├── undo/History       Snapshot dual-stack undo/redo
│   └── util/TimeCode      Microsecond timecode / frame-grid math
├── data/                  JSON project persistence (atomic writes) + language setting
├── media/                 Media import (copied into private storage) / metadata / frame thumbnails
├── playback/              CompositionPlayer preview controller
├── export/
│   ├── CompositionBuilder ★ Timeline → Media3 Composition (shared by preview & export — the root of WYSIWYG)
│   ├── effects/           Per-clip volume fades (PCM processor) / timed text overlay (CanvasOverlay)
│   ├── ExportEngine       Transformer export + MediaStore insertion
│   └── ExportService      Foreground service + notification progress
└── ui/                    Compose UI (Home / Editor / Timeline / Panels)
```

### Frame-Rate Strategy

For mixed footage (30FPS phone clips + 24FPS AI-generated clips):

1. **Project FPS**: the first imported video's FPS snaps to 24/25/30/50/60 (a 24FPS AI video makes a 24FPS project)
2. **Frame stepping**: stepping follows **the current clip's source FPS** (stepping a 24FPS AI clip inside a 30FPS project = 1/24 s per step)
3. **Trim alignment**: Trim/Split boundaries snap to **the source file's frame grid** — no duplicated or dropped frames
4. **Export**: each clip keeps its native timestamps (VFR); 24 and 30 mix without interference, avoiding forced-resample judder

---

## Known Limitations

1. ~~Transitions not implemented~~ → **v1.5.0 delivered the full 21-style transition library**
2. ~~Independent audio tracks~~ → **implemented in v1.0.4** (multi-sequence audio mixing); audio extraction (lifting original sound into its own track) remains future work
3. **Export is VFR** (variable frame rate) MP4 — supported by every mainstream player/platform, but strict-CFR tools may require transcoding
4. **HDR footage (HLG/HDR10) is auto tone-mapped to SDR** — preview matches export, stable on all devices; V1 does not export HDR. If a device's GL pipeline misbehaves, preview falls back to compatibility mode (effects hidden; export unaffected)
5. **Free crop** is implemented via margin sliders (not a box-select gesture)
6. Text content edits don't enter the undo stack (style adjustments do)
7. Debug APK is large (~71 MB, full icons + debug symbols); release build is ~53 MB
8. CompositionPlayer is a Media3 experimental API (`@UnstableApi`) — regression-test when upgrading major Media3 versions

## Roadmap (suggested priorities)

1. ~~V1.1: transitions (crossfade/dissolve/black/white)~~ → **v1.5.0 delivered all 21 styles**
2. ~~V1.2: background music track~~ → **implemented in v1.0.4** (multi-sequence audio mixing); audio extraction remains future work
3. V1.3: release build + R8 + Play signing
4. V2: keyframe animation (text/scale), stickers, PiP → **PiP/stickers shipped in v1.2**

---

## License (AGPL-3.0)

All of this project's own source code is released under the **GNU Affero General Public License v3.0** (full text in [`LICENSE`](LICENSE)). In short:

- ✅ **Anyone** may use, study, modify, and redistribute this project
- 🔗 But **distributing a work based on it, or deploying it as a network service, requires the entire derivative work (including your modifications) to be open-sourced under AGPL-3.0**
- 🔒 Closed-source commercial use, white-labeling, or embedding into your own product? There is no such path under AGPL — write your own from scratch, or contact the author for alternative licensing (the author, as copyright holder, retains all rights — including commercial releases and app store listings — and is not bound by the AGPL)
- 🧑‍💻 **Contribution terms**: submitting a pull request means you agree that your contribution is licensed under AGPL-3.0, plus an additional permission for the project author to include it in commercial distributions (including app store releases)

> The permissive licenses of the dependencies and fonts (see below) do not affect this: AGPL's open-source obligation applies only to **this project's code and derivatives**; Apache-2.0 dependencies are one-way compatible with AGPL-3.0 and may be lawfully combined and distributed.

## Third-Party Components & Licenses

This project **copies no third-party source code** — all Kotlin / GLSL code is original (OpenCut and LibreCuts were studied as architecture references only; none of their code was incorporated). The open-source components used are standard libraries, all under permissive licenses (the dependencies' own permissive nature is unaffected by this project's AGPL — anyone may still use them in any project without open-sourcing anything):

| Component | Purpose | License |
|---|---|---|
| AndroidX / Jetpack Compose | UI framework | Apache-2.0 |
| Media3 (ExoPlayer / Transformer / Effect) | Preview playback + export composition | Apache-2.0 |
| Kotlin Coroutines / Serialization | Async & JSON persistence | Apache-2.0 |
| Coil | Image loading | Apache-2.0 |
| JUnit 4 | Unit testing (test-time only) | EPL-1.0 |

Bundled fonts (font binaries distributed inside the APK; all permit free commercial use and redistribution within apps):

| Font | File | License |
|---|---|---|
| Noto Sans SC | `assets/fonts/font_hei.ttf` | SIL OFL 1.1 |
| Noto Serif SC | `assets/fonts/font_song.ttf` | SIL OFL 1.1 |
| LXGW WenKai | `assets/fonts/font_kai.ttf` | SIL OFL 1.1 |
| Smiley Sans | `assets/fonts/font_duyi.ttf` | SIL OFL 1.1 |
| ZCOOL KuaiLe | `assets/fonts/font_kuaile.ttf` | ZCOOL free commercial license |

The 8 built-in `.cube` LUT filters are procedurally generated by this project — no third-party copyright.

---

*Built with Kotlin 2.2 · Jetpack Compose · Media3 1.11 · single-layer timeline editing kernel with 358 passing unit tests · source code released under AGPL-3.0*
