package dev.ross.glasses

import java.io.File

data class AudioSegment(val file: File, val sequence: Long, val pcmBytes: Long, val startedAt: Long) {
  val durationMs get() = pcmBytes * 1000 / WavWriter.BYTES_PER_SECOND
}

/** Rotates storage, never the microphone. All methods run on the ordered IO consumer. */
class SegmentedAudioWriter(
  private val directory: File,
  private val capacity: (Long) -> Unit,
  private val committed: (AudioSegment) -> Unit,
  private val segmentBytes: Long = 5L * 60 * WavWriter.BYTES_PER_SECOND,
  private val clock: () -> Long = System::currentTimeMillis,
  initialSequence: Long = 0,
) : AutoCloseable {
  private var writer: WavWriter? = null
  private var partial: File? = null
  private var sequence = 0L
  private var startedAt = 0L
  private var checkpointBytes = 0L
  private var closed = false
  var bytes = 0L
    private set

  init {
    require(segmentBytes in 2..WavWriter.MAX_BYTES && segmentBytes % 2 == 0L)
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create recording directory." }
    require(initialSequence >= 0)
    sequence = maxOf(initialSequence, directory.listFiles().orEmpty().mapNotNull {
      it.name.substringBefore('.').toLongOrNull()
    }.maxOrNull() ?: 0L)
  }

  fun append(pcm: ByteArray) {
    check(!closed)
    require(pcm.size % 2 == 0) { "Incomplete PCM sample." }
    var offset = 0
    while (offset < pcm.size) {
      if (writer == null) {
        capacity(44)
        sequence = Math.addExact(sequence, 1L)
        partial = File(directory, sequence.toString().padStart(8, '0') + ".wav.part")
        startedAt = clock()
        checkpointBytes = 0
        writer = WavWriter(partial!!)
      }
      val current = writer!!
      val count = minOf(segmentBytes - current.dataBytes, (pcm.size - offset).toLong()).toInt()
      capacity(count.toLong())
      current.append(pcm.copyOfRange(offset, offset + count))
      bytes = Math.addExact(bytes, count.toLong())
      offset += count
      if (current.dataBytes == segmentBytes) seal()
      else if (current.dataBytes - checkpointBytes >= 5L * WavWriter.BYTES_PER_SECOND) {
        current.checkpoint()
        checkpointBytes = current.dataBytes
      }
    }
  }

  private fun seal() {
    val current = writer ?: return
    val source = partial!!
    // Clear ownership first: a failed metadata commit must never duplicate this segment.
    writer = null
    partial = null
    current.close()
    val file = WavWriter.recover(source)
    if (file != null) committed(AudioSegment(file, sequence, file.length() - 44, startedAt))
    else check(source.delete()) { "Cannot remove empty audio segment." }
  }

  override fun close() {
    if (closed) return
    closed = true
    seal()
  }
}
