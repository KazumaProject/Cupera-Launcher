package io.github.kazumaproject.cuperalauncher

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LauncherScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun slowShortDragDoesNotTurnIntoAnEditingHold() {
        val home = open(HomeActivity::class.java)
        try {
            val (x, y) = whitespaceBelowGrid(home)
            val width = home.resources.displayMetrics.widthPixels
            val duration = maxOf(600L, ViewConfiguration.getLongPressTimeout() * 2L)
            val down = SystemClock.uptimeMillis()
            touch(down, MotionEvent.ACTION_DOWN, x, y)
            for (sample in 1..12) {
                SystemClock.sleep(maxOf(0L, down + sample * duration / 12 - SystemClock.uptimeMillis()))
                touch(down, MotionEvent.ACTION_MOVE, x + width * .03f * sample / 12, y)
            }
            touch(down, MotionEvent.ACTION_UP, x + width * .03f, y)
            onMain {
                assertFalse(descendants(home.window.decorView).filterIsInstance<TextView>()
                    .any { it.text == "Add Widget" })
            }
        } finally { onMain { home.finish() } }
    }

    @Test fun longPressInWhitespaceBelowTheGridOpensEditing() {
        val home = open(HomeActivity::class.java)
        try {
            val (x, y) = whitespaceBelowGrid(home)
            var originalGridTop = 0
            onMain {
                val viewport = descendants(home.window.decorView).filterIsInstance<ScrollView>().first()
                val location = IntArray(2).also(viewport::getLocationOnScreen)
                originalGridTop = location[1]
            }
            val down = SystemClock.uptimeMillis()
            touch(down, MotionEvent.ACTION_DOWN, x, y)
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100L)
            touch(down, MotionEvent.ACTION_UP, x, y)
            waitFor(home) { views -> views.any { it is TextView && it.text == "Add Widget" && it.height > 0 } }
            onMain {
                val viewport = descendants(home.window.decorView).filterIsInstance<ScrollView>().first()
                val location = IntArray(2).also(viewport::getLocationOnScreen)
                assertEquals(originalGridTop, location[1])
            }
        } finally { onMain { home.finish() } }
    }

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

    @Test fun relockingPrivateSpaceDuringSearchKeepsItsAppsOutOfResults() {
        val fixture = InstrumentationRegistry.getArguments().getString("privateFixture")
        assumeTrue("Requires an isolated emulator with an unlocked private fixture", !fixture.isNullOrBlank())
        val apps = Apps(instrumentation.targetContext)
        val user = requireNotNull(apps.privateUser())
        val title = apps.privateEntries(user).single { it.info.componentName.packageName == fixture }.title
        val home = open(HomeActivity::class.java)
        try {
            waitFor(home) { views -> views.any { it is TextView && it.text == "⌕  Search" } }
            onMain {
                val search = descendants(home.window.decorView).filterIsInstance<TextView>().first { it.text == "⌕  Search" }
                assertTrue(search.performClick())
                descendants(home.window.decorView).filterIsInstance<EditText>().first().setText(title)
            }
            waitFor(home) { views -> views.any { it is TextView && it.text == "No apps found" } }
            assertTrue(apps.requestPrivate(user, locked = true))
            onMain {
                val search = descendants(home.window.decorView).filterIsInstance<EditText>().first()
                search.setText("")
                search.setText(title)
            }
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (apps.privateUnlocked(user) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
            assertFalse(apps.privateUnlocked(user))
            assertTrue(apps.privateEntries(user).isEmpty())
            waitFor(home) { views -> views.any { it is TextView && it.text == "No apps found" } }
            onMain {
                assertFalse(descendants(home.window.decorView).filterIsInstance<TextView>()
                    .any { it !is EditText && it.text.toString() == title })
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

    private fun whitespaceBelowGrid(home: Activity): Pair<Float, Float> {
        waitFor(home) { views -> views.any { it is ScrollView && it.height > it.getChildAt(0).bottom } }
        val point = FloatArray(2)
        onMain {
            val viewport = descendants(home.window.decorView).filterIsInstance<ScrollView>().first()
            val location = IntArray(2).also(viewport::getLocationOnScreen)
            point[0] = location[0] + viewport.width / 2f
            point[1] = location[1] + (viewport.getChildAt(0).bottom + viewport.height) / 2f
        }
        return point[0] to point[1]
    }

    private fun touch(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
    }

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
