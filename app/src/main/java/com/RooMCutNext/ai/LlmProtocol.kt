package com.RooMCutNext.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * AI 剪辑（v1.6.0）— OpenAI 兼容 chat/completions 线协议。
 *
 * 手写 JSON（JsonObject）而非 @Serializable DTO：tool_calls / content 可空
 * 且各家字段有差异，动态构建最稳。协议面刻意保持最小：
 * messages(system/user/assistant/tool) + tools + tool_choice=auto。
 *
 * v1.6.9：user 消息支持视频部件（[WireVideo]），把整段素材压成小代理
 * 直传给原生视频理解模型 —— 对齐用户在电脑 web/编码 Agent 里“附文件”
 * 的体验；帧与视频可混合在同一条消息里。
 */

/** 一次模型发起的工具调用。 */
data class LlmToolCall(
    val id: String,
    val name: String,
    /** 已解析为 JSON 对象的参数（空对象容错）。 */
    val arguments: JsonObject,
)

/** 一轮模型回复：文本与工具调用至少有其一。 */
data class LlmReply(
    val content: String?,
    val toolCalls: List<LlmToolCall>,
)

/**
 * v1.6.9 整段视频：视频部件的线上方言（各家 OpenAI 兼容端点不一致）。
 *
 * - [INPUT_VIDEO]：Qwen 系（DashScope 兼容模式 / vLLM / Ollama 网关等），
 *   content part type = "input_video"，视频放在 input_video.url；
 * - [VIDEO_URL]：GLM-4V-Plus / MiniMax-VL 系，content part type =
 *   "video_url"，视频放在 video_url.url。
 *
 * 两种都支持 data:video/mp4;base64 内联；选错方言会被端点拒，
 * 控制器捕获后自动降级关键帧重试（用户也可在设置里直接换）。
 */
enum class VideoPartStyle(val wireType: String) {
    INPUT_VIDEO("input_video"),
    VIDEO_URL("video_url"),
}

/** v1.6.9 整段视频：一条内联视频部件（本地已压小的 480p 代理）。 */
data class WireVideo(
    /** base64 MP4（无 data: 前缀）。 */
    val b64Mp4: String,
    val mime: String = "video/mp4",
    val style: VideoPartStyle = VideoPartStyle.INPUT_VIDEO,
)

/** 线上消息（含 role=tool 的结果回传）。 */
data class WireMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
    /** v1.6.6 画面感知：base64 JPEG（无 data: 前缀）。仅 user 消息使用。 */
    val images: List<String> = emptyList(),
    /** v1.6.9 整段视频：base64 MP4 代理。仅 user 消息使用。 */
    val videos: List<WireVideo> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("role", role)
        if (images.isNotEmpty() || videos.isNotEmpty()) {
            // OpenAI 兼容多模态格式：content = 部件数组，顺序 text → 图 → 视频。
            put("content", JsonArray(buildList {
                if (!content.isNullOrBlank()) {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", content)
                    })
                }
                images.forEach { b64 ->
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", "data:image/jpeg;base64,$b64")
                        })
                    })
                }
                videos.forEach { v ->
                    add(buildJsonObject {
                        put("type", v.style.wireType)
                        put(v.style.wireType, buildJsonObject {
                            put("url", "data:${v.mime};base64,${v.b64Mp4}")
                        })
                    })
                }
            }))
        } else {
            if (content != null) put("content", content) else put("content", JsonPrimitive(null as String?))
        }
        if (toolCalls.isNotEmpty()) {
            put("tool_calls", JsonArray(toolCalls.map { call ->
                buildJsonObject {
                    put("id", call.id)
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", call.name)
                        put("arguments", Json.encodeToString(JsonObject.serializer(), call.arguments))
                    })
                }
            }))
        }
        if (toolCallId != null) put("tool_call_id", toolCallId)
        if (name != null) put("name", name)
    }
}

/** 可注入的传输层（测试用假实现替换网络）。 */
interface LlmTransport {
    /**
     * @throws LlmException 网络 / HTTP / 协议错误（消息面向用户可读）
     */
    suspend fun chatCompletion(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<WireMessage>,
        tools: JsonArray,
    ): LlmReply
}

class LlmException(message: String) : Exception(message)

/** OkHttp 实现：一次完整的非流式补全。 */
class HttpLlmTransport(
    /** 只为超时配置注入；默认 20s 连接 / 180s 读（带工具的长推理普遍偏慢）。 */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) : LlmTransport {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    override suspend fun chatCompletion(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<WireMessage>,
        tools: JsonArray,
    ): LlmReply = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val body = buildJsonObject {
            put("model", model)
            put("messages", JsonArray(messages.map { it.toJson() }))
            put("tools", tools)
            put("tool_choice", "auto")
            put("temperature", 0.3)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw LlmException(e.javaClass.simpleName + ": " + (e.message?.take(120) ?: "network error"))
        }
        response.use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val apiMsg = runCatching {
                    json.parseToJsonElement(text).jsonObject["error"]?.jsonObject
                        ?.get("message")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                throw LlmException(
                    "HTTP ${resp.code}" + (apiMsg?.let { ": $it" } ?: text.take(120).ifBlank { "" }),
                )
            }
            parseReply(text)
        }
    }

    private fun parseReply(text: String): LlmReply {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw LlmException("响应不是合法 JSON: " + text.take(80))
        }
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw LlmException("响应缺少 choices")
        val message = choice["message"]?.jsonObject
            ?: throw LlmException("响应缺少 message")

        val content = message["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val calls = message["tool_calls"]?.jsonArray?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val fn = obj["function"]?.jsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
            val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val rawArgs = fn["arguments"]?.jsonPrimitive?.contentOrNull
            val args = if (rawArgs.isNullOrBlank()) {
                JsonObject(emptyMap())
            } else {
                runCatching { json.parseToJsonElement(rawArgs).jsonObject }
                    .getOrElse { JsonObject(emptyMap()) }
            }
            LlmToolCall(id = id, name = name, arguments = args)
        } ?: emptyList()

        return LlmReply(content = content, toolCalls = calls)
    }
}
