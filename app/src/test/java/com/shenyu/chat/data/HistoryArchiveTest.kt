package com.shenyu.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 导入导出的往返测试：不受设备影响，纯 JVM 跑。
 * 覆盖：完整往返、思考/搜索/附件还原、空包与版本过新、单条消息链还原。
 */
class HistoryArchiveTest {

    private val archive = HistoryArchive()

    private fun sampleSession(): Session = Session(
        id = "11111111-2222-3333-4444-555555555555",
        title = "项目周报",
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_100_000,
        messages = listOf(
            Message(
                role = Role.USER,
                blocks = listOf(Block.Text("帮我把这周进展整理成周报")),
                status = MessageStatus.FINISHED,
                createdAt = 1_700_000_000_000,
                attachments = listOf(Attachment("spec.pdf", "application/pdf", 1024, fileId = "file-1")),
            ),
            Message(
                role = Role.ASSISTANT,
                blocks = listOf(
                    Block.Thinking("先看本周提交记录", seconds = 6, done = true),
                    Block.Search(
                        kind = "search",
                        queries = listOf("周报模板"),
                        status = SearchStatus.DONE,
                        hits = listOf(SearchHit("模板", "https://example.com/a", "摘要", "2026-11-01T00:00:00Z")),
                    ),
                    Block.Text("一、本周完成……"),
                ),
                status = MessageStatus.FINISHED,
                createdAt = 1_700_000_010_000,
            ),
        ),
    )

    @Test
    fun `导出再导入内容一致`() {
        val session = sampleSession()
        val bytes = archive.exportToBytes(listOf(session))
        val restored = archive.read(ByteArrayInputStream(bytes))

        assertEquals(1, restored.size)
        val r = restored.first()
        assertEquals(session.id, r.id)
        assertEquals(session.title, r.title)
        assertEquals(2, r.messages.size)

        val user = r.messages[0]
        assertEquals(Role.USER, user.role)
        assertEquals("帮我把这周进展整理成周报", user.plainText)
        assertEquals(1, user.attachments.size)
        assertEquals("spec.pdf", user.attachments.first().name)

        val assistant = r.messages[1]
        assertEquals(Role.ASSISTANT, assistant.role)
        assertEquals("先看本周提交记录", assistant.thinkingText)
        assertEquals(6, (assistant.blocks.first() as Block.Thinking).seconds)
        val search = assistant.blocks.filterIsInstance<Block.Search>().first()
        assertEquals(listOf("周报模板"), search.queries)
        assertEquals(1, search.hits.size)
        assertEquals("https://example.com/a", search.hits.first().url)
        assertEquals("一、本周完成……", assistant.plainText)
    }

    @Test
    fun `导出包结构符合官方约定`() {
        val bytes = archive.exportToBytes(listOf(sampleSession()))
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                zip.closeEntry()
            }
        }
        assertEquals("manifest.json", names.first())
        assertTrue(names.contains("chat_sessions/11111111-2222-3333-4444-555555555555.json"))
        assertTrue(names.contains("chat_session_index.json"))
    }

    @Test(expected = ArchiveException::class)
    fun `缺少 manifest 直接拒绝`() {
        val bogus = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("hello.json"))
                zip.write("{}".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        archive.read(ByteArrayInputStream(bogus))
    }

    @Test(expected = ArchiveException::class)
    fun `版本过新直接拒绝`() {
        val future = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                zip.write("""{"export_format_version":9}""".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        archive.read(ByteArrayInputStream(future))
    }

    @Test
    fun `只还原 current_message_id 指向的主链`() {
        // 1 -> 2 -> 3，另有 4 是 2 的分支；current 指向 3，只应还原 1/2/3
        val zipBytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                zip.write("""{"export_format_version":3}""".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("chat_sessions/s1.json"))
                zip.write(
                    """
                    {
                      "id":"s1","title":"分支测试","pinned":false,"current_message_id":3,
                      "inserted_at":"2026-11-01T00:00:00.000Z","updated_at":"2026-11-01T00:10:00.000Z",
                      "messages":[
                        {"id":1,"parent_id":null,"role":"USER","status":"FINISHED","thinking_enabled":false,"search_enabled":false,"accumulated_token_usage":0,"inserted_at":"2026-11-01T00:00:00.000Z","fragments":[{"type":"REQUEST","content":"问题一"}]},
                        {"id":2,"parent_id":1,"role":"ASSISTANT","status":"FINISHED","thinking_enabled":false,"search_enabled":false,"accumulated_token_usage":0,"inserted_at":"2026-11-01T00:01:00.000Z","fragments":[{"type":"RESPONSE","content":"回答一"}]},
                        {"id":3,"parent_id":2,"role":"USER","status":"FINISHED","thinking_enabled":false,"search_enabled":false,"accumulated_token_usage":0,"inserted_at":"2026-11-01T00:02:00.000Z","fragments":[{"type":"REQUEST","content":"问题二"}]},
                        {"id":4,"parent_id":2,"role":"USER","status":"FINISHED","thinking_enabled":false,"search_enabled":false,"accumulated_token_usage":0,"inserted_at":"2026-11-01T00:03:00.000Z","fragments":[{"type":"REQUEST","content":"被放弃的分支"}]}
                      ]
                    }
                    """.trimIndent().toByteArray()
                )
                zip.closeEntry()
            }
        }.toByteArray()

        val restored = archive.read(ByteArrayInputStream(zipBytes)).first()
        assertEquals(3, restored.messages.size)
        assertEquals(listOf("问题一", "回答一", "问题二"), restored.messages.map { it.plainText })
    }

    @Test
    fun `从文件流读大包不炸内存`() {
        // 回归：链接导入曾把整个包读进 ByteArrayOutputStream，124.7 MB 的备份在真机上
        // 直接 OOM（Failed to allocate a 130721176 byte allocation）。现在改成落临时文件后按流解析，
        // 这里用 200 个会话（每会话约 200 KB 正文）压一遍，验证按条目流式解析的结果仍然正确。
        val filler = "正文".repeat(25_000)
        val sessions = (1..200).map { index ->
            Session(
                id = "s-$index",
                title = "会话 $index",
                messages = listOf(
                    Message(
                        role = Role.USER,
                        blocks = listOf(Block.Text("问题 $index")),
                        status = MessageStatus.FINISHED,
                        createdAt = 1_700_000_000_000 + index,
                    ),
                    Message(
                        role = Role.ASSISTANT,
                        blocks = listOf(Block.Text(filler)),
                        status = MessageStatus.FINISHED,
                        createdAt = 1_700_000_001_000 + index,
                    ),
                ),
            )
        }
        val file = java.io.File.createTempFile("archive-test-", ".zip")
        try {
            java.io.FileOutputStream(file).use { out ->
                out.write(archive.exportToBytes(sessions))
            }
            val restored = file.inputStream().use { archive.read(it) }
            assertEquals(200, restored.size)
            assertTrue(restored.all { it.messages.size == 2 })
        } finally {
            file.delete()
        }
    }
}

