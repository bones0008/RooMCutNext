package com.RooMCutNext.ui.editor

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.RooMCutNext.core.model.AudioClip
import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.ColorAdjust
import com.RooMCutNext.core.model.CropSpec
import com.RooMCutNext.core.model.IMAGE_DEFAULT_DURATION_US
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.PipLayer
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.model.TextOverlayItem
import com.RooMCutNext.core.model.snapProjectFps
import com.RooMCutNext.core.ops.TimelineOps
import com.RooMCutNext.core.undo.SnapshotHistory
import com.RooMCutNext.core.util.IdGen
import com.RooMCutNext.data.ProjectRepository
import com.RooMCutNext.export.ExportBus
import com.RooMCutNext.export.ExportService
import com.RooMCutNext.media.FontRegistry
import com.RooMCutNext.media.LutRegistry
import com.RooMCutNext.media.MediaImporter
import com.RooMCutNext.media.ThumbnailStore
import com.RooMCutNext.playback.OverlayPreviewController
import com.RooMCutNext.playback.PlaybackController
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PanelKind { SPEED, ROTATE, CROP, ADJUST, TEXT, AUDIO, INFO, EXPORT, GOTO, PIP, TRANSITION, MOSAIC }

data class ImportingState(val done: Int, val total: Int, val current: String)

data class TrimPreview(val clipId: String, val sourceInUs: Long, val sourceOutUs: Long)

/** Live audio trim preview: keeps the timeline start too (left-edge trims move it). */
data class AudioTrimPreview(
    val audioId: String,
    val startUs: Long,
    val sourceInUs: Long,
    val sourceOutUs: Long,
)

/** Live voiceover take in progress (v1.1.4). */
data class VoiceoverState(
    /** Milliseconds since the take started (drives the in-panel clock). */
    val elapsedMs: Long = 0L,
    /** Timeline position where the resulting AudioClip will land. */
    val startUs: Long = 0L,
)

data class EditorUiState(
    val project: Project = Project(id = "", name = ""),
    val selectedClipId: String? = null,
    val selectedTextId: String? = null,
    val selectedAudioId: String? = null,
    val selectedPipId: String? = null,
    val playheadUs: Long = 0L,
    val isPlaying: Boolean = false,
    val pxPerSecond: Float = 64f,
    val activePanel: PanelKind? = null,
    val importing: ImportingState? = null,
    val toast: String? = null,
    val playbackError: String? = null,
    /** Compatibility preview active: per-clip effects stripped after a player error. */
    val previewDegraded: Boolean = false,
    /** Live trim preview while the user drags a trim handle (not yet committed). */
    val trimPreview: TrimPreview? = null,
    /** Live audio trim preview while dragging (not yet committed). */
    val audioTrimPreview: AudioTrimPreview? = null,
    val missingAssetIds: Set<String> = emptySet(),
    val exportState: ExportBus.ExportState? = null,
    /** Selectable fonts (v1.1): system + bundled + user-imported. */
    val availableFonts: List<FontRegistry.FontSpec> = emptyList(),
    /** Selectable LUT filters (v1.1): bundled + user-imported. */
    val availableLuts: List<LutRegistry.LutSpec> = emptyList(),
    /** On-screen diagnostic log (last lines) — only shown when overlay enabled. */
    val diagLog: List<String> = emptyList(),
    /** Hidden diagnostics: toggled by tapping the project title 5 times. */
    val diagOverlayVisible: Boolean = false,
    /** Live voiceover recording (v1.1.4): elapsed ms + timeline start of the take. */
    val voiceover: VoiceoverState? = null,
    /** Selected mosaic region inside the selected clip (v1.2.2). */
    val selectedMosaicId: String? = null,
) {
    val totalDurationUs: Long get() = project.totalDurationUs
    val selectedClip: Clip? get() = selectedClipId?.let { project.clipById(it) }
    val selectedText: TextOverlayItem? get() = selectedTextId?.let { project.textById(it) }
    val selectedAudio: AudioClip? get() = selectedAudioId?.let { project.audioById(it) }
    val selectedPip: PipLayer? get() = selectedPipId?.let { project.pipById(it) }
    /** Selected mosaic region of the selected clip, if any. */
    val selectedMosaic: com.RooMCutNext.core.model.MosaicRegion? get()
        = selectedMosaicId?.let { id -> selectedClip?.mosaics?.firstOrNull { it.id == id } }
    val hasSelection: Boolean get() = selectedClipId != null || selectedTextId != null || selectedAudioId != null || selectedPipId != null
}

/**
 * The editor's single source of truth.
 *
 * State flows one way (immutable [EditorUiState] -> Compose); every committed
 * edit goes through [mutate], which records an undo snapshot, refreshes the
 * preview composition and schedules the debounced autosave.
 */
class EditorViewModel(
    application: Application,
    val projectId: String,
) : AndroidViewModel(application) {

    private val repo = ProjectRepository(application)
    private val importer = MediaImporter(application)
    val thumbnails = ThumbnailStore()

    private val history = SnapshotHistory<Project>(capacity = 100)

    val _state = MutableStateFlow(
        EditorUiState(project = repo.load(projectId) ?: Project(id = projectId, name = "Project"))
    )
    val state = _state.asStateFlow()

    /** Redraw-scoped derived flows so the playhead does not re-draw the whole timeline. */
    val playheadFlow: kotlinx.coroutines.flow.StateFlow<Long> =
        _state.map { it.playheadUs }.distinctUntilChanged()
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), 0L)

    data class TimelineSnapshot(
        val project: Project,
        val selectedClipId: String?,
        val selectedTextId: String?,
        val selectedAudioId: String?,
        val selectedPipId: String?,
        val trimPreview: TrimPreview?,
        val audioTrimPreview: AudioTrimPreview?,
        val missingAssetIds: Set<String>,
    )

    val timelineFlow: kotlinx.coroutines.flow.StateFlow<TimelineSnapshot?> =
        _state.map {
            TimelineSnapshot(
                it.project, it.selectedClipId, it.selectedTextId, it.selectedAudioId, it.selectedPipId,
                it.trimPreview, it.audioTrimPreview, it.missingAssetIds,
            )
        }.distinctUntilChanged()
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), null)

    val zoomFlow: kotlinx.coroutines.flow.StateFlow<Float> =
        _state.map { it.pxPerSecond }.distinctUntilChanged()
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), 64f)

    val playback = PlaybackController(application, viewModelScope)

    /**
     * v1.2.3: view-layer preview engine for PiP layers and crossfade heads.
     * The CompositionPlayer stays single-video-sequence (seek/scrub-safe —
     * upstream Media3 cannot seek multi-video-sequence previews) and this
     * engine composites everything that would have been a second video
     * sequence on top of the PlayerView. Driven from the UI state below so
     * it follows play/pause/scrub/slider drags at the state-update rate.
     */
    val overlays = OverlayPreviewController(application)

    /** Localized notice shown when preview falls back to compatibility mode. */
    private val degradedNotice: String =
        application.getString(com.RooMCutNext.R.string.preview_degraded_toast)

    /** Set true while the user scrubbs the playhead by touch (poll must not fight the finger). */
    var scrubbingByTouch = false

    private var autosaveJob: Job? = null

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo
    val undoLabel: String? get() = history.undoLabel
    val redoLabel: String? get() = history.redoLabel

    /** Version counter bumped on every committed mutation (drives canvas invalidation). */
    private val _structureVersion = MutableStateFlow(0)
    val structureVersion = _structureVersion.asStateFlow()

    init {
        _state.update {
            it.copy(
                availableFonts = FontRegistry.specs(),
                availableLuts = LutRegistry.specs(),
            )
        }
        playback.onPositionUs = { us ->
            if (!scrubbingByTouch) _state.update { it.copy(playheadUs = us.coerceAtLeast(0L)) }
        }
        playback.onIsPlaying = { playing ->
            _state.update { it.copy(isPlaying = playing) }
        }
        playback.onEnded = {
            _state.update { it.copy(isPlaying = false, playheadUs = it.project.totalDurationUs) }
            playback.pause()
        }
        playback.onError = { msg -> _state.update { it.copy(playbackError = msg) } }
        playback.onDiag = { line -> appendDiag(line) }
        playback.onDegraded = {
            _state.update {
                it.copy(
                    previewDegraded = true,
                    playbackError = null,
                    toast = it.toast ?: degradedNotice,
                )
            }
        }

        refreshPlayback(force = true)

        // v1.2.3: drive the view-layer overlay engine (PiP layers + crossfade
        // heads) from the UI state. The state flow already updates at the
        // playback poll rate while playing (~30Hz) and on every user action
        // while paused (scrub / frame-step / slider drags), which is exactly
        // the sync cadence the overlays need. viewModelScope runs on Main, so
        // the engine may touch views and players directly.
        viewModelScope.launch {
            state.collect { s ->
                overlays.sync(s.project, repo.mediaDir(s.project.id), s.playheadUs, s.isPlaying)
            }
        }

        viewModelScope.launch {
            ExportBus.state.collect { es ->
                if (es == null || es.projectId == projectId) {
                    _state.update { it.copy(exportState = es) }
                }
            }
        }
        viewModelScope.launch { checkMissingAssets() }
        viewModelScope.launch { preloadThumbnails(_state.value.project.assets) }
    }

    // ------------------------------------------------------------------
    // Mutation core
    // ------------------------------------------------------------------

    private fun mutate(label: String, transform: (Project) -> Project) {
        dragOriginProject = null
        val before = _state.value.project
        val after = transform(before)
        if (after == before) return
        history.record(label, before, after)
        _state.update { it.copy(project = after) }
        _structureVersion.value += 1
        refreshPlayback()
        scheduleAutosave()
    }

    /** State captured when a live-preview drag starts (for a correct undo step). */
    private var dragOriginProject: Project? = null

    /** Preview-only state change (no undo record, no autosave) — used while sliders drag. */
    private fun previewTransform(transform: (Project) -> Project) {
        if (dragOriginProject == null) dragOriginProject = _state.value.project
        _state.update { it.copy(project = transform(it.project)) }
        _structureVersion.value += 1
        scheduleDebouncedPlaybackRefresh()
    }

    /**
     * Commit a live-preview edit: record undo from the pre-drag state.
     * When no preview drag is active, behaves like [mutate].
     */
    private fun commitOrMutate(label: String, transform: (Project) -> Project) {
        val origin = dragOriginProject
        if (origin != null) {
            dragOriginProject = null
            val after = _state.value.project
            if (after != origin) {
                history.record(label, origin, after)
                _structureVersion.value += 1
                scheduleAutosave()
            }
            refreshPlayback()
        } else {
            mutate(label, transform)
        }
    }

    private var playbackRefreshJob: Job? = null
    private fun scheduleDebouncedPlaybackRefresh() {
        playbackRefreshJob?.cancel()
        playbackRefreshJob = viewModelScope.launch {
            delay(450)
            refreshPlayback()
        }
    }

    /** Append a diagnostic line to the on-screen log (kept to last 9 lines). */
    private fun appendDiag(line: String) {
        _state.update { it.copy(diagLog = (it.diagLog + line).takeLast(9)) }
    }

    /** Toggle the hidden on-screen DIAG overlay (5 taps on the title). */
    fun toggleDiagOverlay() {
        _state.update { it.copy(diagOverlayVisible = !it.diagOverlayVisible) }
    }

    /** Called from the preview AndroidView update: reports the PlayerView pixel size. */
    fun reportPreviewViewSize(w: Int, h: Int) {
        val line = "view=${w}x${h}"
        if (_state.value.diagLog.lastOrNull() != line && lastReportedViewSize != line) {
            lastReportedViewSize = line
            appendDiag(line)
        }
    }

    private var lastReportedViewSize: String? = null

    private fun refreshPlayback(force: Boolean = false) {
        val project = _state.value.project
        playback.currentProjectRef = project
        if (!project.hasContent) {
            playback.setCompositionForEmpty()
            return
        }
        playback.refresh(project, repo.mediaDir(project.id), _state.value.playheadUs, force)
        // v1.2.2: keep the compatibility-preview chip in sync — the controller
        // now recovers automatically once the timeline structure changes, so
        // the chip must clear together with it.
        if (_state.value.previewDegraded && !playback.degraded) {
            _state.update { it.copy(previewDegraded = false) }
        }
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(500)
            saveNow()
        }
    }

    fun saveNow() {
        val project = _state.value.project
        if (project.id.isNotBlank()) repo.save(project)
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    /**
     * Import files. Default: video/image assets append to the timeline,
     * audio becomes independent-layer clips.
     *
     * v1.2.2 [asPip]: import FOR the picture-in-picture panel — assets go
     * into the library and are inserted directly as PiP layers at the
     * playhead WITHOUT touching the timeline (the "imported sticker also
     * lands on the timeline" trap). This is also the sticker workflow:
     * import a transparent PNG here and it becomes a sticker.
     */
    fun importUris(uris: List<Uri>, asPip: Boolean = false) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(importing = ImportingState(0, uris.size, "")) }
            val outcome = importer.import(uris, repo.mediaDir(projectId)) { done, total, name ->
                _state.update { it.copy(importing = ImportingState(done, total, name)) }
            }
            _state.update { it.copy(importing = null) }

            if (outcome.assets.isNotEmpty()) {
                // Diagnostics: one line per imported asset (resolution/fps/HDR marker).
                outcome.assets.forEach { a ->
                    val hdr = if (a.colorTransfer == 6 || a.colorTransfer == 7) " HDR(ct=${a.colorTransfer})" else ""
                    appendDiag(
                        "asset ${a.type.name.lowercase()} ${a.width}x${a.height}@${if (a.fps > 0f) "${a.fps.toInt()}fps" else "-"}$hdr"
                    )
                }
                val playheadUs = _state.value.playheadUs.coerceAtLeast(0L)
                var lastPipId: String? = null
                mutate(if (asPip) "导入画中画素材" else "导入素材") { p ->
                    var np = p.copy(assets = p.assets + outcome.assets)
                    val visuals = outcome.assets.filter { !it.isAudio }
                    if (!asPip && np.clips.isEmpty() && visuals.isNotEmpty()) {
                        val firstVideo = visuals.firstOrNull { !it.isImage }
                        if (firstVideo != null) {
                            np = np.copy(
                                fps = snapProjectFps(firstVideo.fps),
                                width = firstVideo.width,
                                height = firstVideo.height,
                            )
                        } else {
                            val firstImage = visuals.first()
                            np = np.copy(width = firstImage.width, height = firstImage.height)
                        }
                    }
                    if (asPip) {
                        // Straight into PiP layers at the playhead — the timeline
                        // is not touched (v1.2.2 workflow fix).
                        visuals.forEach { asset ->
                            val durationUs = if (asset.isImage) IMAGE_DEFAULT_DURATION_US else asset.durationUs
                            val layer = PipLayer(
                                id = IdGen.new("pip"),
                                assetId = asset.id,
                                startUs = playheadUs,
                                durationUs = durationUs.coerceAtLeast(TimelineOps.PIP_MIN_DURATION_US),
                            )
                            lastPipId = layer.id
                            np = np.copy(pips = TimelineOps.insertPip(np.pips, layer))
                        }
                    } else {
                        // Video / image assets append as timeline clips.
                        visuals.forEach { asset -> np = np.copy(clips = np.clips + newClipFor(asset)) }
                    }
                    // Audio assets become independent-layer audio clips placed
                    // sequentially from the current playhead.
                    if (outcome.assets.any { it.isAudio }) {
                        var cursor = np.clipsDurationUs.coerceAtLeast(playheadUs)
                        outcome.assets.filter { it.isAudio }.forEach { asset ->
                            np = np.copy(
                                audios = TimelineOps.insertAudio(
                                    np.audios,
                                    AudioClip(
                                        id = IdGen.new("aud"),
                                        assetId = asset.id,
                                        startUs = cursor,
                                        sourceInUs = 0L,
                                        sourceOutUs = asset.durationUs,
                                    ),
                                )
                            )
                            cursor += asset.durationUs
                        }
                    }
                    np
                }
                if (asPip && lastPipId != null) {
                    // Select the newly imported layer so its sliders show up.
                    _state.update {
                        it.copy(selectedPipId = lastPipId, selectedClipId = null, selectedTextId = null, selectedAudioId = null)
                    }
                }
                viewModelScope.launch { preloadThumbnails(outcome.assets) }
            }
            if (outcome.failedNames.isNotEmpty()) {
                _state.update {
                    it.copy(toast = "无法导入 ${outcome.failedNames.size} 个文件：${outcome.failedNames.joinToString().take(60)}")
                }
            }
        }
    }

    private fun newClipFor(asset: MediaAsset): Clip = if (asset.isImage) {
        Clip(id = IdGen.new("c"), assetId = asset.id, sourceInUs = 0L, sourceOutUs = IMAGE_DEFAULT_DURATION_US)
    } else {
        Clip(id = IdGen.new("c"), assetId = asset.id, sourceInUs = 0L, sourceOutUs = asset.durationUs)
    }

    /** Replace the asset used by [clipId] keeping trim (clamped to the new source duration). */
    fun replaceClipAsset(clipId: String, newUri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(importing = ImportingState(0, 1, "")) }
            val outcome = importer.import(listOf(newUri), repo.mediaDir(projectId)) { d, t, n ->
                _state.update { it.copy(importing = ImportingState(d, t, n)) }
            }
            _state.update { it.copy(importing = null) }
            val asset = outcome.assets.firstOrNull() ?: run {
                _state.update { it.copy(toast = "替换失败：无法读取该文件") }
                return@launch
            }
            mutate("替换素材") { p ->
                val clips = p.clips.map { c ->
                    if (c.id != clipId) c
                    else c.copy(
                        assetId = asset.id,
                        sourceInUs = c.sourceInUs.coerceAtMost(
                            if (asset.isImage) c.sourceOutUs - 1 else (asset.durationUs - 1).coerceAtLeast(0)
                        ),
                        sourceOutUs = c.sourceOutUs.coerceAtMost(
                            if (asset.isImage) c.sourceOutUs else asset.durationUs
                        ),
                        // Reset per-clip transforms that depend on the old source.
                        crop = null,
                    )
                }
                p.copy(assets = p.assets + asset, clips = clips)
            }
            viewModelScope.launch { preloadThumbnails(listOf(asset)) }
        }
    }

    private suspend fun preloadThumbnails(assets: List<MediaAsset>) {
        val dir = repo.mediaDir(projectId)
        assets.forEach { thumbnails.load(dir, it) }
        _structureVersion.value += 1
    }

    private suspend fun checkMissingAssets() {
        val p = _state.value.project
        val dir = repo.mediaDir(projectId)
        val used = p.clips.map { it.assetId }.toSet() + p.audios.map { it.assetId }.toSet()
        val missing = p.assets.filter { it.id in used && !File(dir, it.fileName).exists() }
            .map { it.id }.toSet()
        _state.update { it.copy(missingAssetIds = missing) }
    }

    // ------------------------------------------------------------------
    // Selection / panels
    // ------------------------------------------------------------------

    fun selectClip(id: String?) {
        _state.update { it.copy(selectedClipId = id, selectedTextId = null, selectedAudioId = null, selectedPipId = null, selectedMosaicId = null) }
    }

    fun selectText(id: String?) {
        _state.update { it.copy(selectedTextId = id, selectedClipId = null, selectedAudioId = null, selectedPipId = null, selectedMosaicId = null) }
    }

    fun selectAudio(id: String?) {
        _state.update { it.copy(selectedAudioId = id, selectedClipId = null, selectedTextId = null, selectedPipId = null, selectedMosaicId = null) }
    }

    fun selectPip(id: String?) {
        _state.update { it.copy(selectedPipId = id, selectedClipId = null, selectedTextId = null, selectedAudioId = null, selectedMosaicId = null) }
    }

    /** Tap on empty timeline space: drop every selection. */
    fun clearSelection() {
        if (_state.value.hasSelection) {
            _state.update { it.copy(selectedClipId = null, selectedTextId = null, selectedAudioId = null, selectedPipId = null, selectedMosaicId = null) }
        }
    }

    fun openPanel(panel: PanelKind?) {
        // v1.1.1 toggle: tapping the toolbar button of the already-open
        // panel closes it — the inline panel sits in the timeline area, so
        // closing it reveals the timeline again.
        if (panel != null && panel == _state.value.activePanel) {
            _state.update { it.copy(activePanel = null) }
            return
        }
        // Reopening the export sheet must never show a stale finished result
        // from an earlier run: only a live RUNNING export is kept, everything
        // else (COMPLETED / FAILED / CANCELLED) is cleared so the "start
        // export" action is available again after timeline edits.
        if (panel == PanelKind.EXPORT) {
            val es = _state.value.exportState
            if (es != null && es.phase != ExportBus.Phase.RUNNING) {
                ExportBus.clear()
                _state.update { it.copy(exportState = null) }
            }
        }
        _state.update { it.copy(activePanel = panel) }
    }

    fun clearToast() = _state.update { it.copy(toast = null) }

    /** Show a transient notice from view-layer code (frame export etc.). */
    fun flashToast(message: String) {
        _state.update { it.copy(toast = message) }
    }

    fun clearPlaybackError() = _state.update { it.copy(playbackError = null) }

    // ------------------------------------------------------------------
    // Structural edits
    // ------------------------------------------------------------------

    fun splitAtPlayhead() {
        val s = _state.value
        val videoResult = TimelineOps.splitAtTimeline(
            s.project.clips,
            { id -> s.project.assetById(id) },
            s.playheadUs,
            IdGen.new("c"),
        )
        val audioResult = TimelineOps.splitAudioAt(
            s.project.audios,
            s.playheadUs,
            IdGen.new("aud"),
        )
        if (videoResult == null && audioResult == null) {
            _state.update { it.copy(toast = "此处无法分割（位于片段边界或片段过短）") }
            return
        }
        // One undo step: cutting at the playhead slices EVERY layer under it
        // (video AND audio), like a multi-track razor in classic NLEs.
        mutate("分割") { p ->
            p.copy(
                clips = videoResult?.clips ?: p.clips,
                audios = audioResult?.first ?: p.audios,
            )
        }
        _state.update {
            // Selection stays exclusive: prefer the video right half (the
            // layer the user most likely wants to keep trimming), otherwise
            // the audio right half.
            if (videoResult != null) {
                it.copy(selectedClipId = videoResult.rightId, selectedTextId = null, selectedAudioId = null)
            } else {
                it.copy(selectedClipId = null, selectedTextId = null, selectedAudioId = audioResult!!.second)
            }
        }
    }

    fun deleteSelected() {
        val s = _state.value
        val textId = s.selectedTextId
        if (textId != null) {
            mutate("删除文字") { p -> p.copy(texts = p.texts.filter { it.id != textId }) }
            _state.update { it.copy(selectedTextId = null, activePanel = null) }
            return
        }
        val audioId = s.selectedAudioId
        if (audioId != null) {
            mutate("删除音频") { p -> p.copy(audios = TimelineOps.deleteAudio(p.audios, audioId)) }
            _state.update { it.copy(selectedAudioId = null, activePanel = null) }
            return
        }
        val pipId = s.selectedPipId
        if (pipId != null) {
            mutate("删除画中画") { p -> p.copy(pips = TimelineOps.deletePip(p.pips, pipId)) }
            _state.update { it.copy(selectedPipId = null, activePanel = null) }
            return
        }
        val clipId = s.selectedClipId ?: return
        // Texts and audios are independent layers: deleting a video clip no
        // longer shortens / drops them (the black background covers the tail).
        mutate("删除") { p -> p.copy(clips = TimelineOps.deleteById(p.clips, clipId)) }
        _state.update { it.copy(selectedClipId = null, activePanel = null) }
    }

    fun duplicateSelected() {
        val clipId = _state.value.selectedClipId ?: return
        val idx = _state.value.project.clips.indexOfFirst { it.id == clipId }
        if (idx < 0) return
        val newId = IdGen.new("c")
        mutate("复制") { p -> p.copy(clips = TimelineOps.duplicate(p.clips, idx, newId)) }
        _state.update { it.copy(selectedClipId = newId) }
    }

    fun moveClip(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        mutate("移动") { p -> p.copy(clips = TimelineOps.move(p.clips, fromIndex, toIndex)) }
    }

    /**
     * Free-position drop (v1.0.9): place the dragged clip at [insertAt] with
     * an explicit leading [gapBeforeUs] black hole — how the user puts space
     * between two clips. A zero gap degenerates to a plain reorder-insert.
     */
    fun placeClip(fromIndex: Int, insertAt: Int, gapBeforeUs: Long) {
        mutate("放置") { p ->
            p.copy(clips = TimelineOps.place(p.clips, fromIndex, insertAt, gapBeforeUs))
        }
    }

    fun insertClipAt(clip: Clip, index: Int) {
        mutate("插入") { p -> p.copy(clips = TimelineOps.insert(p.clips, clip, index)) }
    }

    // ------------------------------------------------------------------
    // Trim
    // ------------------------------------------------------------------

    /** Live preview during handle drag (no undo record). */
    fun previewTrim(clipId: String, requestedInUs: Long, requestedOutUs: Long) {
        val s = _state.value
        val idx = s.project.clips.indexOfFirst { it.id == clipId }
        if (idx < 0) return
        val asset = s.project.assetById(s.project.clips[idx].assetId)
        val clamped = TimelineOps.trim(s.project.clips, idx, asset, requestedInUs, requestedOutUs)
        _state.update {
            it.copy(
                trimPreview = TrimPreview(clipId, clamped[idx].sourceInUs, clamped[idx].sourceOutUs)
            )
        }
    }

    /** Commit the trim after the handle is released. */
    fun commitTrim() {
        val preview = _state.value.trimPreview ?: return
        _state.update { it.copy(trimPreview = null) }
        mutate("修剪") { p ->
            val idx = p.clips.indexOfFirst { it.id == preview.clipId }
            if (idx < 0) p
            else {
                val asset = p.assetById(p.clips[idx].assetId)
                p.copy(
                    clips = TimelineOps.trim(p.clips, idx, asset, preview.sourceInUs, preview.sourceOutUs),
                )
            }
        }
    }

    fun cancelTrim() {
        _state.update { it.copy(trimPreview = null) }
    }

    // ------------------------------------------------------------------
    // Clip property edits
    // ------------------------------------------------------------------

    fun setClipSpeed(clipId: String, speed: Float, commit: Boolean = true) {
        val s = speed.coerceIn(0.25f, 4f)
        if (commit) {
            commitOrMutate("变速") { p ->
                p.copy(clips = TimelineOps.update(p.clips, clipId) { it.copy(speed = s) })
            }
        } else {
            previewTransform { p ->
                p.copy(clips = TimelineOps.update(p.clips, clipId) { it.copy(speed = s) })
            }
        }
    }

    fun rotateClipBy(clipId: String, deltaDeg: Int) {
        mutate("旋转") { p ->
            p.copy(clips = TimelineOps.update(p.clips, clipId) {
                it.copy(rotationDeg = ((it.rotationDeg + deltaDeg) % 360 + 360) % 360)
            })
        }
    }

    fun toggleFlip(clipId: String, horizontal: Boolean) {
        mutate("镜像") { p ->
            p.copy(clips = TimelineOps.update(p.clips, clipId) {
                if (horizontal) it.copy(flipH = !it.flipH) else it.copy(flipV = !it.flipV)
            })
        }
    }

    fun setClipCrop(clipId: String, crop: CropSpec?, commit: Boolean = true) {
        val apply: (Project) -> Project = { p ->
            p.copy(clips = TimelineOps.update(p.clips, clipId) { it.copy(crop = crop) })
        }
        if (commit) commitOrMutate("裁剪") { apply(it) } else previewTransform { apply(it) }
    }

    fun setClipAdjust(clipId: String, adjust: ColorAdjust, commit: Boolean = true) {
        if (commit) {
            commitOrMutate("画面调整") { p ->
                p.copy(clips = TimelineOps.update(p.clips, clipId) { it.copy(adjust = adjust) })
            }
        } else {
            previewTransform { p ->
                p.copy(clips = TimelineOps.update(p.clips, clipId) { it.copy(adjust = adjust) })
            }
        }
    }

    /**
     * v1.1.2: copy the given clip's full color grading (LUT + strength +
     * basic parameters) onto EVERY clip on the timeline — videos and images
     * alike. One history step, so a single undo reverts the whole batch.
     */
    fun applyAdjustToAll(adjust: ColorAdjust) {
        val s = _state.value
        val clips = s.project.clips
        // No-op when the timeline is empty or every clip already carries this
        // exact grading (also keeps the undo stack free of empty entries).
        if (clips.isEmpty() || clips.all { it.adjust == adjust }) return
        mutate("调色应用到全部") { p ->
            p.copy(clips = TimelineOps.applyAdjustToAll(p.clips, adjust))
        }
        _state.update {
            it.copy(toast = app.getString(com.RooMCutNext.R.string.adjust_applied_all_fmt, clips.size))
        }
    }

    fun setClipVolume(clipId: String, volume: Float, commit: Boolean = true) {
        val v = volume.coerceIn(0f, 2f)
        val apply: (Project) -> Project = { p ->
            p.copy(clips = TimelineOps.update(p.clips, clipId) { it.copy(volume = v) })
        }
        if (commit) commitOrMutate("音量") { apply(it) } else previewTransform { apply(it) }
    }

    fun toggleClipMuted(clipId: String) {
        mutate("静音") { p ->
            p.copy(clips = TimelineOps.update(p.clips, clipId) { it.copy(muted = !it.muted) })
        }
    }

    fun setClipFades(clipId: String, fadeInMs: Int, fadeOutMs: Int, commit: Boolean = true) {
        val fi = fadeInMs.coerceIn(0, 2000)
        val fo = fadeOutMs.coerceIn(0, 2000)
        val apply: (Project) -> Project = { p ->
            p.copy(clips = TimelineOps.update(p.clips, clipId) {
                it.copy(fadeInMs = fi, fadeOutMs = fo)
            })
        }
        if (commit) commitOrMutate("淡入淡出") { apply(it) } else previewTransform { apply(it) }
    }

    // ------------------------------------------------------------------
    // Crossfade transitions (v1.2.1)
    // ------------------------------------------------------------------

    /**
     * Set the crossfade-in duration of the selected clip (microseconds).
     * The overlap shortens the timeline cumulatively; texts/audios/PiPs stay
     * at their absolute positions (CapCut-like: the video shifts under them).
     */
    fun setClipTransition(clipId: String, us: Long, commit: Boolean = true) {
        val apply: (Project) -> Project = { p ->
            val idx = p.clips.indexOfFirst { it.id == clipId }
            if (idx < 0) p
            else p.copy(clips = TimelineOps.setTransitionIn(p.clips, idx, us))
        }
        if (commit) commitOrMutate("转场") { apply(it) } else previewTransform { apply(it) }
    }

    // ------------------------------------------------------------------
    // Mosaic regions (v1.2.2)
    // ------------------------------------------------------------------

    /** Add a default-centered mosaic region to the selected clip and select it. */
    fun addMosaicRegion(clipId: String) {
        val region = com.RooMCutNext.core.model.MosaicRegion(id = IdGen.new("mos"))
        var added = false
        mutate("添加马赛克") { p ->
            val result = TimelineOps.addMosaicRegion(p.clips, clipId, region)
            if (result == null) p
            else {
                added = true
                p.copy(clips = result.first)
            }
        }
        if (added) {
            _state.update { it.copy(selectedMosaicId = region.id) }
        } else {
            _state.update { it.copy(toast = app.getString(com.RooMCutNext.R.string.panel_mosaic_full)) }
        }
    }

    /** Update one region of the selected clip (slider drags preview live). */
    fun updateMosaicRegion(clipId: String, region: com.RooMCutNext.core.model.MosaicRegion, commit: Boolean = true) {
        val apply: (Project) -> Project = { p ->
            p.copy(clips = TimelineOps.updateMosaicRegion(p.clips, clipId, region))
        }
        if (commit) commitOrMutate("编辑马赛克") { apply(it) } else previewTransform { apply(it) }
    }

    /** Remove one region of the selected clip. */
    fun removeMosaicRegion(clipId: String, regionId: String) {
        mutate("删除马赛克") { p ->
            p.copy(clips = TimelineOps.removeMosaicRegion(p.clips, clipId, regionId))
        }
        if (_state.value.selectedMosaicId == regionId) {
            _state.update { it.copy(selectedMosaicId = null) }
        }
    }

    /** Select which region of the selected clip the mosaic panel edits. */
    fun selectMosaic(regionId: String?) {
        _state.update { it.copy(selectedMosaicId = regionId) }
    }

    // ------------------------------------------------------------------
    // Text overlays (independent layer)
    // ------------------------------------------------------------------

    /**
     * Add a text overlay at the playhead. Texts are their own layer: they can
     * be added on an empty timeline (black background) and may extend beyond
     * the video clips.
     */
    fun addTextAtPlayhead() {
        val s = _state.value
        val start = s.playheadUs.coerceAtLeast(0L)
        val item = TextOverlayItem(
            id = IdGen.new("txt"),
            text = "",
            startUs = start,
            endUs = start + 3_000_000L,
        )
        mutate("添加文字") { p -> p.copy(texts = TimelineOps.insertText(p.texts, item)) }
        _state.update {
            it.copy(selectedTextId = item.id, selectedClipId = null, selectedAudioId = null, activePanel = PanelKind.TEXT)
        }
    }

    fun updateText(item: TextOverlayItem, commit: Boolean = true) {
        if (commit) {
            commitOrMutate("编辑文字") { p ->
                p.copy(texts = p.texts.map { if (it.id == item.id) item else it })
            }
        } else {
            previewTransform { p ->
                p.copy(texts = p.texts.map { if (it.id == item.id) item else it })
            }
        }
    }

    // ------------------------------------------------------------------
    // Fonts & LUT filters (v1.1)
    // ------------------------------------------------------------------

    private val app: Application get() = getApplication()

    /**
     * Import a picked font file and — when a text overlay is selected —
     * apply it right away (that is why the user opened the picker).
     */
    fun importFont(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val spec = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                FontRegistry.importUri(app, uri)
            }
            if (spec == null) {
                _state.update { it.copy(toast = app.getString(com.RooMCutNext.R.string.font_import_failed)) }
                return@launch
            }
            _state.update { it.copy(availableFonts = FontRegistry.specs()) }
            _state.update { it.copy(toast = app.getString(com.RooMCutNext.R.string.font_imported_fmt, spec.displayName())) }
            _state.value.selectedTextId?.let { textId ->
                _state.value.project.textById(textId)?.let { item ->
                    mutate("字体") { p ->
                        p.copy(texts = p.texts.map { if (it.id == textId) it.copy(style = it.style.copy(fontId = spec.id)) else it })
                    }
                }
            }
        }
    }

    /** Long-press delete on a user-imported font. */
    fun deleteUserFont(id: String) {
        viewModelScope.launch {
            val name = FontRegistry.specs().firstOrNull { it.id == id }?.displayName() ?: id
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                FontRegistry.deleteUserFont(id)
            }
            if (ok) {
                _state.update { it.copy(availableFonts = FontRegistry.specs()) }
                _state.update { it.copy(toast = app.getString(com.RooMCutNext.R.string.font_deleted_fmt, name)) }
            }
        }
    }

    /** Import a picked .cube LUT and apply it to the selected clip when any. */
    fun importLut(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val spec = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                LutRegistry.importUri(app, uri)
            }
            if (spec == null) {
                _state.update { it.copy(toast = app.getString(com.RooMCutNext.R.string.lut_import_failed)) }
                return@launch
            }
            _state.update { it.copy(availableLuts = LutRegistry.specs()) }
            _state.update { it.copy(toast = app.getString(com.RooMCutNext.R.string.lut_imported_fmt, spec.displayName())) }
            _state.value.selectedClipId?.let { clipId ->
                mutate("滤镜") { p ->
                    p.copy(clips = TimelineOps.update(p.clips, clipId) {
                        it.copy(adjust = it.adjust.copy(lutId = spec.id, lutStrength = 1f))
                    })
                }
            }
        }
    }

    /** Long-press delete on a user-imported LUT. */
    fun deleteUserLut(id: String) {
        viewModelScope.launch {
            val name = LutRegistry.specs().firstOrNull { it.id == id }?.displayName() ?: id
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                LutRegistry.deleteUserLut(id)
            }
            if (ok) {
                _state.update { it.copy(availableLuts = LutRegistry.specs()) }
                _state.update { it.copy(toast = app.getString(com.RooMCutNext.R.string.lut_deleted_fmt, name)) }
                // Clips still referencing the deleted LUT degrade to no
                // filter at build time; nothing to clean in project JSON.
            }
        }
    }

    fun moveText(id: String, newStartUs: Long, commit: Boolean = true) {
        if (commit) {
            commitOrMutate("移动文字") { p -> p.copy(texts = TimelineOps.moveText(p.texts, id, newStartUs)) }
        } else {
            previewTransform { p -> p.copy(texts = TimelineOps.moveText(p.texts, id, newStartUs)) }
        }
    }

    fun trimText(id: String, newStartUs: Long, newEndUs: Long, commit: Boolean = true) {
        val apply: (Project) -> Project = { p ->
            p.copy(
                texts = p.texts.map {
                    if (it.id != id) it
                    else {
                        val start = newStartUs.coerceAtLeast(0L)
                        var end = newEndUs.coerceAtLeast(start + 200_000L)
                        if (end - start < 200_000L) end = start + 200_000L
                        it.copy(startUs = start, endUs = end)
                    }
                }
            )
        }
        if (commit) commitOrMutate("调整文字时间") { apply(it) } else previewTransform { apply(it) }
    }

    fun deleteText(id: String) {
        mutate("删除文字") { p -> p.copy(texts = p.texts.filter { it.id != id }) }
        _state.update { it.copy(selectedTextId = null, activePanel = null) }
    }

    // ------------------------------------------------------------------
    // PiP layers (v1.2)
    // ------------------------------------------------------------------

    /**
     * Insert a picture-in-picture layer from an EXISTING project asset
     * (video or image — transparent PNGs become stickers) at the playhead.
     * Images default to 3s like text; videos take their full duration.
     */
    fun addPipAtPlayhead(assetId: String) {
        val s = _state.value
        val asset = s.project.assetById(assetId) ?: return
        val startUs = s.playheadUs.coerceAtLeast(0L)
        val durationUs = if (asset.isImage) IMAGE_DEFAULT_DURATION_US else asset.durationUs
        val layer = PipLayer(
            id = IdGen.new("pip"),
            assetId = asset.id,
            startUs = startUs,
            durationUs = durationUs.coerceAtLeast(TimelineOps.PIP_MIN_DURATION_US),
        )
        mutate("添加画中画") { p -> p.copy(pips = TimelineOps.insertPip(p.pips, layer)) }
        _state.update {
            it.copy(selectedPipId = layer.id, selectedClipId = null, selectedTextId = null, selectedAudioId = null)
        }
    }

    fun updatePip(item: PipLayer, commit: Boolean = true) {
        val apply: (Project) -> Project = { p ->
            p.copy(pips = TimelineOps.updatePip(p.pips, item.id) { item })
        }
        if (commit) commitOrMutate("编辑画中画") { apply(it) } else previewTransform { apply(it) }
    }

    /** Timeline drag: move the whole block (start slides, duration fixed). */
    fun movePip(id: String, newStartUs: Long, commit: Boolean = true) {
        val apply: (Project) -> Project = { p ->
            p.copy(pips = TimelineOps.movePip(p.pips, id, newStartUs))
        }
        if (commit) commitOrMutate("移动画中画") { apply(it) } else previewTransform { apply(it) }
    }

    /** Timeline drag on an edge: trim, opposite edge fixed. */
    fun trimPipEdge(id: String, isLeft: Boolean, timelineTargetUs: Long, commit: Boolean = true) {
        val s = _state.value
        val pip = s.project.pipById(id) ?: return
        val asset = s.project.assetById(pip.assetId)
        val apply: (Project) -> Project = { p ->
            p.copy(pips = TimelineOps.trimPipEdge(p.pips, id, asset, isLeft, timelineTargetUs))
        }
        if (commit) commitOrMutate("修剪画中画") { apply(it) } else previewTransform { apply(it) }
    }

    fun deletePip(id: String) {
        mutate("删除画中画") { p -> p.copy(pips = TimelineOps.deletePip(p.pips, id)) }
        _state.update { it.copy(selectedPipId = null) }
    }

    // ------------------------------------------------------------------
    // Audio clips (independent layer)
    // ------------------------------------------------------------------

    fun updateAudio(item: AudioClip, commit: Boolean = true) {
        if (commit) {
            commitOrMutate("编辑音频") { p ->
                p.copy(audios = p.audios.map { if (it.id == item.id) item else it })
            }
        } else {
            previewTransform { p ->
                p.copy(audios = p.audios.map { if (it.id == item.id) item else it })
            }
        }
    }

    fun moveAudio(id: String, newStartUs: Long, commit: Boolean = true) {
        if (commit) {
            commitOrMutate("移动音频") { p -> p.copy(audios = TimelineOps.moveAudio(p.audios, id, newStartUs)) }
        } else {
            previewTransform { p -> p.copy(audios = TimelineOps.moveAudio(p.audios, id, newStartUs)) }
        }
    }

    /** Live trim preview during handle drag (no undo record). */
    fun previewAudioTrimEdge(id: String, isLeft: Boolean, timelineTargetUs: Long) {
        val s = _state.value
        val audio = s.project.audioById(id) ?: return
        val asset = s.project.assetById(audio.assetId)
        val trimmed = TimelineOps.trimAudioEdge(s.project.audios, id, asset, isLeft, timelineTargetUs)
        val result = trimmed.firstOrNull { it.id == id } ?: return
        _state.update {
            it.copy(
                audioTrimPreview = AudioTrimPreview(
                    id, result.startUs, result.sourceInUs, result.sourceOutUs,
                )
            )
        }
    }

    /** Commit the audio trim after the handle is released. */
    fun commitAudioTrim() {
        val preview = _state.value.audioTrimPreview ?: return
        _state.update { it.copy(audioTrimPreview = null) }
        mutate("修剪音频") { p ->
            p.copy(
                audios = p.audios.map {
                    if (it.id != preview.audioId) it
                    else it.copy(
                        startUs = preview.startUs,
                        sourceInUs = preview.sourceInUs,
                        sourceOutUs = preview.sourceOutUs,
                    )
                }
            )
        }
    }

    fun cancelAudioTrim() {
        _state.update { it.copy(audioTrimPreview = null) }
    }

    fun setAudioVolume(id: String, volume: Float, commit: Boolean = true) {
        val v = volume.coerceIn(0f, 2f)
        val apply: (Project) -> Project = { p ->
            p.copy(audios = TimelineOps.updateAudio(p.audios, id) { it.copy(volume = v) })
        }
        if (commit) commitOrMutate("音量") { apply(it) } else previewTransform { apply(it) }
    }

    fun toggleAudioMuted(id: String) {
        mutate("静音") { p ->
            p.copy(audios = TimelineOps.updateAudio(p.audios, id) { it.copy(muted = !it.muted) })
        }
    }

    fun setAudioFades(id: String, fadeInMs: Int, fadeOutMs: Int, commit: Boolean = true) {
        val fi = fadeInMs.coerceIn(0, 2000)
        val fo = fadeOutMs.coerceIn(0, 2000)
        val apply: (Project) -> Project = { p ->
            p.copy(audios = TimelineOps.updateAudio(p.audios, id) { it.copy(fadeInMs = fi, fadeOutMs = fo) })
        }
        if (commit) commitOrMutate("淡入淡出") { apply(it) } else previewTransform { apply(it) }
    }

    fun deleteAudio(id: String) {
        mutate("删除音频") { p -> p.copy(audios = TimelineOps.deleteAudio(p.audios, id)) }
        _state.update { it.copy(selectedAudioId = null, activePanel = null) }
    }

    // ------------------------------------------------------------------
    // Voiceover recording (v1.1.4)
    // ------------------------------------------------------------------

    private var voiceRecorder: com.RooMCutNext.media.VoiceRecorder? = null
    private var voiceoverJob: Job? = null
    private var voiceoverFile: File? = null
    private var voiceoverStartUs = 0L
    private var voiceoverStartWallMs = 0L

    /** True while a voiceover take is in progress. */
    val isRecordingVoiceover: Boolean get() = _state.value.voiceover != null

    /**
     * Start a narration take at the playhead: playback runs along so the
     * user can narrate against the picture; mic audio goes to an m4a inside
     * the project media dir. [stopVoiceover] turns it into an AudioClip.
     */
    fun startVoiceover() {
        if (voiceRecorder != null) return
        val s = _state.value
        val startUs = s.playheadUs.coerceAtLeast(0L)
        val file = File(repo.mediaDir(projectId), "${IdGen.new("vo")}.m4a")
        val recorder = com.RooMCutNext.media.VoiceRecorder(app, file)
        try {
            recorder.start()
        } catch (e: Exception) {
            _state.update { it.copy(toast = app.getString(com.RooMCutNext.R.string.voiceover_start_failed)) }
            return
        }
        voiceRecorder = recorder
        voiceoverFile = file
        voiceoverStartUs = startUs
        voiceoverStartWallMs = android.os.SystemClock.elapsedRealtime()
        _state.update { it.copy(voiceover = VoiceoverState(elapsedMs = 0L, startUs = startUs)) }
        // Play along (from the very end, restart like a normal play tap).
        if (s.project.hasContent && !s.isPlaying) {
            if (s.playheadUs >= s.project.totalDurationUs) seekTo(0L)
            playback.play()
        }
        voiceoverJob = viewModelScope.launch {
            while (voiceRecorder != null) {
                _state.update {
                    it.copy(voiceover = it.voiceover?.copy(
                        elapsedMs = android.os.SystemClock.elapsedRealtime() - voiceoverStartWallMs,
                    ))
                }
                delay(200L)
            }
        }
    }

    /**
     * Finish the take. Valid recordings become an AudioClip at the take's
     * timeline position; too-short takes are discarded with a notice.
     */
    fun stopVoiceover() {
        val recorder = voiceRecorder ?: return
        voiceRecorder = null
        voiceoverJob?.cancel()
        voiceoverJob = null
        val file = voiceoverFile ?: return
        voiceoverFile = null
        // Stop the picture too — the user wants to listen to the take.
        playback.pause()
        val startUs = voiceoverStartUs
        val hadTake = recorder.stop()
        _state.update { it.copy(voiceover = null) }
        if (!hadTake) {
            _state.update { it.copy(toast = app.getString(com.RooMCutNext.R.string.voiceover_too_short)) }
            return
        }
        viewModelScope.launch {
            val asset = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.RooMCutNext.media.MetadataExtractor.extract(file)
            }
            if (asset == null || asset.durationUs <= 0L) {
                file.delete()
                _state.update { it.copy(toast = app.getString(com.RooMCutNext.R.string.voiceover_too_short)) }
                return@launch
            }
            val clip = AudioClip(
                id = IdGen.new("aud"),
                assetId = asset.id,
                startUs = startUs,
                sourceInUs = 0L,
                sourceOutUs = asset.durationUs,
            )
            mutate("旁白录音") { p ->
                p.copy(
                    assets = p.assets + asset,
                    audios = TimelineOps.insertAudio(p.audios, clip),
                )
            }
            _state.update {
                it.copy(
                    selectedAudioId = clip.id,
                    selectedClipId = null,
                    selectedTextId = null,
                    toast = app.getString(com.RooMCutNext.R.string.voiceover_added_fmt, asset.displayName),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    fun togglePlay() {
        val s = _state.value
        if (!s.project.hasContent) return
        if (s.isPlaying) {
            playback.pause()
        } else {
            if (s.playheadUs >= s.project.totalDurationUs) {
                seekTo(0L)
            }
            playback.play()
        }
    }

    fun seekTo(us: Long) {
        val total = _state.value.project.totalDurationUs
        val clamped = us.coerceIn(0L, total)
        _state.update { it.copy(playheadUs = clamped) }
        playback.seekTo(clamped)
    }

    /** Drag-path seek: UI playhead updates instantly, player seek coalesced. */
    fun seekDuringDrag(us: Long) {
        val total = _state.value.project.totalDurationUs
        val clamped = us.coerceIn(0L, total)
        _state.update { it.copy(playheadUs = clamped) }
        playback.seekThrottled(clamped)
    }

    /** Media3 scrubbing mode on: frames render immediately while dragging. */
    fun beginScrub() {
        scrubbingByTouch = true
        playback.beginScrub()
    }

    /** Scrubbing off; restores the pre-drag play/pause state. */
    fun endScrub() {
        scrubbingByTouch = false
        playback.endScrub()
    }

    fun stepFrame(forward: Boolean) {
        playback.pause()
        val s = _state.value
        val target = playback.stepFrame(s.project, s.playheadUs, forward)
        _state.update { it.copy(playheadUs = target) }
    }

    fun goToStart() = seekTo(0L)
    fun goToEnd() = seekTo(_state.value.project.totalDurationUs)

    // ------------------------------------------------------------------
    // Zoom
    // ------------------------------------------------------------------

    fun setZoom(pxPerSecond: Float) {
        _state.update { it.copy(pxPerSecond = pxPerSecond.coerceIn(MIN_PPS, MAX_PPS)) }
    }

    fun zoomIn() = setZoom(_state.value.pxPerSecond * 1.6f)
    fun zoomOut() = setZoom(_state.value.pxPerSecond / 1.6f)

    /** Set by the timeline UI so "fit" can be computed from the real viewport. */
    var viewportWidthPx: Int = 0

    fun fitZoom() {
        val totalSec = _state.value.project.totalDurationUs / 1_000_000f
        if (totalSec > 0f && viewportWidthPx > 0) {
            setZoom(viewportWidthPx / totalSec * 0.92f)
        }
    }

    // ------------------------------------------------------------------
    // Undo / redo
    // ------------------------------------------------------------------

    fun undo() {
        val current = _state.value.project
        val previous = history.undo(current) ?: return
        applyRestored(previous)
    }

    fun redo() {
        val current = _state.value.project
        val next = history.redo(current) ?: return
        applyRestored(next)
    }

    private fun applyRestored(project: Project) {
        dragOriginProject = null
        _state.update { s ->
            s.copy(
                project = project,
                selectedClipId = s.selectedClipId?.takeIf { project.clipById(it) != null },
                selectedTextId = s.selectedTextId?.takeIf { project.textById(it) != null },
                selectedAudioId = s.selectedAudioId?.takeIf { project.audioById(it) != null },
            )
        }
        _structureVersion.value += 1
        refreshPlayback(force = true)
        scheduleAutosave()
    }

    // ------------------------------------------------------------------
    // Project-level
    // ------------------------------------------------------------------

    fun renameProject(name: String) {
        val trimmed = name.trim().ifBlank { _state.value.project.name }
        _state.update { it.copy(project = _state.value.project.copy(name = trimmed)) }
        saveNow()
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    /** Height chosen in the export sheet (kept across the permission dialog). */
    var pendingExportHeight: Int? = null

    fun startExport(height: Int? = pendingExportHeight) {
        val s = _state.value
        if (!s.project.hasContent) {
            _state.update { it.copy(toast = "时间线为空，无法导出") }
            return
        }
        pendingExportHeight = height
        saveNow()
        ExportBus.update(ExportBus.ExportState(s.project.id, s.project.name, ExportBus.Phase.RUNNING, -1f))
        ExportService.start(getApplication(), s.project.id, height)
    }

    fun cancelExport() {
        ExportService.cancel(getApplication())
    }

    fun dismissExportResult() {
        val es = _state.value.exportState
        if (es != null && es.phase != ExportBus.Phase.RUNNING) ExportBus.clear()
    }

    override fun onCleared() {
        saveNow()
        overlays.release()
        playback.release()
        super.onCleared()
    }

    companion object {
        const val MIN_PPS = 6f
        const val MAX_PPS = 480f

        fun factory(app: Application, projectId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return EditorViewModel(app, projectId) as T
                }
            }
    }
}
