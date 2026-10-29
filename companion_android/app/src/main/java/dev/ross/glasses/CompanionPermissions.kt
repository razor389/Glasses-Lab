package dev.ross.glasses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Essential connection permissions shared by visible setup and background recording readiness. */
object CompanionPermissions {
  fun required(): List<String> = buildList {
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= 31) {
      add(Manifest.permission.BLUETOOTH_SCAN)
      add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
  }

  fun granted(context: Context): Boolean = required().all {
    context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
  }
}
