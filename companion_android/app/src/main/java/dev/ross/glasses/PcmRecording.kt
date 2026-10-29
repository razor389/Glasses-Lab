package dev.ross.glasses

import com.mentra.bluetoothsdk.MicPcmEvent
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel

/** Bounded IO queue: a stalled disk stops recording instead of silently dropping audio. */
class PcmRecording(
  scope: CoroutineScope, directory: File, capacity: (Long) -> Unit,
  committed: (AudioSegment) -> Unit, private val failed: (Exception) -> Unit,
  initialSequence: Long = 0,
) {
  private val frames = Channel<ByteArray>(256)
  val bytes = AtomicLong()
  private val writer = scope.async(Dispatchers.IO) {
    try {
      SegmentedAudioWriter(directory, capacity, committed, initialSequence = initialSequence).use { wav ->
        for (pcm in frames) { wav.append(pcm); bytes.set(wav.bytes) }
      }
      bytes.get()
    } catch (error: Exception) {
      failed(error)
      throw error
    } finally {
      frames.cancel()
    }
  }

  fun offer(event: MicPcmEvent) {
    require(event.sampleRate == 16_000 && event.channels == 1 && event.bitsPerSample == 16 && event.encoding == "pcm_s16le") {
      "Unsupported glasses audio format."
    }
    check(!writer.isCompleted) { "Audio file writing stopped unexpectedly." }
    check(frames.trySend(event.pcm.copyOf()).isSuccess) { "Phone storage cannot keep up with the microphone." }
  }

  suspend fun finish(): Long { frames.close(); return writer.await() }
}
