package dev.ross.glasses

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

data class SavedVideoSession(
  val id: String, val createdAt: Long, val state: String, val reason: String,
  val bytes: Long, val segments: Int,
)

data class SavedVideoSegment(
  val sessionId: String, val index: Int, val sourcePath: String, val uri: String,
  val bytes: Long, val sha256: String,
) { val file: File get() = File(sourcePath) }

/** Durable ordered membership survives source reclamation and interrupted receipt replies. */
class VideoSessionStore(context: Context, databaseName: String = "video-sessions.sqlite") :
  SQLiteOpenHelper(context, databaseName, null, 1) {
  init { setWriteAheadLoggingEnabled(true) }

  override fun onConfigure(db: SQLiteDatabase) {
    db.setForeignKeyConstraintsEnabled(true)
    db.execSQL("PRAGMA synchronous=FULL")
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE video_sessions (id TEXT PRIMARY KEY, created INTEGER NOT NULL, state TEXT NOT NULL, reason TEXT NOT NULL)")
    db.execSQL("CREATE TABLE video_segments (session TEXT NOT NULL REFERENCES video_sessions(id), sequence INTEGER NOT NULL, source TEXT NOT NULL UNIQUE, uri TEXT NOT NULL, bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, PRIMARY KEY(session,sequence))")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    error("Unsupported video session database upgrade; saved media retained.")
  }

  @Synchronized fun begin(id: String, createdAt: Long = System.currentTimeMillis()) {
    VideoSegmentMetadata(id, 0)
    insertSession(id, createdAt, "RECORDING", "")
    readableDatabase.rawQuery("SELECT state FROM video_sessions WHERE id=?", arrayOf(id)).use {
      check(it.moveToFirst() && it.getString(0) == "RECORDING") { "This video session has already ended." }
    }
  }

  @Synchronized fun finish(id: String, reason: String = "") {
    VideoSegmentMetadata(id, 0)
    insertSession(id, System.currentTimeMillis(), "FINISHED", reason)
    writableDatabase.update("video_sessions", ContentValues().apply {
      put("state", "FINISHED"); put("reason", reason)
    }, "id=?", arrayOf(id))
  }

  /** A restored live request is reconciled with glasses by its controller; other sessions never restart. */
  @Synchronized fun recover(activeId: String? = null) {
    writableDatabase.update("video_sessions", ContentValues().apply {
      put("state", "INTERRUPTED"); put("reason", "The phone restarted. Completed video parts are retained.")
    }, if (activeId == null) "state='RECORDING'" else "state='RECORDING' AND id<>?",
      activeId?.let { arrayOf(it) })
  }

  /** Called only after the public copy is verified, before requesting glasses reclamation. */
  @Synchronized fun register(remote: RemoteMedia, published: PublishedMedia) {
    val part = remote.videoSegment ?: return
    val source = published.file.canonicalFile
    require(!remote.photo && published.kind == MediaKind.VIDEO && source.name == remote.localName &&
      published.bytes == remote.bytes && published.sha256.matches(Regex("[0-9a-f]{64}"))) {
      "Video session part does not match its verified publication."
    }
    val db = writableDatabase
    db.beginTransaction()
    try {
      insertSession(part.sessionId, published.createdAt, "RECOVERED", "Recovered from completed glasses video parts.")
      db.insertWithOnConflict("video_segments", null, ContentValues().apply {
        put("session", part.sessionId); put("sequence", part.index); put("source", source.path)
        put("uri", published.uri.toString()); put("bytes", published.bytes); put("sha256", published.sha256)
      }, SQLiteDatabase.CONFLICT_IGNORE)
      db.rawQuery("SELECT source,bytes,sha256 FROM video_segments WHERE session=? AND sequence=?",
        arrayOf(part.sessionId, part.index.toString())).use {
        check(it.moveToFirst() && it.getString(0) == source.path && it.getLong(1) == published.bytes &&
          it.getString(2) == published.sha256) { "Conflicting video part identity; glasses source retained." }
      }
      // The publication journal verified this exact copy; a legitimate URI change keeps membership intact.
      db.update("video_segments", ContentValues().apply { put("uri", published.uri.toString()) },
        "session=? AND sequence=?", arrayOf(part.sessionId, part.index.toString()))
      db.setTransactionSuccessful()
    } finally { db.endTransaction() }
  }

  @Synchronized fun list(): List<SavedVideoSession> = readableDatabase.rawQuery("""
    SELECT id,created,state,reason,
      (SELECT COALESCE(SUM(bytes),0) FROM video_segments WHERE session=s.id),
      (SELECT COUNT(*) FROM video_segments WHERE session=s.id)
    FROM video_sessions s ORDER BY created DESC
  """.trimIndent(), null).use { rows -> buildList {
    while (rows.moveToNext()) add(SavedVideoSession(rows.getString(0), rows.getLong(1), rows.getString(2),
      rows.getString(3), rows.getLong(4), rows.getInt(5)))
  } }

  @Synchronized fun segments(id: String): List<SavedVideoSegment> = readableDatabase.rawQuery(
    "SELECT sequence,source,uri,bytes,sha256 FROM video_segments WHERE session=? ORDER BY sequence", arrayOf(id),
  ).use { rows -> buildList {
    while (rows.moveToNext()) add(SavedVideoSegment(id, rows.getInt(0), rows.getString(1), rows.getString(2), rows.getLong(3), rows.getString(4)))
  } }

  @Synchronized fun segmentCount(id: String): Int = readableDatabase.rawQuery(
    "SELECT COUNT(*) FROM video_segments WHERE session=?", arrayOf(id),
  ).use { it.moveToFirst(); it.getInt(0) }

  /** A count alone cannot distinguish all parts from a sparse or conflicting sequence. */
  @Synchronized fun hasAllSegments(id: String, expected: Int): Boolean {
    require(expected >= 0)
    return readableDatabase.rawQuery(
      "SELECT COUNT(*),MIN(sequence),MAX(sequence) FROM video_segments WHERE session=?", arrayOf(id),
    ).use {
      it.moveToFirst()
      it.getInt(0) == expected && (expected == 0 || (it.getInt(1) == 0 && it.getInt(2) == expected - 1))
    }
  }

  @Synchronized fun containsSource(file: File): Boolean = readableDatabase.rawQuery(
    "SELECT 1 FROM video_segments WHERE source=? LIMIT 1", arrayOf(file.canonicalPath),
  ).use { it.moveToFirst() }

  @Synchronized fun sources(): Set<String> = readableDatabase.rawQuery("SELECT source FROM video_segments", null).use { rows ->
    buildSet { while (rows.moveToNext()) add(rows.getString(0)) }
  }

  private fun insertSession(id: String, createdAt: Long, state: String, reason: String) {
    writableDatabase.insertWithOnConflict("video_sessions", null, ContentValues().apply {
      put("id", id); put("created", createdAt); put("state", state); put("reason", reason)
    }, SQLiteDatabase.CONFLICT_IGNORE)
  }
}
