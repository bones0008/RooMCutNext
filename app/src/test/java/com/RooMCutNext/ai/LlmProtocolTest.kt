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
}
