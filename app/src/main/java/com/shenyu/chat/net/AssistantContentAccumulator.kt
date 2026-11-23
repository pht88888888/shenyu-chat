package com.shenyu.chat.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reconstruct the exact assistant content for a client-tool continuation, including thinking signatures. */
internal class AssistantContentAccumulator {
    private val json = Json { ignoreUnknownKeys = true }
    private val blocks = sortedMapOf<Int, JsonObject>()
    private val strings = mutableMapOf<Pair<Int, String>, StringBuilder>()
    private val arguments = mutableMapOf<Int, StringBuilder>()

    fun accept(eventName: String?, raw: String) {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: eventName
        val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
        when (type) {
            "content_block_start" -> {
                val block = obj["content_block"] as? JsonObject ?: return
                blocks[index] = block
            }
            "content_block_delta" -> {
                if (index !in blocks) return
                val delta = obj["delta"] as? JsonObject ?: return
                when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                    "input_json_delta" -> arguments.getOrPut(index) { StringBuilder() }
                        .append(delta["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    "text_delta" -> append(index, "text", delta)
                    "thinking_delta" -> append(index, "thinking", delta)
                    "signature_delta" -> append(index, "signature", delta)
                }
            }
        }
    }

    private fun append(index: Int, field: String, delta: JsonObject) {
        strings.getOrPut(index to field) {
            StringBuilder(blocks[index]?.get(field)?.jsonPrimitive?.contentOrNull.orEmpty())
        }.append(delta[field]?.jsonPrimitive?.contentOrNull.orEmpty())
    }

    fun snapshot(): JsonArray = JsonArray(blocks.map { (index, initial) ->
        val block = initial.toMutableMap()
        for (field in listOf("text", "thinking", "signature")) {
            strings[index to field]?.let { block[field] = JsonPrimitive(it.toString()) }
        }
        arguments[index]?.let { partial ->
            block["input"] = try {
                json.parseToJsonElement(partial.toString()).jsonObject
            } catch (_: Exception) {
                throw ApiException(ApiException.Kind.PROTOCOL, "工具参数不完整，请重试")
            }
        }
        JsonObject(block)
    })
}
