package com.shenyu.chat.net

import com.shenyu.chat.data.ProviderConfig
import com.shenyu.chat.data.ProviderProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.source
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** 一次流式响应里可能出现的事件，语义对齐服务端 anthropic 兼容协议。 */
sealed interface StreamEvent {
    data class Started(val messageId: String, val model: String) : StreamEvent
    data class TextStart(val index: Int, val text: String) : StreamEvent
    data class ThinkingStart(val index: Int, val text: String) : StreamEvent
    data class SearchToolUse(val index: Int, val kind: String, val queries: List<String>, val url: String) : StreamEvent
    data class SearchResults(val index: Int, val kind: String, val hits: List<SearchHitDto>) : StreamEvent
    data class SearchFailed(val index: Int, val kind: String, val errorCode: String) : StreamEvent
    data class TextDelta(val index: Int, val text: String) : StreamEvent
    data class ThinkingDelta(val index: Int, val text: String) : StreamEvent
    data class CitationDelta(val index: Int, val title: String, val url: String) : StreamEvent
    data class Finished(val stopReason: String?, val title: String?) : StreamEvent
    /** Exact content blocks for the next step of a client-tool round, including signatures. */
    data class AssistantContent(val content: JsonArray) : StreamEvent
    data object Stopped : StreamEvent
}

data class UploadedFile(val id: String, val name: String, val mime: String)

data class SearchHitDto(
    val title: String,
    val url: String,
    val snippet: String,
    val date: String,
    val siteName: String = "",
    val siteIcon: String = "",
)

/** 请求失败：message 已是可直接展示的中文/服务端原文。 */
class ApiException(
    val kind: Kind,
    override val message: String,
    val status: Int? = null,
) : Exception(message) {
    enum class Kind { HTTP, NETWORK, PROTOCOL, INTERRUPTED, AUTH, QUOTA }
}

/**
 * 对话客户端：按供应商协议分别走 Anthropic Messages（DeepSeek 应用通道 / 标准 Anthropic）
 * 或 OpenAI Chat Completions。两条线都把增量翻译成同一套 [StreamEvent]。
 */
class AnthropicClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 用 GET /models 校验 Key，不产生费用。 */
    suspend fun verifyKey(provider: ProviderConfig, apiKey: String): Result<Unit> {
        val modelsUrl = ProviderEndpoints.modelsUrl(provider)
        if (modelsUrl.isBlank()) {
            return Result.failure(
                ApiException(ApiException.Kind.PROTOCOL, "请先在供应商里填写接口地址"),
            )
        }
        return try {
            val builder = Request.Builder().url(modelsUrl)
            if (provider.protocol == ProviderProtocol.DEEPSEEK) {
                builder.addHeader("Authorization", "Bearer $apiKey")
            }
            if (provider.protocol.usesAnthropicWire) {
                builder.addHeader("x-api-key", apiKey)
                builder.addHeader("anthropic-version", ANTHROPIC_VERSION)
            } else {
                builder.addHeader("Authorization", "Bearer $apiKey")
            }
            http.newCall(builder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else if (response.code == 404 || response.code == 405) {
                    Result.failure(
                        ApiException(
                            ApiException.Kind.PROTOCOL,
                            "接口地址可以连通，但该服务没有 /models 校验接口；可直接保存后发一条消息测试。",
                            response.code,
                        ),
                    )
                } else {
                    Result.failure(toException(response))
                }
            }
        } catch (e: IOException) {
            Result.failure(ApiException(ApiException.Kind.NETWORK, e.message ?: "网络不可用"))
        } catch (e: IllegalArgumentException) {
            Result.failure(ApiException(ApiException.Kind.PROTOCOL, "接口地址格式不对：${e.message ?: ""}"))
        }
    }

    /**
     * 发一次流式请求。body 由调用方组装（模型、system、messages、thinking、tools）。
     * 出错时以 ApiException 抛出；正常结束时发送 Stopped 后关闭。
     */
    fun stream(provider: ProviderConfig, body: JsonObject, hasFile: Boolean): Flow<StreamEvent> =
        if (provider.protocol == ProviderProtocol.OPENAI) {
            streamOpenAi(provider, body)
        } else {
            streamAnthropic(provider, body, hasFile)
        }

    /** Anthropic / DeepSeek 应用通道：SSE 事件名 + data，以 message_stop 结束。 */
    private fun streamAnthropic(
        provider: ProviderConfig,
        body: JsonObject,
        hasFile: Boolean,
    ): Flow<StreamEvent> = callbackFlow {
        var call: Call? = null
        val worker = launch(Dispatchers.IO) {
            try {
                val requestBuilder = Request.Builder()
                    .url(ProviderEndpoints.messagesUrl(provider))
                    .addHeader("x-api-key", provider.apiKey)
                    .addHeader("anthropic-version", ANTHROPIC_VERSION)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                if (hasFile) {
                    requestBuilder.addHeader("anthropic-beta", "files-api-2025-04-14")
                }
                val payload = JsonObject(body + ("stream" to kotlinx.serialization.json.JsonPrimitive(true)))
                requestBuilder.post(payload.toString().toRequestBody(JSON))

                val c = http.newCall(requestBuilder.build())
                call = c
                c.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw toException(response)
                    }
                    val contentType = response.header("Content-Type") ?: ""
                    if (!contentType.contains("text/event-stream")) {
                        throw ApiException(ApiException.Kind.PROTOCOL, "服务端没有返回流式响应")
                    }
                    val source = response.body?.source()
                        ?: throw ApiException(ApiException.Kind.PROTOCOL, "响应为空")
                    var eventName: String? = null
                    val data = StringBuilder()
                    val content = AssistantContentAccumulator()
                    var done = false
                    while (!done) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> {
                                if (data.isNotEmpty()) {
                                    val name = eventName
                                    val raw = data.toString()
                                    eventName = null
                                    data.setLength(0)
                                    content.accept(name, raw)
                                    for (event in emitEvent(name, raw)) send(event)
                                    if (name == "message_stop") {
                                        send(StreamEvent.AssistantContent(content.snapshot()))
                                        done = true
                                    }
                                }
                            }

                            line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.removePrefix("data:").trim())
                            }

                            line.startsWith(":") -> Unit
                            else -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line)
                            }
                        }
                    }
                    if (!done) throw ApiException(ApiException.Kind.INTERRUPTED, "连接中断，请重试")
                }
                send(StreamEvent.Stopped)
                close()
            } catch (e: ApiException) {
                close(e)
            } catch (e: IOException) {
                close(ApiException(ApiException.Kind.NETWORK, e.message ?: "连接中断"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                close(ApiException(ApiException.Kind.PROTOCOL, e.message ?: "响应解析失败"))
            }
        }
        awaitClose {
            call?.cancel()
            worker.cancel()
        }
    }

    /** OpenAI Chat Completions 通道：只有 data 行，以 [DONE] 或 finish_reason 结束。 */
    private fun streamOpenAi(provider: ProviderConfig, body: JsonObject): Flow<StreamEvent> = callbackFlow {
        var call: Call? = null
        val worker = launch(Dispatchers.IO) {
            try {
                val payload = OpenAiAdapter.request(provider, body)
                val request = Request.Builder()
                    .url(ProviderEndpoints.messagesUrl(provider))
                    .addHeader("Authorization", "Bearer ${provider.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "text/event-stream")
                    .post(payload.toString().toRequestBody(JSON))
                    .build()
                val c = http.newCall(request)
                call = c
                c.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw toException(response)
                    }
                    val contentType = response.header("Content-Type") ?: ""
                    if (contentType.contains("application/json") && !contentType.contains("event-stream")) {
                        throw ApiException(
                            ApiException.Kind.PROTOCOL,
                            errorMessageFrom(response.body?.string().orEmpty()),
                        )
                    }
                    val source = response.body?.source()
                        ?: throw ApiException(ApiException.Kind.PROTOCOL, "响应为空")
                    val data = StringBuilder()
                    val parser = OpenAiStreamParser()
                    var done = false
                    var finished = false
                    suspend fun flush() {
                        if (data.isEmpty()) return
                        val raw = data.toString()
                        data.setLength(0)
                        if (raw.trim() == "[DONE]") {
                            done = true
                            return
                        }
                        val events = parser.parse(raw)
                        if (events.any { it is StreamEvent.Finished }) finished = true
                        for (event in events) send(event)
                    }
                    while (!done) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.isBlank() -> flush()
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.removePrefix("data:").trim())
                            }

                            line.startsWith(":") || line.startsWith("event:") -> Unit
                            // 少数网关省略 data: 前缀，按原始 JSON 处理。
                            else -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line)
                            }
                        }
                    }
                    flush()
                    if (!done && !finished) {
                        throw ApiException(ApiException.Kind.INTERRUPTED, "连接中断，请重试")
                    }
                }
                send(StreamEvent.Stopped)
                close()
            } catch (e: ApiException) {
                close(e)
            } catch (e: IOException) {
                close(ApiException(ApiException.Kind.NETWORK, e.message ?: "连接中断"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                close(ApiException(ApiException.Kind.PROTOCOL, e.message ?: "响应解析失败"))
            }
        }
        awaitClose {
            call?.cancel()
            worker.cancel()
        }
    }

    /** 非 2xx 且不是 SSE 时，尽量从 OpenAI 错误体里取出可读原因。 */
    private fun errorMessageFrom(raw: String): String = runCatching {
        val error = json.parseToJsonElement(raw).jsonObject["error"]
        when (error) {
            is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
            is kotlinx.serialization.json.JsonPrimitive -> error.contentOrNull
            else -> null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: raw.take(300).ifBlank { "服务端返回了错误响应" }

    private fun emitEvent(eventName: String?, raw: String): List<StreamEvent> {
        if (raw.isBlank()) return emptyList()
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        // 只含 error 的帧按错误处理
        if (eventName == "error" || (obj.containsKey("error") && !obj.containsKey("type"))) {
            val message = obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                ?: raw
            throw ApiException(ApiException.Kind.PROTOCOL, message)
        }
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: eventName ?: return emptyList()
        val result = mutableListOf<StreamEvent>()
        when (type) {
            "message_start" -> {
                val message = obj["message"]?.jsonObject
                val id = message?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
                val model = message?.get("model")?.jsonPrimitive?.contentOrNull.orEmpty()
                result += StreamEvent.Started(id, model)
            }

            "content_block_start" -> {
                val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
                val block = obj["content_block"]?.jsonObject ?: return result
                when (block["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> result += StreamEvent.TextStart(
                        index,
                        block["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )

                    "thinking" -> result += StreamEvent.ThinkingStart(
                        index,
                        block["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )

                    "tool_use" -> result += StreamEvent.SearchToolUse(
                        index = index,
                        kind = if (block["name"]?.jsonPrimitive?.contentOrNull == "read_webpage") "read_link" else "search",
                        queries = emptyList(),
                        url = "",
                    )

                    "server_tool_use" -> {
                        val kind = block["kind"]?.jsonPrimitive?.contentOrNull ?: "search"
                        val input = block["input"]?.jsonObject
                        val queries = input?.get("queries")?.let { q ->
                            runCatching { q.jsonArray.map { it.jsonPrimitive.contentOrNull.orEmpty() } }
                                .getOrDefault(emptyList())
                        } ?: listOfNotNull(input?.get("query")?.jsonPrimitive?.contentOrNull)
                        val url = input?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty()
                        result += StreamEvent.SearchToolUse(index, kind, queries, url)
                    }

                    "web_search_tool_result" -> {
                        val kind = block["kind"]?.jsonPrimitive?.contentOrNull ?: "search"
                        val content = block["content"] as? JsonArray ?: JsonArray(emptyList())
                        var errorCode: String? = null
                        val hits = mutableListOf<SearchHitDto>()
                        content.forEach { item ->
                            val o = item.jsonObject
                            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                                "web_search_result" -> hits += SearchHitDto(
                                    title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    url = o["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    snippet = o["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    date = o["page_age"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    siteName = o["site_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    siteIcon = o["site_icon"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                )

                                "web_search_tool_result_error" -> errorCode =
                                    o["error_code"]?.jsonPrimitive?.contentOrNull ?: "unavailable"
                            }
                        }
                        if (errorCode != null) {
                            result += StreamEvent.SearchFailed(index, kind, errorCode)
                        } else {
                            result += StreamEvent.SearchResults(index, kind, hits)
                        }
                    }

                    "web_fetch_tool_result" -> {
                        val kind = block["kind"]?.jsonPrimitive?.contentOrNull ?: "read_link"
                        val content = block["content"]?.jsonObject
                        val fetch = content?.get("url")?.jsonPrimitive?.contentOrNull
                        if (fetch != null) {
                            val title = content["content"]?.jsonObject
                                ?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty()
                            val date = content["published_at"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            result += StreamEvent.SearchResults(
                                index,
                                kind,
                                listOf(SearchHitDto(title.ifBlank { fetch }, fetch, "", date)),
                            )
                        } else {
                            result += StreamEvent.SearchFailed(index, kind, "unavailable")
                        }
                    }
                }
            }

            "content_block_delta" -> {
                val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
                val delta = obj["delta"]?.jsonObject ?: return result
                when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                    "text_delta" -> result += StreamEvent.TextDelta(
                        index,
                        delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )

                    "thinking_delta" -> result += StreamEvent.ThinkingDelta(
                        index,
                        delta["thinking"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )

                    "citations_delta" -> {
                        val citation = delta["citation"]?.jsonObject
                        val url = citation?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (url.isNotBlank()) {
                            result += StreamEvent.CitationDelta(
                                index,
                                citation?.get("title")?.jsonPrimitive?.contentOrNull.orEmpty(),
                                url,
                            )
                        }
                    }
                }
            }

            "message_delta" -> {
                val delta = obj["delta"]?.jsonObject
                val stop = delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                val title = delta?.get("title")?.jsonPrimitive?.contentOrNull
                result += StreamEvent.Finished(stop, title)
            }
        }
        return result
    }

    /**
     * POST {origin}/anthropic/v1/files 上传附件，返回 file_id。
     * 图片不传 purpose；PDF/Word/PPT 需要 rich_layout_recognition，其余用 user_data。
     * 仅 DeepSeek 应用通道支持（Files API 与后续消息块都由该协议定义）。
     */
    suspend fun uploadFile(
        provider: ProviderConfig,
        name: String,
        mime: String,
        input: InputStream,
        length: Long,
    ): UploadedFile {
        if (!provider.supportsFiles) {
            throw ApiException(
                ApiException.Kind.PROTOCOL,
                "当前供应商不支持附件上传，请切换到 DeepSeek 官方或支持 Files API 的供应商。",
            )
        }
        val purpose = when {
            mime.startsWith("image/") -> null
            mime == "application/pdf" -> "rich_layout_recognition"
            mime.contains("word") || mime.contains("presentation") -> "rich_layout_recognition"
            else -> "user_data"
        }
        val fileBody = object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: okio.BufferedSink) {
                input.use { stream -> sink.writeAll(stream.source()) }
            }
        }
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        if (purpose != null) builder.addFormDataPart("purpose", purpose)
        builder.addFormDataPart("file", name, fileBody)
        val request = Request.Builder()
            .url(ProviderEndpoints.filesUrl(provider))
            .addHeader("x-api-key", provider.apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("anthropic-beta", "files-api-2025-04-14")
            .post(builder.build())
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw toException(response)
                val text = response.body?.string().orEmpty()
                val obj = json.parseToJsonElement(text).jsonObject
                UploadedFile(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: error("上传响应缺少 id"),
                    name = obj["filename"]?.jsonPrimitive?.contentOrNull ?: name,
                    mime = obj["mime_type"]?.jsonPrimitive?.contentOrNull ?: mime,
                )
            }
        } catch (e: IOException) {
            throw ApiException(ApiException.Kind.NETWORK, e.message ?: "附件上传失败")
        }
    }

    private fun toException(response: Response): ApiException {
        val status = response.code
        val text = runCatching { response.body?.string() }.getOrNull().orEmpty()
        val message = runCatching {
            val error = json.parseToJsonElement(text).jsonObject["error"]?.jsonObject
            error?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: text.take(200).ifBlank { response.message }
        return when (status) {
            401, 403 -> ApiException(ApiException.Kind.AUTH, "API Key 无效或已失效（$status）：$message", status)
            402 -> ApiException(ApiException.Kind.QUOTA, "账户余额不足（402）：$message", status)
            429 -> ApiException(ApiException.Kind.HTTP, "请求太频繁或服务器繁忙（429），稍后再试", status)
            else -> ApiException(ApiException.Kind.HTTP, "请求失败（$status）：$message", status)
        }
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
