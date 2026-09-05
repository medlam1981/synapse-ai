package com.synapse.localai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.UUID

/**
 * ChatSessionEntity — Persistent record of a conversation session.
 */
data class ChatSessionEntity(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val summary: String = ""
)

/**
 * ChatMessageEntity — Persistent record of a single message within a session.
 */
data class ChatMessageEntity(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val sender: String,
    val text: String,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * ChatDatabaseHelper — SQLite storage engine for Chat Sessions, Messages, and Agent Long-Term Memory.
 */
class ChatDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "ChatDatabase"
        private const val DATABASE_NAME = "medlam_ai_chat.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_SESSIONS = "chat_sessions"
        private const val TABLE_MESSAGES = "chat_messages"

        private const val COL_SESSION_ID = "id"
        private const val COL_SESSION_TITLE = "title"
        private const val COL_SESSION_CREATED = "created_at"
        private const val COL_SESSION_UPDATED = "updated_at"
        private const val COL_SESSION_SUMMARY = "summary"

        private const val COL_MSG_ID = "id"
        private const val COL_MSG_SESSION_ID = "session_id"
        private const val COL_MSG_SENDER = "sender"
        private const val COL_MSG_TEXT = "text"
        private const val COL_MSG_IS_ERROR = "is_error"
        private const val COL_MSG_TIMESTAMP = "timestamp"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_SESSIONS (
                $COL_SESSION_ID TEXT PRIMARY KEY,
                $COL_SESSION_TITLE TEXT NOT NULL,
                $COL_SESSION_CREATED INTEGER NOT NULL,
                $COL_SESSION_UPDATED INTEGER NOT NULL,
                $COL_SESSION_SUMMARY TEXT DEFAULT ''
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_MESSAGES (
                $COL_MSG_ID TEXT PRIMARY KEY,
                $COL_MSG_SESSION_ID TEXT NOT NULL,
                $COL_MSG_SENDER TEXT NOT NULL,
                $COL_MSG_TEXT TEXT NOT NULL,
                $COL_MSG_IS_ERROR INTEGER DEFAULT 0,
                $COL_MSG_TIMESTAMP INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg_session ON $TABLE_MESSAGES($COL_MSG_SESSION_ID, $COL_MSG_TIMESTAMP ASC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_updated ON $TABLE_SESSIONS($COL_SESSION_UPDATED DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Upgrades handled gracefully
    }

    fun getAllSessions(): List<ChatSessionEntity> {
        val list = mutableListOf<ChatSessionEntity>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SESSIONS,
            null,
            null,
            null,
            null,
            null,
            "$COL_SESSION_UPDATED DESC"
        )
        cursor.use {
            val idIdx = cursor.getColumnIndexOrThrow(COL_SESSION_ID)
            val titleIdx = cursor.getColumnIndexOrThrow(COL_SESSION_TITLE)
            val createdIdx = cursor.getColumnIndexOrThrow(COL_SESSION_CREATED)
            val updatedIdx = cursor.getColumnIndexOrThrow(COL_SESSION_UPDATED)
            val summaryIdx = cursor.getColumnIndexOrThrow(COL_SESSION_SUMMARY)

            while (cursor.moveToNext()) {
                list.add(
                    ChatSessionEntity(
                        id = cursor.getString(idIdx),
                        title = cursor.getString(titleIdx),
                        createdAt = cursor.getLong(createdIdx),
                        updatedAt = cursor.getLong(updatedIdx),
                        summary = cursor.getString(summaryIdx) ?: ""
                    )
                )
            }
        }
        return list
    }

    fun getSession(sessionId: String): ChatSessionEntity? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SESSIONS,
            null,
            "$COL_SESSION_ID = ?",
            arrayOf(sessionId),
            null,
            null,
            null
        )
        cursor.use {
            if (cursor.moveToFirst()) {
                return ChatSessionEntity(
                    id = cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_ID)),
                    title = cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_TITLE)),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_SESSION_CREATED)),
                    updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_SESSION_UPDATED)),
                    summary = cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_SUMMARY)) ?: ""
                )
            }
        }
        return null
    }

    fun insertSession(session: ChatSessionEntity) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_SESSION_ID, session.id)
            put(COL_SESSION_TITLE, session.title)
            put(COL_SESSION_CREATED, session.createdAt)
            put(COL_SESSION_UPDATED, session.updatedAt)
            put(COL_SESSION_SUMMARY, session.summary)
        }
        db.insertWithOnConflict(TABLE_SESSIONS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateSessionTitle(sessionId: String, title: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_SESSION_TITLE, title)
            put(COL_SESSION_UPDATED, System.currentTimeMillis())
        }
        db.update(TABLE_SESSIONS, cv, "$COL_SESSION_ID = ?", arrayOf(sessionId))
    }

    fun updateSessionTimestamp(sessionId: String, timestamp: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_SESSION_UPDATED, timestamp)
        }
        db.update(TABLE_SESSIONS, cv, "$COL_SESSION_ID = ?", arrayOf(sessionId))
    }

    fun deleteSession(sessionId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_MESSAGES, "$COL_MSG_SESSION_ID = ?", arrayOf(sessionId))
            db.delete(TABLE_SESSIONS, "$COL_SESSION_ID = ?", arrayOf(sessionId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun cleanupEmptySessions(exceptSessionId: String? = null) {
        val db = writableDatabase
        try {
            // Purge any legacy telemetry messages erroneously saved as chat messages
            db.delete(TABLE_MESSAGES, "$COL_MSG_TEXT LIKE '⚡ Execution Complete%' OR $COL_MSG_TEXT LIKE '⚡ Generation Chunk%'", null)

            val whereClause = if (exceptSessionId.isNullOrBlank()) {
                "$COL_SESSION_ID NOT IN (SELECT DISTINCT $COL_MSG_SESSION_ID FROM $TABLE_MESSAGES)"
            } else {
                "$COL_SESSION_ID != ? AND $COL_SESSION_ID NOT IN (SELECT DISTINCT $COL_MSG_SESSION_ID FROM $TABLE_MESSAGES)"
            }
            val whereArgs = if (exceptSessionId.isNullOrBlank()) null else arrayOf(exceptSessionId)
            db.delete(TABLE_SESSIONS, whereClause, whereArgs)
        } catch (e: Exception) {
            Log.e(TAG, "cleanupEmptySessions error: ${e.message}")
        }
    }

    fun getMessagesForSession(sessionId: String): List<ChatMessageEntity> {
        val list = mutableListOf<ChatMessageEntity>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_MESSAGES,
            null,
            "$COL_MSG_SESSION_ID = ?",
            arrayOf(sessionId),
            null,
            null,
            "$COL_MSG_TIMESTAMP ASC"
        )
        cursor.use {
            val idIdx = cursor.getColumnIndexOrThrow(COL_MSG_ID)
            val sessIdIdx = cursor.getColumnIndexOrThrow(COL_MSG_SESSION_ID)
            val senderIdx = cursor.getColumnIndexOrThrow(COL_MSG_SENDER)
            val textIdx = cursor.getColumnIndexOrThrow(COL_MSG_TEXT)
            val errorIdx = cursor.getColumnIndexOrThrow(COL_MSG_IS_ERROR)
            val timeIdx = cursor.getColumnIndexOrThrow(COL_MSG_TIMESTAMP)

            while (cursor.moveToNext()) {
                val text = cursor.getString(textIdx)
                if (text.startsWith("⚡ Execution Complete") || text.startsWith("⚡ Generation Chunk")) {
                    continue
                }
                list.add(
                    ChatMessageEntity(
                        id = cursor.getString(idIdx),
                        sessionId = cursor.getString(sessIdIdx),
                        sender = cursor.getString(senderIdx),
                        text = text,
                        isError = cursor.getInt(errorIdx) == 1,
                        timestamp = cursor.getLong(timeIdx)
                    )
                )
            }
        }
        return list
    }

    fun insertMessage(message: ChatMessageEntity) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_MSG_ID, message.id)
            put(COL_MSG_SESSION_ID, message.sessionId)
            put(COL_MSG_SENDER, message.sender)
            put(COL_MSG_TEXT, message.text)
            put(COL_MSG_IS_ERROR, if (message.isError) 1 else 0)
            put(COL_MSG_TIMESTAMP, message.timestamp)
        }
        db.insertWithOnConflict(TABLE_MESSAGES, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        updateSessionTimestamp(message.sessionId, message.timestamp)
    }

    fun saveFullSessionMessages(sessionId: String, title: String, logs: List<LogEntry>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val sessionCv = ContentValues().apply {
                put(COL_SESSION_ID, sessionId)
                put(COL_SESSION_TITLE, title)
                put(COL_SESSION_UPDATED, now)
            }
            val rows = db.update(TABLE_SESSIONS, sessionCv, "$COL_SESSION_ID = ?", arrayOf(sessionId))
            if (rows == 0) {
                sessionCv.put(COL_SESSION_CREATED, now)
                sessionCv.put(COL_SESSION_SUMMARY, "")
                db.insert(TABLE_SESSIONS, null, sessionCv)
            }

            db.delete(TABLE_MESSAGES, "$COL_MSG_SESSION_ID = ?", arrayOf(sessionId))
            logs.forEach { log ->
                val cv = ContentValues().apply {
                    put(COL_MSG_ID, log.id)
                    put(COL_MSG_SESSION_ID, sessionId)
                    put(COL_MSG_SENDER, log.sender)
                    put(COL_MSG_TEXT, log.text)
                    put(COL_MSG_IS_ERROR, if (log.isError) 1 else 0)
                    put(COL_MSG_TIMESTAMP, log.timestamp)
                }
                db.insert(TABLE_MESSAGES, null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getRecentConversationsSummary(limitSessions: Int = 3): String {
        val sessions = getAllSessions().take(limitSessions)
        if (sessions.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("=== LONG-TERM BRAIN CONTEXT ===")
        sessions.forEach { session ->
            sb.appendLine("Session: ${session.title}")
            val msgs = getMessagesForSession(session.id).takeLast(4)
            msgs.forEach { m ->
                val snippet = if (m.text.length > 80) m.text.take(80) + "..." else m.text
                sb.appendLine("  ${m.sender}: $snippet")
            }
        }
        sb.appendLine("=== END LONG-TERM BRAIN CONTEXT ===")
        return sb.toString()
    }
}
