package io.github.kazumaproject.cuperalauncher

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun searchCanOpenFromHomeAndFiltersApps() {
        val home = open(HomeActivity::class.java)
        try {
            waitFor(home) { views -> views.any { it is TextView && it.text == "⌕  Search" } }
            onMain {
                val search = descendants(home.window.decorView).filterIsInstance<TextView>().first { it.text == "⌕  Search" }
                assertTrue(search.performClick())
            }
            waitFor(home) { views -> views.any { it is EditText && it.hint == "Search apps" } }
            onMain { descendants(home.window.decorView).filterIsInstance<EditText>().first().setText("cupera") }
            waitFor(home) { views -> views.any { it is TextView && it.text == "Cupera Launcher" } }
            onMain {
                val names = descendants(home.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
                assertFalse(names.contains("No apps found"))
                assertFalse(names.contains("Chrome"))
            }
        } finally { onMain { home.finish() } }
    }

    @Test fun settingsOpensItsDisplaySection() {
        val settings = open(SettingsActivity::class.java)
        try {
            waitFor(settings) { views -> views.any { it is TextView && it.text == "Cupera Settings" } }
            onMain {
                val row = descendants(settings.window.decorView).filterIsInstance<TextView>().first { it.text == "Icons & Dock" }
                var clickable: View = row
                while (!clickable.isClickable) clickable = clickable.parent as View
                assertTrue(clickable.performClick())
            }
            waitFor(settings) { views -> views.any { it is TextView && it.text == "Show Dock" } }
        } finally { onMain { settings.finish() } }
    }

    private fun open(type: Class<out Activity>): Activity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun waitFor(activity: Activity, predicate: (Sequence<View>) -> Boolean) {
        val end = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < end) {
            var ready = false
            onMain { ready = predicate(descendants(activity.window.decorView)) }
            if (ready) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected view not found in ${activity.javaClass.simpleName}")
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
