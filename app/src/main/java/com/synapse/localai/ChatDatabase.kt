package com.synapse.localai

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.synapse.localai.memory.VectorRecord
import com.synapse.localai.AtlasLog as Log
import java.util.UUID

data class ChatSessionEntity(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val summary: String = ""
)

data class ChatMessageEntity(
    val id: String,
    val sessionId: String,
    val sender: String,
    val text: String,
    val isError: Boolean = false,
    val timestamp: Long
)

class ChatDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "ChatDatabaseHelper"
        private const val DATABASE_NAME = "synapse_chat_history.db"
        private const val DATABASE_VERSION = 3

        private const val TABLE_SESSIONS = "chat_sessions"
        private const val COL_SESSION_ID = "id"
        private const val COL_SESSION_TITLE = "title"
        private const val COL_SESSION_CREATED = "created_at"
        private const val COL_SESSION_UPDATED = "updated_at"
        private const val COL_SESSION_SUMMARY = "summary"

        private const val TABLE_MESSAGES = "chat_messages"
        private const val COL_MSG_ID = "id"
        private const val COL_MSG_SESSION_ID = "session_id"
        private const val COL_MSG_SENDER = "sender"
        private const val COL_MSG_TEXT = "text"
        private const val COL_MSG_IS_ERROR = "is_error"
        private const val COL_MSG_TIMESTAMP = "timestamp"
        private const val TABLE_MEMORIES = "atlas_memories"
        private const val TABLE_MEMORY_FTS = "atlas_memory_fts"
        private const val TABLE_CHAT_FTS = "atlas_chat_fts"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createSessionsTable = """
            CREATE TABLE $TABLE_SESSIONS (
                $COL_SESSION_ID TEXT PRIMARY KEY,
                $COL_SESSION_TITLE TEXT,
                $COL_SESSION_CREATED INTEGER,
                $COL_SESSION_UPDATED INTEGER,
                $COL_SESSION_SUMMARY TEXT
            )
        """.trimIndent()

        val createMessagesTable = """
            CREATE TABLE $TABLE_MESSAGES (
                $COL_MSG_ID TEXT PRIMARY KEY,
                $COL_MSG_SESSION_ID TEXT,
                $COL_MSG_SENDER TEXT,
                $COL_MSG_TEXT TEXT,
                $COL_MSG_IS_ERROR INTEGER,
                $COL_MSG_TIMESTAMP INTEGER,
                FOREIGN KEY($COL_MSG_SESSION_ID) REFERENCES $TABLE_SESSIONS($COL_SESSION_ID) ON DELETE CASCADE
            )
        """.trimIndent()

        db.execSQL(createSessionsTable)
        db.execSQL(createMessagesTable)
        db.execSQL("CREATE INDEX idx_msg_session ON $TABLE_MESSAGES($COL_MSG_SESSION_ID, $COL_MSG_TIMESTAMP)")
        createMemoryTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) createMemoryTables(db)
    }

    private fun createMemoryTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $TABLE_MEMORIES (
                id TEXT PRIMARY KEY,
                content TEXT NOT NULL,
                category TEXT NOT NULL,
                record_json TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE_MEMORY_FTS USING fts4(content, category)"
        )
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS atlas_memory_insert AFTER INSERT ON $TABLE_MEMORIES BEGIN
                INSERT INTO $TABLE_MEMORY_FTS(docid, content, category) VALUES (new.rowid, new.content, new.category);
            END""".trimIndent()
        )
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS atlas_memory_update AFTER UPDATE ON $TABLE_MEMORIES BEGIN
                DELETE FROM $TABLE_MEMORY_FTS WHERE docid = old.rowid;
                INSERT INTO $TABLE_MEMORY_FTS(docid, content, category) VALUES (new.rowid, new.content, new.category);
            END""".trimIndent()
        )
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS atlas_memory_delete AFTER DELETE ON $TABLE_MEMORIES BEGIN
                DELETE FROM $TABLE_MEMORY_FTS WHERE docid = old.rowid;
            END""".trimIndent()
        )
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE_CHAT_FTS USING fts4(chat_text, sender)"
        )
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS atlas_chat_insert AFTER INSERT ON $TABLE_MESSAGES BEGIN
                INSERT INTO $TABLE_CHAT_FTS(docid, chat_text, sender) VALUES (new.rowid, new.$COL_MSG_TEXT, new.$COL_MSG_SENDER);
            END""".trimIndent()
        )
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS atlas_chat_update AFTER UPDATE ON $TABLE_MESSAGES BEGIN
                DELETE FROM $TABLE_CHAT_FTS WHERE docid = old.rowid;
                INSERT INTO $TABLE_CHAT_FTS(docid, chat_text, sender) VALUES (new.rowid, new.$COL_MSG_TEXT, new.$COL_MSG_SENDER);
            END""".trimIndent()
        )
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS atlas_chat_delete AFTER DELETE ON $TABLE_MESSAGES BEGIN
                DELETE FROM $TABLE_CHAT_FTS WHERE docid = old.rowid;
            END""".trimIndent()
        )
        db.execSQL(
            """INSERT INTO $TABLE_CHAT_FTS(docid, chat_text, sender)
                SELECT rowid, $COL_MSG_TEXT, $COL_MSG_SENDER FROM $TABLE_MESSAGES
                WHERE rowid NOT IN (SELECT docid FROM $TABLE_CHAT_FTS)""".trimIndent()
        )
    }

    fun getMemoryRecords(): List<VectorRecord> {
        val records = mutableListOf<VectorRecord>()
        readableDatabase.rawQuery("SELECT record_json FROM $TABLE_MEMORIES ORDER BY created_at DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { VectorRecord.fromJson(org.json.JSONObject(cursor.getString(0))) }
                    .getOrNull()
                    ?.let(records::add)
            }
        }
        return records
    }

    fun replaceMemoryRecords(records: List<VectorRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_MEMORIES, null, null)
            records.forEach { record ->
                val values = ContentValues().apply {
                    put("id", record.id)
                    put("content", record.content)
                    put("category", record.category)
                    put("record_json", record.toJson().toString())
                    put("created_at", record.createdAt)
                }
                db.insertOrThrow(TABLE_MEMORIES, null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun searchMemoryRecords(query: String, limit: Int = 20): List<VectorRecord> {
        val tokens = query.split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length > 1 }
            .distinct()
            .take(12)
        if (tokens.isEmpty()) return emptyList()
        val matchExpression = tokens.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }
        val records = mutableListOf<VectorRecord>()
        readableDatabase.rawQuery(
            """SELECT memories.record_json FROM $TABLE_MEMORY_FTS AS search
                JOIN $TABLE_MEMORIES AS memories ON memories.rowid = search.docid
                WHERE $TABLE_MEMORY_FTS MATCH ?
                LIMIT ?""".trimIndent(),
            arrayOf(matchExpression, limit.coerceIn(1, 100).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { VectorRecord.fromJson(org.json.JSONObject(cursor.getString(0))) }
                    .getOrNull()
                    ?.let(records::add)
            }
        }
        return records
    }

    fun searchConversationContext(
        query: String,
        excludeSessionId: String? = null,
        limit: Int = 4
    ): List<String> {
        val tokens = query.split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length > 1 }
            .distinct()
            .take(12)
        if (tokens.isEmpty()) return emptyList()
        val matchExpression = tokens.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }
        val context = mutableListOf<String>()
        readableDatabase.rawQuery(
            """SELECT sessions.title, messages.sender, messages.text FROM $TABLE_CHAT_FTS AS search
                JOIN $TABLE_MESSAGES AS messages ON messages.rowid = search.docid
                LEFT JOIN $TABLE_SESSIONS AS sessions ON sessions.$COL_SESSION_ID = messages.$COL_MSG_SESSION_ID
                WHERE $TABLE_CHAT_FTS MATCH ? AND (? IS NULL OR messages.$COL_MSG_SESSION_ID != ?)
                ORDER BY messages.$COL_MSG_TIMESTAMP DESC LIMIT ?""".trimIndent(),
            arrayOf(matchExpression, excludeSessionId, excludeSessionId, limit.coerceIn(1, 12).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val title = cursor.getString(0).orEmpty().take(80)
                val sender = cursor.getString(1).orEmpty()
                val text = cursor.getString(2).orEmpty()
                    .replace(Regex("\\s+"), " ")
                    .take(360)
                if (text.isNotBlank()) context.add("[$title] $sender: $text")
            }
        }
        return context
    }

    fun getAllSessions(): List<ChatSessionEntity> {
        val list = mutableListOf<ChatSessionEntity>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_SESSIONS ORDER BY $COL_SESSION_UPDATED DESC", null)
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    ChatSessionEntity(
                        id = it.getString(it.getColumnIndexOrThrow(COL_SESSION_ID)),
                        title = it.getString(it.getColumnIndexOrThrow(COL_SESSION_TITLE)),
                        createdAt = it.getLong(it.getColumnIndexOrThrow(COL_SESSION_CREATED)),
                        updatedAt = it.getLong(it.getColumnIndexOrThrow(COL_SESSION_UPDATED)),
                        summary = it.getString(it.getColumnIndexOrThrow(COL_SESSION_SUMMARY)) ?: ""
                    )
                )
            }
        }
        return list
    }

    fun getSession(sessionId: String): ChatSessionEntity? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_SESSIONS WHERE $COL_SESSION_ID = ?", arrayOf(sessionId))
        cursor.use {
            if (it.moveToFirst()) {
                return ChatSessionEntity(
                    id = it.getString(it.getColumnIndexOrThrow(COL_SESSION_ID)),
                    title = it.getString(it.getColumnIndexOrThrow(COL_SESSION_TITLE)),
                    createdAt = it.getLong(it.getColumnIndexOrThrow(COL_SESSION_CREATED)),
                    updatedAt = it.getLong(it.getColumnIndexOrThrow(COL_SESSION_UPDATED)),
                    summary = it.getString(it.getColumnIndexOrThrow(COL_SESSION_SUMMARY)) ?: ""
                )
            }
        }
        return null
    }

    fun insertSession(session: ChatSessionEntity) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SESSION_ID, session.id)
            put(COL_SESSION_TITLE, session.title)
            put(COL_SESSION_CREATED, session.createdAt)
            put(COL_SESSION_UPDATED, session.updatedAt)
            put(COL_SESSION_SUMMARY, session.summary)
        }
        db.insertWithOnConflict(TABLE_SESSIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateSessionTitle(sessionId: String, title: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SESSION_TITLE, title)
            put(COL_SESSION_UPDATED, System.currentTimeMillis())
        }
        db.update(TABLE_SESSIONS, values, "$COL_SESSION_ID = ?", arrayOf(sessionId))
    }

    fun getMessagesForSession(sessionId: String): List<ChatMessageEntity> {
        val list = mutableListOf<ChatMessageEntity>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT * FROM $TABLE_MESSAGES WHERE $COL_MSG_SESSION_ID = ? ORDER BY $COL_MSG_TIMESTAMP ASC", arrayOf(sessionId))
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    ChatMessageEntity(
                        id = it.getString(it.getColumnIndexOrThrow(COL_MSG_ID)),
                        sessionId = it.getString(it.getColumnIndexOrThrow(COL_MSG_SESSION_ID)),
                        sender = it.getString(it.getColumnIndexOrThrow(COL_MSG_SENDER)),
                        text = it.getString(it.getColumnIndexOrThrow(COL_MSG_TEXT)),
                        isError = it.getInt(it.getColumnIndexOrThrow(COL_MSG_IS_ERROR)) == 1,
                        timestamp = it.getLong(it.getColumnIndexOrThrow(COL_MSG_TIMESTAMP))
                    )
                )
            }
        }
        return list
    }

    fun insertMessage(msg: ChatMessageEntity) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_MSG_ID, msg.id)
            put(COL_MSG_SESSION_ID, msg.sessionId)
            put(COL_MSG_SENDER, msg.sender)
            put(COL_MSG_TEXT, msg.text)
            put(COL_MSG_IS_ERROR, if (msg.isError) 1 else 0)
            put(COL_MSG_TIMESTAMP, msg.timestamp)
        }
        db.insertWithOnConflict(TABLE_MESSAGES, null, values, SQLiteDatabase.CONFLICT_REPLACE)

        val sessionValues = ContentValues().apply {
            put(COL_SESSION_UPDATED, System.currentTimeMillis())
        }
        db.update(TABLE_SESSIONS, sessionValues, "$COL_SESSION_ID = ?", arrayOf(msg.sessionId))
    }

    fun saveFullSessionMessages(sessionId: String, title: String, logs: List<LogEntry>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val sValues = ContentValues().apply {
                put(COL_SESSION_ID, sessionId)
                put(COL_SESSION_TITLE, title)
                put(COL_SESSION_UPDATED, System.currentTimeMillis())
                put(COL_SESSION_CREATED, System.currentTimeMillis())
            }
            db.insertWithOnConflict(TABLE_SESSIONS, null, sValues, SQLiteDatabase.CONFLICT_REPLACE)
            db.execSQL("DELETE FROM $TABLE_MESSAGES WHERE $COL_MSG_SESSION_ID = ?", arrayOf(sessionId))

            for (log in logs) {
                val mValues = ContentValues().apply {
                    put(COL_MSG_ID, log.id)
                    put(COL_MSG_SESSION_ID, sessionId)
                    put(COL_MSG_SENDER, log.sender)
                    put(COL_MSG_TEXT, log.text)
                    put(COL_MSG_IS_ERROR, if (log.isError) 1 else 0)
                    put(COL_MSG_TIMESTAMP, log.timestamp)
                }
                db.insertWithOnConflict(TABLE_MESSAGES, null, mValues, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving session messages: ${e.message}")
        } finally {
            db.endTransaction()
        }
    }

    fun deleteSession(sessionId: String) {
        val db = writableDatabase
        db.delete(TABLE_MESSAGES, "$COL_MSG_SESSION_ID = ?", arrayOf(sessionId))
        db.delete(TABLE_SESSIONS, "$COL_SESSION_ID = ?", arrayOf(sessionId))
    }

    fun cleanupEmptySessions(exceptSessionId: String? = null) {
        val db = writableDatabase
        if (exceptSessionId != null) {
            db.execSQL("DELETE FROM $TABLE_SESSIONS WHERE $COL_SESSION_ID NOT IN (SELECT DISTINCT $COL_MSG_SESSION_ID FROM $TABLE_MESSAGES) AND $COL_SESSION_ID != ?", arrayOf(exceptSessionId))
        } else {
            db.execSQL("DELETE FROM $TABLE_SESSIONS WHERE $COL_SESSION_ID NOT IN (SELECT DISTINCT $COL_MSG_SESSION_ID FROM $TABLE_MESSAGES)")
        }
    }

    fun getRecentConversationsSummary(limitSessions: Int = 3): String {
        val sessions = getAllSessions().take(limitSessions)
        if (sessions.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("=== LONG-TERM BRAIN CONTEXT ===")
        for (session in sessions) {
            sb.appendLine("Session: ${session.title}")
            val msgs = getMessagesForSession(session.id).takeLast(4)
            for (m in msgs) {
                val snippet = if (m.text.length > 80) m.text.take(80) + "..." else m.text
                sb.appendLine("  ${m.sender}: $snippet")
            }
        }
        return sb.toString().trim()
    }
}
