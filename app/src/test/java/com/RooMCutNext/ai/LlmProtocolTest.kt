package com.RooMCutNext.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.6.6 画面感知：OpenAI 兼容 vision 线格式测试 —— 带图消息的
 * content 必须是部件数组（text + image_url data URI），不带图的
 * 消息保持纯字符串（兼容全部纯文本端点）。
 *
 * v1.6.9 整段视频：视频部件两种方言（input_video = Qwen 系 /
 * video_url = GLM·MiniMax 系）+ 混合帧/视频顺序 + 原生视频模型启发式。
 */
class LlmProtocolTest {

    @Test
    fun visionMessageBuildsContentArray() {
        val msg = WireMessage("user", "看图", images = listOf("QUJD", "WFla"))
        val json = msg.toJson()

        val content = json["content"]!!.jsonArray
        assertEquals(3, content.size)

        val textPart = content[0].jsonObject
        assertEquals("text", textPart["type"]!!.jsonPrimitive.content)
        assertEquals("看图", textPart["text"]!!.jsonPrimitive.content)

        content.drop(1).forEach { el ->
            val part = el.jsonObject
            assertEquals("image_url", part["type"]!!.jsonPrimitive.content)
        }
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "data:image/jpeg;base64,WFla",
            content[2].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
        assertEquals("user", json["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun nullContentWithImagesStillHasImageParts() {
        val msg = WireMessage("user", null, images = listOf("QUJD"))
        val content = msg.toJson()["content"] as JsonArray
        assertEquals(1, content.size)
        assertEquals("image_url", content[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun plainMessageKeepsStringContent() {
        val msg = WireMessage("user", "普通消息")
        val json: JsonObject = msg.toJson()
        assertEquals("普通消息", json["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun multimodalModelHeuristic() {
        // 命中（敢发图）。
        listOf(
            "gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini",
            "glm-4v", "glm-4v-plus", "glm-4.5v",
            "moonshot-v1-8k-vision-preview", "qwen-vl-plus", "qvq-72b",
            "gemini-2.0-flash", "llava-1.5-7b",
        ).forEach { m -> assertTrue("应识别为多模态: $m", AiSettings.modelLooksMultimodal(m)) }

        // 不命中（纯文本，正确不发）。
        listOf(
            "glm-4.6", "glm-4.5-air", "glm-4-flash",
            "deepseek-chat", "deepseek-reasoner",
            "kimi-k2-turbo-preview", "kimi-k2-0905-preview", "moonshot-v1-auto",
        ).forEach { m -> assertEquals("应识别为纯文本: $m", false, AiSettings.modelLooksMultimodal(m)) }
    }

    @Test
    fun wantFramesRespectsMode() {
        val gpt = AiSettings(model = "gpt-4o")
        val ds = AiSettings(model = "deepseek-chat")
        assertTrue(gpt.wantFrames(true))
        assertFalse(gpt.wantFrames(false))
        assertFalse(ds.wantFrames(true)) // auto + 纯文本模型 → 不发
        assertTrue(
            AiSettings(model = "deepseek-chat", visionMode = AiSettings.VISION_ON).wantFrames(true),
        )
        assertFalse(AiSettings(model = "gpt-4o", visionMode = AiSettings.VISION_OFF).wantFrames(true))
    }

    // ------------------------------------------------------------------
    // v1.6.9 整段视频（input_video / video_url 双方言 + 混合顺序）
    // ------------------------------------------------------------------

    @Test
    fun videoMessageBuildsInputVideoParts() {
        val msg = WireMessage("user", "看视频", videos = listOf(WireVideo("QUJD")))
        val content = msg.toJson()["content"]!!.jsonArray

        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        val part = content[1].jsonObject
        assertEquals("input_video", part["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:video/mp4;base64,QUJD",
            part["input_video"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun videoMessageBuildsVideoUrlParts() {
        val msg = WireMessage(
            "user",
            videos = listOf(WireVideo("WFla", style = VideoPartStyle.VIDEO_URL)),
        )
        val part = msg.toJson()["content"]!!.jsonArray[0].jsonObject
        assertEquals("video_url", part["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:video/mp4;base64,WFla",
            part["video_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun mixedImagesAndVideosOrderIsTextImagesVideos() {
        val msg = WireMessage(
            "user",
            "混合",
            images = listOf("QQ=="),
            videos = listOf(WireVideo("QUE=", style = VideoPartStyle.VIDEO_URL)),
        )
        val content = msg.toJson()["content"]!!.jsonArray

        assertEquals(3, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("video_url", content[2].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun videoNativeModelHeuristic() {
        // 命中（敢整段直传）。
        listOf(
            "qwen-vl-plus", "qwen2.5-vl-72b-instruct", "qwen3-vl-plus",
            "glm-4v-plus", "glm-4.5v", "minimax-vl-01", "intern-vl-8b",
            "qvq-max", "my-video-model",
        ).forEach { m -> assertTrue("应识别为原生视频: $m", AiSettings.modelLooksVideoNative(m)) }

        // 不命中：多模态但只吃图（gpt-4o），或纯文本。
        listOf(
            "gpt-4o", "gpt-4.1", "gemini-2.0-flash",
            "glm-4.6", "glm-4.5-air", "glm-4-flash",
            "deepseek-chat", "kimi-k2-turbo-preview", "moonshot-v1-8k-vision-preview",
        ).forEach { m -> assertFalse("不应识别为原生视频: $m", AiSettings.modelLooksVideoNative(m)) }
    }

    @Test
    fun wantVideoRespectsStyleAndMode() {
        val qwenVl = AiSettings(model = "qwen3-vl-plus")
        // 默认关键帧档：怎么都不走视频。
        assertFalse(qwenVl.wantVideo(true))

        val video = { m: String, mode: String ->
            AiSettings(
                model = m,
                visionMode = mode,
                visionStyle = AiSettings.VISION_STYLE_VIDEO,
            )
        }
        assertTrue(video("qwen3-vl-plus", AiSettings.VISION_AUTO).wantVideo(true))
        assertTrue(video("deepseek-chat", AiSettings.VISION_ON).wantVideo(true)) // on 无视启发式
        assertFalse(video("gpt-4o", AiSettings.VISION_AUTO).wantVideo(true)) // auto 只吃图 → 回落帧
        assertFalse(video("qwen3-vl-plus", AiSettings.VISION_OFF).wantVideo(true))
        assertFalse(video("qwen3-vl-plus", AiSettings.VISION_AUTO).wantVideo(false)) // 没视频片段
    }

    @Test
    fun effectiveVideoPartStyleMapsSetting() {
        assertEquals(VideoPartStyle.INPUT_VIDEO, AiSettings().effectiveVideoPartStyle)
        assertEquals(
            VideoPartStyle.VIDEO_URL,
            AiSettings(videoPartStyle = AiSettings.VIDEO_PART_VIDEO_URL).effectiveVideoPartStyle,
        )
    }
}
