package dev.ross.glasses

import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import com.mentra.bluetoothsdk.DeviceManager
import org.junit.Test
import org.junit.runner.RunWith

/** Run while idle: launches controls and may restore a requested connection; never starts capture. */
@RunWith(AndroidJUnit4::class)
class CompanionActivityDeviceTest {
  @Test fun hostOwnsForegroundLifecycleWithoutPhoneMicrophonePermission() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val manager = context.packageManager
    val info = manager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
    assertTrue(info.metaData.getBoolean(DeviceManager.HOST_MANAGES_CONNECTION_LIFECYCLE))
    val declared = manager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
    assertFalse(declared.contains(android.Manifest.permission.RECORD_AUDIO))
    assertFalse(declared.contains(android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE))
    assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
      manager.getServiceInfo(ComponentName(context, CompanionService::class.java), 0).foregroundServiceType)
    try {
      manager.getServiceInfo(ComponentName(context.packageName, "com.mentra.bluetoothsdk.services.ForegroundService"), 0)
      fail("The SDK's separate service must not be registered by this host")
    } catch (_: PackageManager.NameNotFoundException) {}
  }

  @Test fun mainScreenRendersAfterAsynchronousServiceBinding() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    try {
      fun rendered(view: View): Boolean = (view is TextView && view.text.startsWith("Media audio:")) ||
        (view is ViewGroup && (0 until view.childCount).any { rendered(view.getChildAt(it)) })
      var ready = false
      val deadline = System.nanoTime() + 5_000_000_000L
      while (!ready && System.nanoTime() < deadline) {
        instrumentation.runOnMainSync { ready = rendered(activity.window.decorView) }
        if (!ready) Thread.sleep(50)
      }
      assertTrue("Main screen did not bind and render", ready)
    } finally { instrumentation.runOnMainSync { activity.finish() } }
  }
}
