package com.shenyu.chat.chat

import com.shenyu.chat.data.LongDocMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长文档模式的行为约定。
 *
 * 事实来源：2026-11-19 对官方接口的实测（qa/context-1m-20261119/official-api-ab.json）——
 * 同一段 40,015 Token 的输入，普通通道 200，带 title / safety_check / 联网工具则 400，
 * 报错原文为 `user input too long, 40015 tokens exceeds max 32768 tokens`。
 */
class ContextBudgetTest {

    private val serverError =
        "请求失败（400）：user input too long, 40015 tokens exceeds max 32768 tokens (request_id: f6a31ace)"

    @Test
    fun `识别服务端的输入超长报错`() {
        assertTrue(ContextBudget.isInputTooLong(serverError))
        assertTrue(ContextBudget.isInputTooLong("user input too long, 1 tokens exceeds max 32768 tokens"))
        assertFalse(ContextBudget.isInputTooLong("请求失败（429）：请求太频繁"))
        assertFalse(ContextBudget.isInputTooLong("API Key 无效或已失效（401）"))
        assertFalse(ContextBudget.isInputTooLong(null))
        assertFalse(ContextBudget.isInputTooLong(""))
    }

    @Test
    fun `从报错里取上限与被拒的输入量`() {
        assertEquals(32768, ContextBudget.reportedLimit(serverError))
        assertEquals(40015, ContextBudget.reportedInputTokens(serverError))
        assertNull(ContextBudget.reportedLimit("user input too long"))
    }

    @Test
    fun `中文长文本估算不会低估`() {
        // 6 个汉字按 0.6 Token 估约 3.6，放大后应 >= 4
        val six = ContextBudget.estimateTokens("上下文长度不足")
        assertTrue("实测 $six", six >= 4)
        assertEquals(0, ContextBudget.estimateTokens(""))

        // 一份 6 万汉字的文档必须被判为超限，不能漏降级
        val long = ContextBudget.estimateTokens("字".repeat(60_000))
        assertTrue("实测 $long", long > ContextBudget.ENHANCED_INPUT_LIMIT)
    }

    @Test
    fun `短提问不会误触发降级`() {
        val short = ContextBudget.estimateTokens("倒数第三页的第一题是什么")
        assertTrue("实测 $short", short < ContextBudget.PROACTIVE_INPUT_BUDGET)
    }

    @Test
    fun `关闭模式下的说明引导用户去设置里开启`() {
        val text = ContextBudget.explainTooLong(serverError, LongDocMode.OFF)
        assertTrue(text, text.contains("上下文模式"))
        assertTrue(text, text.contains("1M"))
    }

    @Test
    fun `自动模式下说明已尝试长上下文仍未通过`() {
        val text = ContextBudget.explainTooLong(serverError, LongDocMode.AUTO)
        assertTrue(text, text.contains("4.0 万"))
        assertTrue(text, text.contains("拆分"))
    }
}

/** 请求形态：增强通道带 title / safety_check / tools，长上下文通道只留四项。 */
class MessagePayloadsTest {

    private fun messages(): JsonArray = buildJsonArray {
        add(
            kotlinx.serialization.json.buildJsonObject {
                put("role", "user")
                put("content", "你好")
            }
        )
    }

    private fun build(enhanced: Boolean, search: Boolean = true, withTitle: Boolean = true) =
        MessagePayloads.build(
            messages = messages(),
            options = MessagePayloads.Options(
                model = "deepseek-flash",
                system = "system",
                thinking = true,
                search = search,
                enhanced = enhanced,
                withTitle = withTitle,
            ),
        )

    @Test
    fun `增强通道带标题风控与联网工具`() {
        val body = build(enhanced = true)
        assertEquals("deepseek-flash", body["model"]!!.jsonPrimitive.content)
        assertTrue(body.containsKey("title"))
        assertTrue(body.containsKey("safety_check"))
        val tools = body["tools"] as JsonArray
        assertEquals(2, tools.size)
        assertEquals("deepseek_web_search", tools[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("deepseek_read_url", tools[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `搜索关闭时只留阅读链接`() {
        val tools = build(enhanced = true, search = false)["tools"] as JsonArray
        assertEquals(1, tools.size)
        assertEquals("deepseek_read_url", tools[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `会话已有标题时不再重复索要`() {
        assertFalse(build(enhanced = true, withTitle = false).containsKey("title"))
    }

    @Test
    fun `长文档关闭搜索时不会向模型开放搜索工具`() {
        val tools = build(enhanced = false, search = false)["tools"] as JsonArray
        assertEquals(listOf("read_webpage"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun `长上下文保留思考而不携带触发32K限制的内置扩展`() {
        val body = build(enhanced = false)
        assertFalse(body.containsKey("title"))
        assertFalse(body.containsKey("safety_check"))
        val tools = body["tools"] as JsonArray
        assertEquals(listOf("lookup_web", "read_webpage"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertTrue(tools.all { !it.jsonObject.containsKey("type") && it.jsonObject.containsKey("input_schema") })
        assertEquals(messages(), body["messages"])
        // 必需字段仍在
        assertTrue(body.containsKey("model"))
        assertTrue(body.containsKey("system"))
        assertTrue(body.containsKey("messages"))
        assertTrue(body.containsKey("thinking"))
    }

    @Test
    fun `标准 Anthropic 通道补齐 max_tokens 与思考预算`() {
        val body = MessagePayloads.build(
            messages = messages(),
            options = MessagePayloads.Options(
                model = "claude-3-5-sonnet-latest",
                system = "system",
                thinking = true,
                search = false,
                enhanced = false,
                withTitle = false,
                clientTools = false,
                maxTokens = 8192,
                thinkingBudget = 4096,
            ),
        )
        assertEquals(8192, body["max_tokens"]!!.jsonPrimitive.int)
        val thinking = body["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        assertEquals(4096, thinking["budget_tokens"]!!.jsonPrimitive.int)
        assertFalse(body.containsKey("tools"))
        assertFalse(body.containsKey("title"))
        assertFalse(body.containsKey("safety_check"))
    }

    @Test
    fun `DeepSeek 应用通道不额外发送 max_tokens`() {
        val body = build(enhanced = true)
        assertFalse(body.containsKey("max_tokens"))
        assertFalse(body["thinking"]!!.jsonObject.containsKey("budget_tokens"))
    }
}
