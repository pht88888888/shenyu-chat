package com.shenyu.chat.chat

import com.shenyu.chat.net.ApiException
import com.shenyu.chat.net.SearchHitDto
import com.shenyu.chat.net.StreamEvent
import com.shenyu.chat.net.WebToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ClientToolRunnerTest {
    private val json = Json
    private val documentMessage = json.parseToJsonElement("""{"role":"user","content":[{"type":"document","source":{"type":"file","file_id":"file-long-pdf"}},{"type":"text","text":"倒数第三页第一题是什么，同时搜索来源"}]}""").jsonObject
    private val request = MessagePayloads.build(JsonArray(listOf(documentMessage)), MessagePayloads.Options(
        "deepseek-flash", "system", true, true, false, false,
    ))
    private val toolContent = json.parseToJsonElement("""[{"type":"thinking","thinking":"先搜索后核对原文","signature":"must-keep-signature"},{"type":"tool_use","id":"call-1","name":"lookup_web","input":{"query":"官方上下文长度"}}]""") as JsonArray
    private fun toolEvents() = flowOf(
        StreamEvent.ThinkingStart(0, "先搜索后核对原文"),
        StreamEvent.SearchToolUse(1, "search", emptyList(), ""),
        StreamEvent.Finished("tool_use", null),
        StreamEvent.AssistantContent(toolContent),
        StreamEvent.Stopped,
    )
    private fun finalEvents() = flowOf(
        StreamEvent.TextStart(0, "第78页：原题"),
        StreamEvent.TextDelta(0, "，来源 https://example.com"),
        StreamEvent.Finished("end_turn", null),
        StreamEvent.Stopped,
    )

    @Test
    fun `search continuation preserves full document signed reasoning source cards and final text`() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val events = mutableListOf<StreamEvent>()
        ClientToolRunner(
            model = { body -> requests += body; if (requests.size == 1) toolEvents() else finalEvents() },
            execute = { name, input ->
                assertEquals("lookup_web", name)
                assertEquals("官方上下文长度", input["query"]!!.jsonPrimitive.content)
                WebToolResult("真实检索结果", listOf(SearchHitDto("来源", "https://example.com", "摘要", "")))
            },
        ).run(request) { events += it }
        assertEquals(2, requests.size)
        val history = requests[1]["messages"] as JsonArray
        assertEquals(documentMessage, history[0])
        assertEquals(toolContent, history[1].jsonObject["content"])
        val result = (history[2].jsonObject["content"] as JsonArray)[0].jsonObject
        assertEquals("call-1", result["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("真实检索结果", result["content"]!!.jsonPrimitive.content)
        assertTrue(events.any { it is StreamEvent.SearchResults && it.index == 1 && it.hits.single().url == "https://example.com" })
        assertTrue(events.any { it is StreamEvent.TextStart && it.index == 2 })
        assertTrue(events.any { it is StreamEvent.TextDelta && it.index == 2 })
    }

    @Test
    fun `disabled search cannot be executed even if model invents a call`() = runBlocking {
        val withoutSearch = MessagePayloads.build(JsonArray(listOf(documentMessage)), MessagePayloads.Options(
            "deepseek-flash", "system", false, false, false, false,
        ))
        var modelSteps = 0
        var dispatched = false
        val events = mutableListOf<StreamEvent>()
        ClientToolRunner(
            model = { if (++modelSteps == 1) toolEvents() else finalEvents() },
            execute = { _, _ -> dispatched = true; error("Search should remain off") },
        ).run(withoutSearch) { events += it }
        assertFalse(dispatched)
        assertTrue(events.any { it is StreamEvent.SearchFailed })
        assertFalse(events.any { it is StreamEvent.SearchResults })
    }

    @Test
    fun `cancellation during search stops model continuation`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var modelCalls = 0
        val runner = ClientToolRunner(
            model = { modelCalls++; toolEvents() },
            execute = { _, _ -> entered.complete(Unit); awaitCancellation() },
        )
        val job = launch { runner.run(request) {} }
        entered.await()
        job.cancelAndJoin()
        assertEquals(1, modelCalls)
    }

    @Test
    fun `balance failure is propagated and search marked failed`() = runBlocking {
        val events = mutableListOf<StreamEvent>()
        try {
            ClientToolRunner(
                model = { toolEvents() },
                execute = { _, _ -> throw ApiException(ApiException.Kind.QUOTA, "账户余额不足", 402) },
            ).run(request) { events += it }
            fail("A balance failure must not be hidden")
        } catch (e: ApiException) {
            assertEquals(402, e.status)
        }
        assertTrue(events.any { it is StreamEvent.SearchFailed })
    }
}
