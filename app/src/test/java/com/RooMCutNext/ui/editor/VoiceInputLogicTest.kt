package com.RooMCutNext.ui.editor

import android.speech.SpeechRecognizer
import com.RooMCutNext.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.14 语音输入纯逻辑测试 —— [mergeVoiceText] 拼接语义与
 * [voiceErrorHint] 错误码→文案映射（SpeechRecognizer 的 ERROR_* 与
 * R.string.* 均为编译期常量，JVM 单测可直接引用，无需 Robolectric）。
 *
 * 回调语义不在此测（系统识别器集成层，真机验收）：
 * 语音→partial 填入输入框→用户手动发送，见 README v1.6.14 条目。
 */
class VoiceInputLogicTest {

    // ---- mergeVoiceText：追加而非覆盖 ----

    @Test
    fun mergeNullSpokenKeepsBase() {
        assertEquals("帮我剪", mergeVoiceText("帮我剪", null))
    }

    @Test
    fun mergeEmptySpokenKeepsBase() {
        assertEquals("帮我剪", mergeVoiceText("帮我剪", "   "))
    }

    @Test
    fun mergeOntoBlankBaseIsVerbatim() {
        assertEquals("跳剪一下", mergeVoiceText("", "跳剪一下"))
    }

    @Test
    fun mergeAppendsWithSingleSpace() {
        assertEquals("帮我剪 跳剪一下", mergeVoiceText("帮我剪", "跳剪一下"))
    }

    @Test
    fun mergeTrimsSpokenEdges() {
        assertEquals("帮我剪 跳剪一下", mergeVoiceText("帮我剪 ", "  跳剪一下  "))
    }

    @Test
    fun mergeTrimsBaseTailBeforeJoining() {
        assertEquals("帮我剪 跳剪一下", mergeVoiceText("帮我剪\t", "跳剪一下"))
    }

    /** base 全是空白时 spoken 直接顶上（isBlank 而非 isEmpty —— 空格也该被顶掉）。 */
    @Test
    fun mergeBlankishBaseIsReplaced() {
        assertEquals("跳剪一下", mergeVoiceText("   ", "跳剪一下"))
    }

    // ---- voiceErrorHint：错误码 → 文案映射 ----

    @Test
    fun noMatchAndTimeoutHintRetry() {
        assertEquals(R.string.ai_voice_no_match, voiceErrorHint(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(
            R.string.ai_voice_no_match,
            voiceErrorHint(SpeechRecognizer.ERROR_SPEECH_TIMEOUT),
        )
    }

    @Test
    fun permissionErrorHintsPermission() {
        assertEquals(
            R.string.ai_voice_permission,
            voiceErrorHint(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS),
        )
    }

    @Test
    fun busyErrorHintsBusy() {
        assertEquals(
            R.string.ai_voice_busy,
            voiceErrorHint(SpeechRecognizer.ERROR_RECOGNIZER_BUSY),
        )
    }

    @Test
    fun networkErrorsHintNetwork() {
        assertEquals(
            R.string.ai_voice_network,
            voiceErrorHint(SpeechRecognizer.ERROR_NETWORK),
        )
        assertEquals(
            R.string.ai_voice_network,
            voiceErrorHint(SpeechRecognizer.ERROR_NETWORK_TIMEOUT),
        )
    }

    /**
     * 兜底不静默：ERROR_CLIENT（ROM 差异噪音）/ ERROR_AUDIO（录音失败）
     * 等未枚举码也给出通用重试文案 —— 无反馈比一句提示更让人困惑。
     */
    @Test
    fun unknownErrorsFallBackToGenericRetry() {
        assertNotNull(voiceErrorHint(SpeechRecognizer.ERROR_CLIENT))
        assertNotNull(voiceErrorHint(SpeechRecognizer.ERROR_AUDIO))
        assertNotNull(voiceErrorHint(SpeechRecognizer.ERROR_SERVER))
        assertEquals(
            R.string.ai_voice_retry,
            voiceErrorHint(SpeechRecognizer.ERROR_CLIENT),
        )
    }

    /** 映射不返回 null —— 任何错误码都至少有通用重试文案兜底。 */
    @Test
    fun everyKnownErrorCodeMapsToSomething() {
        val codes = listOf(
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
        )
        codes.forEach { c ->
            assertTrue("error code=$c has no hint", voiceErrorHint(c) != null)
        }
    }
}
