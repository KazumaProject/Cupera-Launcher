package io.github.kazumaproject.cuperalauncher

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

fun Context.dp(value: Float) = (value * resources.displayMetrics.density).roundToInt()
fun Context.dp(value: Int) = dp(value.toFloat())

fun rounded(fill: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = radius
    stroke?.let { setStroke(1, it) }
}

fun Context.label(value: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

fun Context.dark(store: Workspace) = when (store.setting("appearance", "system")) {
    "light" -> false
    "dark" -> true
    else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}

fun Activity.systemBars(
    root: View, dark: Boolean, keepStatusInset: Boolean = false, padForIme: Boolean = true
) {
    window.setDecorFitsSystemWindows(false)
    window.isNavigationBarContrastEnforced = false
    window.insetsController?.setSystemBarsAppearance(
        if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    )
    root.setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val top = if (keepStatusInset) maxOf(bars.top, insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top)
            else bars.top
        val bottom = if (padForIme) maxOf(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom) else bars.bottom
        view.setPadding(bars.left, top, bars.right, bottom)
        insets
    }
}

val ink = Color.rgb(20, 24, 32)
val accent = Color.rgb(54, 117, 236)
