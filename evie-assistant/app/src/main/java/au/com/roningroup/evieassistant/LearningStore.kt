package au.com.roningroup.evieassistant

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

class LearningStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "evie_memory.db", null, 3) {

    companion object {
        @Volatile private var instance: LearningStore? = null

        fun get(context: Context): LearningStore =
            instance ?: synchronized(this) {
                instance ?: LearningStore(context).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                content TEXT NOT NULL,
                importance INTEGER NOT NULL DEFAULT 5,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE action_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_command TEXT,
                tool_name TEXT NOT NULL,
                arguments TEXT,
                result TEXT,
                success INTEGER NOT NULL,
                package_name TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE routines (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                trigger_phrase TEXT,
                description TEXT NOT NULL,
                use_count INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE conversation (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE voice_scripts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                source_model TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX idx_memories_kind ON memories(kind)")
        db.execSQL("CREATE INDEX idx_actions_tool ON action_history(tool_name)")
        db.execSQL("CREATE INDEX idx_actions_created ON action_history(created_at)")
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS conversation (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        if (oldVersion < 3) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS voice_scripts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    source_model TEXT,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }


    fun addConversationTurn(
        role: String,
        content: String
    ) {
        val clean = content.trim()
        if (clean.isBlank()) return

        val values = ContentValues().apply {
            put("role", role.trim().ifBlank { "user" })
            put("content", clean.take(12000))
            put("created_at", System.currentTimeMillis())
        }

        writableDatabase.insert(
            "conversation",
            null,
            values
        )

        writableDatabase.execSQL(
            """
            DELETE FROM conversation
            WHERE id NOT IN (
                SELECT id FROM conversation
                ORDER BY id DESC
                LIMIT 24
            )
            """.trimIndent()
        )
    }

    fun recentConversation(limit: Int = 10): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()

        readableDatabase.rawQuery(
            """
            SELECT role, content
            FROM conversation
            ORDER BY id DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.coerceIn(1, 24).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursor.getString(0) to cursor.getString(1)
            }
        }

        rows.reverse()
        return rows
    }


    fun saveVoiceScript(
        title: String,
        content: String,
        sourceModel: String
    ): Long {
        val clean = content.trim()
        if (clean.isBlank()) return -1

        val values = ContentValues().apply {
            put(
                "title",
                title.trim().ifBlank {
                    "Evie Voice Script"
                }
            )
            put("content", clean)
            put("source_model", sourceModel.trim())
            put("created_at", System.currentTimeMillis())
        }

        return writableDatabase.insert(
            "voice_scripts",
            null,
            values
        )
    }

    fun voiceScriptCount(): Long =
        count("voice_scripts")

    fun remember(
        content: String,
        kind: String = "general",
        importance: Int = 5
    ): Long {
        val clean = content.trim()
        if (clean.isBlank()) return -1

        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("kind", kind.trim().ifBlank { "general" })
            put("content", clean)
            put("importance", importance.coerceIn(1, 10))
            put("created_at", now)
            put("updated_at", now)
        }

        return writableDatabase.insert("memories", null, values)
    }

    fun forget(query: String): Int {
        val q = query.trim()
        if (q.isBlank()) return 0
        val needle = "%" + q.lowercase(Locale.ROOT) + "%"

        return writableDatabase.delete(
            "memories",
            "LOWER(content) LIKE ? OR LOWER(kind) LIKE ?",
            arrayOf(needle, needle)
        )
    }

    fun saveRoutine(
        name: String,
        trigger: String,
        description: String
    ): String {
        val cleanName = name.trim()
        val cleanDescription = description.trim()

        if (cleanName.isBlank() || cleanDescription.isBlank()) {
            return "ERROR: Routine needs a name and description."
        }

        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("name", cleanName)
            put("trigger_phrase", trigger.trim())
            put("description", cleanDescription)
            put("updated_at", now)
        }

        val existing = writableDatabase.query(
            "routines",
            arrayOf("id"),
            "LOWER(name) = LOWER(?)",
            arrayOf(cleanName),
            null,
            null,
            null,
            "1"
        )

        existing.use {
            if (it.moveToFirst()) {
                writableDatabase.update(
                    "routines",
                    values,
                    "id = ?",
                    arrayOf(it.getLong(0).toString())
                )
                return "OK: Updated routine \"" + cleanName + "\"."
            }
        }

        values.put("created_at", now)
        values.put("use_count", 0)

        writableDatabase.insert("routines", null, values)
        return "OK: Learned routine \"" + cleanName + "\"."
    }

    fun findRoutine(query: String): String {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return "No routine query supplied."
        val needle = "%" + q + "%"

        val cursor = readableDatabase.query(
            "routines",
            arrayOf("id", "name", "trigger_phrase", "description", "use_count"),
            "LOWER(name) LIKE ? OR LOWER(trigger_phrase) LIKE ? OR LOWER(description) LIKE ?",
            arrayOf(needle, needle, needle),
            null,
            null,
            "use_count DESC, updated_at DESC",
            "5"
        )

        val rows = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                rows += "Routine #" + it.getLong(0) + " " + it.getString(1) +
                    " — trigger=\"" + it.getString(2).orEmpty() + "\" — " + it.getString(3)
            }
        }

        return if (rows.isEmpty()) "No learned routine matched \"" + query + "\"."
        else rows.joinToString("\n")
    }

    fun markRoutineUsed(name: String) {
        writableDatabase.execSQL(
            """
            UPDATE routines
            SET use_count = use_count + 1,
                updated_at = ?
            WHERE LOWER(name) = LOWER(?)
            """.trimIndent(),
            arrayOf(System.currentTimeMillis(), name.trim())
        )
    }

    fun logAction(
        userCommand: String,
        toolName: String,
        arguments: String,
        result: String,
        packageName: String? = null
    ) {
        val values = ContentValues().apply {
            put("user_command", userCommand.take(2000))
            put("tool_name", toolName)
            put("arguments", arguments.take(4000))
            put("result", result.take(4000))
            put(
                "success",
                if (result.startsWith("OK:", ignoreCase = true)) 1 else 0
            )
            put("package_name", packageName.orEmpty())
            put("created_at", System.currentTimeMillis())
        }

        writableDatabase.insert("action_history", null, values)

        writableDatabase.execSQL(
            """
            DELETE FROM action_history
            WHERE id NOT IN (
                SELECT id FROM action_history
                ORDER BY id DESC
                LIMIT 1200
            )
            """.trimIndent()
        )
    }

    fun contextFor(query: String): String {
        val tokens = query
            .lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .distinct()
            .take(8)

        val memoryRows = mutableListOf<String>()

        if (tokens.isNotEmpty()) {
            val where = tokens.joinToString(" OR ") { "LOWER(content) LIKE ?" }
            val args = tokens.map { "%" + it + "%" }.toTypedArray()

            val cursor = readableDatabase.query(
                "memories",
                arrayOf("id", "kind", "content", "importance"),
                where,
                args,
                null,
                null,
                "importance DESC, updated_at DESC",
                "12"
            )

            cursor.use {
                while (it.moveToNext()) {
                    memoryRows +=
                        "#" + it.getLong(0) + " [" + it.getString(1) + "] " + it.getString(2)
                }
            }
        }

        val routineRows = mutableListOf<String>()
        if (tokens.isNotEmpty()) {
            val clauses = mutableListOf<String>()
            val args = mutableListOf<String>()

            tokens.forEach { token ->
                clauses += "(LOWER(name) LIKE ? OR LOWER(trigger_phrase) LIKE ? OR LOWER(description) LIKE ?)"
                val needle = "%" + token + "%"
                args += needle
                args += needle
                args += needle
            }

            val cursor = readableDatabase.query(
                "routines",
                arrayOf("name", "trigger_phrase", "description", "use_count"),
                clauses.joinToString(" OR "),
                args.toTypedArray(),
                null,
                null,
                "use_count DESC, updated_at DESC",
                "6"
            )

            cursor.use {
                while (it.moveToNext()) {
                    routineRows +=
                        it.getString(0) + " — trigger=\"" +
                            it.getString(1).orEmpty() + "\" — " + it.getString(2)
                }
            }
        }

        val actionRows = mutableListOf<String>()
        val cursor = readableDatabase.query(
            "action_history",
            arrayOf("tool_name", "arguments", "result"),
            "success = 1",
            null,
            null,
            null,
            "created_at DESC",
            "12"
        )

        cursor.use {
            while (it.moveToNext()) {
                actionRows +=
                    it.getString(0) + "(" + it.getString(1) + ") -> " + it.getString(2)
            }
        }

        return buildString {
            if (memoryRows.isNotEmpty()) {
                append("RELEVANT LONG-TERM MEMORY:\n")
                append(memoryRows.joinToString("\n"))
                append("\n\n")
            }

            if (routineRows.isNotEmpty()) {
                append("LEARNED ROUTINES:\n")
                append(routineRows.joinToString("\n"))
                append("\n\n")
            }

            if (actionRows.isNotEmpty()) {
                append("RECENT SUCCESSFUL PHONE ACTIONS (use these as hints, not guarantees):\n")
                append(actionRows.joinToString("\n"))
            }
        }.trim()
    }

    fun summary(): String {
        val memoryCount = count("memories")
        val routineCount = count("routines")
        val actionCount = count("action_history")
        val conversationCount = count("conversation")
        val voiceScriptCount = count("voice_scripts")

        return "Evie memory: " + memoryCount + " memories, " +
            routineCount + " routines, " +
            actionCount + " logged actions, " +
            conversationCount + " recent conversation turns, " +
            voiceScriptCount + " saved voice scripts."
    }

    private fun count(table: String): Long {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM " + table,
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0
        }
    }
}
