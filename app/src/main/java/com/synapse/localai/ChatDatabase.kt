package com.synapse.localai

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.synapse.localai.memory.VectorRecord
import com.synapse.localai.AtlasLog as Log
import kotlin.math.sqrt

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
        private const val DATABASE_VERSION = 4

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
        private const val TABLE_MEMORY_PREFERENCES = "atlas_memory_preferences"
        private const val TABLE_MEMORY_FACTS = "atlas_memory_facts"
        private const val TABLE_AGENT_STATE = "atlas_agent_state"
        private const val TABLE_MEMORY_VECTOR_BUCKETS = "atlas_memory_vector_buckets"
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
        if (oldVersion < 4) upgradeMemoryStorage(db)
    }

    private fun createMemoryTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $TABLE_MEMORIES (
                id TEXT PRIMARY KEY,
                content TEXT NOT NULL,
                category TEXT NOT NULL,
                record_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                vector_int8 BLOB,
                vector_scale REAL NOT NULL DEFAULT 1.0,
                last_accessed_at INTEGER NOT NULL DEFAULT 0,
                access_count INTEGER NOT NULL DEFAULT 0
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
        createStructuredMemoryTables(db)
    }

    private fun upgradeMemoryStorage(db: SQLiteDatabase) {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($TABLE_MEMORIES)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex >= 0) columns.add(cursor.getString(nameIndex))
            }
        }
        if ("vector_int8" !in columns) db.execSQL("ALTER TABLE $TABLE_MEMORIES ADD COLUMN vector_int8 BLOB")
        if ("vector_scale" !in columns) db.execSQL("ALTER TABLE $TABLE_MEMORIES ADD COLUMN vector_scale REAL NOT NULL DEFAULT 1.0")
        if ("last_accessed_at" !in columns) db.execSQL("ALTER TABLE $TABLE_MEMORIES ADD COLUMN last_accessed_at INTEGER NOT NULL DEFAULT 0")
        if ("access_count" !in columns) db.execSQL("ALTER TABLE $TABLE_MEMORIES ADD COLUMN access_count INTEGER NOT NULL DEFAULT 0")
        createStructuredMemoryTables(db)
    }

    private fun createStructuredMemoryTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $TABLE_MEMORY_VECTOR_BUCKETS (
                memory_id TEXT NOT NULL,
                band INTEGER NOT NULL,
                signature INTEGER NOT NULL,
                PRIMARY KEY(memory_id, band),
                FOREIGN KEY(memory_id) REFERENCES $TABLE_MEMORIES(id) ON DELETE CASCADE
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_atlas_vector_bucket ON $TABLE_MEMORY_VECTOR_BUCKETS(band, signature)"
        )
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS atlas_memory_vector_buckets_delete
                AFTER DELETE ON $TABLE_MEMORIES BEGIN
                    DELETE FROM $TABLE_MEMORY_VECTOR_BUCKETS WHERE memory_id = old.id;
                END""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $TABLE_MEMORY_PREFERENCES (
                preference_key TEXT PRIMARY KEY,
                preference_value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $TABLE_MEMORY_FACTS (
                fact_key TEXT PRIMARY KEY,
                fact_value TEXT NOT NULL,
                category TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $TABLE_AGENT_STATE (
                state_key TEXT PRIMARY KEY,
                state_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_atlas_memory_access ON $TABLE_MEMORIES(category, access_count, last_accessed_at)"
        )
    }

    fun getMemoryRecords(): List<VectorRecord> {
        val records = mutableListOf<VectorRecord>()
        val recordsToQuantize = mutableListOf<VectorRecord>()
        readableDatabase.rawQuery(
            "SELECT record_json, vector_int8, vector_scale, last_accessed_at, access_count FROM $TABLE_MEMORIES ORDER BY created_at DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching {
                    val record = VectorRecord.fromJson(org.json.JSONObject(cursor.getString(0)))
                    val vectorIndex = cursor.getColumnIndex("vector_int8")
                    if (vectorIndex >= 0 && !cursor.isNull(vectorIndex)) {
                        val scaleIndex = cursor.getColumnIndexOrThrow("vector_scale")
                        record.vector = VectorRecord.dequantize(cursor.getBlob(vectorIndex), cursor.getFloat(scaleIndex))
                    } else {
                        recordsToQuantize.add(record)
                    }
                    record.lastAccessedAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_accessed_at"))
                    record.accessCount = cursor.getInt(cursor.getColumnIndexOrThrow("access_count"))
                    record
                }.getOrNull()?.let(records::add)
            }
        }
        recordsToQuantize.forEach(::upsertMemoryRecord)
        return records
    }

    fun upsertMemoryRecord(record: VectorRecord) {
        val (quantizedVector, scale) = VectorRecord.quantize(record.vector)
        val values = ContentValues().apply {
            put("id", record.id)
            put("content", record.content)
            put("category", record.category)
            put("record_json", record.toJson(includeVector = false).toString())
            put("created_at", record.createdAt)
            put("vector_int8", quantizedVector)
            put("vector_scale", scale)
            put("last_accessed_at", record.lastAccessedAt)
            put("access_count", record.accessCount)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_MEMORY_VECTOR_BUCKETS, "memory_id = ?", arrayOf(record.id))
            db.delete(TABLE_MEMORIES, "lower(content) = lower(?) AND id != ?", arrayOf(record.content, record.id))
            val updated = db.update(TABLE_MEMORIES, values, "id = ?", arrayOf(record.id))
            if (updated == 0) db.insertOrThrow(TABLE_MEMORIES, null, values)
            vectorBuckets(record.vector).forEachIndexed { band, signature ->
                val bucketValues = ContentValues().apply {
                    put("memory_id", record.id)
                    put("band", band)
                    put("signature", signature)
                }
                db.insertOrThrow(TABLE_MEMORY_VECTOR_BUCKETS, null, bucketValues)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun recordMemoryAccess(ids: Collection<String>, accessedAt: Long) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("last_accessed_at", accessedAt)
            }
            ids.forEach { id ->
                db.update(TABLE_MEMORIES, values, "id = ?", arrayOf(id))
                db.execSQL("UPDATE $TABLE_MEMORIES SET access_count = access_count + 1 WHERE id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun searchSimilarMemoryRecords(
        queryVector: FloatArray,
        limit: Int,
        minThreshold: Float
    ): List<Pair<VectorRecord, Float>> =
        searchSimilarMemoryRecordsInternal(queryVector, limit, minThreshold, upgradeMissingVectors = true)

    private fun searchSimilarMemoryRecordsInternal(
        queryVector: FloatArray,
        limit: Int,
        minThreshold: Float,
        upgradeMissingVectors: Boolean
    ): List<Pair<VectorRecord, Float>> {
        if (queryVector.isEmpty() || limit <= 0) return emptyList()
        val queryNorm = queryVector.sumOf {
            val value = it.toDouble()
            value * value
        }
        if (queryNorm <= 0.0) return emptyList()

        val signatures = vectorBuckets(queryVector)
        val where = signatures.indices.joinToString(" OR ") { "(b.band = ? AND b.signature = ?)" }
        val selectionArgs = signatures.flatMapIndexed { band, signature ->
            listOf(band.toString(), signature.toString())
        }.toTypedArray()
        val candidateIds = mutableListOf<String>()
        readableDatabase.rawQuery(
            """SELECT DISTINCT b.memory_id
                FROM $TABLE_MEMORY_VECTOR_BUCKETS AS b
                JOIN $TABLE_MEMORIES AS m ON m.id = b.memory_id
                WHERE $where""".trimIndent(),
            selectionArgs
        ).use { cursor ->
            while (cursor.moveToNext()) candidateIds.add(cursor.getString(0))
        }
        if (candidateIds.size < limit) {
            readableDatabase.rawQuery("SELECT id FROM $TABLE_MEMORIES", null).use { cursor ->
                while (cursor.moveToNext()) candidateIds.add(cursor.getString(0))
            }
        }

        data class Candidate(val id: String, val score: Float)
        val candidates = mutableListOf<Candidate>()
        var hasUnquantizedRecords = false
        val uniqueIds = candidateIds.distinct()
        uniqueIds.chunked(900).forEach { ids ->
            if (ids.isEmpty()) return@forEach
            val placeholders = ids.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT id, vector_int8, vector_scale FROM $TABLE_MEMORIES WHERE id IN ($placeholders)",
                ids.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.isNull(1)) {
                        hasUnquantizedRecords = true
                        continue
                    }
                    val vector = cursor.getBlob(1)
                    val length = minOf(vector.size, queryVector.size)
                    val scale = cursor.getFloat(2)
                    var dot = 0.0
                    var vectorNorm = 0.0
                    for (index in 0 until length) {
                        val value = vector[index].toInt() * scale
                        dot += queryVector[index] * value
                        vectorNorm += value * value
                    }
                    if (vectorNorm <= 0.0) continue
                    val similarity = (dot / sqrt(queryNorm * vectorNorm)).toFloat()
                    if (similarity >= minThreshold) {
                        candidates.add(Candidate(cursor.getString(0), similarity))
                    }
                }
            }
        }
        if (hasUnquantizedRecords && upgradeMissingVectors) {
            getMemoryRecords()
            return searchSimilarMemoryRecordsInternal(queryVector, limit, minThreshold, upgradeMissingVectors = false)
        }

        val matches = candidates.sortedByDescending { it.score }.take(limit)
        val records = matches.mapNotNull { candidate ->
            val record = readableDatabase.rawQuery(
                "SELECT record_json, vector_int8, vector_scale, last_accessed_at, access_count FROM $TABLE_MEMORIES WHERE id = ?",
                arrayOf(candidate.id)
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                runCatching {
                    readVectorRecord(cursor)
                }.getOrNull()
            }
            record?.let { it to candidate.score }
        }
        recordMemoryAccess(records.map { it.first.id }, System.currentTimeMillis())
        return records
    }

    private fun readVectorRecord(cursor: Cursor): VectorRecord =
        VectorRecord.fromJson(org.json.JSONObject(cursor.getString(0))).apply {
            val vectorIndex = cursor.getColumnIndex("vector_int8")
            if (vectorIndex >= 0 && !cursor.isNull(vectorIndex)) {
                vector = VectorRecord.dequantize(
                    cursor.getBlob(vectorIndex),
                    cursor.getFloat(cursor.getColumnIndexOrThrow("vector_scale"))
                )
            }
            val accessedIndex = cursor.getColumnIndex("last_accessed_at")
            if (accessedIndex >= 0) lastAccessedAt = cursor.getLong(accessedIndex)
            val countIndex = cursor.getColumnIndex("access_count")
            if (countIndex >= 0) accessCount = cursor.getInt(countIndex)
        }

    private fun vectorBuckets(vector: FloatArray): List<Int> {
        val bandSize = 16
        val bandCount = 8
        return List(bandCount) { band ->
            var signature = 0
            repeat(bandSize) { bit ->
                val index = band * bandSize + bit
                if (index < vector.size && vector[index] >= 0f) signature = signature or (1 shl bit)
            }
            signature
        }
    }

    fun saveMemoryPreference(key: String, value: String, updatedAt: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply {
            put("preference_key", key)
            put("preference_value", value)
            put("updated_at", updatedAt)
        }
        writableDatabase.insertWithOnConflict(TABLE_MEMORY_PREFERENCES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getMemoryPreferences(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        readableDatabase.rawQuery(
            "SELECT preference_key, preference_value FROM $TABLE_MEMORY_PREFERENCES ORDER BY updated_at DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) result[cursor.getString(0)] = cursor.getString(1)
        }
        return result
    }

    fun saveMemoryFact(key: String, value: String, category: String, updatedAt: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply {
            put("fact_key", key)
            put("fact_value", value)
            put("category", category)
            put("updated_at", updatedAt)
        }
        writableDatabase.insertWithOnConflict(TABLE_MEMORY_FACTS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getMemoryFacts(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        readableDatabase.rawQuery(
            "SELECT fact_key, fact_value FROM $TABLE_MEMORY_FACTS ORDER BY updated_at DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.getString(0) to cursor.getString(1))
        }
        return result
    }

    fun saveAgentState(key: String, json: String, updatedAt: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply {
            put("state_key", key)
            put("state_json", json)
            put("updated_at", updatedAt)
        }
        writableDatabase.insertWithOnConflict(TABLE_AGENT_STATE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun saveAgentMind(json: String, preferences: List<String>, facts: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_MEMORY_PREFERENCES, null, null)
            db.delete(TABLE_MEMORY_FACTS, null, null)
            val now = System.currentTimeMillis()
            preferences.forEachIndexed { index, value ->
                val values = ContentValues().apply {
                    put("preference_key", "user_preference_$index")
                    put("preference_value", value)
                    put("updated_at", now + index)
                }
                db.insertOrThrow(TABLE_MEMORY_PREFERENCES, null, values)
            }
            facts.forEachIndexed { index, value ->
                val values = ContentValues().apply {
                    put("fact_key", "learned_fact_$index")
                    put("fact_value", value)
                    put("category", "LEARNED_FACT")
                    put("updated_at", now + index)
                }
                db.insertOrThrow(TABLE_MEMORY_FACTS, null, values)
            }
            val stateValues = ContentValues().apply {
                put("state_key", "agent_mind")
                put("state_json", json)
                put("updated_at", now)
            }
            db.insertWithOnConflict(TABLE_AGENT_STATE, null, stateValues, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAgentState(key: String): String? =
        readableDatabase.rawQuery(
            "SELECT state_json FROM $TABLE_AGENT_STATE WHERE state_key = ? LIMIT 1",
            arrayOf(key)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun deleteMemoryRecord(id: String): Boolean =
        writableDatabase.delete(TABLE_MEMORIES, "id = ?", arrayOf(id)) > 0

    fun clearMemoryRecords() {
        writableDatabase.delete(TABLE_MEMORIES, null, null)
    }

    fun pruneMemoryRecords(cutoff: Long, maxRecords: Int): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val preservedCategories = "'USER_PREFERENCE','CORE_MEMORY'"
            var removed = db.delete(
                TABLE_MEMORIES,
                "created_at < ? AND category NOT IN ($preservedCategories)",
                arrayOf(cutoff.toString())
            )
            db.execSQL(
                """DELETE FROM $TABLE_MEMORIES
                    WHERE category NOT IN ($preservedCategories)
                    AND id IN (
                        SELECT id FROM $TABLE_MEMORIES
                        WHERE category NOT IN ($preservedCategories)
                        ORDER BY access_count ASC, last_accessed_at ASC, created_at ASC
                        LIMIT MAX(0, (SELECT COUNT(*) FROM $TABLE_MEMORIES WHERE category NOT IN ($preservedCategories)) - ?)
                    )""".trimIndent(),
                arrayOf(maxRecords.coerceAtLeast(0))
            )
            db.rawQuery("SELECT changes()", null).use { cursor ->
                if (cursor.moveToFirst()) removed += cursor.getInt(0)
            }
            db.setTransactionSuccessful()
            return removed
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
            """SELECT memories.record_json, memories.vector_int8, memories.vector_scale,
                    memories.last_accessed_at, memories.access_count
                FROM $TABLE_MEMORY_FTS AS search
                JOIN $TABLE_MEMORIES AS memories ON memories.rowid = search.docid
                WHERE $TABLE_MEMORY_FTS MATCH ?
                LIMIT ?""".trimIndent(),
            arrayOf(matchExpression, limit.coerceIn(1, 100).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { readVectorRecord(cursor) }
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
