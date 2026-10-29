package dev.ross.glasses

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/** Observes the selected glasses' standard Android audio profiles, independently of BLE commands. */
class BluetoothAudio(
  private val context: Context,
  private val address: () -> String?,
  private val deviceName: () -> String?,
  private val changed: () -> Unit,
) {
  private val audio = context.getSystemService(AudioManager::class.java)
  private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
  private var a2dp: BluetoothProfile? = null
  private var headset: BluetoothHeadset? = null
  private var started = false
  private var stateReceiver: BroadcastReceiver? = null
  private var deviceCallback: AudioDeviceCallback? = null
  private var modeListener: AudioManager.OnModeChangedListener? = null
  var mediaConnected = false
    private set
  var callsConnected = false
    private set
  var callActive = false
    private set
  val mediaVolume get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
  val mediaMax get() = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
  val callVolume get() = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
  val callMax get() = audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)

  private fun permitted() = Build.VERSION.SDK_INT < 31 ||
    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

  fun start() {
    if (!permitted()) return
    if (started) { refresh(); return }
    started = true
    val listener = object : BluetoothProfile.ServiceListener {
      override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
        if (!started) { adapter?.closeProfileProxy(profile, proxy); return }
        if (profile == BluetoothProfile.A2DP) a2dp = proxy
        if (profile == BluetoothProfile.HEADSET) headset = proxy as BluetoothHeadset
        refresh()
      }
      override fun onServiceDisconnected(profile: Int) {
        if (profile == BluetoothProfile.A2DP) a2dp = null
        if (profile == BluetoothProfile.HEADSET) headset = null
        refresh()
      }
    }
    adapter?.getProfileProxy(context, listener, BluetoothProfile.A2DP)
    adapter?.getProfileProxy(context, listener, BluetoothProfile.HEADSET)
    val filter = IntentFilter().apply {
      addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
      addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
      addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
    }
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }
    stateReceiver = receiver
    if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    else context.registerReceiver(receiver, filter)
    deviceCallback = object : AudioDeviceCallback() {
      override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { refresh() }
      override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { refresh() }
    }.also { audio.registerAudioDeviceCallback(it, Handler(Looper.getMainLooper())) }
    if (Build.VERSION.SDK_INT >= 31) {
      modeListener = AudioManager.OnModeChangedListener { refresh() }.also {
        audio.addOnModeChangedListener(context.mainExecutor, it)
      }
    }
    refresh()
  }

  fun close() {
    started = false
    stateReceiver?.let { runCatching { context.unregisterReceiver(it) } }
    deviceCallback?.let { audio.unregisterAudioDeviceCallback(it) }
    if (Build.VERSION.SDK_INT >= 31) modeListener?.let { audio.removeOnModeChangedListener(it) }
    runCatching { a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } }
    runCatching { headset?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
    stateReceiver = null; deviceCallback = null; modeListener = null; a2dp = null; headset = null
  }

  fun refresh() {
    if (!permitted()) return
    val target = address()
    runCatching {
      mediaConnected = a2dp?.connectedDevices?.any { it.address.equals(target, true) } == true
      val handsFree = headset?.connectedDevices?.firstOrNull { it.address.equals(target, true) }
      callsConnected = handsFree != null
      callActive = audio.mode in listOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE) ||
        (handsFree != null && headset?.isAudioConnected(handsFree) == true)
    }
    changed()
  }

  fun adjustMedia(direction: Int) {
    check(mediaConnected) { "Connect Media audio for these glasses in Bluetooth settings." }
    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    refresh()
  }
  fun adjustCall(direction: Int) {
    check(callsConnected) { "Enable Calls for these glasses in Bluetooth settings." }
    audio.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, direction, AudioManager.FLAG_SHOW_UI)
    refresh()
  }

  suspend fun playTestTone() = withContext(Dispatchers.IO) {
    check(mediaConnected && !callActive) { "Connect media audio and finish any call before testing playback." }
    val output = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
      it.type in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET) &&
        (it.address.equals(address(), true) || it.productName.toString() == deviceName())
    } ?: error("Android has not made the glasses available as a media output. Select them in Bluetooth settings.")
    val samples = ShortArray(16_000) { i -> (sin(2 * PI * 440 * i / 16_000) * 2000).toInt().toShort() }
    val track = AudioTrack.Builder()
      .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
      .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
      .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
    try {
      check(track.setPreferredDevice(output)) { "Android could not select the glasses audio output." }
      track.write(samples, 0, samples.size)
      track.play()
      delay(100)
      check(track.routedDevice?.id == output.id) { "Audio was not routed to the selected glasses." }
      delay(1000)
    } finally { runCatching { track.stop() }; track.release() }
  }
}
