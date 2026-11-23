package com.shenyu.chat.net

import com.shenyu.chat.data.ProviderConfig
import com.shenyu.chat.data.ProviderProtocol
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenAiAdapterTest {

    private val provider = ProviderConfig(
        id = "openai",
        name = "兼容测试",
        baseUrl = "https://relay.example.com/v1",
        apiKey = "sk-test",
        model = "gpt-4o-mini",
        protocol = ProviderProtocol.OPENAI,
    )

    private fun anthropicShapedBody() = buildJsonObject {
        put("model", "deepseek-flash")
        put("system", "你是助手")
        put("messages", buildJsonArray {
            add(
                buildJsonObject {
                    put("role", "latest_reminder")
                    put("content", "2026-11-21,星期六,CN,Web,中文")
                },
            )
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", "你好")
                },
            )
            add(
                buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "你好！") })
                    })
                },
            )
        })
        put("thinking", buildJsonObject { put("type", "enabled") })
        put("tools", buildJsonArray {
            add(buildJsonObject { put("type", "deepseek_web_search") })
        })
        put("title", buildJsonObject { put("enabled", true) })
    }

    @Test
    fun convertsMessagesAndDropsDeepSeekExtensions() {
        val request = OpenAiAdapter.request(provider, anthropicShapedBody())
        assertEquals("gpt-4o-mini", request["model"]?.jsonPrimitive?.content)
        assertEquals("true", request["stream"]?.jsonPrimitive?.content)
        assertTrue(!request.containsKey("tools"))
        assertTrue(!request.containsKey("title"))
        assertTrue(!request.containsKey("thinking"))

        val messages = request["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertTrue(messages[0].jsonObject["content"]!!.jsonPrimitive.content.contains("2026-11-21"))
        assertEquals("user", messages[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("你好", messages[1].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("assistant", messages[2].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("你好！", messages[2].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun parserEmitsThinkingThenText() {
        val parser = OpenAiStreamParser()
        val reasoning = parser.parse(
            """{"model":"relay","choices":[{"delta":{"reasoning_content":"先想"},"finish_reason":null}]}""",
        )
        assertTrue(reasoning.any { it is StreamEvent.ThinkingStart && it.index == 0 })
        assertTrue(reasoning.any { it is StreamEvent.ThinkingDelta && it.text == "先想" })

        val content = parser.parse(
            """{"choices":[{"delta":{"content":"答案"},"finish_reason":null}]}""",
        )
        assertTrue(content.any { it is StreamEvent.TextStart && it.index == 1 })
        assertTrue(content.any { it is StreamEvent.TextDelta && it.index == 1 && it.text == "答案" })

        val finish = parser.parse(
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )
        assertTrue(finish.any { it is StreamEvent.Finished && it.stopReason == "stop" })
    }

    @Test
    fun parserWithoutThinkingUsesIndexZero() {
        val parser = OpenAiStreamParser()
        val content = parser.parse(
            """{"choices":[{"delta":{"content":"直接回答"},"finish_reason":null}]}""",
        )
        assertTrue(content.any { it is StreamEvent.TextStart && it.index == 0 })
        assertTrue(content.any { it is StreamEvent.TextDelta && it.index == 0 })
    }

    @Test
    fun parserSurfacesErrorFrame() {
        val parser = OpenAiStreamParser()
        try {
            parser.parse("""{"error":{"message":"invalid api key"}}""")
            fail("应当抛出 ApiException")
        } catch (e: ApiException) {
            assertTrue(e.message.contains("invalid api key"))
        }
    }
}
