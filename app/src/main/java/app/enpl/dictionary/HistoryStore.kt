package app.enpl.dictionary

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class HistoryItem(
    val word: String,
    val count: Int,
    val lastUsed: Long
)

internal class HistoryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE history(
                word TEXT PRIMARY KEY COLLATE NOCASE,
                count INTEGER NOT NULL DEFAULT 1,
                last_used INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun record(word: String) {
        val now = System.currentTimeMillis()
        writableDatabase.beginTransaction()
        try {
            val updated = writableDatabase.compileStatement(
                "UPDATE history SET count=count+1, last_used=? WHERE word=? COLLATE NOCASE"
            ).apply {
                bindLong(1, now)
                bindString(2, word)
            }.executeUpdateDelete()

            if (updated == 0) {
                writableDatabase.insert(
                    "history", null,
                    ContentValues().apply {
                        put("word", word)
                        put("count", 1)
                        put("last_used", now)
                    }
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun list(sort: String): List<HistoryItem> {
        val order = when (sort) {
            "alpha" -> "word COLLATE NOCASE ASC"
            "count" -> "count DESC, last_used DESC"
            else -> "last_used DESC"
        }
        val out = ArrayList<HistoryItem>()
        readableDatabase.query("history", arrayOf("word", "count", "last_used"), null, null, null, null, order)
            .use { c ->
                while (c.moveToNext()) {
                    out += HistoryItem(c.getString(0), c.getInt(1), c.getLong(2))
                }
            }
        return out
    }

    fun delete(words: Collection<String>) {
        writableDatabase.beginTransaction()
        try {
            for (word in words) {
                writableDatabase.delete("history", "word=? COLLATE NOCASE", arrayOf(word))
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun clear() {
        writableDatabase.delete("history", null, null)
    }
}
