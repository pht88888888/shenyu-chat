package com.shenyu.chat.net

import com.shenyu.chat.data.ProviderConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 把内部统一的 Anthropic 形状请求体翻译成 OpenAI Chat Completions 请求体，
 * 并把 OpenAI 的 SSE 增量翻译回统一的 [StreamEvent]。
 *
 * 和 DeepSeek 应用通道相比，OpenAI 通道只保留纯文本对话：
 * - `latest_reminder` 角色转成 system 里的一行「当前信息」；
 * - `thinking`、`deepseek_*` 工具、`title`、`safety_check` 全部丢弃；
 * - 附件（Files API）不在此通道支持，调用方会提前拦截。
 */
object OpenAiAdapter {

    fun request(provider: ProviderConfig, body: JsonObject): JsonObject {
        val system = StringBuilder()
        body["system"]?.let { element ->
            contentText(element).takeIf { it.isNotBlank() }?.let { system.append(it) }
        }

        val chatMessages = buildJsonArray {
            body["messages"]?.jsonArray?.forEach { element ->
                val message = element as? JsonObject ?: return@forEach
                val role = message["role"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val text = contentText(message["content"])
                when (role) {
                    "latest_reminder" -> if (text.isNotBlank()) {
                        if (system.isNotEmpty()) system.append('\n')
                        system.append("当前信息：").append(text)
                    }

                    "user", "assistant" -> add(
                        buildJsonObject {
                            put("role", role)
                            put("content", text)
                        },
                    )

                    "system" -> if (text.isNotBlank()) {
                        if (system.isNotEmpty()) system.append('\n')
                        system.append(text)
                    }
                }
            }
        }

        val withSystem = buildJsonArray {
            if (system.isNotBlank()) {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", system.toString())
                    },
                )
            }
            addAll(chatMessages)
        }

        return buildJsonObject {
            put("model", provider.effectiveModel)
            put("messages", withSystem)
            put("stream", true)
        }
    }

    /** 从 content（字符串或 Anthropic 的内容块数组）里抽出纯文本。 */
    internal fun contentText(element: JsonElement?): String = when (element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonArray -> element.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> obj["text"]?.jsonPrimitive?.contentOrNull
                "image" -> "[图片]"
                "document" -> "[文档]"
                else -> null
            }
        }.filter { it.isNotBlank() }.joinToString("\n")

        else -> ""
    }
}

/**
 * 有状态的 SSE 解析器：OpenAI 的增量没有块序号，文本块与思考块的位置由这里补齐。
 * 一个流复用一个实例；[parse] 返回本次增量对应的事件列表。
 */
class OpenAiStreamParser {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    private var started = false
    private var modelName = ""
    private var thinkingStarted = false
    private var textStarted = false
    private var textIndex = 0
    private var finished = false

    fun parse(raw: String): List<StreamEvent> {
        val payload = raw.trim()
        if (payload.isEmpty() || payload == "[DONE]") return emptyList()
        val obj = runCatching { json.parseToJsonElement(payload).jsonObject }
            .getOrElse { throw ApiException(ApiException.Kind.PROTOCOL, "服务端返回了无法解析的流式数据") }

        obj["error"]?.let { error ->
            throw ApiException(ApiException.Kind.PROTOCOL, errorMessage(error, payload))
        }

        val choice = obj["choices"]?.jsonArray?.firstOrNull() as? JsonObject ?: return emptyList()
        val events = mutableListOf<StreamEvent>()
        if (!started) {
            started = true
            modelName = obj["model"]?.jsonPrimitive?.contentOrNull.orEmpty()
            events += StreamEvent.Started("", modelName)
        }

        val delta = choice["delta"] as? JsonObject
        val reasoning = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
            ?: delta?.get("reasoning")?.jsonPrimitive?.contentOrNull
        if (!reasoning.isNullOrEmpty() && !textStarted) {
            if (!thinkingStarted) {
                thinkingStarted = true
                events += StreamEvent.ThinkingStart(0, "")
            }
            events += StreamEvent.ThinkingDelta(0, reasoning)
        }

        val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
        if (!content.isNullOrEmpty()) {
            if (!textStarted) {
                textStarted = true
                textIndex = if (thinkingStarted) 1 else 0
                events += StreamEvent.TextStart(textIndex, "")
            }
            events += StreamEvent.TextDelta(textIndex, content)
        }

        val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        if (!finish.isNullOrEmpty() && !finished) {
            finished = true
            events += StreamEvent.Finished(finish, null)
        }
        return events
    }

    private fun errorMessage(error: JsonElement, raw: String): String = when (error) {
        is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
            ?: error.toString()

        is JsonPrimitive -> error.contentOrNull ?: raw
        else -> raw
    }
}
