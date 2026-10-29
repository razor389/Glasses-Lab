package dev.ross.glasses

import android.app.Activity
import android.content.*
import android.os.*
import android.view.View
import android.view.ViewGroup

/** Screens bind to the service; only explicit work keeps the service started. */
abstract class CompanionActivity : Activity() {
  protected lateinit var controller: GlassesController
  private var connected = false
  private var visible = false
  private var bound = false
  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      controller = (service as CompanionService.LocalBinder).owner.controller
      connected = true
      enable(window.decorView, true)
      if (visible) showCompanion()
    }
    override fun onServiceDisconnected(name: ComponentName?) {
      if (connected && visible) onCompanionUnavailable()
      connected = false
      enable(window.decorView, false)
    }
  }
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    bound = bindService(Intent(this, CompanionService::class.java), connection, BIND_AUTO_CREATE)
  }
  override fun onStart() {
    super.onStart()
    visible = true
    if (connected) showCompanion() else enable(window.decorView, false)
  }
  override fun onStop() {
    if (connected) onCompanionUnavailable()
    visible = false
    super.onStop()
  }
  override fun onDestroy() {
    if (bound) unbindService(connection)
    bound = false
    super.onDestroy()
  }
  protected abstract fun onCompanionReady()
  protected abstract fun onCompanionUnavailable()
  private fun showCompanion() {
    controller.onAppVisible()
    onCompanionReady()
  }
  private fun enable(view: View, value: Boolean) {
    view.isEnabled = value
    if (view is ViewGroup) for (index in 0 until view.childCount) enable(view.getChildAt(index), value)
  }
}
