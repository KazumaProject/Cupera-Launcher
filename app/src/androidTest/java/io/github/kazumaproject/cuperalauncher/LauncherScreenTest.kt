package io.github.kazumaproject.cuperalauncher

import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
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
            val automation = instrumentation.uiAutomation
            val moveTimes = mutableListOf<Long>()
            val down = SystemClock.uptimeMillis()
            touch(down, MotionEvent.ACTION_DOWN, x, y, automation)
            for (sample in 1..12) {
                SystemClock.sleep(maxOf(0L, down + sample * duration / 12 - SystemClock.uptimeMillis()))
                moveTimes.add(SystemClock.uptimeMillis() - down)
                touch(down, MotionEvent.ACTION_MOVE, x + width * .03f * sample / 12, y, automation, sync = false)
            }
            touch(down, MotionEvent.ACTION_UP, x + width * .03f, y, automation)
            onMain {
                assertFalse("Move events injected at ${moveTimes.joinToString()} ms", descendants(home.window.decorView).filterIsInstance<TextView>()
                    .any { it.text == "Edit" })
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
            waitFor(home) { views -> views.any { it is TextView && it.text == "Edit" && it.height > 0 } }
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

    @Test fun cancellingFolderDragKeepsLayoutAndReleasingCreatesAnOpenableFolder() {
        assumeTrue("Requires an isolated emulator with Photos and Play Store on Home",
            InstrumentationRegistry.getArguments().getString("folderFixture") == "true")
        Workspace(instrumentation.targetContext).use { workspace ->
            val initial = workspace.read()
            val apps = Apps(instrumentation.targetContext).entries()
            val photosKey = apps.single { it.title == "Photos" }.key
            val playStoreKey = apps.single { it.title == "Play Store" }.key
            val photos = initial.tiles.single { it.key == photosKey }
            val playStore = initial.tiles.single { it.key == playStoreKey }
            assertEquals(0, photos.slot.page)
            assertEquals(0, playStore.slot.page)
            val home = open(HomeActivity::class.java)
            try {
                val (blankX, blankY) = whitespaceBelowGrid(home)
                val hold = SystemClock.uptimeMillis()
                touch(hold, MotionEvent.ACTION_DOWN, blankX, blankY)
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100L)
                touch(hold, MotionEvent.ACTION_UP, blankX, blankY)
                waitFor(home) { views -> views.any { it is TextView && it.text == "Edit" && it.height > 0 } }

                val source = screenCenter(home, "Photos")
                val destination = screenCenter(home, "Play Store")
                dragAndHold(source, destination, MotionEvent.ACTION_CANCEL)
                assertEquals(initial, workspace.read())

                dragAndHold(source, destination, MotionEvent.ACTION_UP)
                waitFor(home) { views -> views.any { it.contentDescription == "Folder" } }
                val saved = workspace.read()
                val folder = saved.tiles.single { it.kind == TileKind.FOLDER && initial.tiles.none { before -> before.id == it.id } }
                assertEquals(setOf(photos.id, playStore.id), saved.tiles.filter { it.slot.folder == folder.id }.map { it.id }.toSet())
                assertEquals(initial.revision + 1, saved.revision)
                onMain {
                    descendants(home.window.decorView).filterIsInstance<TextView>().first { it.text == "Done" }.performClick()
                }
                waitFor(home) { views -> views.any { it.contentDescription == "Folder" && it.width > 0 } }
                val (folderX, folderY) = screenCenter(home, "Folder")
                val tap = SystemClock.uptimeMillis()
                touch(tap, MotionEvent.ACTION_DOWN, folderX, folderY)
                touch(tap, MotionEvent.ACTION_UP, folderX, folderY)
                waitFor(home) { views -> views.any {
                    it.contentDescription == "Folder. Rename folder" && it.height > 0 && it.alpha == 1f &&
                        (it.parent as View).scaleX == 1f
                } }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                waitFor(home) { views ->
                    views.none { it.contentDescription == "Folder. Rename folder" } &&
                        views.any { it.contentDescription == "Folder" && it.alpha == 1f }
                }
                assertEquals(saved, workspace.read())
            } finally {
                onMain { home.finish() }
                val saved = workspace.read()
                if (saved.revision == initial.revision + 1) assertTrue(workspace.save(saved.revision, initial.tiles))
            }
        }
    }

    @Suppress("DEPRECATION")
    @Test fun backDuringFolderOpeningReturnsWithoutEditingPlacement() {
        assumeTrue("Requires an isolated Home workspace",
            InstrumentationRegistry.getArguments().getString("folderFixture") == "true")
        Workspace(instrumentation.targetContext).use { store ->
            val initial = store.read()
            val (source, target) = initial.tiles.filter { it.isApp && it.slot.page == 0 && it.slot.folder.isEmpty() }.take(2)
            val placed = requireNotNull(initial.move(source.id, target.slot, createFolder = true))
            assertTrue(store.save(initial.revision, placed))
            val saved = store.read()
            var activity: Activity? = null
            try {
                val home = open(HomeActivity::class.java).also { activity = it }
                waitFor(home) { views -> views.any { it.contentDescription == "Folder" && it.width > 0 } }
                onMain {
                    val views = descendants(home.window.decorView)
                    assertTrue(views.first { it.contentDescription == "Folder" }.performClick())
                    val title = views.first { it.contentDescription == "Folder. Rename folder" }
                    assertTrue("Back must be sent before the folder is fully open", (title.parent as View).scaleX < 1f)
                    home.onBackPressed()
                }
                waitFor(home) { views ->
                    views.none { it.contentDescription == "Folder. Rename folder" } &&
                        views.any { it.contentDescription == "Folder" && it.alpha == 1f }
                }
                assertEquals(saved, store.read())
            } finally {
                activity?.let { home -> onMain { home.finish() } }
                if (store.read().revision == saved.revision) assertTrue(store.save(saved.revision, initial.tiles))
            }
        }
    }

    private fun open(type: Class<out Activity>): Activity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun screenCenter(activity: Activity, description: String): Pair<Float, Float> {
        val point = FloatArray(2)
        onMain {
            val view = descendants(activity.window.decorView).first { it.contentDescription == description }
            val location = IntArray(2).also(view::getLocationOnScreen)
            point[0] = location[0] + view.width / 2f
            point[1] = location[1] + view.height / 2f
        }
        return point[0] to point[1]
    }

    private fun dragAndHold(from: Pair<Float, Float>, to: Pair<Float, Float>, finish: Int) {
        val automation = instrumentation.uiAutomation
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, from.first, from.second, automation)
        for (sample in 1..24) {
            SystemClock.sleep(maxOf(0L, down + 180 + sample * 650 / 24 - SystemClock.uptimeMillis()))
            val x = from.first + (to.first - from.first) * sample / 24
            val y = from.second + (to.second - from.second) * sample / 24
            touch(down, MotionEvent.ACTION_MOVE, x, y, automation, sync = false)
        }
        SystemClock.sleep(850)
        touch(down, finish, to.first, to.second, automation)
    }

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

    private fun touch(down: Long, action: Int, x: Float, y: Float, automation: UiAutomation? = null, sync: Boolean = true) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            if (automation == null) instrumentation.sendPointerSync(event)
            else assertTrue("Could not inject ${MotionEvent.actionToString(action)}", automation.injectInputEvent(event, sync))
        } finally { event.recycle() }
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
