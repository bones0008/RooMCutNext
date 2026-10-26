package com.RooMCutNext.ui.editor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.RooMCutNext.R
import java.util.Locale

// ===========================================================================
// AI 对话语音输入（v1.6.14）— 用户反馈：手动打字指挥 AI 太慢，希望像
// 语音助手一样说话即可。
//
// 方案：系统 SpeechRecognizer（零依赖零费用，不碰 BYOK 服务）——
//  - 引擎择优：在线识别（GMS 服务，质量好）优先，设备无 GMS 时退
//    minSdk=31 的 createOnDeviceSpeechRecognizer（系统离线引擎）；
//    两者皆无（极端 ROM）→ 按钮不渲染，干净降级；
//  - 交互形态：麦克风内嵌输入框 trailingIcon（v1.6.3/1.6.5 已验证
//    输入行宽度敏感，独立 48dp 按钮会把输入框挤到 ~140dp）；
//  - 结果语义：partial 实时预览进输入框，final 落定后**不自动发送** —
//    用户扫一眼、改两个字再点发送，防"听岔了直接执行错误剪辑指令"；
//  - 追加而非覆盖：连续说多段时以开始听时的文本为 base 拼接。
//
// 语音识别会话随面板销毁即止（DisposableEffect → destroy），不进
// 任何前台服务；音频只经系统识别器流转，本 APP 不落盘、不上传。
// ===========================================================================

/**
 * 把已输入文本与一句语音识别结果拼接。base 为空 → 原样 spoken；
 * 非空 → 单空格连接（中英文均可接受）。spoken 两端空白被裁剪。
 */
internal fun mergeVoiceText(base: String, spoken: String?): String {
    val s = spoken?.trim().orEmpty()
    if (s.isEmpty()) return base
    return if (base.isBlank()) s else base.trimEnd() + " " + s
}

/**
 * 识别错误码 → 用户文案资源 id。null 表示静默复位（不惊扰），
 * 兜底返回「请再试一次」—— 真机上 ERROR_CLIENT 多为 ROM 差异噪音，
 * 提示一次比无反馈更友好。
 */
internal fun voiceErrorHint(errorCode: Int): Int? = when (errorCode) {
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.ai_voice_no_match

    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.ai_voice_permission

    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.ai_voice_busy

    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.ai_voice_network

    else -> R.string.ai_voice_retry
}

/**
 * 无 UI 的语音输入会话控制（系统 SpeechRecognizer 封装）。
 *
 * 生命周期：[toggle] 开/停一次会话；[destroy] 随 Composable 卸载释放。
 * 引擎实例懒创建（首次 start）并复用 —— onError/onResults 后可直接
 * 再次 startListening。回调经可变字段暴露，由 [rememberVoiceInput]
 * 每次 SideEffect 刷新，避免捕获过期闭包。
 */
internal class VoiceInputHandle(private val appContext: Context) {

    /** 识别结果回调（partial 与 final 都已与 base 拼接）。 */
    var onPartial: (String) -> Unit = {}
    var onFinal: (String) -> Unit = {}

    /** 识别失败回调（[voiceErrorHint] 的错误码，由调用方转文案）。 */
    var onError: (Int) -> Unit = {}

    /** 是否正在听（UI 脉动/变色的依据）。 */
    var listening by mutableStateOf(false)
        private set

    /** 本设备是否有任何可用识别引擎（无则麦克风按钮不渲染）。 */
    val available: Boolean = SpeechRecognizer.isRecognitionAvailable(appContext) ||
        SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    private var recognizer: SpeechRecognizer? = null
    private var baseText: String = ""

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            // 用户停顿 → 系统即将给出 final；保持 listening 直到结果落定。
        }

        override fun onError(error: Int) {
            listening = false
            onError(error)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            onFinal(mergeVoiceText(baseText, text))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            onPartial(mergeVoiceText(baseText, text))
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * 开/停一次会话。[base] 为开始听时输入框已有文本（追加语义的锚点）。
     * 停止走 stopListening —— 系统随后回调 onResults 给出 final。
     */
    fun toggle(base: String) {
        if (listening) {
            recognizer?.stopListening()
            return
        }
        baseText = base
        if (recognizer == null) {
            // 择优：在线识别（GMS）优先 —— 标点/断句质量更好；
            // 无 GMS 的 ROM 用系统离线引擎（minSdk 31 直接可用）。
            recognizer = (
                if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
                    SpeechRecognizer.createSpeechRecognizer(appContext)
                } else {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                }
                ).also { it.setRecognitionListener(listener) }
        }
        listening = true
        recognizer?.startListening(speechIntent())
    }

    fun destroy() {
        listening = false
        recognizer?.destroy()
        recognizer = null
    }

    private fun speechIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // 跟随 APP 语言（而非系统全局）—— 中文界面就该听中文。
        val tag = appContext.resources.configuration.locales[0]?.toLanguageTag()
            ?: Locale.getDefault().toLanguageTag()
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
    }
}

/**
 * Compose 侧入口：持有 [VoiceInputHandle] 并随卸载销毁会话。
 * 回调用 SideEffect 持续刷新到最新组合实例（输入框 text setter 等
 * lambda 每次重组都是新对象，直接 remember 捕获会指向旧闭包）。
 */
@Composable
internal fun rememberVoiceInput(
    onPartial: (String) -> Unit,
    onFinal: (String) -> Unit,
    onError: (Int) -> Unit,
): VoiceInputHandle {
    val context = LocalContext.current
    val handle = remember { VoiceInputHandle(context.applicationContext) }
    SideEffect {
        handle.onPartial = onPartial
        handle.onFinal = onFinal
        handle.onError = onError
    }
    DisposableEffect(Unit) {
        onDispose { handle.destroy() }
    }
    return handle
}
