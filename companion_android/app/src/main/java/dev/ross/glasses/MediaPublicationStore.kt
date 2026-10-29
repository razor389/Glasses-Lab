package dev.ross.glasses

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class MediaPublicationEntry(
  val sourcePath: String, val token: String, val uri: String?, val bytes: Long,
  val sha256: String, val kind: MediaKind, val displayName: String,
  val relativePath: String, val createdAt: Long, val state: String,
)

/** Durable publication intent exists before the first MediaStore insert. */
internal class MediaPublicationStore(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 1) {
  init { setWriteAheadLoggingEnabled(true) }
  override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("""CREATE TABLE publications (
      source_path TEXT PRIMARY KEY, token TEXT NOT NULL UNIQUE, uri TEXT,
      bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, kind TEXT NOT NULL,
      display_name TEXT NOT NULL, relative_path TEXT NOT NULL,
      created INTEGER NOT NULL, state TEXT NOT NULL
    )""".trimIndent())
    db.execSQL("CREATE INDEX publication_state ON publications(state)")
  }
  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    error("Unsupported media-publication database upgrade; originals retained.")
  }

  fun find(sourcePath: String): MediaPublicationEntry? = readableDatabase.query(
    "publications", null, "source_path=?", arrayOf(sourcePath), null, null, null,
  ).use { if (it.moveToFirst()) entry(it) else null }

  fun all(): List<MediaPublicationEntry> = readableDatabase.query(
    "publications", null, null, null, null, null, "created DESC, source_path DESC",
  ).use { rows -> buildList { while (rows.moveToNext()) add(entry(rows)) } }

  fun insert(entry: MediaPublicationEntry) {
    writableDatabase.insertOrThrow("publications", null, ContentValues().apply {
      put("source_path", entry.sourcePath); put("token", entry.token); put("uri", entry.uri)
      put("bytes", entry.bytes); put("sha256", entry.sha256); put("kind", entry.kind.name)
      put("display_name", entry.displayName); put("relative_path", entry.relativePath)
      put("created", entry.createdAt); put("state", entry.state)
    })
  }

  fun update(entry: MediaPublicationEntry, state: String = entry.state, uri: String? = entry.uri): MediaPublicationEntry {
    check(writableDatabase.update("publications", ContentValues().apply {
      put("state", state); put("uri", uri)
    }, "source_path=? AND token=?", arrayOf(entry.sourcePath, entry.token)) == 1) {
      "Publication identity changed; original retained."
    }
    return entry.copy(state = state, uri = uri)
  }

  private fun entry(row: Cursor) = MediaPublicationEntry(
    row.getString(row.getColumnIndexOrThrow("source_path")),
    row.getString(row.getColumnIndexOrThrow("token")),
    row.getColumnIndexOrThrow("uri").let { if (row.isNull(it)) null else row.getString(it) },
    row.getLong(row.getColumnIndexOrThrow("bytes")),
    row.getString(row.getColumnIndexOrThrow("sha256")),
    MediaKind.valueOf(row.getString(row.getColumnIndexOrThrow("kind"))),
    row.getString(row.getColumnIndexOrThrow("display_name")),
    row.getString(row.getColumnIndexOrThrow("relative_path")),
    row.getLong(row.getColumnIndexOrThrow("created")),
    row.getString(row.getColumnIndexOrThrow("state")),
  )
}
