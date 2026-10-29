package dev.ross.glasses

import android.companion.CompanionDeviceService
import android.content.Intent
import android.util.Log

/** System-bound only; reconnects the selected glasses without starting a recording. */
@Suppress("DEPRECATION")
class CompanionPresenceService : CompanionDeviceService() {
  override fun onDeviceAppeared(address: String) {
    val prefs = getSharedPreferences("companion", MODE_PRIVATE)
    if (!prefs.getBoolean("stay_connected", false) || !address.equals(prefs.getString("address", null), true)) return
    runCatching { startForegroundService(Intent(this, CompanionService::class.java).setAction(CompanionService.RECONNECT)) }
      .onFailure { Log.w("GlassesLab", "Background reconnect unavailable; open Glasses Lab", it) }
  }
}
