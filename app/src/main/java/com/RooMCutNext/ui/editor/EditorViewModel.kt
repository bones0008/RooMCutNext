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
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.model.TextOverlayItem
import com.RooMCutNext.core.model.snapProjectFps
import com.RooMCutNext.core.ops.TimelineOps
import com.RooMCutNext.core.undo.SnapshotHistory
import com.RooMCutNext.core.util.IdGen
import com.RooMCutNext.data.ProjectRepository
import com.RooMCutNext.export.ExportBus
import com.RooMCutNext.export.ExportService
import com.RooMCutNext.media.MediaImporter
import com.RooMCutNext.media.ThumbnailStore
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

enum class PanelKind { SPEED, ROTATE, CROP, ADJUST, TEXT, AUDIO, INFO, EXPORT, GOTO }

data class ImportingState(val done: Int, val total: Int, val current: String)

data class TrimPreview(val clipId: String, val sourceInUs: Long, val sourceOutUs: Long)

/** Live audio trim preview: keeps the timeline start too (left-edge trims move it). */
data class AudioTrimPreview(
    val audioId: String,
    val startUs: Long,
    val sourceInUs: Long,
    val sourceOutUs: Long,
)

data class EditorUiState(
    val project: Project = Project(id = "", name = ""),
    val selectedClipId: String? = null,
    val selectedTextId: String? = null,
    val selectedAudioId: String? = null,
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
) {
    val totalDurationUs: Long get() = project.totalDurationUs
    val selectedClip: Clip? get() = selectedClipId?.let { project.clipById(it) }
    val selectedText: TextOverlayItem? get() = selectedTextId?.let { project.textById(it) }
    val selectedAudio: AudioClip? get() = selectedAudioId?.let { project.audioById(it) }
    val hasSelection: Boolean get() = selectedClipId != null || selectedTextId != null || selectedAudioId != null
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
        val trimPreview: TrimPreview?,
        val audioTrimPreview: AudioTrimPreview?,
        val missingAssetIds: Set<String>,
    )

    val timelineFlow: kotlinx.coroutines.flow.StateFlow<TimelineSnapshot?> =
        _state.map {
            TimelineSnapshot(
                it.project, it.selectedClipId, it.selectedTextId, it.selectedAudioId,
                it.trimPreview, it.audioTrimPreview, it.missingAssetIds,
            )
        }.distinctUntilChanged()
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), null)

    val zoomFlow: kotlinx.coroutines.flow.StateFlow<Float> =
        _state.map { it.pxPerSecond }.distinctUntilChanged()
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), 64f)

    val playback = PlaybackController(application, viewModelScope)

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

    private fun refreshPlayback(force: Boolean = false) {
        val project = _state.value.project
        playback.currentProjectRef = project
        if (!project.hasContent) {
            playback.setCompositionForEmpty()
            return
        }
        playback.refresh(project, repo.mediaDir(project.id), _state.value.playheadUs, force)
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

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(importing = ImportingState(0, uris.size, "")) }
            val outcome = importer.import(uris, repo.mediaDir(projectId)) { done, total, name ->
                _state.update { it.copy(importing = ImportingState(done, total, name)) }
            }
            _state.update { it.copy(importing = null) }

            if (outcome.assets.isNotEmpty()) {
                mutate("导入素材") { p ->
                    var np = p.copy(assets = p.assets + outcome.assets)
                    val visuals = outcome.assets.filter { !it.isAudio }
                    if (np.clips.isEmpty() && visuals.isNotEmpty()) {
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
                    // Video / image assets append as timeline clips.
                    visuals.forEach { asset -> np = np.copy(clips = np.clips + newClipFor(asset)) }
                    // Audio assets become independent-layer audio clips placed
                    // sequentially from the current playhead.
                    if (outcome.assets.any { it.isAudio }) {
                        var cursor = np.clipsDurationUs.coerceAtLeast(_state.value.playheadUs)
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
        _state.update { it.copy(selectedClipId = id, selectedTextId = null, selectedAudioId = null) }
    }

    fun selectText(id: String?) {
        _state.update { it.copy(selectedTextId = id, selectedClipId = null, selectedAudioId = null) }
    }

    fun selectAudio(id: String?) {
        _state.update { it.copy(selectedAudioId = id, selectedClipId = null, selectedTextId = null) }
    }

    fun openPanel(panel: PanelKind?) {
        _state.update { it.copy(activePanel = panel) }
    }

    fun clearToast() = _state.update { it.copy(toast = null) }

    fun clearPlaybackError() = _state.update { it.copy(playbackError = null) }

    // ------------------------------------------------------------------
    // Structural edits
    // ------------------------------------------------------------------

    fun splitAtPlayhead() {
        val s = _state.value
        val result = TimelineOps.splitAtTimeline(
            s.project.clips,
            { id -> s.project.assetById(id) },
            s.playheadUs,
            IdGen.new("c"),
        )
        if (result == null) {
            _state.update { it.copy(toast = "此处无法分割（位于片段边界或片段过短）") }
            return
        }
        mutate("分割") { p -> p.copy(clips = result.clips) }
        _state.update { it.copy(selectedClipId = result.rightId) }
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
