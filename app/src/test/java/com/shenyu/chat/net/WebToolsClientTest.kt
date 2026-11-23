package com.shenyu.chat.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 联网工具的请求形态、结果整形、错误语义与取消行为。
 * 全程使用假的 [Call.Factory]，不联网、不读取任何凭据。
 */
class WebToolsClientTest {

    private val origin = "https://api.deepseek.com"
    private val key = "test-key-not-real"

    /* ---------------------------------------------------------------- */
    /* 搜索                                                              */
    /* ---------------------------------------------------------------- */

    @Test
    fun `搜索按官方协议发请求并只取六条`() = runBlocking {
        val results = (1..9).joinToString(",") {
            """{"rerank_score":2.0,"title":"结果$it","url":"https://example.com/$it",
               "snippet":"摘要$it","summary":"长摘要$it","content":"",
               "site_name":"站点$it","site_icon":"https://example.com/favicon.ico",
               "date":"2026-06-01T00:00:00+00:00","authority":3}"""
        }
        val factory = FakeCallFactory { request ->
            respond(request, 200, """{"results":[$results]}""")
        }
        val client = WebToolsClient(factory)

        val result = client.execute(origin, key, WebToolsClient.TOOL_LOOKUP_WEB, query("DeepSeek API"))

        val request = factory.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.deepseek.com/v1/tools/search", request.url.toString())
        assertEquals("Bearer $key", request.header("Authorization"))
        val body = Json.parseToJsonElement(request.bodyText()).jsonObject
        assertEquals("DeepSeek API", body["query"]!!.jsonPrimitive.content)
        assertEquals(WebToolsClient.MAX_RESULTS, body["count"]!!.jsonPrimitive.content.toInt())
        assertEquals("false", body["need_content"]!!.jsonPrimitive.content)

        assertFalse(result.isError)
        assertEquals(WebToolsClient.MAX_RESULTS, result.hits.size)
        assertEquals("结果1", result.hits.first().title)
        assertEquals("站点1", result.hits.first().siteName)

        val payload = Json.parseToJsonElement(result.content).jsonObject
        assertEquals(WebToolsClient.TOOL_LOOKUP_WEB, payload["tool"]!!.jsonPrimitive.content)
        assertEquals(6, payload["results"]!!.jsonArray.size)
        assertEquals(
            "https://example.com/1",
            payload["results"]!!.jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `搜索词为空或超长不发请求`() = runBlocking {
        val factory = FakeCallFactory { request -> respond(request, 200, """{"results":[]}""") }
        val client = WebToolsClient(factory)

        assertTrue(client.execute(origin, key, WebToolsClient.TOOL_LOOKUP_WEB, query("   ")).isError)
        val tooLong = buildJsonObject { put("query", "词".repeat(WebToolsClient.MAX_QUERY_CHARS + 1)) }
        assertTrue(client.execute(origin, key, WebToolsClient.TOOL_LOOKUP_WEB, tooLong).isError)
        assertTrue("参数不合法时不应发起请求", factory.requests.isEmpty())
    }

    /* ---------------------------------------------------------------- */
    /* 抓取网页                                                          */
    /* ---------------------------------------------------------------- */

    @Test
    fun `抓取网页在6万字符处截断并显式标注`() = runBlocking {
        val body = "字".repeat(WebToolsClient.MAX_CONTENT_CHARS + 25)
        val factory = FakeCallFactory { request ->
            respond(request, 200, """{"title":"长页面","content":"$body"}""")
        }
        val client = WebToolsClient(factory)

        val result = client.execute(
            origin,
            key,
            WebToolsClient.TOOL_READ_WEBPAGE,
            buildJsonObject { put("url", "https://example.com/long") },
        )

        val request = factory.requests.single()
        assertEquals("https://api.deepseek.com/v1/tools/fetch", request.url.toString())
        assertEquals(
            "https://example.com/long",
            Json.parseToJsonElement(request.bodyText()).jsonObject["url"]!!.jsonPrimitive.content,
        )

        val payload = Json.parseToJsonElement(result.content).jsonObject
        assertEquals("true", payload["truncated"]!!.jsonPrimitive.content)
        assertEquals(
            WebToolsClient.MAX_CONTENT_CHARS,
            payload["content"]!!.jsonPrimitive.content.length,
        )
        assertEquals(
            WebToolsClient.MAX_CONTENT_CHARS + 25,
            payload["content_length"]!!.jsonPrimitive.content.toInt(),
        )
        assertEquals("https://example.com/long", payload["url"]!!.jsonPrimitive.content)
        assertNotNull(payload["note"])
    }

    @Test
    fun `短网页不标注截断`() = runBlocking {
        val factory = FakeCallFactory { request -> respond(request, 200, """{"title":"短文","content":"正文"}""") }
        val client = WebToolsClient(factory)

        val result = client.execute(
            origin,
            key,
            WebToolsClient.TOOL_READ_WEBPAGE,
            buildJsonObject { put("url", "https://example.com/ok") },
        )
        val payload = Json.parseToJsonElement(result.content).jsonObject
        assertEquals("false", payload["truncated"]!!.jsonPrimitive.content)
        assertEquals("正文", payload["content"]!!.jsonPrimitive.content)
        assertEquals(1, result.hits.size)
        assertEquals("短文", result.hits.single().title)
    }

    @Test
    fun `非法URL在发送前被拦下`() = runBlocking {
        val factory = FakeCallFactory { request -> respond(request, 200, "{}") }
        val client = WebToolsClient(factory)

        for (bad in listOf("ftp://example.com/a", "example.com/a", "https://user:pw@example.com/a", "file:///tmp/a")) {
            val result = client.execute(
                origin,
                key,
                WebToolsClient.TOOL_READ_WEBPAGE,
                buildJsonObject { put("url", bad) },
            )
            assertTrue("应拒绝 $bad", result.isError)
        }
        assertTrue("非法 URL 不应发起请求", factory.requests.isEmpty())
    }

    /* ---------------------------------------------------------------- */
    /* 错误与取消                                                        */
    /* ---------------------------------------------------------------- */

    @Test
    fun `未知工具名抛协议异常`() = runBlocking {
        val client = WebToolsClient(FakeCallFactory { request -> respond(request, 200, "{}") })
        try {
            client.execute(origin, key, "shell_exec", buildJsonObject { })
            fail("应当抛出 ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiException.Kind.PROTOCOL, e.kind)
        }
    }

    @Test
    fun `鉴权失败保留状态码与官方错误文案`() = runBlocking {
        val factory = FakeCallFactory { request ->
            respond(request, 401, """{"error":{"message":"invalid api key"}}""")
        }
        val client = WebToolsClient(factory)
        try {
            client.execute(origin, key, WebToolsClient.TOOL_LOOKUP_WEB, query("deepseek"))
            fail("401 应当抛出 ApiException")
        } catch (e: ApiException) {
            assertEquals(ApiException.Kind.AUTH, e.kind)
            assertEquals(401, e.status)
            assertTrue(e.message, e.message.contains("invalid api key"))
        }
    }

    @Test
    fun `取消协程会取消底层HTTP调用`() = runBlocking {
        val factory = FakeCallFactory(respond = null)
        val client = WebToolsClient(factory)

        val job = launch(Dispatchers.IO) {
            client.execute(origin, key, WebToolsClient.TOOL_LOOKUP_WEB, query("deepseek"))
        }
        withContext(Dispatchers.Default) {
            var waited = 0
            while (factory.lastCall == null && waited < 200) {
                delay(5)
                waited += 5
            }
        }
        assertNotNull("调用应已创建", factory.lastCall)
        job.cancelAndJoin()
        assertTrue("协程取消后应取消底层 Call", factory.lastCall!!.isCanceled())
        assertTrue(job.isCancelled)
    }

    /* ---------------------------------------------------------------- */
    /* 测试脚手架                                                        */
    /* ---------------------------------------------------------------- */

    private fun query(text: String): JsonObject = buildJsonObject { put("query", text) }

    private fun respond(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()

    private fun Request.bodyText(): String {
        val buffer = okio.Buffer()
        body!!.writeTo(buffer)
        return buffer.readUtf8()
    }

    /** respond 为 null 时模拟挂起的请求（用于取消测试）。 */
    private class FakeCallFactory(private val respond: ((Request) -> Response)?) : Call.Factory {
        val requests = mutableListOf<Request>()
        var lastCall: FakeCall? = null

        override fun newCall(request: Request): Call {
            requests += request
            return FakeCall(request, respond).also { lastCall = it }
        }
    }

    private class FakeCall(
        private val original: Request,
        private val respond: ((Request) -> Response)?,
    ) : Call {
        private var executed = false
        private var canceled = false

        override fun request(): Request = original

        override fun execute(): Response = throw UnsupportedOperationException("只走异步路径")

        override fun enqueue(responseCallback: Callback) {
            executed = true
            val make = respond ?: return
            try {
                responseCallback.onResponse(this, make(original))
            } catch (e: Throwable) {
                responseCallback.onFailure(this, java.io.IOException(e))
            }
        }

        override fun cancel() {
            canceled = true
        }

        override fun isExecuted(): Boolean = executed

        override fun isCanceled(): Boolean = canceled

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = FakeCall(original, respond)
    }
}
