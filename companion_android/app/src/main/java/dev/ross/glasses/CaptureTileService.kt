package dev.ross.glasses

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import java.util.Locale

/** System-bound controls dispatch only the current tap; no delayed capture or reconnect queue. */
abstract class CaptureTileService : TileService() {
  protected abstract val captureAction: String
  protected abstract val captureKind: String
  protected abstract val title: String
  protected abstract val iconResource: Int

  override fun onStartListening() { super.onStartListening(); render() }
  override fun onTileAdded() { super.onTileAdded(); render() }
  override fun onClick() {
    super.onClick()
    val state = QuickControls.read(this)
    val stopping = state.isStopping(captureKind)
    if (!state.canStart(captureAction) && !stopping) {
      message(state.unavailableReason(captureAction))
      render()
      return
    }
    try {
      startForegroundService(Intent(this, CompanionService::class.java).setAction(captureAction))
    } catch (_: Exception) {
      message("Open Glasses Lab to use this control and check background permissions.")
    }
    // Acknowledged service state will refresh the tile; never claim capture started here.
    render()
  }

  private fun message(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
  private fun render() {
    val tile = qsTile ?: return
    val current = QuickControls.read(this)
    val stopping = current.isStopping(captureKind)
    val unavailable = !current.canStart(captureAction) && !stopping
    tile.label = if (stopping) "Stop ${captureKind.lowercase(Locale.US)}" else title
    tile.icon = Icon.createWithResource(this, iconResource)
    tile.state = when { stopping -> Tile.STATE_ACTIVE; unavailable -> Tile.STATE_UNAVAILABLE; else -> Tile.STATE_INACTIVE }
    val description = when {
      stopping -> "Tap to stop ${captureKind.lowercase(Locale.US)} recording"
      unavailable -> current.unavailableReason(captureAction)
      captureKind == "VIDEO" -> "Record video with sound"
      captureKind == "AUDIO" -> "Record glasses microphone"
      else -> "Take a glasses photo"
    }
    tile.contentDescription = "${tile.label}. $description"
    if (Build.VERSION.SDK_INT >= 29) tile.subtitle = description
    if (Build.VERSION.SDK_INT >= 30) tile.stateDescription = description
    tile.updateTile()
  }
}

class PhotoTileService : CaptureTileService() {
  override val captureAction = QuickControls.PHOTO
  override val captureKind = ""
  override val title = "Glasses photo"
  override val iconResource = R.drawable.ic_quick_photo
}

class VideoTileService : CaptureTileService() {
  override val captureAction = QuickControls.VIDEO
  override val captureKind = "VIDEO"
  override val title = "Glasses video"
  override val iconResource = R.drawable.ic_quick_video
}

class AudioTileService : CaptureTileService() {
  override val captureAction = QuickControls.AUDIO
  override val captureKind = "AUDIO"
  override val title = "Glasses audio"
  override val iconResource = R.drawable.ic_quick_audio
}
