package dev.ross.glasses

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Manifest-only checks: never dispatches a capture, edits a tile, or modifies preferences. */
@RunWith(AndroidJUnit4::class)
class QuickControlsDeviceTest {
  @Test fun systemTilesArePermissionProtectedAndShortcutsOpenVisibleActivity() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val manager = context.packageManager
    listOf(PhotoTileService::class.java, VideoTileService::class.java, AudioTileService::class.java).forEach { type ->
      val info = manager.getServiceInfo(ComponentName(context, type), PackageManager.GET_META_DATA)
      assertTrue(info.exported)
      assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", info.permission)
      assertTrue(info.metaData.getBoolean("android.service.quicksettings.ACTIVE_TILE"))
    }
    assertFalse(manager.getServiceInfo(ComponentName(context, CompanionService::class.java), 0).exported)
    val activity = manager.getActivityInfo(ComponentName(context, MainActivity::class.java), PackageManager.GET_META_DATA)
    val resource = activity.metaData.getInt("android.app.shortcuts")
    assertTrue(resource != 0)
    val actions = mutableSetOf<String>()
    context.resources.getXml(resource).use { parser ->
      while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "intent") {
          val namespace = "http://schemas.android.com/apk/res/android"
          assertEquals(context.packageName, parser.getAttributeValue(namespace, "targetPackage"))
          assertEquals(MainActivity::class.java.name, parser.getAttributeValue(namespace, "targetClass"))
          actions.add(parser.getAttributeValue(namespace, "action"))
        }
        parser.next()
      }
    }
    assertEquals(setOf(QuickControls.PHOTO, QuickControls.VIDEO, QuickControls.AUDIO), actions)
  }
}
