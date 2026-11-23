package com.shenyu.chat.data

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 官方「导出所有历史对话」产物的读写。
 *
 * 产物结构（export_format_version = 3）：
 * 1. manifest.json：{"export_format_version":3}
 * 2. chat_sessions/{id}.json：每条会话一个文件
 * 3. chat_session_index.json：会话索引
 * 4. chat_sessions/{id}_files/…：图片原文件（可选）
 *
 * 导入时只读前三类条目；分卷、加密、ZIP64 的包直接拒绝并给出可读原因。
 */
class HistoryArchive {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // ---------------------------------------------------------------- 导出

    /** 把本机会话写成 deepseek_data-日期.zip，返回文件路径。 */
    fun export(dir: File, sessions: List<Session>): File {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "deepseek_data-$stamp.zip")
        file.outputStream().use { out -> writeZip(out, sessions) }
        return file
    }

    /** 导出到内存，便于单元测试与分享。 */
    fun exportToBytes(sessions: List<Session>): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        writeZip(buffer, sessions)
        return buffer.toByteArray()
    }

    private fun writeZip(out: OutputStream, sessions: List<Session>) {
        ZipOutputStream(out).use { zip ->
            // 1) manifest 必须是第一个条目
            zip.putEntry("manifest.json", buildJsonObject {
                put("export_format_version", 3)
            }.toString())

            // 2) 每条会话一个文件
            val indexEntries = mutableListOf<JsonObject>()
            sessions.forEach { session ->
                zip.putEntry("chat_sessions/${session.id}.json", sessionToJson(session).toString())
                indexEntries += buildJsonObject {
                    put("id", session.id)
                    put("title", session.title ?: session.displayTitle)
                    put("pinned", session.pinned)
                    put("message_count", session.messages.size)
                    put("inserted_at", iso(session.createdAt))
                    put("updated_at", iso(session.updatedAt))
                }
            }

            // 3) 会话索引
            zip.putEntry("chat_session_index.json", JsonArray(indexEntries).toString())
        }
    }

    private fun ZipOutputStream.putEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun sessionToJson(session: Session): JsonObject {
        val messages = session.messages
        return buildJsonObject {
            put("id", session.id)
            put("title", session.title ?: session.displayTitle)
            put("pinned", session.pinned)
            put("current_message_id", messages.size)
            put("inserted_at", iso(session.createdAt))
            put("updated_at", iso(session.updatedAt))
            putJsonArray("messages") {
                messages.forEachIndexed { index, message ->
                    add(messageToJson(message, index + 1, if (index == 0) null else index))
                }
            }
        }
    }

    private fun messageToJson(message: Message, id: Int, parentId: Int?): JsonObject = buildJsonObject {
        put("id", id)
        if (parentId == null) put("parent_id", JsonPrimitive(null as String?)) else put("parent_id", parentId)
        put("role", if (message.role == Role.USER) "USER" else "ASSISTANT")
        put("status", if (message.status == MessageStatus.FAILED) "FAILED" else "FINISHED")
        put("thinking_enabled", message.blocks.any { it is Block.Thinking })
        put("search_enabled", message.blocks.any { it is Block.Search })
        put("accumulated_token_usage", 0)
        put("inserted_at", iso(message.createdAt))
        putJsonArray("fragments") {
            message.blocks.forEach { block ->
                when (block) {
                    is Block.Thinking -> add(buildJsonObject {
                        put("type", "THINK")
                        put("content", block.text)
                        if (block.seconds == null) {
                            put("elapsed_secs", JsonPrimitive(null as String?))
                        } else {
                            put("elapsed_secs", block.seconds)
                        }
                    })

                    is Block.Search -> add(buildJsonObject {
                        put("type", "TOOL_SEARCH")
                        put("queries", JsonArray(block.queries.map { JsonPrimitive(it) }))
                        put("results", JsonArray(block.hits.map { hit ->
                            buildJsonObject {
                                put("url", hit.url)
                                put("title", hit.title)
                                put("snippet", hit.snippet)
                                if (hit.date.isBlank()) {
                                    put("published_at", JsonPrimitive(null as String?))
                                } else {
                                    put("published_at", hit.date)
                                }
                                put("site_name", JsonPrimitive(null as String?))
                            }
                        }))
                        put(
                            "status",
                            when (block.status) {
                                SearchStatus.RUNNING -> "WIP"
                                SearchStatus.FAILED -> "FAILED"
                                SearchStatus.DONE -> "FINISHED"
                            },
                        )
                    })

                    is Block.Text -> add(buildJsonObject {
                        put("type", if (message.role == Role.USER) "REQUEST" else "RESPONSE")
                        put("content", block.text)
                    })
                }
            }
            if (message.role == Role.USER && message.attachments.isNotEmpty()) {
                add(buildJsonObject {
                    put("type", "FILE")
                    put("files", JsonArray(message.attachments.map { attachment ->
                        buildJsonObject {
                            put("id", attachment.fileId ?: attachment.name)
                            put("file_name", attachment.name)
                            put("file_size", attachment.size)
                            put("token_usage", JsonPrimitive(null as String?))
                            put("inserted_at", iso(message.createdAt))
                            put("export_path", JsonPrimitive(null as String?))
                            put("is_image", attachment.isImage)
                            put("width", JsonPrimitive(null as String?))
                            put("height", JsonPrimitive(null as String?))
                        }
                    }))
                })
            }
        }
    }

    // ---------------------------------------------------------------- 导入

    /** 解析官方导出包；失败抛出带中文原因的异常。 */
    fun read(input: InputStream): List<Session> {
        // 逐个条目就地解析成 Session，不再把所有 JsonObject 攒在内存里：
        // 真实备份包（含图片）常见 100 MB+，一边攒 JSON 树一边攒 Session 峰值会翻好几倍。
        val sessions = mutableListOf<Session>()
        var manifestVersion: Int? = null
        var sawAnyEntry = false

        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry: ZipEntry = zip.nextEntry ?: break
                sawAnyEntry = true
                val name = entry.name
                if (entry.isDirectory) continue
                if (!name.endsWith(".json")) continue
                val text = zip.readBytes().toString(Charsets.UTF_8)
                if (name == "manifest.json") {
                    manifestVersion = runCatching {
                        json.parseToJsonElement(text).jsonObject["export_format_version"]
                            ?.jsonPrimitive?.intOrNull
                    }.getOrNull()
                } else if (name.startsWith("chat_sessions/") && name.endsWith(".json")) {
                    runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()?.let { obj ->
                        runCatching { jsonToSession(obj) }.getOrNull()?.let { session ->
                            if (session.messages.isNotEmpty()) sessions += session
                        }
                    }
                }
                zip.closeEntry()
            }
        }

        if (!sawAnyEntry) throw ArchiveException("压缩包是空的，或者已经损坏")
        if (manifestVersion == null) {
            throw ArchiveException("这不是 DeepSeek 导出的历史记录包（缺少 manifest.json）")
        }
        if (manifestVersion > 3) {
            throw ArchiveException("这个包用了更新的导出格式（v$manifestVersion），请先升级 App")
        }
        if (sessions.isEmpty()) {
            throw ArchiveException("包里没有对话内容")
        }
        return sessions
    }

    private fun jsonToSession(obj: JsonObject): Session {
        val messagesJson = obj["messages"]?.jsonArray ?: JsonArray(emptyList())
        val byId = mutableMapOf<Int, Message>()
        val parentOf = mutableMapOf<Int, Int?>()
        messagesJson.forEach { element ->
            val message = element.jsonObject
            val id = message["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
            parentOf[id] = message["parent_id"]?.let { it as? JsonPrimitive }?.intOrNull
            byId[id] = jsonToMessage(message)
        }
        // 从 current_message_id 沿 parent_id 回溯出主链，再反转成时间顺序
        var cursor = obj["current_message_id"]?.jsonPrimitive?.intOrNull
        if (cursor == null || !byId.containsKey(cursor)) {
            cursor = byId.keys.maxOrNull()
        }
        val chain = mutableListOf<Message>()
        val guard = mutableSetOf<Int>()
        while (cursor != null && byId.containsKey(cursor) && guard.add(cursor)) {
            chain += byId.getValue(cursor)
            cursor = parentOf[cursor]
        }
        chain.reverse()
        return Session(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return Session(messages = chain.toList()),
            title = obj["title"]?.jsonPrimitive?.contentOrNull,
            pinned = obj["pinned"]?.jsonPrimitive?.booleanOrNull ?: false,
            createdAt = parseIso(obj["inserted_at"]?.jsonPrimitive?.contentOrNull),
            updatedAt = parseIso(obj["updated_at"]?.jsonPrimitive?.contentOrNull),
            messages = chain.toList(),
        )
    }

    private fun jsonToMessage(obj: JsonObject): Message {
        val role = if (obj["role"]?.jsonPrimitive?.contentOrNull == "USER") Role.USER else Role.ASSISTANT
        val statusText = obj["status"]?.jsonPrimitive?.contentOrNull
        val blocks = mutableListOf<Block>()
        var attachments: List<Attachment> = emptyList()
        var error: MessageError? = null

        obj["fragments"]?.jsonArray?.forEach { element ->
            val fragment = element.jsonObject
            when (fragment["type"]?.jsonPrimitive?.contentOrNull) {
                "REQUEST", "RESPONSE" -> {
                    val content = fragment["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (content.isNotBlank()) blocks += Block.Text(content)
                }

                "THINK" -> blocks += Block.Thinking(
                    text = fragment["content"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    seconds = fragment["elapsed_secs"]?.jsonPrimitive?.intOrNull,
                    done = true,
                )

                "TOOL_SEARCH" -> {
                    val queries = fragment["queries"]?.let { q ->
                        runCatching { q.jsonArray.map { it.jsonPrimitive.contentOrNull.orEmpty() } }
                            .getOrDefault(emptyList())
                    } ?: emptyList()
                    val hits = runCatching {
                        fragment["results"]?.jsonArray?.map { item ->
                            val hit = item.jsonObject
                            SearchHit(
                                title = hit["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                url = hit["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                snippet = hit["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                date = hit["published_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            )
                        } ?: emptyList()
                    }.getOrDefault(emptyList())
                    blocks += Block.Search(
                        queries = queries,
                        status = SearchStatus.DONE,
                        hits = hits,
                    )
                }

                "FILE" -> {
                    attachments = runCatching {
                        fragment["files"]?.jsonArray?.map { item ->
                            val file = item.jsonObject
                            Attachment(
                                name = file["file_name"]?.jsonPrimitive?.contentOrNull ?: "附件",
                                mime = if (file["is_image"]?.jsonPrimitive?.booleanOrNull == true) {
                                    "image/*"
                                } else {
                                    "application/octet-stream"
                                },
                                size = file["file_size"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0L,
                            )
                        } ?: emptyList()
                    }.getOrDefault(emptyList())
                }

                "TEMPLATE_RESPONSE" -> {
                    val content = fragment["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (content.isNotBlank()) error = MessageError(content, retryable = false)
                }

                else -> Unit
            }
        }

        val status = when (statusText) {
            "FAILED", "TIMEOUT", "CONTENT_FILTER", "CONTEXT_LENGTH_EXCEEDED" -> MessageStatus.FAILED
            "WIP" -> MessageStatus.STOPPED
            else -> if (error != null) MessageStatus.FAILED else MessageStatus.FINISHED
        }
        return Message(
            role = role,
            blocks = blocks,
            status = status,
            createdAt = parseIso(obj["inserted_at"]?.jsonPrimitive?.contentOrNull),
            error = error,
            attachments = attachments,
        )
    }

    // ---------------------------------------------------------------- 工具

    private fun iso(time: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(time))
    }

    private fun parseIso(text: String?): Long {
        if (text.isNullOrBlank()) return System.currentTimeMillis()
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )
        patterns.forEach { pattern ->
            runCatching {
                val format = SimpleDateFormat(pattern, Locale.US)
                if (pattern.endsWith("'Z'")) format.timeZone = TimeZone.getTimeZone("UTC")
                return format.parse(text)?.time ?: 0L
            }
        }
        return System.currentTimeMillis()
    }

}

class ArchiveException(message: String) : Exception(message)

/** 把 Uri 的输入流交给调用方，读不到时抛出可读错误。 */
fun Context.openArchiveStream(uri: Uri): InputStream =
    contentResolver.openInputStream(uri) ?: throw ArchiveException("无法读取这个文件")
