package com.RooMCutNext.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.RooMCutNext.ai.AiChatController
import com.RooMCutNext.ai.AiClipFrame
import com.RooMCutNext.ai.AiClipVideo
import com.RooMCutNext.ai.AiProjectContext
import com.RooMCutNext.ai.AiSettings
import com.RooMCutNext.ai.AiSettingsStore
import com.RooMCutNext.ai.CustomSkillCodec
import com.RooMCutNext.ai.EditSkill
import com.RooMCutNext.ai.HttpLlmTransport
import com.RooMCutNext.ai.VideoPartStyle
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.Effects
import com.RooMCutNext.core.model.AudioClip
import com.RooMCutNext.core.model.Clip
import com.RooMCutNext.core.model.ColorAdjust
import com.RooMCutNext.core.model.CropSpec
import com.RooMCutNext.core.model.IMAGE_DEFAULT_DURATION_US
import com.RooMCutNext.core.model.BLACK_DEFAULT_DURATION_US
import com.RooMCutNext.core.model.BLACK_FRAME_ASSET_ID
import com.RooMCutNext.core.model.BLACK_FRAME_FILE_NAME
import com.RooMCutNext.core.model.MediaAsset
import com.RooMCutNext.core.model.MediaType
import com.RooMCutNext.core.model.PipLayer
import com.RooMCutNext.core.model.Project
import com.RooMCutNext.core.model.TextOverlayItem
import com.RooMCutNext.core.model.snapProjectFps
import com.RooMCutNext.core.ops.TimelineOps
import com.RooMCutNext.core.undo.SnapshotHistory
import com.RooMCutNext.core.util.IdGen
import com.RooMCutNext.data.ProjectRepository
import com.RooMCutNext.data.CustomSkillStore
import com.RooMCutNext.data.SettingsRepository
import com.RooMCutNext.export.ExportBus
import com.RooMCutNext.export.ExportService
import com.RooMCutNext.media.FontRegistry
import com.RooMCutNext.media.LutRegistry
import com.RooMCutNext.media.MediaImporter
import com.RooMCutNext.media.ThumbnailStore
import com.RooMCutNext.playback.OverlayPreviewController
import com.RooMCutNext.playback.PlaybackController
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** v1.6.6 画面感知：单轮最多发的帧数与帧尺寸（token 成本护栏）。 */
private const val AI_MAX_FRAMES = 10
private const val AI_FRAME_EDGE = 384
private const val AI_FRAME_CACHE_MAX = 64

/**
 * v1.6.9 整段视频直传：代理规格与流量护栏。
 *
 * 480p/700kbps 的可辨认代理（视频理解模型只采样帧，对低分辨率很
 * 鲁棒）；单条与总量的 base64 预算对齐主流端点的请求体上限
 * （DashScope/Qwen 系最大，OpenAI 兼容网关普遍 ≥10MB）。
 */
private const val AI_VIDEO_PROXY_HEIGHT = 480
private const val AI_VIDEO_PROXY_BITRATE = 700_000
/** 单条内容窗口发送上限（更长只附开头，消息里标注）。 */
private const val AI_VIDEO_MAX_SEND_US = 60_000_000L
/** 太短的窗口不值得走视频（中间帧已够）。 */
private const val AI_VIDEO_MIN_WINDOW_US = 1_000_000L
/** 单条 base64 上限（≈4.5MB 原始视频）。 */
private const val AI_VIDEO_MAX_B64 = 6_000_000
/** 单轮总 base64 预算（超出的段退回关键帧）。 */
private const val AI_VIDEO_TOTAL_B64 = 12_000_000
private const val AI_VIDEO_CACHE_MAX = 6
private const val AI_VIDEO_PROXY_TIMEOUT_MS = 120_000L

enum class PanelKind { SPEED, ROTATE, CROP, ADJUST, TEXT, AUDIO, INFO, EXPORT, GOTO, PIP, TRANSITION, MOSAIC, AI_CHAT }

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
    /**
     * v1.2.10: PLAY INTENT (the player's playWhenReady) — unlike
     * [isPlaying] it does NOT flicker false while the player buffers across
     * an item boundary. Drives the overlay engine (crossfade fade-out tail
     * + overlay play/pause) and the transport icon.
     */
    val wantsPlay: Boolean = false,
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
    private val settingsRepo = SettingsRepository(application)
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

    /**
     * v1.6.0: AI 剪辑会话（OpenChatCut 模式复刻）。
     *
     * agent 工具直接落到 TimelineOps 纯函数 —— 与手动编辑、撤销栈、导出
     * 管线共享同一套核心命令；v1.6.4 起修改完直接以单条撤销记录提交
     * （不满意靠撤销兜底，不再弹确认）。密钥经
     * [SettingsRepository] 只存本机。
     */
    val aiChat: AiChatController = AiChatController(
        scope = viewModelScope,
        transport = HttpLlmTransport(),
        contextProvider = {
            val s = _state.value
            AiProjectContext(
                project = s.project,
                playheadUs = s.playheadUs,
                fonts = s.availableFonts.map { it.id to it.nameZh },
                luts = s.availableLuts.map { it.id to it.nameZh },
            )
        },
        applyDraft = ::aiApplyDraftInternal,
        settingsStore = object : AiSettingsStore {
            override fun load(): AiSettings = settingsRepo.aiSettings()
            override fun save(settings: AiSettings) = settingsRepo.setAiSettings(settings)
        },
        // v1.6.6 画面感知：多模态模型由帧真正“看到”内容（高潮前置/
        // 整片精简等判断型技能的前提）；提取失败返回空 = 退回先问模式。
        framesProvider = { project -> aiCollectFrames(project) },
        // v1.6.9 整段视频：原生视频模型直接“看”整段素材（对齐电脑上
        // 附文件的体验）；转码失败返回空 = 退回关键帧。
        videosProvider = { project -> aiCollectVideos(project) },
        // v1.6.8 自定义技能：每轮 send 现取 —— 技能面板里刚导入的下一句即生效。
        customSkillsProvider = { _customSkills.value },
    )

    // ------------------------------------------------------------------
    // v1.6.8 自定义技能（导入/删除/列表 —— 技能面板消费）
    // ------------------------------------------------------------------

    private val customSkillStore = CustomSkillStore(application)

    private val _customSkills = MutableStateFlow(customSkillStore.list())

    /** 已导入的自定义技能（技能面板列表 + system prompt 注入源）。 */
    val customSkills: StateFlow<List<EditSkill>> = _customSkills.asStateFlow()

    /**
     * 导入技能（粘贴文本或文件内容同一入口）：校验 → 落盘 → 刷新列表。
     * 返回 [CustomSkillCodec.Result] 供 UI 展示“导入了 N 个/失败原因”。
     */
    fun importCustomSkills(text: String): CustomSkillCodec.Result {
        val r = CustomSkillCodec.parse(text, existing = _customSkills.value)
        if (r is CustomSkillCodec.Result.Success) {
            r.skills.forEach { customSkillStore.save(it) }
            _customSkills.value = customSkillStore.list()
        }
        return r
    }

    fun deleteCustomSkill(id: String) {
        customSkillStore.delete(id)
        _customSkills.value = customSkillStore.list()
    }

    /**
     * 读取导入文件内容（IO 线程，超限 256KB 返回 null —— 技能 JSON
     * 正常在几 KB 以内，选错大文件直接拒读，不把内存吃干）。
     */
    suspend fun readSkillFileText(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { ins ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = ins.read(chunk)
                    if (n <= 0) break
                    if (buf.size() + n > MAX_SKILL_FILE_BYTES) return@runCatching null
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray().decodeToString()
            }
        }.getOrNull()
    }

    /** v1.6.6 画面感知：帧缓存（assetId+窗口位置 → base64），避免每轮重复解码。 */
    private val aiFrameCache = mutableMapOf<String, String>()

    /**
     * v1.6.6 画面感知 — 为时间线每段提取中间帧小图（≤384px JPEG base64）。
     *
     * 视频：MediaMetadataRetriever 取内容窗口中间的最近同步帧（判断
     * 内容足够，精确边界仍靠模型工具）；图片：直接解码整图；黑场与
     * 音频跳过（无画面信息）。段数超过 [AI_MAX_FRAMES] 时均匀采样，
     * 保持时间线分布。单段失败不阻塞整轮（缓存空串 = 该段无帧）。
     */
    private suspend fun aiCollectFrames(project: Project): List<AiClipFrame> =
        withContext(Dispatchers.IO) {
            val dir = repo.mediaDir(project.id)
            val assets = project.assets.associateBy { it.id }
            val candidates = project.clips.mapNotNull { clip ->
                val asset = assets[clip.assetId] ?: return@mapNotNull null
                if (asset.id == BLACK_FRAME_ASSET_ID || asset.type == MediaType.AUDIO) return@mapNotNull null
                clip to asset
            }
            if (candidates.isEmpty()) return@withContext emptyList()
            val selected = if (candidates.size <= AI_MAX_FRAMES) candidates else {
                (0 until AI_MAX_FRAMES).map { i -> candidates[i * candidates.size / AI_MAX_FRAMES] }
            }
            selected.mapNotNull { (clip, asset) ->
                val midUs = (clip.sourceInUs + clip.sourceOutUs) / 2
                val key = "${asset.id}:${midUs / 100_000}"
                val b64 = aiFrameCache.getOrPut(key) {
                    if (aiFrameCache.size > AI_FRAME_CACHE_MAX) aiFrameCache.clear()
                    extractFrameB64(File(dir, asset.fileName), asset, midUs).orEmpty()
                }
                if (b64.isEmpty()) null else AiClipFrame(
                    clipId = clip.id,
                    assetName = asset.displayName,
                    midSec = midUs / 1_000_000.0,
                    b64Jpeg = b64,
                )
            }
        }

    /** 解码一帧并压到 ≤[AI_FRAME_EDGE] 边长的 JPEG base64（失败 null）。 */
    private fun extractFrameB64(source: File, asset: MediaAsset, midUs: Long): String? {
        val raw = when (asset.type) {
            MediaType.VIDEO -> runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(source.absolutePath)
                    r.getFrameAtTime(midUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            }.getOrNull()
            MediaType.IMAGE -> runCatching { BitmapFactory.decodeFile(source.absolutePath) }.getOrNull()
            else -> null
        } ?: return null
        val edge = maxOf(raw.width, raw.height)
        val scaled = if (edge > AI_FRAME_EDGE) {
            val ratio = AI_FRAME_EDGE.toFloat() / edge
            Bitmap.createScaledBitmap(
                raw,
                (raw.width * ratio).roundToInt().coerceAtLeast(1),
                (raw.height * ratio).roundToInt().coerceAtLeast(1),
                true,
            )
        } else raw
        if (scaled !== raw) raw.recycle()
        val bos = ByteArrayOutputStream()
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, 60, bos)) return null
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    // -------------------------------------------------------------
    // v1.6.9 整段视频直传（原生视频理解模型）
    // -------------------------------------------------------------

    /**
     * v1.6.9 整段视频 — 为时间线每个视频片段压一个 480p 小代理并
     * base64 直传（Media3 Transformer，与导出同一引擎）。
     *
     * 护栏：窗口超 [AI_VIDEO_MAX_SEND_US] 只附开头（消息里标注）；
     * 单条/总量 base64 预算超限或转码失败的段自动缺席 —— 控制器用
     * 该段的中间帧补齐。代理文件落盘缓存（同窗口重对话不重压）。
     */
    private suspend fun aiCollectVideos(project: Project): List<AiClipVideo> =
        withContext(Dispatchers.IO) {
            val dir = repo.mediaDir(project.id)
            val assets = project.assets.associateBy { it.id }
            val style = settingsRepo.aiSettings().effectiveVideoPartStyle
            var budget = AI_VIDEO_TOTAL_B64
            val out = mutableListOf<AiClipVideo>()
            for (clip in project.clips) {
                if (budget <= 0) break
                val asset = assets[clip.assetId] ?: continue
                if (asset.type != MediaType.VIDEO) continue
                val windowUs = (clip.sourceOutUs - clip.sourceInUs).coerceAtLeast(0L)
                if (windowUs < AI_VIDEO_MIN_WINDOW_US) continue
                val sendUs = minOf(windowUs, AI_VIDEO_MAX_SEND_US)
                val proxy = runCatching {
                    transcodeVideoProxy(File(dir, asset.fileName), clip.sourceInUs, clip.sourceInUs + sendUs)
                }.getOrNull() ?: continue
                val bytes = runCatching { proxy.readBytes() }.getOrNull() ?: continue
                if (bytes.isEmpty()) continue
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                if (b64.length > AI_VIDEO_MAX_B64) continue
                budget -= b64.length
                out.add(
                    AiClipVideo(
                        clipId = clip.id,
                        assetName = asset.displayName,
                        startSec = clip.sourceInUs / 1_000_000.0,
                        sentSec = sendUs / 1_000_000.0,
                        windowSec = windowUs / 1_000_000.0,
                        b64Mp4 = b64,
                        style = style,
                    ),
                )
            }
            out
        }

    /**
     * 把源文件 [startUs, endUs) 窗口转成 480p/低码率 H264+AAC 代理，
     * 结果落盘缓存（cacheDir/ai_video，[AI_VIDEO_CACHE_MAX] 条 LRU）。
     * 失败/超时返回 null。
     */
    private suspend fun transcodeVideoProxy(source: File, startUs: Long, endUs: Long): File? {
        val cacheDir = File(app.cacheDir, "ai_video").apply { mkdirs() }
        val key = "${source.nameWithoutExtension}-${startUs / 1000}-${endUs / 1000}"
        val out = File(cacheDir, "$key.mp4")
        if (out.exists() && out.length() > 0L) {
            out.setLastModified(System.currentTimeMillis())
            return out
        }
        // LRU：腾出空位再压。
        cacheDir.listFiles()?.let { files ->
            val excess = files.size - (AI_VIDEO_CACHE_MAX - 1)
            if (excess > 0) {
                files.sortedBy { it.lastModified() }.take(excess).forEach { runCatching { it.delete() } }
            }
        }
        if (out.exists()) out.delete()

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(source))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startUs / 1000)
                    .setEndPositionMs(endUs / 1000)
                    .build(),
            )
            .build()
        val effects = Effects(
            emptyList<AudioProcessor>(),
            listOf<Effect>(Presentation.createForHeight(AI_VIDEO_PROXY_HEIGHT)),
        )
        val item = EditedMediaItem.Builder(mediaItem).setEffects(effects).build()
        val composition = Composition.Builder(
            EditedMediaItemSequence.Builder(listOf(item)).build(),
        ).build()

        val done = withTimeoutOrNull(AI_VIDEO_PROXY_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(app)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(app)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder()
                                    .setBitrate(AI_VIDEO_PROXY_BITRATE)
                                    .build(),
                            )
                            .build(),
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(c: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(out, null)
                        }

                        override fun onError(
                            c: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            runCatching { out.delete() }
                            if (cont.isActive) cont.resume(null, null)
                        }
                    })
                    .build()
                transformer.start(composition, out.absolutePath)
                cont.invokeOnCancellation { runCatching { transformer.cancel() } }
            }
        }
        return done?.takeIf { it.exists() && it.length() > 0L }
    }

    init {
        // v1.3.0: LIVE TEXT SOURCE — both preview engines' baked text overlays
        // read the CURRENT project state on every output frame (thread-safe:
        // StateFlow.value is a volatile read of an immutable tree), so text
        // visual edits (gesture drags / sliders / color / font / content)
        // render instantly with zero composition rebuilds. Export is
        // unaffected: it always bakes the frozen snapshot.
        val liveTexts: (String) -> com.RooMCutNext.core.model.TextOverlayItem? = { id ->
            _state.value.project.textById(id)
        }
        playback.liveTextLookup = liveTexts
        overlays.liveTextLookup = liveTexts
    }

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
        // v1.2.10: play INTENT echo — see EditorUiState.wantsPlay. Wired from
        // onPlayWhenReadyChanged so it only moves on real transport changes.
        playback.onWantsPlay = { wanted ->
            _state.update { it.copy(wantsPlay = wanted) }
        }
        playback.onEnded = {
            _state.update { it.copy(isPlaying = false, wantsPlay = false, playheadUs = it.project.totalDurationUs) }
            playback.pause()
        }
        playback.onError = { msg -> _state.update { it.copy(playbackError = msg) } }
        playback.onDiag = { line -> appendDiag(line) }
        // v1.2.5: overlay-layer events (PiP / crossfade-head player errors,
        // first-frame milestones, effects→plain fallback) join the same DIAG
        // stream — silent overlay failures were undiagnosable in the field.
        overlays.onDiag = { line -> appendDiag(line) }
        // v1.5.9: body first-frame events gate the transition tail's reveal —
        // the body's onRenderedFirstFrame fires per ITEM-BOUNDARY crossing,
        // telling the overlay engine the exact moment the incoming clip is
        // actually DISPLAYING (see OverlayPreviewController.noteBodyFirstFrame
        // and HeadWindowMath.gatedTailAlphaAt).
        playback.onBodyFirstFrame = { overlays.noteBodyFirstFrame() }
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
                // v1.2.10: PLAY INTENT, not actual-isPlaying — the body
                // player's isPlaying flickers false for the BUFFERING blip at
                // every item boundary, which used to disengage the crossfade
                // fade-out tail at the exact moment it was needed (the exit
                // flash) and pause/resume-cycle every overlay player.
                overlays.sync(s.project, repo.mediaDir(s.project.id), s.playheadUs, s.wantsPlay)
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

    /**
     * v1.6.0: AI 草稿提交入口 —— 整组 AI 修改作为单条撤销记录写入
     * （等价于 OpenChatCut 的 apply edit session），可选把播放头跳到 agent
     * 指定的观察位置。清空选择避免悬空 id。
     *
     * v1.6.1: 草稿里若引用了黑场资产（agent 的 add_black_clip 工具），
     * 先把内置 black_frame.png 落到项目媒体目录再提交 —— EditTools 是纯
     * Kotlin，无法自己碰文件系统，落地责任在本层。
     */
    fun aiApplyDraftInternal(after: Project, label: String, seekUs: Long?) {
        // 快路径：草稿完全没碰黑场资产时零额外开销。
        if (after.clips.none { it.assetId == BLACK_FRAME_ASSET_ID } &&
            after.assets.none { it.id == BLACK_FRAME_ASSET_ID }
        ) {
            commitAiDraft(after, label, seekUs)
            return
        }
        viewModelScope.launch {
            // 幂等：文件在则只重建元数据（6KB PNG 解码开销可忽略）；
            // 同时把草稿里的合成资产治愈为真实落盘资产（本地化名/真实字节），
            // 覆盖"文件已存在但资产条目来自模板"的撤销重放等边缘情况。
            val real = importer.ensureBlackFrameAsset(repo.mediaDir(projectId))
            if (real == null) {
                _state.update { it.copy(toast = app.getString(com.RooMCutNext.R.string.black_gap_failed)) }
                return@launch
            }
            val healed = after.copy(
                assets = after.assets.map { a -> if (a.id == BLACK_FRAME_ASSET_ID) real else a }
            )
            commitAiDraft(healed, label, seekUs)
        }
    }

    private fun commitAiDraft(after: Project, label: String, seekUs: Long?) {
        dragOriginProject = null
        val before = _state.value.project
        if (after != before) {
            history.record(label, before, after)
            _state.update {
                it.copy(
                    project = after,
                    selectedClipId = null,
                    selectedTextId = null,
                    selectedAudioId = null,
                    selectedPipId = null,
                    selectedMosaicId = null,
                )
            }
            _structureVersion.value += 1
            refreshPlayback()
            scheduleAutosave()
        }
        seekUs?.let { seekTo(it.coerceIn(0L, _state.value.project.totalDurationUs)) }
    }

    /** Preview-only state change (no undo record, no autosave) — used while sliders drag. */
    private fun previewTransform(transform: (Project) -> Project) {
        if (dragOriginProject == null) dragOriginProject = _state.value.project
        _state.update { it.copy(project = transform(it.project)) }
        _structureVersion.value += 1
        scheduleDebouncedPlaybackRefresh()
        // v1.3.2: PAUSED live edits must still SHOW. The baked text overlays
        // re-read the live state per OUTPUT frame, but a paused player
        // produces no frames — without this nudge a pinch-scaled text stayed
        // stale until the user pressed play. Both engines no-op while
        // playing, so this line is unconditionally cheap.
        nudgePreviewRedraw()
    }

    /**
     * v1.3.2: push one fresh frame through both preview engines (throttled,
     * paused-only) so live text edits render immediately. See
     * PlaybackController.nudgeRedraw / OverlayPreviewController.nudgeHeadRedraw.
     */
    private fun nudgePreviewRedraw(force: Boolean = false) {
        playback.nudgeRedraw(force)
        overlays.nudgeHeadRedraw(force)
    }

    /**
     * v1.6.2: the preview stage (AndroidView) just resized — IME opening or
     * closing, panel switches, anything that changes the stage bounds.
     *
     * While paused the video pipeline pushes no frames, so after a resize the
     * surface keeps showing the paused frame at the OLD output size (the
     * video graph reconfigures only when the next frame flows) — the exact
     * "close the keyboard and the video stays small until I press play"
     * real-device report. A forced redraw replays the cached last frame
     * through the effects pipeline at the new size, restoring the picture
     * without needing playback. No-op while playing (frames flow on their
     * own) and while nothing is prepared.
     */
    fun onPreviewStageResized() {
        nudgePreviewRedraw(force = true)
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
            // v1.3.0: kill the debounce a live drag left pending — the commit
            // refresh below already syncs everything; without this, a stale
            // job could fire a SECOND refresh shortly after and churn the
            // pipeline for nothing.
            playbackRefreshJob?.cancel()
            refreshPlayback()
            // v1.3.2: the throttled nudges during the drag may have eaten the
            // very last motion tick — force one final re-render so the frame
            // on screen is exactly the committed geometry.
            nudgePreviewRedraw(force = true)
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
                    // Audio assets become independent-layer audio clips
                    // placed sequentially FROM THE PLAYHEAD (v1.5.5: the
                    // cursor used to start at max(clipsDuration, playhead),
                    // so an import into a populated timeline always landed
                    // AFTER the last clip — "每次导入音频一定在素材的最后面".
                    // Overlapping existing audio is fine: audioLanes packs
                    // independent layers into lanes, exactly like a BGM bed
                    // under a voiceover).
                    if (outcome.assets.any { it.isAudio }) {
                        var cursor = playheadUs
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

    /**
     * v1.3.3: the preview's CONFIRM gesture (checkmark chip, or a still tap
     * that drops the selection). Keeps the placed geometry — the undoable
     * commit already happened on finger-up — drops EVERY selection AND closes
     * the inline tool panel so the editor returns to the standard timeline
     * view.
     *
     * v1.3.2 shipped the chip as a bare selectText(null): the TEXT/PIP panel
     * stayed open afterwards, and the text panel then showed its "text was
     * deleted" fallback (panel_text_gone) because its target was gone — the
     * user read that as "confirming deleted my text". Closing the panel here
     * fixes both the stuck tool highlight and that misleading hint in one
     * move: check → confirmed, timeline back, nothing selected.
     */
    fun confirmPreviewPlacement() {
        val s = _state.value
        if (!s.hasSelection && s.activePanel == null) return
        _state.update {
            it.copy(
                selectedClipId = null,
                selectedTextId = null,
                selectedAudioId = null,
                selectedPipId = null,
                selectedMosaicId = null,
                activePanel = null,
            )
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
        // v1.5.5: PiP layers split too — the razor now cuts EVERY layer type
        // under the playhead ("画中画无法被剪辑" was the missing half; the
        // right half plays the source from the cut point via sourceInUs).
        val pipResult = TimelineOps.splitPipAt(
            s.project.pips,
            s.playheadUs,
            IdGen.new("pip"),
        )
        if (videoResult == null && audioResult == null && pipResult == null) {
            _state.update { it.copy(toast = "此处无法分割（位于片段边界或片段过短）") }
            return
        }
        // One undo step: cutting at the playhead slices EVERY layer under it
        // (video, audio AND PiP), like a multi-track razor in classic NLEs.
        mutate("分割") { p ->
            p.copy(
                clips = videoResult?.clips ?: p.clips,
                audios = audioResult?.first ?: p.audios,
                pips = pipResult?.first ?: p.pips,
            )
        }
        _state.update {
            // Selection stays exclusive: prefer the video right half (the
            // layer the user most likely wants to keep trimming), then the
            // PiP right half, otherwise the audio right half.
            when {
                videoResult != null ->
                    it.copy(selectedClipId = videoResult.rightId, selectedTextId = null, selectedAudioId = null, selectedPipId = null)
                pipResult != null ->
                    it.copy(selectedPipId = pipResult.second, selectedClipId = null, selectedTextId = null, selectedAudioId = null)
                else ->
                    it.copy(selectedClipId = null, selectedTextId = null, selectedAudioId = audioResult!!.second, selectedPipId = null)
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
        // Texts and audios are independent layers anchored to the timeline:
        // deleting a video clip ripples the video track closed (v1.6.1) and
        // never shortens / drops them by itself — need a black pause? The
        // user adds a 黑场 clip (addBlackGap) instead of relying on leftovers.
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

    /**
     * v1.6.1 黑场：插入一段可自由拉伸的纯黑图片素材（默认 2s，拖边 0.1..60s）。
     *
     * [before] == null → 追加到时间线末尾（无选中素材时的入口）；
     * 否则以当前选中素材为锚，紧贴其前/后插入。首次调用把内置
     * black_frame.png 幂等地落到项目媒体目录（6KB，与普通导入同构：
     * 素材归项目所有，导出/回放与图片素材零差别）。
     */
    fun addBlackGap(before: Boolean?, durationUs: Long = BLACK_DEFAULT_DURATION_US) {
        viewModelScope.launch {
            val dir = repo.mediaDir(projectId)
            val asset = _state.value.project.assets.firstOrNull { it.isBlackFrame }
                ?: importer.ensureBlackFrameAsset(dir)
                ?: run {
                    _state.update {
                        it.copy(toast = app.getString(com.RooMCutNext.R.string.black_gap_failed))
                    }
                    return@launch
                }
            val newId = IdGen.new("c")
            mutate("添加黑场") { p ->
                val withAsset =
                    if (p.assets.any { it.isBlackFrame }) p else p.copy(assets = p.assets + asset)
                val selectedId = _state.value.selectedClipId
                val anchor = if (before != null && selectedId != null) {
                    withAsset.clips.indexOfFirst { it.id == selectedId }
                } else -1
                p.copy(
                    assets = withAsset.assets,
                    clips = TimelineOps.insertBlackGap(
                        withAsset.clips,
                        anchor,
                        after = before != false,
                        durationUs = durationUs,
                        newId = newId,
                    ),
                )
            }
            _state.update {
                it.copy(
                    selectedClipId = newId,
                    selectedTextId = null,
                    selectedAudioId = null,
                    selectedPipId = null,
                )
            }
            viewModelScope.launch { preloadThumbnails(listOf(asset)) }
        }
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

    // ------------------------------------------------------------------
    // Track-level mutes (v1.3.1)
    // ------------------------------------------------------------------

    /**
     * Mute the WHOLE video track (all clips) — the audio lanes (background
     * music / voice-overs) are NOT covered by this switch, only the video
     * track's own audio. Non-destructive: per-clip mute flags are untouched;
     * the pipelines OR the track flag in (Project.clipAudioMuted — the same
     * helper preview and export consume, so they can never disagree).
     *
     * The video-track flag goes through mutate → refreshPlayback: clip audio
     * is baked into the body composition (setRemoveAudio / GainAudioProcessor),
     * so toggling must rebuild it. PlaybackController gates the rebuild on
     * the clips hash — which does NOT move when only this flag flips — so
     * the flag is folded into the refresh signature there.
     */
    fun toggleVideoTrackMute() {
        mutate("静音视频轨道") { p -> p.copy(videoTrackMuted = !p.videoTrackMuted) }
    }

    /**
     * Mute the whole PiP track (all layers' audio). Preview applies this
     * live — drivePip sets each overlay player's volume from the flag on
     * every sync — so no composition churn is needed; export strips the
     * audio in buildPipSequence.
     */
    fun togglePipTrackMute() {
        mutate("静音画中画轨道") { p -> p.copy(pipTrackMuted = !p.pipTrackMuted) }
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

    /**
     * v1.5.0: set the transition STYLE of the selected clip (a
     * [com.RooMCutNext.core.model.TransitionStyle] id). Style and duration
     * stay orthogonal at the ops layer; HERE a look picked while the
     * duration is 0 (none) also seeds the default 0.5s so tapping a style
     * immediately shows something — one undo step covers both fields.
     */
    fun setClipTransitionType(clipId: String, styleId: String) {
        commitOrMutate("转场样式") { p ->
            val idx = p.clips.indexOfFirst { it.id == clipId }
            if (idx < 0) p
            else {
                var clips = TimelineOps.setTransitionType(p.clips, idx, styleId)
                if (p.clips[idx].transitionInUs == 0L) {
                    clips = TimelineOps.setTransitionIn(clips, idx, TRANSITION_TYPE_DEFAULT_US)
                }
                if (clips === p.clips) p else p.copy(clips = clips)
            }
        }
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
        // v1.2.10: decide on PLAY INTENT — actual-isPlaying blips false at
        // item boundaries, which would have made the button re-issue play()
        // mid-playback (a no-op, but it also skipped the pause the user
        // asked for when tapped exactly during a blip).
        if (s.wantsPlay) {
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

    /**
     * Scrubbing off. v1.2.6: playback stays PAUSED after a timeline drag
     * (user request) — see PlaybackController.endScrub.
     */
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
        // v1.2.6: the editor's ViewModel store is now scoped to the editor
        // screen (AppRoot.EditorStoreOwner), so onCleared() actually runs
        // when the user returns to the project list — that is what stops
        // the background playback. An in-progress voiceover take must not
        // leave the MIC open in the background either: discard it
        // synchronously (the async save path in stopVoiceover() cannot run
        // from here — viewModelScope is already cancelled by the time
        // onCleared() executes).
        voiceRecorder?.let { rec ->
            voiceRecorder = null
            voiceoverJob?.cancel()
            voiceoverJob = null
            runCatching { rec.stop() } // releases the mic; deletes invalid partials
            // A "valid" take cannot be inserted as an AudioClip without the
            // cancelled scope, so drop its file too instead of orphaning it
            // in the project media dir.
            voiceoverFile?.let { f -> runCatching { f.delete() } }
            voiceoverFile = null
        }
        saveNow()
        overlays.release()
        playback.release()
        super.onCleared()
    }

    companion object {
        private const val MAX_SKILL_FILE_BYTES = 256 * 1024
        const val MIN_PPS = 6f
        const val MAX_PPS = 480f

        /** v1.5.0: duration seeded when a style is picked while duration=0. */
        private const val TRANSITION_TYPE_DEFAULT_US = 500_000L

        fun factory(app: Application, projectId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return EditorViewModel(app, projectId) as T
                }
            }
    }
}
