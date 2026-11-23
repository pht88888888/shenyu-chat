package com.shenyu.chat.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 会话与消息的本地存储。
 * 只存「已经定稿」的消息；生成过程中的增量留在内存里，结束时统一落库，避免每帧写盘。
 */
class ChatDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val blocksSerializer = ListSerializer(Block.serializer())
    private val attachmentsSerializer = ListSerializer(Attachment.serializer())

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                pinned INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY NOT NULL,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                blocks TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                error TEXT,
                attachments TEXT NOT NULL DEFAULT '[]',
                feedback INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_session ON messages(session_id, created_at)")
        db.execSQL("CREATE INDEX idx_sessions_updated ON sessions(updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2：加上「喜欢/不喜欢」反馈列（官方消息操作条）
            runCatching { db.execSQL("ALTER TABLE messages ADD COLUMN feedback INTEGER NOT NULL DEFAULT 0") }
        }
    }

    fun readSessions(): List<SessionHeader> {
        val list = mutableListOf<SessionHeader>()
        readableDatabase.rawQuery(
            "SELECT id, title, created_at, updated_at, pinned FROM sessions ORDER BY pinned DESC, updated_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list += SessionHeader(
                    id = c.getString(0),
                    title = if (c.isNull(1)) null else c.getString(1),
                    createdAt = c.getLong(2),
                    updatedAt = c.getLong(3),
                    pinned = c.getInt(4) == 1,
                )
            }
        }
        return list
    }

    fun readMessages(sessionId: String): List<Message> {
        val list = mutableListOf<Message>()
        readableDatabase.rawQuery(
            "SELECT id, role, blocks, status, created_at, error, attachments, feedback FROM messages WHERE session_id = ? ORDER BY created_at ASC",
            arrayOf(sessionId)
        ).use { c ->
            while (c.moveToNext()) {
                list += Message(
                    id = c.getString(0),
                    role = runCatching { Role.valueOf(c.getString(1)) }.getOrDefault(Role.ASSISTANT),
                    blocks = runCatching { json.decodeFromString(blocksSerializer, c.getString(2)) }
                        .getOrDefault(emptyList()),
                    status = runCatching { MessageStatus.valueOf(c.getString(3)) }
                        .getOrDefault(MessageStatus.FINISHED),
                    createdAt = c.getLong(4),
                    error = c.getString(5)?.let { raw ->
                        runCatching { json.decodeFromString(MessageError.serializer(), raw) }.getOrNull()
                    },
                    attachments = runCatching {
                        json.decodeFromString(attachmentsSerializer, c.getString(6) ?: "[]")
                    }.getOrDefault(emptyList()),
                    feedback = if (c.columnCount > 7) c.getInt(7) else 0,
                )
            }
        }
        return list
    }

    fun upsertSession(session: Session) {
        val values = ContentValues().apply {
            put("id", session.id)
            put("title", session.title)
            put("created_at", session.createdAt)
            put("updated_at", session.updatedAt)
            put("pinned", if (session.pinned) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("sessions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateSessionMeta(sessionId: String, title: String?, updatedAt: Long) {
        writableDatabase.execSQL(
            "UPDATE sessions SET title = ?, updated_at = ? WHERE id = ?",
            arrayOf(title, updatedAt, sessionId)
        )
    }

    /** 批量置顶/取消置顶。 */
    fun updatePinned(ids: Collection<String>, pinned: Boolean) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put("pinned", if (pinned) 1 else 0) }
            ids.forEach { id -> db.update("sessions", values, "id = ?", arrayOf(id)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 批量删除（连同消息）。 */
    fun deleteSessions(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { id ->
                db.delete("messages", "session_id = ?", arrayOf(id))
                db.delete("sessions", "id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteSession(sessionId: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("messages", "session_id = ?", arrayOf(sessionId))
            writableDatabase.delete("sessions", "id = ?", arrayOf(sessionId))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun deleteAll() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("messages", null, null)
            writableDatabase.delete("sessions", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun upsertMessage(sessionId: String, message: Message) {
        val values = ContentValues().apply {
            put("id", message.id)
            put("session_id", sessionId)
            put("role", message.role.name)
            put("blocks", json.encodeToString(blocksSerializer, message.blocks))
            put("status", message.status.name)
            put("created_at", message.createdAt)
            put("error", message.error?.let { json.encodeToString(MessageError.serializer(), it) })
            put("attachments", json.encodeToString(attachmentsSerializer, message.attachments))
            put("feedback", message.feedback)
        }
        writableDatabase.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 单独更新一条消息的喜欢/不喜欢状态（不重写整轮消息）。 */
    /** 按消息正文搜会话 id（官方「搜索对话内容」）。 */
    fun searchBodySessions(query: String): Set<String> {
        if (query.isBlank()) return emptySet()
        val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val ids = mutableSetOf<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT session_id FROM messages WHERE blocks LIKE ? ESCAPE '\\'",
            arrayOf("%$escaped%")
        ).use { c ->
            while (c.moveToNext()) ids += c.getString(0)
        }
        return ids
    }

    fun updateFeedback(sessionId: String, messageId: String, value: Int) {
        writableDatabase.execSQL(
            "UPDATE messages SET feedback = ? WHERE session_id = ? AND id = ?",
            arrayOf(value, sessionId, messageId)
        )
    }

    fun deleteMessage(sessionId: String, messageId: String) {
        writableDatabase.delete("messages", "session_id = ? AND id = ?", arrayOf(sessionId, messageId))
    }

    fun deleteMessagesFrom(sessionId: String, createdAt: Long) {
        writableDatabase.delete("messages", "session_id = ? AND created_at >= ?", arrayOf(sessionId, createdAt.toString()))
    }

    fun saveSession(session: Session) {
        writableDatabase.beginTransaction()
        try {
            upsertSession(session)
            writableDatabase.delete("messages", "session_id = ?", arrayOf(session.id))
            session.messages.forEach { upsertMessage(session.id, it) }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    data class SessionHeader(
        val id: String,
        val title: String?,
        val createdAt: Long,
        val updatedAt: Long,
        val pinned: Boolean,
    )

    private companion object {
        const val NAME = "shenyu_chat.db"
        const val VERSION = 2
    }
}
