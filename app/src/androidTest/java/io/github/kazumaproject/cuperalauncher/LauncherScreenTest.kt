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
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun longPressUsesEventDurationWhenDeliveryIsDelayed() {
        val home = open(HomeActivity::class.java)
        try {
            val (x, y) = whitespaceBelowGrid(home)
            val automation = instrumentation.uiAutomation
            val timeout = ViewConfiguration.getLongPressTimeout().toLong()
            val blocked = CountDownLatch(1)
            val release = CountDownLatch(1)
            onMain {
                home.window.decorView.post {
                    blocked.countDown()
                    release.await(timeout + 3_000, TimeUnit.MILLISECONDS)
                }
            }
            try {
                assertTrue(blocked.await(2, TimeUnit.SECONDS))
                val down = SystemClock.uptimeMillis()
                touch(down, MotionEvent.ACTION_DOWN, x, y, automation, sync = false)
                SystemClock.sleep(timeout + 75)
                touch(down, MotionEvent.ACTION_UP, x, y, automation, sync = false)
            } finally {
                release.countDown()
            }
            waitFor(home) { views -> views.any { it is TextView && it.text == "Edit" && it.height > 0 } }
        } finally { onMain { home.finish() } }
    }

    @Test fun reversingAPageDragKeepsItsPageAndTheNextSwipeStillWorks() = pageDrag(reduceMotion = false)

    @Test fun reducedMotionKeepsHomePagesFollowingTheFinger() = pageDrag(reduceMotion = true)

    private fun pageDrag(reduceMotion: Boolean) {
        assumeTrue("Requires an isolated emulator with an editable home layout",
            InstrumentationRegistry.getArguments().getString("pageFixture") == "true")
        if (reduceMotion) assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        Workspace(instrumentation.targetContext).use { workspace ->
            val initial = workspace.read()
            assumeTrue(workspace.hiddenPages().isEmpty() && initial.pages == 1)
            val previousMotion = workspace.setting("motion", "system")
            val marker = Tile(java.util.UUID.randomUUID().toString(), TileKind.DATE, Slot(1, 0))
            assertTrue(workspace.save(initial.revision, initial.tiles + marker))
            var activity: Activity? = null
            try {
                if (reduceMotion) workspace.put("motion", "reduced")
                val home = open(HomeActivity::class.java).also { activity = it }
                val (x, y) = whitespaceBelowGrid(home)
                val travel = home.resources.displayMetrics.widthPixels * .34f
                fun swipe(vararg offsets: Float, verifyPreview: Boolean = false) {
                    val automation = instrumentation.uiAutomation
                    val down = SystemClock.uptimeMillis()
                    var movedBeforeRelease = false
                    touch(down, MotionEvent.ACTION_DOWN, x, y, automation)
                    val start = SystemClock.uptimeMillis()
                    offsets.forEachIndexed { index, offset ->
                        SystemClock.sleep(maxOf(0L, start + (index + 1) * 30L - SystemClock.uptimeMillis()))
                        touch(down, MotionEvent.ACTION_MOVE, x + offset, y, automation)
                        if (verifyPreview && index == 0) onMain {
                            movedBeforeRelease = descendants(home.window.decorView)
                                .any { it.translationX in -travel..(-travel / 4f) }
                        }
                    }
                    touch(down, MotionEvent.ACTION_UP, x + offsets.last(), y, automation)
                    if (verifyPreview) assertTrue("The home page must move before release", movedBeforeRelease)
                }
                swipe(-travel / 2, -travel, -travel / 2, 0f, verifyPreview = reduceMotion)
                waitFor(home) { views -> views.any { it.contentDescription == "Page 1 of 2. Next page" } }
                swipe(-travel / 2, -travel)
                waitFor(home) { views -> views.any { it.contentDescription == "Page 2 of 2. Next page" } }
                swipe(travel / 2, travel)
                waitFor(home) { views -> views.any { it.contentDescription == "Page 1 of 2. Next page" } }
                assertEquals(initial.tiles + marker, workspace.read().tiles)
            } finally {
                activity?.let { home -> onMain { home.finish() } }
                if (reduceMotion) workspace.put("motion", previousMotion)
                val current = workspace.read()
                if (current.tiles.any { it.id == marker.id }) {
                    assertTrue(workspace.save(current.revision, current.tiles.filterNot { it.id == marker.id }))
                }
            }
        }
    }

    @Test fun searchSwipePreviewsBeforeReleaseAndCanReverse() {
        assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        val home = open(HomeActivity::class.java)
        val automation = instrumentation.uiAutomation
        try {
            val size = home.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
            val x = size.width() * .5f
            val y = size.height() * .32f
            fun homeLayer(): View {
                var layer: View? = null
                onMain {
                    val footer = accessibleDescendants(home.window.decorView).filterIsInstance<TextView>()
                        .first { it.text == "⌕  Search" }
                    layer = (footer.parent as View).parent as View
                }
                return checkNotNull(layer)
            }
            fun preview(): Long {
                waitFor(home) { views ->
                    home.hasWindowFocus() && views.any { it is TextView && it.text == "⌕  Search" }
                }
                val down = SystemClock.uptimeMillis()
                touch(down, MotionEvent.ACTION_DOWN, x, y, automation)
                touch(down, MotionEvent.ACTION_MOVE, x, y + size.height() * .025f, automation)
                touch(down, MotionEvent.ACTION_MOVE, x, y + size.height() * .15f, automation)
                waitFor(home) { views -> views.any { it is EditText && it.hint == "Search apps" } }
                onMain {
                    assertFalse(descendants(home.window.decorView).filterIsInstance<EditText>().first().hasFocus())
                }
                return down
            }
            waitFor(home) { views -> views.any { it is TextView && it.text == "⌕  Search" } }
            val initialLayer = homeLayer()
            val reversed = preview()
            touch(reversed, MotionEvent.ACTION_MOVE, x, y, automation)
            touch(reversed, MotionEvent.ACTION_UP, x, y, automation)
            waitFor(home) { views -> views.none { it is EditText && it.hint == "Search apps" } }
            assertTrue(initialLayer === homeLayer())

            val interrupted = preview()
            onMain { home.onBackPressed() }
            touch(interrupted, MotionEvent.ACTION_UP, x, y + size.height() * .15f, automation)
            waitFor(home) { views -> views.none { it is EditText && it.hint == "Search apps" } }

            val committed = preview()
            touch(committed, MotionEvent.ACTION_UP, x, y + size.height() * .15f, automation)
            waitFor(home) { views -> views.any { it is EditText && it.hint == "Search apps" && it.hasFocus() } }
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
            waitFor(home) {
                accessibleDescendants(home.window.decorView).any { it is TextView && it.text == "Cupera Launcher" }
            }
            onMain {
                val names = accessibleDescendants(home.window.decorView).filterIsInstance<TextView>()
                    .map { it.text.toString() }.toList()
                assertFalse(names.contains("No apps found"))
                assertFalse(names.contains("Chrome"))
                assertFalse(names.contains("⌕  Search"))
                val back = accessibleDescendants(home.window.decorView).filterIsInstance<TextView>()
                    .first { it.text == "‹ Home" }
                assertTrue(back.performClick())
            }
            waitFor(home) { views ->
                views.none { it is EditText && it.hint == "Search apps" } &&
                    accessibleDescendants(home.window.decorView).any { it is TextView && it.text == "⌕  Search" }
            }
        } finally { onMain { home.finish() } }
    }

    @Test fun searchKeyboardKeepsTheHomeBackdropInPlace() {
        assumeTrue("Requires an enabled software keyboard",
            InstrumentationRegistry.getArguments().getString("imeFixture") == "true")
        val home = open(HomeActivity::class.java)
        try {
            fun Sequence<View>.searchButton() = filterIsInstance<TextView>().first { it.text == "⌕  Search" }
            waitFor(home) { views -> home.hasWindowFocus() && views.any { it is TextView && it.text == "⌕  Search" } }
            lateinit var backdrop: View
            var height = 0
            onMain {
                val button = descendants(home.window.decorView).searchButton()
                backdrop = (button.parent as View).parent as View
                height = backdrop.height
                assertTrue(button.performClick())
            }
            val imeType = android.view.WindowInsets.Type.ime()
            waitFor(home) { views ->
                val decor = home.window.decorView
                val insets = decor.rootWindowInsets
                val input = views.filterIsInstance<EditText>().firstOrNull { it.hint == "Search apps" }
                if (insets?.isVisible(imeType) != true || input == null || input.height == 0) false else {
                    val position = IntArray(2).also(input::getLocationOnScreen)
                    position[1] + input.height <= decor.height - insets.getInsets(imeType).bottom
                }
            }
            onMain {
                assertTrue(backdrop.isAttachedToWindow)
                assertEquals(height, backdrop.height)
                home.onBackPressed()
            }
            waitFor(home) { views -> views.none { it is EditText && it.hint == "Search apps" } }
            onMain {
                val button = descendants(home.window.decorView).searchButton()
                assertTrue(backdrop === (button.parent as View).parent)
                assertEquals(height, backdrop.height)
            }
        } finally { onMain { home.finish() } }
    }

    @Test fun iconRequestsAfterTheLoaderClosesAreIgnored() {
        val context = instrumentation.targetContext
        val entry = Apps(context).entries().first { it.info.componentName.packageName == context.packageName }
        onMain {
            val loader = AppIcons()
            val icon = ImageView(context)
            loader.close()
            loader.show(icon, entry)
            assertNull(icon.drawable)
        }
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
                waitFor(home) { views ->
                    home.hasWindowFocus() && views.any { it.contentDescription == "Folder" && it.width > 0 }
                }
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
    @Test fun backDuringFolderOpeningReturnsWithoutEditingPlacement() = backDuringFolderOpening(reduceMotion = false)

    @Test fun reducedMotionFadesFolderAndBackPreservesPlacement() = backDuringFolderOpening(reduceMotion = true)

    private fun backDuringFolderOpening(reduceMotion: Boolean) {
        assumeTrue("Requires an isolated Home workspace",
            InstrumentationRegistry.getArguments().getString("folderFixture") == "true")
        assumeTrue("Requires platform animations", android.animation.ValueAnimator.areAnimatorsEnabled())
        Workspace(instrumentation.targetContext).use { store ->
            val initial = store.read()
            val (source, target) = initial.tiles.filter { it.isApp && it.slot.page == 0 && it.slot.folder.isEmpty() }.take(2)
            val placed = requireNotNull(initial.move(source.id, target.slot, createFolder = true))
            assertTrue(store.save(initial.revision, placed))
            val saved = store.read()
            val originalMotion = store.setting("motion", "system")
            var activity: Activity? = null
            try {
                store.put("motion", if (reduceMotion) "reduced" else "system")
                val home = open(HomeActivity::class.java).also { activity = it }
                waitFor(home) { views -> views.any { it.contentDescription == "Folder" && it.width > 0 } }
                onMain {
                    val views = descendants(home.window.decorView)
                    assertTrue(views.first { it.contentDescription == "Folder" }.performClick())
                    val title = views.first { it.contentDescription == "Folder. Rename folder" }
                    val card = title.parent as View
                    if (reduceMotion) {
                        assertEquals(1f, card.scaleX, 0f)
                        assertEquals(0f, card.translationX, 0f)
                        assertTrue(card.alpha < 1f)
                    } else {
                        assertTrue("Back must be sent before the folder is fully open", card.scaleX < 1f)
                    }
                    home.onBackPressed()
                }
                waitFor(home) { views ->
                    views.none { it.contentDescription == "Folder. Rename folder" } &&
                        views.any { it.contentDescription == "Folder" && it.alpha == 1f }
                }
                assertEquals(saved, store.read())
            } finally {
                activity?.let { home -> onMain { home.finish() } }
                store.put("motion", originalMotion)
                if (store.read().revision == saved.revision) assertTrue(store.save(saved.revision, initial.tiles))
            }
        }
    }

    @Test fun folderSwipesAndEdgeDragPreserveTheOtherTiles() {
        assumeTrue("Requires an isolated workspace",
            InstrumentationRegistry.getArguments().getString("folderFixture") == "true")
        Workspace(instrumentation.targetContext).use { store ->
            val initial = store.read()
            val apps = initial.tiles.filter { it.isApp && it.slot.page == 0 && it.slot.folder.isEmpty() }.take(10)
            assumeTrue("Requires ten apps on the first page", apps.size == 10)
            val folder = Tile(java.util.UUID.randomUUID().toString(), TileKind.FOLDER, apps.first().slot, name = "Paged folder")
            val children = apps.mapIndexed { index, tile ->
                tile.copy(slot = Slot(0, index, folder.id), name = "Folder app ${index + 1}")
            }
            assertTrue(store.save(initial.revision, initial.tiles - apps.toSet() + folder + children))
            val installed = store.read()
            var activity: Activity? = null
            try {
                val home = open(HomeActivity::class.java).also { activity = it }
                waitFor(home) { views -> home.hasWindowFocus() && views.any { it.contentDescription == "Paged folder" } }
                onMain { descendants(home.window.decorView).first { it.contentDescription == "Paged folder" }.performClick() }
                waitFor(home) { views -> views.any {
                    it.contentDescription == "Paged folder. Rename folder" && (it.parent as View).scaleX == 1f
                } }
                fun swipe(forward: Boolean, coalesced: Boolean = false, cancelled: Boolean = false) {
                    val path = FloatArray(3)
                    onMain {
                        val pager = descendants(home.window.decorView).filterIsInstance<FolderPager>().single()
                        val origin = IntArray(2).also(pager::getLocationOnScreen)
                        path[0] = origin[0] + pager.width * .8f
                        path[1] = origin[0] + pager.width * .2f
                        path[2] = origin[1] + pager.height * .65f
                    }
                    val from = if (forward) path[0] else path[1]
                    val to = if (forward) path[1] else path[0]
                    val down = SystemClock.uptimeMillis()
                    touch(down, MotionEvent.ACTION_DOWN, from, path[2], instrumentation.uiAutomation)
                    if (coalesced) {
                        for (fraction in listOf(.40f, .72f)) {
                            SystemClock.sleep(130)
                            touch(down, MotionEvent.ACTION_MOVE, from + (to - from) * fraction,
                                path[2], instrumentation.uiAutomation)
                        }
                        SystemClock.sleep(130)
                    } else {
                        for (step in 1..9) {
                            SystemClock.sleep(23)
                            touch(down, MotionEvent.ACTION_MOVE, from + (to - from) * step / 9,
                                path[2], instrumentation.uiAutomation)
                        }
                    }
                    val finish = if (cancelled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
                    touch(down, finish, to, path[2], instrumentation.uiAutomation)
                }
                fun expectPage(number: Int, shown: String?, hidden: String, count: Int = 2) {
                    waitFor(home) {
                        val visible = accessibleDescendants(home.window.decorView).map { it.contentDescription }.toSet()
                        "Folder page $number of $count" in visible && (shown == null || shown in visible) && hidden !in visible
                    }
                }
                expectPage(1, "Folder app 1", "Folder app 10")
                swipe(true)
                expectPage(2, "Folder app 10", "Folder app 1")
                swipe(false)
                expectPage(1, "Folder app 1", "Folder app 10")
                swipe(true, coalesced = true)
                expectPage(2, "Folder app 10", "Folder app 1")
                swipe(false)
                expectPage(1, "Folder app 1", "Folder app 10")
                swipe(true, cancelled = true)
                expectPage(1, "Folder app 1", "Folder app 10")
                assertEquals(installed, store.read())

                onMain { home.onBackPressed() }
                waitFor(home) { views -> views.none { it is FolderPager } }
                val (blankX, blankY) = whitespaceBelowGrid(home)
                val hold = SystemClock.uptimeMillis()
                touch(hold, MotionEvent.ACTION_DOWN, blankX, blankY)
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100L)
                touch(hold, MotionEvent.ACTION_UP, blankX, blankY)
                waitFor(home) { views -> views.any { it is TextView && it.text == "Edit" } }
                onMain { descendants(home.window.decorView).first { it.contentDescription == "Paged folder" }.performClick() }
                waitFor(home) { views -> views.any {
                    it.contentDescription == "Paged folder. Rename folder" && (it.parent as View).scaleX == 1f
                } }
                expectPage(1, "Folder app 1", "Folder app 10", count = 3)
                val (sourceX, sourceY) = screenCenter(home, "Folder app 1")
                val destination = FloatArray(3)
                onMain {
                    val pager = descendants(home.window.decorView).filterIsInstance<FolderPager>().single()
                    val origin = IntArray(2).also(pager::getLocationOnScreen)
                    destination[0] = origin[0] + pager.width - home.dp(10).toFloat()
                    destination[1] = origin[0] + pager.width * .83f
                    destination[2] = origin[1] + pager.height * .83f
                }
                val automation = instrumentation.uiAutomation
                val animates = android.animation.ValueAnimator.areAnimatorsEnabled() && store.setting("motion") != "reduced"
                val interrupted = SystemClock.uptimeMillis()
                val turning = CountDownLatch(1)
                val observeTurn = object : android.view.Choreographer.FrameCallback {
                    override fun doFrame(time: Long) {
                        val pager = descendants(home.window.decorView).filterIsInstance<FolderPager>().firstOrNull()
                        val moving = pager?.let {
                            val stride = (it.getChildAt(0).width - it.width) / 2
                            if (animates) it.scrollX in 1 until stride else it.scrollX == stride && stride > 0
                        } ?: false
                        if (moving) turning.countDown()
                        else android.view.Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                touch(interrupted, MotionEvent.ACTION_DOWN, sourceX, sourceY, automation)
                try {
                    onMain { android.view.Choreographer.getInstance().postFrameCallback(observeTurn) }
                    SystemClock.sleep(32)
                    touch(interrupted, MotionEvent.ACTION_MOVE, destination[0], destination[2], automation)
                    assertTrue("Folder edge turn did not start", turning.await(5, TimeUnit.SECONDS))
                } finally {
                    touch(interrupted, MotionEvent.ACTION_CANCEL, destination[0], destination[2], automation)
                    onMain { android.view.Choreographer.getInstance().removeFrameCallback(observeTurn) }
                }
                SystemClock.sleep(800)
                expectPage(2, "Folder app 10", "Folder app 1", count = 3)
                assertEquals(installed, store.read())
                onMain {
                    val indicator = descendants(home.window.decorView).first { it.contentDescription == "Folder page 2 of 3" }
                    assertTrue(indicator.performClick())
                    assertTrue(indicator.performClick())
                }
                expectPage(1, "Folder app 1", "Folder app 10", count = 3)
                waitFor(home) { views -> views.filterIsInstance<FolderPager>().any { !it.canScrollHorizontally(-1) } }

                val drag = SystemClock.uptimeMillis()
                touch(drag, MotionEvent.ACTION_DOWN, sourceX, sourceY, automation)
                var released = false
                try {
                    SystemClock.sleep(32)
                    touch(drag, MotionEvent.ACTION_MOVE, destination[0], destination[2], automation)
                    expectPage(2, "Folder app 10", "Folder app 1", count = 3)
                    expectPage(3, null, "Folder app 10", count = 3)
                    waitFor(home) { views -> views.filterIsInstance<FolderPager>().any { !it.canScrollHorizontally(1) } }
                    assertEquals(installed, store.read())
                    touch(drag, MotionEvent.ACTION_MOVE, destination[1], destination[2], automation)
                    touch(drag, MotionEvent.ACTION_UP, destination[1], destination[2], automation)
                    released = true
                } finally {
                    if (!released) touch(drag, MotionEvent.ACTION_CANCEL, destination[0], destination[2], automation)
                }
                expectPage(3, "Folder app 1", "Folder app 10", count = 4)
                assertEquals(installed.revision + 1, store.read().revision)
                val moved = store.read()
                assertEquals(Slot(0, 26, folder.id), moved.tiles.single { it.id == apps.first().id }.slot)
                assertEquals(installed.tiles.filterNot { it.id == apps.first().id }.toSet(),
                    moved.tiles.filterNot { it.id == apps.first().id }.toSet())

                onMain {
                    val item = descendants(home.window.decorView).first { it.contentDescription == "Folder app 1" }
                    assertTrue(item.performClick())
                }
                fun choose(label: String) {
                    val deadline = SystemClock.uptimeMillis() + 5_000
                    while (SystemClock.uptimeMillis() < deadline) {
                        val node = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)
                            ?.firstOrNull { it.text?.toString() == label }
                        val action = generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
                        if (action?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) return
                        SystemClock.sleep(80)
                    }
                    throw AssertionError("Move option unavailable: $label")
                }
                choose("Move…")
                choose("Paged folder, page 1, row 1, column 1")
                expectPage(1, "Folder app 1", "Folder app 10", count = 3)
                val movedByMenu = store.read()
                assertEquals(installed.revision + 2, movedByMenu.revision)
                assertEquals(installed.tiles.toSet(), movedByMenu.tiles.toSet())
            } finally {
                activity?.let { home -> onMain { home.finish() } }
                val current = store.read()
                if (current.revision in installed.revision..installed.revision + 2 && current.tiles.any { it.id == folder.id })
                    assertTrue(store.save(current.revision, initial.tiles))
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
        waitFor(home) { views ->
            home.hasWindowFocus() && views.any { it is ScrollView && it.height > it.getChildAt(0).bottom }
        }
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

    private fun accessibleDescendants(view: View): Sequence<View> = sequence {
        if (view.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
            yield(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(accessibleDescendants(view.getChildAt(i)))
        }
    }
}
