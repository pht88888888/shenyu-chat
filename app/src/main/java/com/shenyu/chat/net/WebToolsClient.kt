package com.shenyu.chat.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 一次联网工具调用的结果：给模型看的文本 + 给界面看的来源卡片。 */
data class WebToolResult(
    /** 回填给模型的工具结果（JSON 文本），已带原始 URL，便于模型引用。 */
    val content: String,
    /** 搜索结果的结构化卡片；fetch 没有来源列表时为空。 */
    val hits: List<SearchHitDto>,
    /** 参数不合法等「可让模型自我纠正」的错误；网络/HTTP 错误一律抛 [ApiException]。 */
    val isError: Boolean = false,
)

/**
 * 官方联网工具客户端（配合 1M 长文档场景使用）。
 *
 * 为什么单独存在：Messages 请求一旦声明 `deepseek_web_search` / `deepseek_read_url`，
 * 服务端会把该请求的输入卡在 32,768 Token（实测见 qa/context-1m-20261119/）。
 * 为了让长文档既能吃满 1M 上下文、又不失去联网能力，长文档请求改用普通 Messages 通道，
 * 由这里以 [lookup_web] / [read_webpage] 两个客户端工具的形式，单独调用官方工具 API：
 *
 * - `POST {origin}/v1/tools/search`，请求 `{query, count, need_content:false}`，最多取 [MAX_RESULTS] 条；
 * - `POST {origin}/v1/tools/fetch`，请求 `{url}`，正文超过 [MAX_CONTENT_CHARS] 字符时截断并标 `truncated:true`。
 *
 * 错误处理约定（调用方据此决定重试还是回填给模型）：
 * - 参数缺失/超长/URL 不合法 ⇒ 返回 `isError = true` 的结果，模型可以自己改正后重试；
 * - 未知工具名 ⇒ 抛 [ApiException]（PROTOCOL），属于客户端编排错误；
 * - 网络异常/HTTP 非 2xx ⇒ 抛 [ApiException]，保留状态码与官方错误语义；
 * - 协程取消 ⇒ 立即 `Call.cancel()`，取消异常原样向上抛，不会被吞掉。
 *
 * 密钥只作为请求头传入，不写日志、不落盘。
 */
class WebToolsClient(
    private val callFactory: Call.Factory = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build(),
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun execute(origin: String, apiKey: String, name: String, input: JsonObject): WebToolResult =
        when (name) {
            TOOL_LOOKUP_WEB -> search(origin, apiKey, input)
            TOOL_READ_WEBPAGE -> fetch(origin, apiKey, input)
            else -> throw ApiException(ApiException.Kind.PROTOCOL, "未知的联网工具：$name")
        }

    /* ---------------------------------------------------------------- */
    /* 搜索                                                              */
    /* ---------------------------------------------------------------- */

    private suspend fun search(origin: String, apiKey: String, input: JsonObject): WebToolResult {
        val query = input.stringOrNull("query")?.trim().orEmpty()
        if (query.isEmpty()) return argumentError(TOOL_LOOKUP_WEB, "缺少 query 参数")
        if (query.length > MAX_QUERY_CHARS) {
            return argumentError(TOOL_LOOKUP_WEB, "query 超过 $MAX_QUERY_CHARS 个字符，请缩短搜索词")
        }

        val body = buildJsonObject {
            put("query", query)
            put("count", MAX_RESULTS)
            put("need_content", false)
        }
        val response = post(origin, apiKey, PATH_SEARCH, body)
        if (response.code !in 200..299) throw httpError(response)

        val rows = runCatching {
            json.parseToJsonElement(response.body).jsonObject["results"]?.jsonArray.orEmpty()
        }.getOrElse { throw ApiException(ApiException.Kind.PROTOCOL, "联网搜索返回了无法解析的内容") }
            .mapNotNull { it as? JsonObject }
            .take(MAX_RESULTS)

        val hits = rows.map { row ->
            SearchHitDto(
                title = row.stringOrNull("title").orEmpty().ifBlank { row.stringOrNull("url").orEmpty() },
                url = row.stringOrNull("url").orEmpty(),
                snippet = row.stringOrNull("snippet").orEmpty(),
                date = row.stringOrNull("date").orEmpty(),
                siteName = row.stringOrNull("site_name").orEmpty(),
                siteIcon = row.stringOrNull("site_icon").orEmpty(),
            )
        }

        val content = buildJsonObject {
            put("tool", TOOL_LOOKUP_WEB)
            put("query", query)
            put("result_count", hits.size)
            put(
                "results",
                buildJsonArray {
                    rows.forEach { row ->
                        add(
                            buildJsonObject {
                                put("title", row.stringOrNull("title").orEmpty())
                                put("url", row.stringOrNull("url").orEmpty())
                                put("snippet", row.stringOrNull("snippet").orEmpty())
                                put("summary", row.stringOrNull("summary").orEmpty())
                                put("site_name", row.stringOrNull("site_name").orEmpty())
                                put("date", row.stringOrNull("date").orEmpty())
                            }
                        )
                    }
                },
            )
            put("note", "以上为服务端联网搜索结果，引用时请使用 results 里的 url。")
        }
        return WebToolResult(content = content.toString(), hits = hits)
    }

    /* ---------------------------------------------------------------- */
    /* 抓取网页                                                          */
    /* ---------------------------------------------------------------- */

    private suspend fun fetch(origin: String, apiKey: String, input: JsonObject): WebToolResult {
        val rawUrl = input.stringOrNull("url")?.trim().orEmpty()
        if (rawUrl.isEmpty()) return argumentError(TOOL_READ_WEBPAGE, "缺少 url 参数")
        validateUrl(rawUrl)?.let { return argumentError(TOOL_READ_WEBPAGE, it) }

        val response = post(
            origin,
            apiKey,
            PATH_FETCH,
            buildJsonObject { put("url", rawUrl) },
        )
        if (response.code !in 200..299) throw httpError(response)

        val obj = runCatching { json.parseToJsonElement(response.body).jsonObject }
            .getOrElse { throw ApiException(ApiException.Kind.PROTOCOL, "网页抓取返回了无法解析的内容") }
        val title = obj.stringOrNull("title").orEmpty()
        val full = obj.stringOrNull("content").orEmpty()
        val truncated = full.length > MAX_CONTENT_CHARS
        val body = if (truncated) full.take(MAX_CONTENT_CHARS) else full

        val content = buildJsonObject {
            put("tool", TOOL_READ_WEBPAGE)
            put("url", rawUrl)
            put("title", title)
            put("content", body)
            put("truncated", truncated)
            put("content_length", full.length)
            if (truncated) {
                put("note", "正文超过 $MAX_CONTENT_CHARS 字符，已截断；只能基于上文作答，不要臆测后续内容。")
            }
        }
        val hits = if (rawUrl.isBlank()) {
            emptyList()
        } else {
            listOf(SearchHitDto(title = title.ifBlank { rawUrl }, url = rawUrl, snippet = "", date = ""))
        }
        return WebToolResult(content = content.toString(), hits = hits)
    }

    /* ---------------------------------------------------------------- */
    /* HTTP                                                              */
    /* ---------------------------------------------------------------- */

    private suspend fun post(origin: String, apiKey: String, path: String, body: JsonObject): RawResponse {
        val request = Request.Builder()
            .url(origin.trimEnd('/') + path)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        val call = callFactory.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            // 协程被取消时同步取消底层 HTTP 调用，避免继续占用连接与配额
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                ApiException(ApiException.Kind.NETWORK, e.message ?: "网络不可用"),
                            )
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val raw = try {
                            response.use { RawResponse(it.code, it.body?.string().orEmpty()) }
                        } catch (e: IOException) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    ApiException(ApiException.Kind.NETWORK, e.message ?: "读取响应失败"),
                                )
                            }
                            return
                        }
                        if (continuation.isActive) continuation.resume(raw)
                    }
                },
            )
        }
    }

    /** 官方错误语义与其他接口保持一致，便于界面统一提示。 */
    private fun httpError(response: RawResponse): ApiException {
        val message = runCatching {
            json.parseToJsonElement(response.body).jsonObject["error"]?.jsonObject
                ?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: response.body.take(200).ifBlank { "HTTP ${response.code}" }
        return when (response.code) {
            401, 403 -> ApiException(
                ApiException.Kind.AUTH,
                "联网工具鉴权失败（${response.code}）：$message",
                response.code,
            )

            402 -> ApiException(ApiException.Kind.QUOTA, "账户余额不足（402）：$message", response.code)
            429 -> ApiException(
                ApiException.Kind.HTTP,
                "联网工具请求太频繁（429），稍后再试",
                response.code,
            )

            else -> ApiException(
                ApiException.Kind.HTTP,
                "联网工具请求失败（${response.code}）：$message",
                response.code,
            )
        }
    }

    /* ---------------------------------------------------------------- */
    /* 参数校验与工具                                                     */
    /* ---------------------------------------------------------------- */

    /** 返回 null 表示可以发请求；否则是给模型看的错误说明。 */
    internal fun validateUrl(raw: String): String? {
        if (raw.length > MAX_URL_CHARS) return "url 超过 $MAX_URL_CHARS 个字符"
        val uri = runCatching { raw.toHttpUrlOrNull() }.getOrNull()
            ?: return "url 不是合法的 HTTP/HTTPS 地址"
        if (uri.scheme != "http" && uri.scheme != "https") return "url 只支持 http/https"
        if (uri.host.isBlank()) return "url 缺少主机名"
        if (uri.username.isNotEmpty() || uri.password.isNotEmpty()) return "url 不能携带用户名或密码"
        return null
    }

    private fun argumentError(tool: String, reason: String): WebToolResult = WebToolResult(
        content = buildJsonObject {
            put("tool", tool)
            put("error", reason)
            put("note", "参数不合法，本次没有发起请求；请修正参数后重试。")
        }.toString(),
        hits = emptyList(),
        isError = true,
    )

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private data class RawResponse(val code: Int, val body: String)

    companion object {
        const val TOOL_LOOKUP_WEB = "lookup_web"
        const val TOOL_READ_WEBPAGE = "read_webpage"

        /** 一次搜索最多带回给模型的条数（官方 count 上限 50，这里按上下文预算收敛）。 */
        const val MAX_RESULTS = 6

        /** 单次抓取只把前 6 万字符交给模型，并显式标注 truncated。 */
        const val MAX_CONTENT_CHARS = 60_000

        const val MAX_QUERY_CHARS = 128
        const val MAX_URL_CHARS = 8_192

        internal const val PATH_SEARCH = "/v1/tools/search"
        internal const val PATH_FETCH = "/v1/tools/fetch"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * 这两个客户端工具的定义，供长文档通道声明给模型。
         * 声明名称必须与这里的常量一致，否则 [execute] 会以 PROTOCOL 异常拒绝。
         */
        fun toolDefinitions(): JsonArray = buildJsonArray {
            add(
                buildJsonObject {
                    put("name", TOOL_LOOKUP_WEB)
                    put(
                        "description",
                        "联网搜索网页。返回带 url 的标题与摘要，需要正文时再用 read_webpage 打开具体链接。",
                    )
                    put(
                        "input_schema",
                        buildJsonObject {
                            put("type", "object")
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "query",
                                        buildJsonObject {
                                            put("type", "string")
                                            put("description", "搜索词，1～$MAX_QUERY_CHARS 个字符，支持 site: 等语法")
                                        },
                                    )
                                },
                            )
                            put("required", buildJsonArray { add("query") })
                        },
                    )
                },
            )
            add(
                buildJsonObject {
                    put("name", TOOL_READ_WEBPAGE)
                    put(
                        "description",
                        "抓取一个网页并返回 Markdown 正文；正文过长时会在 $MAX_CONTENT_CHARS 字符处截断并标注 truncated。",
                    )
                    put(
                        "input_schema",
                        buildJsonObject {
                            put("type", "object")
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "url",
                                        buildJsonObject {
                                            put("type", "string")
                                            put("description", "要抓取的 http/https 网页地址，不能带用户名密码")
                                        },
                                    )
                                },
                            )
                            put("required", buildJsonArray { add("url") })
                        },
                    )
                },
            )
        }
    }
}
