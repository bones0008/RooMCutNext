package com.RooMCutNext.ai

/**
 * AI 剪辑（v1.6.0）— 模型提供方预设与本地设置。
 *
 * 设计对齐 OpenChatCut 的 BYOK（自带密钥）哲学：RooMCutNext 不内置任何
 * 云端账号体系，用户选择一个 OpenAI 兼容的提供方、填入自己的 API Key，
 * 密钥只保存在本机 SharedPreferences，除用户配置的推理端点外不出网。
 *
 * 所有预设端点都讲同一套 /chat/completions + function calling 协议，
 * 因此一个 [HttpLlmTransport] 即可覆盖全部提供方（含本地 Ollama /
 * LM Studio 等 OpenAI 兼容网关，走 custom 预设）。
 */

/** OpenAI 兼容提供方预设。 */
data class AiProviderPreset(
    /** 稳定 id（持久化用）。 */
    val id: String,
    /** OpenAI 兼容 base URL（不含 /chat/completions）。 */
    val baseUrl: String,
    val defaultModel: String,
    val candidateModels: List<String>,
    /** 获取 API Key 的页面，仅用于设置界面提示。 */
    val keyUrl: String,
)

object AiProvider {
    const val ZAI = "zai"
    const val DEEPSEEK = "deepseek"
    const val MOONSHOT = "moonshot"
    const val OPENAI = "openai"
    const val CUSTOM = "custom"

    val PRESETS = listOf(
        AiProviderPreset(
            id = ZAI,
            baseUrl = "https://api.z.ai/api/paas/v4",
            defaultModel = "glm-4.6",
            candidateModels = listOf("glm-4.6", "glm-4.5-air", "glm-4-flash"),
            keyUrl = "https://z.ai/manage-apikey/apikey",
        ),
        AiProviderPreset(
            id = DEEPSEEK,
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-chat",
            candidateModels = listOf("deepseek-chat", "deepseek-reasoner"),
            keyUrl = "https://platform.deepseek.com/api_keys",
        ),
        AiProviderPreset(
            id = MOONSHOT,
            baseUrl = "https://api.moonshot.cn/v1",
            defaultModel = "kimi-k2-turbo-preview",
            candidateModels = listOf(
                "kimi-k2-turbo-preview", "kimi-k2-0905-preview", "moonshot-v1-auto",
            ),
            keyUrl = "https://platform.moonshot.cn/console/api-keys",
        ),
        AiProviderPreset(
            id = OPENAI,
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o",
            candidateModels = listOf("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini"),
            keyUrl = "https://platform.openai.com/api-keys",
        ),
        AiProviderPreset(
            id = CUSTOM,
            baseUrl = "",
            defaultModel = "",
            candidateModels = emptyList(),
            keyUrl = "",
        ),
    )

    fun byId(id: String): AiProviderPreset? = PRESETS.firstOrNull { it.id == id }
}

/**
 * 用户配置。`providerId == CUSTOM`（或 baseUrlOverride 非空）时使用
 * baseUrlOverride 作为端点，否则使用预设地址。
 */
data class AiSettings(
    val providerId: String = AiProvider.ZAI,
    val baseUrlOverride: String = "",
    val apiKey: String = "",
    val model: String = "",
    /**
     * v1.6.6 画面感知：auto = 模型名像多模态才发帧；on = 有帧就发；
     * off = 从不发。纯文本模型收到图会报错，控制器会自动降级重试，
     * 但 auto 启发式先把大多数情况挡在外面。
     */
    val visionMode: String = VISION_AUTO,
    /**
     * v1.6.9 画面方式：frames = 每段中间帧小图（通用多模态）；
     * video = 整段视频压成 480p 代理直传（需原生视频理解模型，
     * 对齐电脑 web/编程 Agent 里“附文件”的体验）。
     */
    val visionStyle: String = VISION_STYLE_FRAMES,
    /** v1.6.9 视频部件方言：input_video = Qwen 系；video_url = GLM/MiniMax 系。 */
    val videoPartStyle: String = VIDEO_PART_INPUT_VIDEO,
) {
    val preset: AiProviderPreset get() = AiProvider.byId(providerId) ?: AiProvider.PRESETS.first()

    /** 实际请求的 base URL（custom 必须填写完整兼容地址）。 */
    val effectiveBaseUrl: String
        get() {
            val custom = baseUrlOverride.trim()
            if (custom.isNotEmpty()) return custom.trimEnd('/')
            return preset.baseUrl
        }

    /** 实际模型：用户填写优先，其次预设默认。 */
    val effectiveModel: String
        get() = model.trim().ifEmpty { preset.defaultModel }

    /** 可以发起请求的最小配置。 */
    val ready: Boolean
        get() = effectiveBaseUrl.isNotBlank() && effectiveModel.isNotBlank() && apiKey.isNotBlank()

    /** 是否应把帧发给该模型（framesAvailable = 宿主是否提到了画面）。 */
    fun wantFrames(framesAvailable: Boolean): Boolean = when (visionMode) {
        VISION_ON -> framesAvailable
        VISION_OFF -> false
        else -> framesAvailable && modelLooksMultimodal(effectiveModel)
    }

    /**
     * v1.6.9：是否应把整段视频直传（videoClipsAvailable = 时间线上有
     * 没有视频片段）。仅 visionStyle = video 时可能为 true；auto 档
     * 还要求模型名像原生视频模型，否则回落关键帧模式。
     */
    fun wantVideo(videoClipsAvailable: Boolean): Boolean {
        if (visionStyle != VISION_STYLE_VIDEO) return false
        return when (visionMode) {
            VISION_OFF -> false
            VISION_ON -> videoClipsAvailable
            else -> videoClipsAvailable && modelLooksVideoNative(effectiveModel)
        }
    }

    /** v1.6.9：实际使用的视频部件方言。 */
    val effectiveVideoPartStyle: VideoPartStyle
        get() = if (videoPartStyle == VIDEO_PART_VIDEO_URL) VideoPartStyle.VIDEO_URL else VideoPartStyle.INPUT_VIDEO

    companion object {
        const val VISION_AUTO = "auto"
        const val VISION_ON = "on"
        const val VISION_OFF = "off"

        /** v1.6.9 画面方式：关键帧 / 整段视频。 */
        const val VISION_STYLE_FRAMES = "frames"
        const val VISION_STYLE_VIDEO = "video"

        /** v1.6.9 视频部件方言。 */
        const val VIDEO_PART_INPUT_VIDEO = "input_video"
        const val VIDEO_PART_VIDEO_URL = "video_url"

        /**
         * 模型名启发式：长得像多模态才敢发图。
         * 覆盖：gpt-4o / gpt-4.1 全系、glm-4v / glm-4.5v、
         * moonshot-v1-*-vision、qwen-vl / intern-vl / llava / qvq、
         * gemini / omni 系。deepseek-chat / kimi-k2 / glm-4.6 等
         * 纯文本模型不匹配（正确不发）。
         */
        fun modelLooksMultimodal(model: String): Boolean {
            val m = model.trim().lowercase()
            return Regex("(4o|4\\.1|vision|4v|4\\.5v|-vl|vl-|gemini|omni|llava|qvq)").containsMatchIn(m)
        }

        /**
         * v1.6.9 原生视频模型启发式（比多模态更严：能“吃整段视频”）：
         * 覆盖 qwen2.5/3-vl 全系（-vl）、qvq、glm-4v-plus、glm-4.5v、
         * minimax-vl、intern-vl、自命名含 video 的网关模型。
         * gpt-4o（只吃图不吃视频）/ glm-4.6 / deepseek / kimi 均不误伤。
         */
        fun modelLooksVideoNative(model: String): Boolean {
            val m = model.trim().lowercase()
            return Regex("(-vl|vl-|qvq|glm-4v|glm-4\\.5v|glm-4\\.6v|4v-plus|video)").containsMatchIn(m)
        }
    }
}

/** 设置持久化（由 data/SettingsRepository 适配实现）。 */
interface AiSettingsStore {
    fun load(): AiSettings
    fun save(settings: AiSettings)
}
