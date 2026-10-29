package dev.ross.glasses

import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.mentra.bluetoothsdk.PhotoSize
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.*

/** Capture defaults, storage limits, and background setup. The service owns settings application. */
class RecordingSettingsActivity : CompanionActivity() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val repository get() = (application as LabApplication).recordings
  private val settings by lazy { RecordingSettings(this, repository) }
  private lateinit var storageStatus: TextView
  private lateinit var backgroundStatus: TextView
  private lateinit var budget: EditText
  private lateinit var reserve: EditText
  private lateinit var association: Button
  private val gigabyte = BigDecimal("1000000000")
  private lateinit var photoSize: Spinner
  private lateinit var photoFlash: Switch
  private lateinit var videoFlash: Switch
  private lateinit var videoResolution: Spinner
  private lateinit var videoFps: Spinner
  private lateinit var videoDuration: Spinner
  private lateinit var videoOffload: Switch
  private lateinit var photoDefaultsStatus: TextView
  private lateinit var videoDefaultsStatus: TextView
  private lateinit var photoSave: Button
  private lateinit var videoSave: Button
  private lateinit var photoRetry: Button
  private lateinit var videoRetry: Button
  private val photoSizes = listOf(PhotoSize.LOW, PhotoSize.MEDIUM, PhotoSize.HIGH, PhotoSize.MAX)
  private val resolutions = listOf(1280 to 720, 1920 to 1080)
  private val frameRates = listOf(5, 15, 30)
  private val durations = VideoRecordingProfile.DURATIONS
  private var defaultsBound = false
  private var draftsInitialized = false
  private var photoSaveError: String? = null
  private var videoSaveError: String? = null
  private val defaultsObserver: () -> Unit = { refreshDefaults() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val page = LabUi.page(this, "Settings").apply {
      // Start on the settings page instead of opening the keyboard for an offscreen storage field.
      isFocusableInTouchMode = true
      requestFocus()
    }
    page.addView(LabUi.button(this, "Back to capture") { finish() })
    page.addView(LabUi.text(this, "Capture, storage & connection", 14, LabUi.muted))
    addCaptureDefaults(page, savedInstanceState)
    val storage = LabUi.card(this, page)
    storage.addView(LabUi.text(this, "Make room for what matters", 20).apply { typeface = Typeface.DEFAULT_BOLD })
    storageStatus = LabUi.text(this, "Checking phone storage…", 14, LabUi.muted)
    storage.addView(storageStatus)
    fun input(label: String, value: String, hint: String): EditText {
      storage.addView(LabUi.text(this, label, 15).apply { typeface = Typeface.DEFAULT_BOLD })
      return EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine(true)
        setTextColor(LabUi.ink)
        setHintTextColor(LabUi.muted)
        backgroundTintList = android.content.res.ColorStateList.valueOf(LabUi.accent)
        setPadding(LabUi.dp(this@RecordingSettingsActivity, 8), LabUi.dp(this@RecordingSettingsActivity, 12), LabUi.dp(this@RecordingSettingsActivity, 8), LabUi.dp(this@RecordingSettingsActivity, 12))
        this.hint = hint
        setText(value)
        contentDescription = label
        storage.addView(this)
      }
    }
    val policy = settings.policy()
    fun gb(value: Long) = BigDecimal.valueOf(value).divide(gigabyte, 3, RoundingMode.CEILING).stripTrailingZeros().toPlainString()
    budget = input("Recording allowance (GB)", policy.budgetBytes?.let(::gb) ?: "", "No limit")
    storage.addView(LabUi.text(this, "Leave blank to use available space above your reserve. Existing audio and video count toward this allowance.", 13, LabUi.muted))
    reserve = input("Phone space to keep free (GB)", gb(policy.reserveBytes), "For example, 1")
    storage.addView(LabUi.text(this, "Keep room for your phone's other apps. Minimum 0.068 GB. One GB is 1,000 MB.", 13, LabUi.muted))
    storage.addView(LabUi.button(this, "Save storage settings", primary = true) {
      runCatching {
        fun bytes(value: String) = BigDecimal(value).multiply(gigabyte).longValueExact()
        settings.save(RecordingStoragePolicy(budget.text.toString().trim().takeIf { it.isNotEmpty() }?.let(::bytes), bytes(reserve.text.toString().trim())))
        controller.recordings.onStorageSettingsChanged()
        refresh()
        Toast.makeText(this, "Storage settings saved", Toast.LENGTH_SHORT).show()
      }.onFailure { Toast.makeText(this, it.message ?: "Enter valid GB amounts", Toast.LENGTH_LONG).show() }
    })
    val lowSpace = LabUi.card(this, page)
    lowSpace.addView(LabUi.text(this, "When space runs low", 18).apply { typeface = Typeface.DEFAULT_BOLD })
    lowSpace.addView(LabUi.text(this, "Recording and transfers stop before your reserve is used. Saved media stays safe. An original on the glasses stays there until its phone copy is verified.", 14, LabUi.muted))
    lowSpace.addView(LabUi.text(this, "Older photos and recordings are never erased automatically to make room. Free space or increase your allowance, then retry.", 14, LabUi.muted))
    val mediaWifi = LabUi.card(this, page)
    mediaWifi.addView(LabUi.text(this, "Automatic photo & video transfers", 20).apply { typeface = Typeface.DEFAULT_BOLD })
    mediaWifi.addView(Switch(this).apply {
      text = "Keep media Wi-Fi ready"
      setTextColor(LabUi.ink)
      isChecked = settings.keepMediaWifiReady()
      setOnCheckedChangeListener { _, checked ->
        runCatching { controller.recordings.setKeepMediaWifiReady(checked) }
          .onFailure { Toast.makeText(this@RecordingSettingsActivity, it.message, Toast.LENGTH_LONG).show() }
      }
    })
    mediaWifi.addView(LabUi.text(this, "Approve Connect media Wi-Fi once for the current connection. Keeping it ready allows screen-off transfers, uses more phone and glasses battery, and may make your phone use mobile data for internet. If Android ends the connection, captures stay on the glasses until you reconnect.", 14, LabUi.muted))
    mediaWifi.addView(LabUi.text(this, "In local recording mode, turning this off closes media Wi-Fi after each transfer. Offload while recording keeps an approved media Wi-Fi connection ready. New connections need the app open and may ask for approval again. Audio-only recording uses Bluetooth.", 13, LabUi.muted))
    mediaWifi.addView(LabUi.button(this, "Connect media Wi-Fi") { controller.recordings.retryAutomaticTransfers() })
    val background = LabUi.card(this, page)
    background.addView(LabUi.text(this, "Stay connected", 20).apply { typeface = Typeface.DEFAULT_BOLD })
    backgroundStatus = LabUi.text(this, "Checking Android setup…", 14, LabUi.muted)
    background.addView(backgroundStatus)
    association = LabUi.button(this, "Enable background association") {
      runCatching {
        val address = controller.savedDeviceAddress ?: error("Connect to your glasses first.")
        CompanionAssociation.associate(this, address) { message ->
          if (!isDestroyed) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); refresh() }
        }
      }.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
    }
    background.addView(association)
    background.addView(LabUi.text(this, "Allow notifications for recording status and Stop controls. Background association helps reconnect your glasses; it does not start recording.", 13, LabUi.muted))
    background.addView(LabUi.button(this, "Android app settings") {
      startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    })
    background.addView(LabUi.text(this, "If Samsung interrupts screen-off recording, allow background battery use for Glasses Lab in Android app settings.", 13, LabUi.muted))
    val housekeeping = LabUi.disclosure(this, page, "Temporary files").body
    housekeeping.addView(LabUi.text(this, "Sharing a long audio session creates a ZIP copy. You can clear completed export copies without deleting the recordings.", 14, LabUi.muted))
    housekeeping.addView(LabUi.button(this, "Clear temporary export copies") {
      AlertDialog.Builder(this).setTitle("Clear export copies?")
        .setMessage("Delete temporary ZIP copies used for sharing? Your original recordings will remain.")
        .setNegativeButton("Cancel", null).setPositiveButton("Clear copies") { _, _ ->
          scope.launch {
            if (controller.recordings.busy) { Toast.makeText(this@RecordingSettingsActivity, "Finish recording first.", Toast.LENGTH_LONG).show(); return@launch }
            val removed = withContext(Dispatchers.IO) {
              java.io.File(repository.directory, "exports").listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".zip") }.count { it.delete() }
            }
            Toast.makeText(this@RecordingSettingsActivity, "$removed export copies cleared", Toast.LENGTH_SHORT).show()
            refresh()
          }
        }.show()
    })
  }
  private fun addCaptureDefaults(page: LinearLayout, savedState: Bundle?) {
    val camera = LabUi.card(this, page)
    camera.addView(LabUi.text(this, "Camera defaults", 20).apply { typeface = Typeface.DEFAULT_BOLD })
    camera.addView(LabUi.text(this, "Used for photos taken from this phone or the glasses button. Changes apply to the next photo.", 14, LabUi.muted))
    photoSize = choice(camera, "Photo size", listOf("Low", "Medium", "High", "Maximum")) {
      photoSaveError = null
      refreshDefaults()
    }
    camera.addView(LabUi.text(this, "Larger photos use more storage and take longer to transfer. The camera chooses a supported size; High and Maximum may produce the same dimensions.", 13, LabUi.muted))
    photoFlash = Switch(this).apply {
      text = "External capture LED"
      setTextColor(LabUi.ink)
      isChecked = true
      setOnCheckedChangeListener { _, _ -> photoSaveError = null; refreshDefaults() }
    }
    camera.addView(photoFlash)
    camera.addView(LabUi.text(this, "The internal capture indicator stays on for each photo.", 13, LabUi.muted))
    photoDefaultsStatus = LabUi.text(this, "Loading camera defaults…", 14, LabUi.muted)
    camera.addView(photoDefaultsStatus)
    photoSave = LabUi.button(this, "Save camera defaults", primary = true) {
      runCatching { controller.savePhotoDefaults(photoDraft()) }
        .onSuccess { photoSaveError = null; Toast.makeText(this, "Camera defaults saved on phone", Toast.LENGTH_SHORT).show() }
        .onFailure { photoSaveError = it.message ?: "Could not save camera defaults." }
      refreshDefaults()
    }
    photoRetry = LabUi.button(this, "Retry applying") { controller.retryPhotoDefaults() }
    camera.addView(photoSave)
    camera.addView(photoRetry)

    val video = LabUi.card(this, page)
    video.addView(LabUi.text(this, "Recording defaults", 20).apply { typeface = Typeface.DEFAULT_BOLD })
    video.addView(LabUi.text(this, "Used for video recorded from this phone or the glasses button. Changes apply to the next video.", 14, LabUi.muted))
    fun videoEdited() { videoSaveError = null; refreshDefaults() }
    videoResolution = choice(video, "Video resolution", listOf("720p (1280 × 720)", "1080p (1920 × 1080)"), ::videoEdited)
    videoFps = choice(video, "Frame rate", frameRates.map { "$it FPS" }, ::videoEdited)
    videoDuration = choice(video, "Maximum video length", durations.map { if (it == 0) "No time limit" else "$it minutes" }, ::videoEdited)
    videoFlash = Switch(this).apply {
      text = "External capture LED"
      setTextColor(LabUi.ink)
      isChecked = true
      setOnCheckedChangeListener { _, _ -> videoEdited() }
    }
    video.addView(videoFlash)
    video.addView(LabUi.text(this, "The internal capture indicator stays on while recording.", 13, LabUi.muted))
    videoOffload = Switch(this).apply {
      text = "Offload while recording"
      setTextColor(LabUi.ink)
      setOnCheckedChangeListener { _, _ -> videoEdited() }
    }
    video.addView(videoOffload)
    video.addView(LabUi.text(this,
      "Off: save on the glasses until later transfer. On: save verified video parts to your phone while recording. " +
        "The phone controls and glasses button use the same resolution, frame rate, length, and mode.", 14, LabUi.muted))
    video.addView(LabUi.text(this,
      "Offload while recording keeps an approved media Wi-Fi connection ready and needs available phone recording space. If the phone falls behind or disconnects, " +
        "the glasses stop when their temporary buffer fills. Local recording can stop earlier at the glasses' file or storage limit. " +
        "Changes apply after the glasses confirm them, and only to the next recording.", 13, LabUi.muted))
    video.addView(LabUi.text(this, "Lower frame rates produce less smooth motion. Battery, storage, or capture errors can end a clip before its maximum length.", 13, LabUi.muted))
    videoDefaultsStatus = LabUi.text(this, "Loading recording defaults…", 14, LabUi.muted)
    video.addView(videoDefaultsStatus)
    videoSave = LabUi.button(this, "Save recording defaults", primary = true) {
      runCatching { controller.saveVideoDefaults(videoDraft()) }
        .onSuccess { videoSaveError = null; Toast.makeText(this, "Recording defaults saved on phone", Toast.LENGTH_SHORT).show() }
        .onFailure { videoSaveError = it.message ?: "Could not save recording defaults." }
      refreshDefaults()
    }
    videoRetry = LabUi.button(this, "Retry applying") { controller.retryVideoDefaults() }
    video.addView(videoSave)
    video.addView(videoRetry)
    photoRetry.visibility = View.GONE
    videoRetry.visibility = View.GONE
    photoSave.isEnabled = false
    videoSave.isEnabled = false
    draftsInitialized = savedState?.getBoolean("capture_drafts_initialized", false) == true
    if (draftsInitialized) {
      photoSize.setSelection(savedState!!.getInt("photo_size_draft", 2).coerceIn(photoSizes.indices))
      videoResolution.setSelection(savedState.getInt("video_resolution_draft", 0).coerceIn(resolutions.indices))
      videoFps.setSelection(savedState.getInt("video_fps_draft", 2).coerceIn(frameRates.indices))
      videoDuration.setSelection(savedState.getInt("video_duration_draft", 1).coerceIn(durations.indices))
      videoOffload.isChecked = savedState.getBoolean("video_offload_draft", false)
      photoFlash.isChecked = savedState.getBoolean("photo_flash_draft", true)
      videoFlash.isChecked = savedState.getBoolean("video_flash_draft", true)
    }
  }

  private fun choice(parent: LinearLayout, label: String, labels: List<String>, changed: () -> Unit): Spinner {
    parent.addView(LabUi.text(this, label, 15).apply { typeface = Typeface.DEFAULT_BOLD })
    return Spinner(this).apply {
      contentDescription = label
      minimumHeight = LabUi.dp(this@RecordingSettingsActivity, 48)
      adapter = object : ArrayAdapter<String>(this@RecordingSettingsActivity, android.R.layout.simple_spinner_item, labels) {
        init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
          (super.getView(position, convertView, parent) as TextView).apply { setTextColor(LabUi.ink); textSize = 16f }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
          (super.getDropDownView(position, convertView, parent) as TextView).apply {
            setTextColor(LabUi.ink)
            setBackgroundColor(LabUi.surfaceRaised)
            minHeight = LabUi.dp(this@RecordingSettingsActivity, 48)
          }
      }
      onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { changed() }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
      }
      parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
  }

  private fun photoDraft() = PhotoCaptureProfile(photoSizes[photoSize.selectedItemPosition.coerceIn(photoSizes.indices)],
    enableFlash = photoFlash.isChecked)
  private fun videoDraft(): VideoRecordingProfile {
    val resolution = resolutions[videoResolution.selectedItemPosition.coerceIn(resolutions.indices)]
    return VideoRecordingProfile(resolution.first, resolution.second,
      frameRates[videoFps.selectedItemPosition.coerceIn(frameRates.indices)],
      durations[videoDuration.selectedItemPosition.coerceIn(durations.indices)], videoOffload.isChecked,
      enableFlash = videoFlash.isChecked)
  }

  private fun refreshDefaults() {
    if (!defaultsBound || isDestroyed) return
    val defaults = controller.defaults
    photoDefaultsStatus.text = photoSaveError ?: defaults.photoStatus +
      if (controller.capturing) "\nThe current photo keeps the settings it started with." else ""
    videoDefaultsStatus.text = videoSaveError ?: defaults.videoStatus +
      if (controller.recordings.session.kind == RecordingSession.Kind.VIDEO) "\nThe current video keeps its original settings. New defaults apply to the next video." else ""
    LabUi.enabled(photoSave, photoDraft() != defaults.photo)
    LabUi.enabled(videoSave, videoDraft() != defaults.video)
    photoRetry.visibility = if (defaults.photoCanRetry) View.VISIBLE else View.GONE
    videoRetry.visibility = if (defaults.videoCanRetry) View.VISIBLE else View.GONE
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putBoolean("video_offload_draft", videoOffload.isChecked)
    outState.putBoolean("photo_flash_draft", photoFlash.isChecked)
    outState.putBoolean("video_flash_draft", videoFlash.isChecked)
    outState.putBoolean("capture_drafts_initialized", draftsInitialized)
    outState.putInt("photo_size_draft", photoSize.selectedItemPosition)
    outState.putInt("video_resolution_draft", videoResolution.selectedItemPosition)
    outState.putInt("video_fps_draft", videoFps.selectedItemPosition)
    outState.putInt("video_duration_draft", videoDuration.selectedItemPosition)
    super.onSaveInstanceState(outState)
  }

  override fun onCompanionReady() {
    if (!defaultsBound) controller.observe(defaultsObserver)
    defaultsBound = true
    if (!draftsInitialized) {
      val defaults = controller.defaults
      photoSize.setSelection(photoSizes.indexOf(defaults.photo.size).coerceAtLeast(0))
      videoResolution.setSelection(resolutions.indexOf(defaults.video.width to defaults.video.height).coerceAtLeast(0))
      videoFps.setSelection(frameRates.indexOf(defaults.video.fps).coerceAtLeast(0))
      videoDuration.setSelection(durations.indexOf(defaults.video.maxMinutes).coerceAtLeast(0))
      videoOffload.isChecked = defaults.video.offloadToPhone
      photoFlash.isChecked = defaults.photo.enableFlash
      videoFlash.isChecked = defaults.video.enableFlash
      draftsInitialized = true
    }
    refreshDefaults()
    refresh()
  }
  override fun onCompanionUnavailable() {
    if (defaultsBound) controller.unobserve(defaultsObserver)
    defaultsBound = false
  }
  private fun refresh() {
    scope.launch {
      val used = withContext(Dispatchers.IO) { repository.usedStorageBytes() }
      val policy = settings.policy()
      val remaining = policy.remaining(used, repository.directory.usableSpace)
      storageStatus.text = "${LabUi.size(used)} of audio and video\n${LabUi.size(remaining.coerceAtLeast(0))} available for recording\n" +
        "${LabUi.size(repository.directory.usableSpace)} free on phone · ${LabUi.size(policy.reserveBytes)} kept free"
      val notifications = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
      val unrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
      val associated = runCatching { CompanionAssociation.associated(this@RecordingSettingsActivity, controller.savedDeviceAddress) }.getOrDefault(false)
      backgroundStatus.text = "Notifications · ${if (notifications) "Ready" else "Not allowed"}\n" +
        "Background association · ${if (associated) "Ready" else "Setup needed"}\n" +
        "Battery use · ${if (unrestricted) "Unrestricted" else "Optimized by Android"}"
      association.text = if (associated) "Check background association" else "Enable background association"
    }
  }
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == CompanionAssociation.REQUEST && resultCode == RESULT_OK) {
      controller.savedDeviceAddress?.let { runCatching { CompanionAssociation.observe(this, it) } }
      refresh()
    }
  }
  override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
