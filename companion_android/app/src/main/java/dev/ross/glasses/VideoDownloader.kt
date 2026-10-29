package dev.ross.glasses

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Uses only the explicitly approved glasses Wi-Fi network, without binding the whole app. */
class VideoDownloader(context: Context, private val onNetworkLost: () -> Unit = {}) : AutoCloseable {
  private val connectivity = context.getSystemService(ConnectivityManager::class.java)
  private val connectionLock = Any()
  private var callback: ConnectivityManager.NetworkCallback? = null
  private var connected: Network? = null
  private var cancelPending: ((Exception) -> Unit)? = null
  val isConnected: Boolean get() = synchronized(connectionLock) { connected != null }

  suspend fun connect(ssid: String, password: String): Network = withTimeout(65_000) {
    check(Build.VERSION.SDK_INT >= 29) { "Video download requires Android 10 or newer." }
    close()
    val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build()
    val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
    suspendCancellableCoroutine { pending ->
      val resolved = AtomicBoolean(false)
      val listener = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          val accepted = synchronized(connectionLock) {
            if (callback !== this) false else {
              connected = network
              cancelPending = null
              true
            }
          }
          if (accepted && resolved.compareAndSet(false, true)) pending.resume(network)
        }
        override fun onUnavailable() {
          disconnect(this, IOException("Wi-Fi connection was declined or unavailable."))
        }
        override fun onLost(network: Network) {
          val owned = synchronized(connectionLock) { callback === this && connected == network }
          if (owned && disconnect(this, IOException("Glasses Wi-Fi disconnected."))) onNetworkLost()
        }
      }
      pending.invokeOnCancellation { disconnect(listener, IOException("Wi-Fi connection was cancelled.")) }
      synchronized(connectionLock) {
        if (!pending.isActive) return@synchronized
        callback = listener
        cancelPending = { error ->
          if (resolved.compareAndSet(false, true)) pending.resumeWithException(error)
        }
        // Registration and ownership are atomic with close(), including a close during setup.
        try { connectivity.requestNetwork(request, listener, 60_000) }
        catch (error: Exception) { disconnect(listener, error) }
      }
    }
  }

  suspend fun download(network: Network, ip: String, requestId: String, target: File,
    validate: (File) -> Unit, progress: (Int) -> Unit, capacity: (Long) -> Unit = {}): File =
    withContext(Dispatchers.IO) {
      require(VideoGallery.privateIpv4(ip)) { "The glasses did not report a private Wi-Fi address." }
      fun open(path: String) = (network.openConnection(URL("http://$ip:8089$path")) as HttpURLConnection).apply {
        connectTimeout = 5000; readTimeout = 15_000; instanceFollowRedirects = false
      }
      val remote = withTimeout(45_000) {
        var found: RemoteVideo? = null
        while (found == null) {
          currentCoroutineContext().ensureActive()
          val connection = open("/api/gallery?limit=0")
          try {
            if (connection.responseCode == 200) {
              val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { VideoGallery.read(it) }
              found = VideoGallery.find(json, requestId)
            }
          } catch (_: IOException) { /* The camera server can still be starting. */ }
          finally { connection.disconnect() }
          if (found == null) delay(1500)
        }
        found
      }
      check(target.parentFile!!.usableSpace > remote.bytes + 32L * 1024 * 1024) { "Not enough free storage on this phone." }
      val connection = open("/api/download?file=" + URLEncoder.encode(remote.name, "UTF-8"))
      try {
        check(connection.responseCode == 200) { "Video download failed (${connection.responseCode})." }
        val jobContext = currentCoroutineContext()
        connection.inputStream.use { input ->
          VideoFileTransfer.save(input, target, remote.bytes, validate, { jobContext.ensureActive() }, progress, capacity)
        }
      } finally {
        connection.disconnect()
      }
    }

  private fun disconnect(expected: ConnectivityManager.NetworkCallback?, reason: Exception): Boolean {
    val release = synchronized(connectionLock) {
      val listener = callback ?: return false
      if (expected != null && listener !== expected) return false
      val cancellation = cancelPending
      callback = null
      connected = null
      cancelPending = null
      listener to cancellation
    }
    val (listener, cancellation) = release
    runCatching { connectivity.unregisterNetworkCallback(listener) }
    cancellation?.invoke(reason)
    return true
  }

  override fun close() {
    disconnect(null, IOException("Glasses Wi-Fi connection closed."))
  }
}
