package dev.ross.glasses

import android.app.Activity
import android.companion.*
import android.bluetooth.le.ScanFilter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.IntentSender
import android.os.*

/** Association grants background presence privileges; the SDK still owns GATT. */
@Suppress("DEPRECATION")
object CompanionAssociation {
  const val REQUEST = 70
  fun associated(context: Context, address: String?): Boolean = address != null &&
    context.getSystemService(CompanionDeviceManager::class.java)?.associations?.any { it.equals(address, true) } == true

  fun observe(context: Context, address: String) {
    if (Build.VERSION.SDK_INT >= 31 && associated(context, address)) {
      context.getSystemService(CompanionDeviceManager::class.java).startObservingDevicePresence(address)
    }
  }

  fun associate(activity: Activity, address: String, result: (String) -> Unit) {
    val manager = activity.getSystemService(CompanionDeviceManager::class.java)
      ?: error("Companion association is unavailable on this phone.")
    if (associated(activity, address)) { observe(activity, address); result("Background association is enabled."); return }
    // The already-bonded audio device can be found while its BLE link is connected.
    val bonded = runCatching { activity.getSystemService(BluetoothManager::class.java).adapter
      ?.bondedDevices?.any { it.address.equals(address, true) } == true }.getOrDefault(false)
    val filter = if (bonded) BluetoothDeviceFilter.Builder().setAddress(address).build()
      else BluetoothLeDeviceFilter.Builder().setScanFilter(ScanFilter.Builder().setDeviceAddress(address).build()).build()
    val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
    result("Looking for your selected glasses. Approve Android's association prompt when it appears.")
    manager.associate(request, object : CompanionDeviceManager.Callback() {
      private fun show(sender: IntentSender) {
        if (!activity.isFinishing && !activity.isDestroyed) {
          runCatching { activity.startIntentSenderForResult(sender, REQUEST, null, 0, 0, 0) }
            .onFailure { result("Could not open association: ${it.message}") }
        }
      }
      override fun onDeviceFound(chooserLauncher: IntentSender) = show(chooserLauncher)
      override fun onAssociationPending(intentSender: IntentSender) = show(intentSender)
      override fun onAssociationCreated(associationInfo: AssociationInfo) {
        runCatching { observe(activity, address) }
          .onSuccess { result("Background association is enabled.") }
          .onFailure { result("Associated, but presence monitoring failed: ${it.message}") }
      }
      override fun onFailure(error: CharSequence?) { result(error?.toString() ?: "Association was not completed.") }
    }, Handler(Looper.getMainLooper()))
  }
}
