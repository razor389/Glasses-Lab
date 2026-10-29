package dev.ross.glasses

import android.app.KeyguardManager
import android.content.Context
import android.net.Network
import android.os.PowerManager
import android.os.SystemClock
import com.mentra.bluetoothsdk.*
import com.google.gson.JsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Service-owned, approved local network. Public operations are called on the main thread. */
class GlassesMediaConnection(
  private val context: Context,
  private val scope: CoroutineScope,
  private val client: () -> MentraBluetoothSdk,
  private val glasses: () -> GlassesRuntimeState,
  private val changed: () -> Unit,
  private val log: (String) -> Unit,
  private val canPromptNow: () -> Boolean = { false },
) : AutoCloseable {
  data class Session(val network: Network, val hotspot: HotspotStatus.Enabled, val token: String)
  private data class Held(val session: Session, val downloader: VideoDownloader, val ownsHotspot: Boolean, val generation: Long)
  private val state = MediaConnectionState<Held>(SystemClock::elapsedRealtime)
  private val mutex = Mutex()
  private val power = context.getSystemService(PowerManager::class.java)
  private val keyguard = context.getSystemService(KeyguardManager::class.java)
  private var renewal: Job? = null
  private var openingJob: Job? = null
  private var openingDownloader: VideoDownloader? = null
  private var connectionWakeLock: PowerManager.WakeLock? = null
  private var closed = false

  val isReady: Boolean get() = state.current?.downloader?.isConnected == true
  val connecting: Boolean get() = state.connecting
  var message = "Open Glasses Lab to connect glasses Wi-Fi."
    private set

  /** Reuses an approved connection while locked; only a visible, unlocked caller may request a new one. */
  suspend fun acquire(allowPrompt: Boolean): Session = mutex.withLock {
    check(!closed) { "Media connection is closed." }
    state.current?.takeIf { it.downloader.isConnected }?.let { return@withLock it.session }
    state.held?.let { releaseHeld("Glasses Wi-Fi expired. Open the app to reconnect.") }
    check(glasses().ready) { "Connect your glasses before saving captures." }
    val attempt = state.begin(allowPrompt && canPromptNow() && power.isInteractive && !keyguard.isDeviceLocked)
    openingJob = currentCoroutineContext()[Job]
    var downloader: VideoDownloader? = null
    var hotspot: HotspotStatus.Enabled? = null
    var ownsHotspot = false
    try {
      message = "Connecting glasses Wi-Fi; approve Android's connection prompt."
      changed()
      val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
      ownsHotspot = (glasses() as? GlassesRuntimeState.Connected)?.hotspot !is HotspotStatus.Enabled
      hotspot = client().setHotspotState(true, token).status as? HotspotStatus.Enabled
        ?: throw IOException("Glasses Wi-Fi could not start.")
      require(VideoGallery.privateIpv4(hotspot.localIp)) { "Glasses Wi-Fi did not report a private address." }
      // Starting the glasses hotspot suspends; the user may have left or locked the phone.
      if (!canPromptNow() || !power.isInteractive || keyguard.isDeviceLocked) throw MediaConnectionNeedsApproval()
      val transport = VideoDownloader(context) {
        scope.launch { onNetworkLost() }
      }
      downloader = transport
      openingDownloader = transport
      val session = Session(transport.connect(hotspot.ssid, hotspot.password), hotspot, token)
      val ttl = exchangeLease(session, MediaConnectionState.MAX_LEASE_SECONDS)
      currentCoroutineContext().ensureActive()
      check(transport.isConnected) { "Glasses Wi-Fi disconnected during setup." }
      val held = Held(session, transport, ownsHotspot, attempt)
      check(state.accept(attempt, held, ttl)) { "Glasses Wi-Fi setup was cancelled." }
      holdConnectionWakeLock()
      openingDownloader = null
      message = "Glasses Wi-Fi ready for automatic transfers."
      startRenewal(held)
      changed()
      session
    } catch (error: Exception) {
      state.invalidate()?.downloader?.close()
      releaseConnectionWakeLock()
      downloader?.close()
      if (ownsHotspot) stopOwnedHotspot(hotspot)
      message = if (error is CancellationException) "Glasses Wi-Fi connection cancelled. Captures remain queued."
        else "Glasses Wi-Fi unavailable: ${error.message}"
      changed()
      throw error
    } finally {
      openingDownloader = null
      openingJob = null
    }
  }

  /** Explicit Disconnect or foreground-only mode releases our reservation after the transfer. */
  suspend fun release(reason: String = "Glasses Wi-Fi disconnected. Captures remain queued.") {
    renewal?.cancel(); renewal = null
    val caller = currentCoroutineContext()[Job]
    openingJob?.takeIf { it !== caller }?.cancel(CancellationException(reason))
    openingDownloader?.close()
    mutex.withLock { releaseHeld(reason) }
  }

  /** BLE loss also clears the server lease; never reopen Wi-Fi from this callback. */
  fun onGlassesConnectionChanged(ready: Boolean) {
    if (!ready) dropLocal("Glasses disconnected. Captures will wait for Wi-Fi.")
  }

  private suspend fun onNetworkLost() {
    mutex.withLock {
      // A callback from an old downloader must not tear down a newer healthy request.
      val held = state.held ?: return@withLock
      if (held.downloader.isConnected) return@withLock
      renewal?.cancel(); renewal = null
      releaseHeld("Glasses Wi-Fi disconnected. Open the app to reconnect.")
    }
  }

  private fun startRenewal(held: Held) {
    renewal?.cancel()
    renewal = scope.launch {
      while (isActive) {
        delay(60_000)
        var keepGoing = true
        mutex.withLock {
          if (state.held !== held) { keepGoing = false; return@withLock }
          if (!glasses().ready || state.current !== held || !held.downloader.isConnected) {
            releaseHeld("Glasses Wi-Fi expired. Open the app to reconnect.")
            keepGoing = false
            return@withLock
          }
          try {
            val ttl = exchangeLease(held.session, MediaConnectionState.MAX_LEASE_SECONDS)
            if (!state.renew(held.generation, ttl)) {
              releaseHeld("Glasses Wi-Fi expired. Open the app to reconnect.")
              keepGoing = false
            } else holdConnectionWakeLock()
          } catch (error: CancellationException) {
            if (state.held === held) releaseConnectionWakeLock()
            throw error
          } catch (error: Exception) {
            log("Glasses Wi-Fi lease ended: ${error.message}")
            releaseHeld("Glasses Wi-Fi needs reconnecting. Captures remain queued.", revoke = false)
            keepGoing = false
          }
        }
        if (!keepGoing) break
      }
    }
  }

  private suspend fun releaseHeld(reason: String, revoke: Boolean = true) {
    val held = state.invalidate()
    releaseConnectionWakeLock()
    if (held != null) {
      if (revoke && held.downloader.isConnected) withContext(NonCancellable) {
        runCatching { exchangeLease(held.session, 0) }
      }
      held.downloader.close()
      if (held.ownsHotspot) stopOwnedHotspot(held.session.hotspot)
    }
    message = reason
    changed()
  }

  private suspend fun stopOwnedHotspot(expected: HotspotStatus.Enabled?): Unit = withContext(NonCancellable) {
    val current = (glasses() as? GlassesRuntimeState.Connected)?.hotspot
    // Do not stop a replacement hotspot another owner has started since our attempt.
    if (glasses().ready && expected != null && current == expected) {
      withTimeoutOrNull(6000) { runCatching { client().setHotspotState(false) } }
    }
  }

  private suspend fun exchangeLease(session: Session, seconds: Int): Int {
    require(seconds in 0..MediaConnectionState.MAX_LEASE_SECONDS)
    val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlassesLab:mediaLease").apply { setReferenceCounted(false) }
    try {
      lock.acquire(15_000)
      return withContext(Dispatchers.IO) {
        val connection = (session.network.openConnection(URL("http://${session.hotspot.localIp}:8089/api/lab/v1/lease")) as HttpURLConnection).apply {
          connectTimeout = 5000; readTimeout = 5000; instanceFollowRedirects = false
          requestMethod = "POST"; doOutput = true
          setRequestProperty("Authorization", "Bearer ${session.token}")
          setRequestProperty("Content-Type", "application/json")
        }
        try {
          val body = JsonObject().apply { addProperty("ttl_seconds", seconds) }.toString()
          connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
          check(connection.responseCode == 200) {
            if (connection.responseCode == 404) "Update the glasses build to keep automatic Wi-Fi transfers ready."
            else "Glasses Wi-Fi lease failed (${connection.responseCode})."
          }
          val data = connection.inputStream.bufferedReader().use { reader ->
            val response = StringBuilder()
            val buffer = CharArray(512)
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (true) {
              check(SystemClock.elapsedRealtime() < deadline) { "Glasses Wi-Fi lease response timed out." }
              val count = reader.read(buffer)
              if (count < 0) break
              check(response.length + count <= 4096) { "Glasses Wi-Fi lease response was too large." }
              response.append(buffer, 0, count)
            }
            RemoteMediaCatalog.data(response.toString())
          }
          check(data.get("api_version")?.asInt == 1) { "Unsupported glasses Wi-Fi lease protocol." }
          val ttl = data.get("expires_in_seconds")?.asInt ?: throw IOException("Missing glasses Wi-Fi lease duration.")
          check(if (seconds == 0) ttl == 0 else ttl in 1..MediaConnectionState.MAX_LEASE_SECONDS) { "Invalid glasses Wi-Fi lease duration." }
          ttl
        } finally { connection.disconnect() }
      }
    } finally { if (lock.isHeld) lock.release() }
  }

  /** Keep the CPU available for the next renewal, bounded if the coroutine stops running. */
  private fun holdConnectionWakeLock() {
    val lock = connectionWakeLock ?: power.newWakeLock(
      PowerManager.PARTIAL_WAKE_LOCK, "GlassesLab:mediaConnection",
    ).apply { setReferenceCounted(false) }.also { connectionWakeLock = it }
    lock.acquire(MediaConnectionState.MAX_LEASE_SECONDS * 1000L)
  }

  private fun releaseConnectionWakeLock() {
    val lock = connectionWakeLock ?: return
    connectionWakeLock = null
    runCatching { if (lock.isHeld) lock.release() }
  }

  private fun dropLocal(reason: String) {
    renewal?.cancel(); renewal = null
    releaseConnectionWakeLock()
    openingJob?.cancel(CancellationException(reason)); openingJob = null
    openingDownloader?.close(); openingDownloader = null
    state.invalidate()?.downloader?.close()
    message = reason
    changed()
  }

  /** Teardown cannot wait on BLE or HTTP; the bounded server lease expires without renewals. */
  override fun close() {
    closed = true
    dropLocal("Glasses Wi-Fi disconnected.")
    state.close()?.downloader?.close()
  }
}
