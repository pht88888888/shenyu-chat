package com.shenyu.chat.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantContentAccumulatorTest {
    private val json = Json

    @Test
    fun `tool continuation keeps signed thinking text and chunked input in order`() {
        val accumulator = AssistantContentAccumulator()
        listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"先查阅"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed-"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"proof"}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call-1","name":"lookup_web","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"query\":"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"上下文长度\"}"}}""",
        ).forEach { accumulator.accept(null, it) }
        assertEquals(
            json.parseToJsonElement("""[{"type":"thinking","thinking":"先查阅","signature":"signed-proof"},{"type":"tool_use","id":"call-1","name":"lookup_web","input":{"query":"上下文长度"}}]"""),
            accumulator.snapshot(),
        )
    }

    @Test(expected = ApiException::class)
    fun `incomplete tool input fails before dispatch`() {
        val accumulator = AssistantContentAccumulator()
        accumulator.accept(null, """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call-1","name":"lookup_web","input":{}}}""")
        accumulator.accept(null, """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{"}}""")
        accumulator.snapshot()
    }
}
