package dev.ross.glasses

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

data class SavedAudioSession(
  val id: String, val createdAt: Long, val state: String, val reason: String,
  val pcmBytes: Long, val segments: Int, val gaps: Int,
) { val durationMs get() = pcmBytes * 1000 / WavWriter.BYTES_PER_SECOND }

/** Transactional metadata; original media remains readable independently of the database. */
class RecordingRepository(
  context: Context,
  databaseName: String = "recordings.sqlite",
  storageDirectory: File = File(context.filesDir, "recordings"),
  private val media: PhoneMediaPublisher? = null,
) : SQLiteOpenHelper(context, databaseName, null, 1) {
  val directory = storageDirectory.apply { mkdirs() }
  val storageGuard by lazy { RecordingStorageGuard(
    { RecordingSettings(context, this).policy() },
    ::usedStorageBytes, { directory.usableSpace },
  ) }

  init { setWriteAheadLoggingEnabled(true) }
  fun usedStorageBytes(): Long = Math.addExact(RecordingStorageGuard.usedBytes(directory), media?.cachedRecordingBytes ?: 0L)
  override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, created INTEGER NOT NULL, updated INTEGER NOT NULL, device TEXT NOT NULL, state TEXT NOT NULL, reason TEXT NOT NULL)")
    db.execSQL("CREATE TABLE segments (session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, sequence INTEGER NOT NULL, name TEXT NOT NULL, pcm INTEGER NOT NULL, started INTEGER NOT NULL, PRIMARY KEY(session,sequence))")
    db.execSQL("CREATE TABLE gaps (session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, started INTEGER NOT NULL, ended INTEGER NOT NULL, reason TEXT NOT NULL)")
    db.execSQL("CREATE INDEX gaps_session ON gaps(session)")
  }
  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    error("Unsupported recording database upgrade; originals retained.")
  }

  fun folder(id: String): File {
    require(id.matches(Regex("audio-[0-9]+-[a-zA-Z0-9]{8}"))) { "Invalid recording identity." }
    return File(directory, id).canonicalFile.also { require(it.parentFile == directory.canonicalFile) }
  }

  @Synchronized fun create(id: String, device: String) {
    val path = folder(id)
    check(!path.exists() && path.mkdirs()) { "Cannot create audio session." }
    insertSession(id, device, "STARTING", "")
  }

  private fun insertSession(id: String, device: String, state: String, reason: String) {
    val now = System.currentTimeMillis()
    writableDatabase.insertOrThrow("sessions", null, ContentValues().apply {
      put("id", id); put("created", id.split('-')[1].toLong()); put("updated", now)
      put("device", device); put("state", state); put("reason", reason)
    })
  }

  @Synchronized fun commit(id: String, segment: AudioSegment) {
    require(segment.file.canonicalFile.parentFile == folder(id))
    require(segment.file.name == segment.sequence.toString().padStart(8, '0') + ".wav")
    require(segment.pcmBytes > 0 && segment.file.length() == segment.pcmBytes + 44)
    val db = writableDatabase
    db.beginTransaction()
    try {
      db.insertWithOnConflict("segments", null, ContentValues().apply {
        put("session", id); put("sequence", segment.sequence); put("name", segment.file.name)
        put("pcm", segment.pcmBytes); put("started", segment.startedAt)
      }, SQLiteDatabase.CONFLICT_IGNORE)
      db.rawQuery("SELECT name,pcm FROM segments WHERE session=? AND sequence=?", arrayOf(id, segment.sequence.toString())).use {
        check(it.moveToFirst() && it.getString(0) == segment.file.name && it.getLong(1) == segment.pcmBytes) {
          "Conflicting segment metadata; originals retained."
        }
      }
      db.setTransactionSuccessful()
    } finally { db.endTransaction() }
  }

  @Synchronized fun state(id: String, state: String, reason: String = "") {
    val db = writableDatabase
    val now = System.currentTimeMillis()
    db.beginTransaction()
    try {
      // A pause still belongs in the timeline when Stop/recovery ends the session
      // without another Resume. The state transition makes this idempotent.
      if (state in listOf("FINISHED", "INTERRUPTED")) {
        db.execSQL("""
          INSERT INTO gaps(session,started,ended,reason)
          SELECT id,updated,?,reason FROM sessions WHERE id=? AND state IN ('PAUSED','WAITING')
        """.trimIndent(), arrayOf(now, id))
      }
      check(db.update("sessions", ContentValues().apply {
        put("state", state); put("reason", reason); put("updated", now)
      }, "id=?", arrayOf(id)) == 1) { "Missing audio session." }
      db.setTransactionSuccessful()
    } finally { db.endTransaction() }
  }

  /** A delayed call notification must not undo an explicit Resume or final Stop. */
  @Synchronized fun pauseWaitingForCall(id: String) {
    writableDatabase.update("sessions", ContentValues().apply {
      put("state", "PAUSED"); put("reason", "Connection interrupted, then paused for a call.")
      // Preserve updated: the gap began when the connection was lost.
    }, "id=? AND state='WAITING'", arrayOf(id))
  }
  @Synchronized fun markRecording(id: String) {
    writableDatabase.update("sessions", ContentValues().apply { put("state", "RECORDING") },
      "id=? AND state='STARTING'", arrayOf(id))
  }

  @Synchronized fun resume(id: String) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      db.rawQuery("SELECT state,updated,reason FROM sessions WHERE id=?", arrayOf(id)).use {
        check(it.moveToFirst())
        if (it.getString(0) in listOf("PAUSED", "WAITING")) {
          db.insertOrThrow("gaps", null, ContentValues().apply {
            put("session", id); put("started", it.getLong(1)); put("ended", System.currentTimeMillis())
            put("reason", it.getString(2))
          })
        }
      }
      state(id, "STARTING")
      db.setTransactionSuccessful()
    } finally { db.endTransaction() }
  }

  @Synchronized fun list(limit: Int = 100, offset: Int = 0): List<SavedAudioSession> {
    require(limit in 1..100 && offset >= 0)
    return readableDatabase.rawQuery("""
      SELECT id,created,state,reason,
        (SELECT COALESCE(SUM(pcm),0) FROM segments WHERE session=s.id),
        (SELECT COUNT(*) FROM segments WHERE session=s.id),
        (SELECT COUNT(*) FROM gaps WHERE session=s.id)
      FROM sessions s WHERE state != 'DELETING' ORDER BY created DESC LIMIT ? OFFSET ?
    """.trimIndent(), arrayOf(limit.toString(), offset.toString())).use { rows ->
      buildList { while (rows.moveToNext()) add(SavedAudioSession(rows.getString(0), rows.getLong(1),
        rows.getString(2), rows.getString(3), rows.getLong(4), rows.getInt(5), rows.getInt(6))) }
    }
  }

  @Synchronized fun segments(id: String): List<AudioSegment> = readableDatabase.rawQuery(
    "SELECT name,sequence,pcm,started FROM segments WHERE session=? ORDER BY sequence", arrayOf(id),
  ).use { rows -> buildList {
    while (rows.moveToNext()) {
      val path = File(folder(id), rows.getString(0)).canonicalFile
      require(path.parentFile == folder(id))
      add(AudioSegment(path, rows.getLong(1), rows.getLong(2), rows.getLong(3)))
    }
  } }

  /** Compare durable segment identity after resolving Android filesDir aliases and path components. */
  @Synchronized fun isCommittedSegment(file: File): Boolean {
    val path: File
    val root: File
    try {
      path = file.canonicalFile
      root = directory.canonicalFile
    } catch (_: java.io.IOException) { return false }
    val parent = path.parentFile ?: return false
    if (parent.parentFile != root || !parent.name.matches(Regex("audio-[0-9]+-[a-zA-Z0-9]{8}"))) return false
    val sequence = path.name.removeSuffix(".wav").toLongOrNull() ?: return false
    if (sequence <= 0 || path.name != sequence.toString().padStart(8, '0') + ".wav") return false
    return readableDatabase.rawQuery("""
      SELECT 1 FROM segments JOIN sessions ON sessions.id=segments.session
      WHERE segments.session=? AND segments.sequence=? AND segments.name=? AND sessions.state!='DELETING'
    """.trimIndent(), arrayOf(parent.name, sequence.toString(), path.name)).use { it.moveToFirst() }
  }

  @Synchronized fun gaps(id: String): List<Map<String, Any>> = readableDatabase.rawQuery(
    "SELECT started,ended,reason FROM gaps WHERE session=? ORDER BY started", arrayOf(id),
  ).use { rows -> buildList {
    while (rows.moveToNext()) add(mapOf("startedAt" to rows.getLong(0), "endedAt" to rows.getLong(1), "reason" to rows.getString(2)))
  } }

  /** Called before any new writer is allowed to start. Does not restart recording. */
  @Synchronized fun recover(): List<String> {
    val errors = mutableListOf<String>()
    // Only unfinished export copies from the previous process. Original audio is elsewhere.
    File(directory, "exports").listFiles().orEmpty().filter {
      it.isFile && it.name.matches(Regex("audio-[0-9a-f-]{36}\\.zip\\.part"))
    }.forEach { if (!it.delete()) errors.add("Could not clean interrupted export ${it.name}") }
    val ids = readableDatabase.rawQuery("SELECT id,state FROM sessions", null).use { rows ->
      buildMap { while (rows.moveToNext()) put(rows.getString(0), rows.getString(1)) }
    }
    directory.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("audio-") }.forEach { dir ->
      runCatching {
        val id = dir.name
        folder(id)
        if (ids[id] == "DELETING") { delete(id); return@runCatching }
        if (id !in ids) insertSession(id, "Recovered", "INTERRUPTED", "Recovered after interrupted index update.")
        dir.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9]+\\.wav\\.part")) }.forEach {
          try { WavWriter.recover(it) }
          catch (error: Exception) { errors.add("$id: ${error.message}") }
        }
        dir.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9]+\\.wav")) }.forEach {
          val pcm = WavWriter.inspect(it)
          commit(id, AudioSegment(it, it.name.substringBefore('.').toLong(), pcm, it.lastModified()))
        }
        if (ids[id] in listOf("STARTING", "RECORDING", "PAUSED", "WAITING")) {
          state(id, "INTERRUPTED", "App restarted; saved segments recovered. Start a new recording when ready.")
        }
      }.onFailure { errors.add("${dir.name}: ${it.message}") }
    }
    // Legacy flat recordings retain their original paths and continue to appear in the library.
    directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".wav.part") }.forEach {
      runCatching { WavWriter.recover(it) }.onFailure { error -> errors.add("${it.name}: ${error.message}") }
    }
    return errors
  }

  @Synchronized fun delete(id: String) {
    state(id, "DELETING", "Deleted by user")
    segments(id).forEach { media?.deletePublished(it.file) }
    val dir = folder(id)
    dir.listFiles().orEmpty().forEach { file ->
      check(file.isFile && file.canonicalFile.parentFile == dir && file.name.matches(Regex("[0-9]+\\.wav(\\.part)?")))
      check(file.delete()) { "Could not delete recording segment." }
    }
    check(!dir.exists() || dir.delete()) { "Could not remove recording folder." }
    writableDatabase.delete("sessions", "id=?", arrayOf(id))
  }
}
