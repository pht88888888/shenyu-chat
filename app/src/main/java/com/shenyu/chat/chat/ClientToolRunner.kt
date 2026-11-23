package com.shenyu.chat.chat

import com.shenyu.chat.net.ApiException
import com.shenyu.chat.net.StreamEvent
import com.shenyu.chat.net.WebToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Full-context client-tool loop. File references and signed thinking blocks are kept in every step. */
internal class ClientToolRunner(
    private val model: (JsonObject) -> Flow<StreamEvent>,
    private val execute: suspend (String, JsonObject) -> WebToolResult,
) {
    suspend fun run(initial: JsonObject, onEvent: (StreamEvent) -> Unit) {
        var body = initial
        var messages = initial["messages"] as JsonArray
        var blockOffset = 0
        var callsSoFar = 0
        val allowedTools = (initial["tools"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
        }.toSet()
        // Leave one final model step without tools to summarize already gathered results.
        repeat(MAX_TOOL_ROUNDS + 1) { round ->
            var content: JsonArray? = null
            var stopReason: String? = null
            model(body).collect { event ->
                when (event) {
                    is StreamEvent.AssistantContent -> content = event.content
                    is StreamEvent.Finished -> {
                        stopReason = event.stopReason
                        onEvent(event)
                    }
                    else -> onEvent(event.shift(blockOffset))
                }
            }
            if (stopReason != "tool_use") return
            val response = content ?: throw ApiException(ApiException.Kind.PROTOCOL, "工具响应不完整，请重试")
            val calls = response.withIndex().filter { (_, value) ->
                (value as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "tool_use"
            }
            if (calls.isEmpty() || round == MAX_TOOL_ROUNDS) {
                throw ApiException(ApiException.Kind.PROTOCOL, "本轮搜索步骤已达上限，请缩小问题范围后重试")
            }
            messages = JsonArray(messages + buildJsonObject {
                put("role", "assistant")
                put("content", response)
            })
            val results = calls.map { (index, value) ->
                val call = value as JsonObject
                val id = call["id"]?.jsonPrimitive?.contentOrNull
                    ?: throw ApiException(ApiException.Kind.PROTOCOL, "工具调用缺少标识，请重试")
                val name = call["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val input = call["input"] as? JsonObject ?: JsonObject(emptyMap())
                val kind = if (name == "read_webpage") "read_link" else "search"
                val query = input["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val url = input["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val uiIndex = index + blockOffset
                onEvent(StreamEvent.SearchToolUse(uiIndex, kind, listOfNotNull(query.ifBlank { null }), url))
                val result = if (name !in allowedTools) {
                    WebToolResult("该工具未开启，不能调用。请依据用户已开启的能力回答。", emptyList(), true)
                } else if (++callsSoFar > MAX_TOOL_CALLS) {
                    WebToolResult("本轮搜索次数已达上限，请基于已有来源回答并说明检索范围。", emptyList(), true)
                } else {
                    try {
                        execute(name, input)
                    } catch (e: ApiException) {
                        if (e.kind == ApiException.Kind.AUTH || e.kind == ApiException.Kind.QUOTA) {
                            onEvent(StreamEvent.SearchFailed(uiIndex, kind, "unavailable"))
                            throw e
                        }
                        WebToolResult("工具调用失败：${e.message}。不得声称此次搜索成功。", emptyList(), true)
                    }
                }
                if (result.isError) onEvent(StreamEvent.SearchFailed(uiIndex, kind, "unavailable"))
                else onEvent(StreamEvent.SearchResults(uiIndex, kind, result.hits))
                buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", id)
                    put("content", result.content)
                    if (result.isError) put("is_error", true)
                }
            }
            messages = JsonArray(messages + buildJsonObject {
                put("role", "user")
                put("content", JsonArray(results))
            })
            blockOffset += response.size
            body = JsonObject(body + ("messages" to messages))
            if (round == MAX_TOOL_ROUNDS - 1 || callsSoFar >= MAX_TOOL_CALLS) {
                body = JsonObject(body - "tools")
            }
        }
    }

    private fun StreamEvent.shift(offset: Int): StreamEvent = when (this) {
        is StreamEvent.TextStart -> copy(index = index + offset)
        is StreamEvent.TextDelta -> copy(index = index + offset)
        is StreamEvent.ThinkingStart -> copy(index = index + offset)
        is StreamEvent.ThinkingDelta -> copy(index = index + offset)
        is StreamEvent.SearchToolUse -> copy(index = index + offset)
        is StreamEvent.SearchResults -> copy(index = index + offset)
        is StreamEvent.SearchFailed -> copy(index = index + offset)
        is StreamEvent.CitationDelta -> copy(index = index + offset)
        else -> this
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 6
        const val MAX_TOOL_CALLS = 12
    }
}
