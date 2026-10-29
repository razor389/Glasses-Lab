package dev.ross.glasses

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared native styling keeps capture, library, and settings visually consistent. */
internal object LabUi {
  val background = Color.rgb(12, 20, 25)
  val surface = Color.rgb(23, 35, 42)
  val surfaceRaised = Color.rgb(33, 47, 55)
  val ink = Color.rgb(240, 245, 246)
  val muted = Color.rgb(162, 181, 189)
  val accent = Color.rgb(153, 230, 207)
  val video = Color.rgb(246, 192, 145)
  val audio = Color.rgb(198, 188, 249)
  val danger = Color.rgb(255, 174, 164)

  fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

  fun column(activity: Activity) = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

  fun page(activity: Activity, title: String): LinearLayout {
    val scroll = ScrollView(activity).apply {
      setBackgroundColor(LabUi.background)
      isFillViewport = true
      clipToPadding = false
    }
    val page = column(activity).apply { setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 32)) }
    scroll.addView(page)
    activity.setContentView(scroll)
    scroll.setOnApplyWindowInsetsListener { view, insets ->
      view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
      insets
    }
    page.addView(text(activity, title, 29).apply { typeface = Typeface.DEFAULT_BOLD })
    return page
  }

  fun text(activity: Activity, label: String, size: Int, color: Int = ink) = TextView(activity).apply {
    text = label
    textSize = size.toFloat()
    setTextColor(color)
    setLineSpacing(dp(activity, 3).toFloat(), 1f)
    setPadding(0, dp(activity, 5), 0, dp(activity, 5))
  }

  fun shape(activity: Activity, color: Int, radius: Int = 18) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(activity, radius).toFloat()
  }

  fun card(activity: Activity, parent: LinearLayout, color: Int = surface): LinearLayout = column(activity).apply {
    setPadding(dp(activity, 17), dp(activity, 13), dp(activity, 17), dp(activity, 16))
    background = shape(activity, color)
    parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 14) })
  }

  fun button(activity: Activity, label: String, primary: Boolean = false, tint: Int = accent, action: () -> Unit) = Button(activity).apply {
    text = label
    isAllCaps = false
    textSize = 15f
    gravity = Gravity.CENTER
    typeface = Typeface.DEFAULT_BOLD
    minHeight = dp(activity, 50)
    minimumHeight = dp(activity, 50)
    setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8))
    setTextColor(if (primary) LabUi.background else tint)
    background = RippleDrawable(ColorStateList.valueOf(Color.argb(36, 255, 255, 255)),
      shape(activity, if (primary) tint else surfaceRaised, 14), null)
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 8) }
    setOnClickListener { action() }
  }

  fun enabled(view: View, enabled: Boolean) { view.isEnabled = enabled; view.alpha = if (enabled) 1f else .4f }

  fun row(activity: Activity, parent: LinearLayout, vararg views: View) {
    val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    views.forEachIndexed { index, view ->
      row.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply {
        topMargin = dp(activity, 8)
        if (index > 0) marginStart = dp(activity, 8)
      })
    }
    parent.addView(row)
  }

  class Disclosure(val toggle: Button, val body: LinearLayout, private val title: String) {
    var expanded = false
      private set
    fun expand(value: Boolean) {
      expanded = value
      body.visibility = if (value) View.VISIBLE else View.GONE
      toggle.text = "$title  ${if (value) "−" else "+"}"
      toggle.contentDescription = "$title, ${if (value) "collapse" else "expand"}"
    }
  }

  fun disclosure(activity: Activity, parent: LinearLayout, title: String): Disclosure {
    val wrapper = card(activity, parent)
    val body = column(activity)
    lateinit var result: Disclosure
    val toggle = button(activity, title) { result.expand(!result.expanded) }.apply {
      gravity = Gravity.START or Gravity.CENTER_VERTICAL
      background = null
      setPadding(0, 0, 0, 0)
      layoutParams = LinearLayout.LayoutParams(-1, dp(activity, 48))
    }
    wrapper.addView(toggle)
    wrapper.addView(body)
    result = Disclosure(toggle, body, title)
    result.expand(false)
    return result
  }

  fun date(millis: Long): String = if (millis <= 0) "Saved recording" else
    SimpleDateFormat("MMM d · h:mm a", Locale.getDefault()).format(Date(millis))

  fun duration(seconds: Long): String {
    val value = seconds.coerceAtLeast(0)
    return if (value >= 3600) String.format(Locale.getDefault(), "%d:%02d:%02d", value / 3600, value / 60 % 60, value % 60)
    else String.format(Locale.getDefault(), "%d:%02d", value / 60, value % 60)
  }

  fun size(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.getDefault(), "%.2f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
    else -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1000.0)
  }
}
