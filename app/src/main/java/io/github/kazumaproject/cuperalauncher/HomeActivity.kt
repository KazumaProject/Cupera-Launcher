package io.github.kazumaproject.cuperalauncher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.WindowInsetsController
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Toast
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val FOLDER_PAGE_SIZE = 9
private const val RETURN_FADE_START = .72f
private const val RETURN_FRAME_WAIT_MS = 120L
private const val LIBRARY_RETURN_PAUSE_MS = 150L
private const val LIBRARY_HELD_RETURN_DISTANCE = .22f

class HomeActivity : Activity() {
    private lateinit var store: Workspace
    private lateinit var apps: Apps
    private lateinit var widgets: Widgets
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var workspace: FrameLayout
    private lateinit var dock: FrameLayout
    private var dockWallpaper: DockWallpaper? = null
    private var systemDockBackdrop: Dialog? = null
    private val dockBackdropPosition = IntArray(2)
    private var dockUsesSystemWallpaper = false
    private var dockBackdropResumed = false
    private val dockBackdropPreDraw = ViewTreeObserver.OnPreDrawListener {
        syncSystemDockBackdrop()
        true
    }
    private val dockBackdropStateListener = Consumer<Boolean> { syncSystemDockBackdrop() }

    private fun hideSystemDockBackdrop() {
        systemDockBackdrop?.dismiss()
        systemDockBackdrop = null
    }

    private fun syncSystemDockBackdrop() {
        if (!dockBackdropResumed || !dockUsesSystemWallpaper || homeReturn != null ||
            !::dock.isInitialized || !dock.isAttachedToWindow || !dock.isShown ||
            dock.width == 0 || dock.height == 0 || search || folderPresentation != null ||
            !windowManager.isCrossWindowBlurEnabled) {
            hideSystemDockBackdrop()
            return
        }
        dock.getLocationInWindow(dockBackdropPosition)
        val (left, top) = dockBackdropPosition
        val existing = systemDockBackdrop
        val panel = existing ?: Dialog(this, R.style.Theme_Cupera_DockBackdrop).apply {
            setContentView(View(this@HomeActivity).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            setCancelable(false)
            window?.apply {
                // Sample the system wallpaper underneath Home; icons and input stay in Home.
                setType(WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                setWindowAnimations(0)
                setBackgroundDrawable(rounded(Color.TRANSPARENT, dp(34).toFloat()))
                setBackgroundBlurRadius(dp(12))
            }
        }
        val panelWindow = panel.window ?: return
        val attributes = panelWindow.attributes
        if (existing == null || attributes.x != left || attributes.y != top ||
            attributes.width != dock.width || attributes.height != dock.height) {
            attributes.token = window.decorView.windowToken
            attributes.title = "Cupera Dock Backdrop"
            attributes.gravity = Gravity.TOP or Gravity.LEFT
            attributes.fitInsetsTypes = 0
            attributes.x = left
            attributes.y = top
            attributes.width = dock.width
            attributes.height = dock.height
            panelWindow.attributes = attributes
        }
        if (existing == null) {
            try {
                panel.show()
                systemDockBackdrop = panel
            } catch (failure: WindowManager.BadTokenException) {
                android.util.Log.w("CuperaDockBackdrop", "Cannot attach background window", failure)
            }
        }
    }
    private var grid: TileGrid? = null
    private var previewPage = -1
    private var homeFooter: LinearLayout? = null
    private var editToolbar: LinearLayout? = null
    private var hiddenPages = emptySet<Int>()
    private var pageVisibilityDraft: Set<Int>? = null
    private val visiblePages get() = layout.visiblePages(hiddenPages)
    private var homeLayer: FrameLayout? = null
    private var folderAnchor: Rect? = null
    private var openedFolderTile: View? = null
    private var folderPresentation: FolderPresentation? = null
    private lateinit var wallpaperLayer: View
    private val folderBlurEffects by lazy {
        List(13) { level ->
            val radius = dp(11) * level / 12f
            if (level == 0) null else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        }
    }
    private var pageIndicatorVisible = false
    private val restoreSearch = Runnable {
        pageIndicatorVisible = false
        homeFooter?.let { footer ->
            fillFooter(footer)
            if (!editing && !reduced) footer.getChildAt(0)?.apply {
                alpha = 0f
                animate().alpha(1f).setDuration(220).start()
            }
        }
    }
    private var previewView: PageViewport? = null
    private var dockGrid: TileGrid? = null
    private var folderGrid: TileGrid? = null
    private var folderPager: FolderPager? = null
    private var folderPage = 0
    private var browser: AppBrowser? = null
    private var searchPanel: View? = null
    private var searchAnimator: ValueAnimator? = null
    private var searchProgress = 0f
    private var searchClosing = false
    private class LibraryMotion(val panel: AppBrowser, val view: View, val home: FrameLayout, val background: View) {
        var progress = 0f
        var animator: ValueAnimator? = null
        var returnStartX = 0f
        var returnStartY = 0f
        var returnProgress = 1f
        var returnLastX = 0f
        var returnLastMoveAt = 0L
        var trackingReturn = false
        var draggingReturn = false
    }
    private var libraryMotion: LibraryMotion? = null
    private val libraryTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val searchBlurEffects by lazy {
        List(9) { step ->
            val radius = dp(24) * step / 8f
            if (step == 0) null else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        }
    }
    private class HomeReturn(val backdrop: View, val fade: Boolean) {
        val duration = if (fade) 80L else 120L
        var start: Runnable? = null
        var animator: ValueAnimator? = null
    }
    private var externalLaunchPending = false
    private var homeReturn: HomeReturn? = null
    private val returnBlurEffects by lazy {
        List(8) { step ->
            val radius = dp(8) * step / 7f
            if (step == 0) null else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        }
    }
    private var catalog = AppBrowser.Catalog(emptyList())
    private val entries get() = catalog.entries
    internal val icons = AppIcons()
    private val appWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var appGeneration = 0
    private var closed = false
    private val newPackages = linkedSetOf<String>()
    private var packageRefreshPending = false
    private var renderedHome: HomeDisplay? = null
    private data class HomeDisplay(
        val layoutRevision: Long, val hiddenPages: Set<Int>, val width: Int, val height: Int, val dark: Boolean,
        val animationsEnabled: Boolean, val settings: Map<String, String>, val badges: Map<String, Int>
    )
    private var layout = Layout(0, emptyList())
    private var page = 0
    private var editing = false
    private var folder: String? = null
    private var library = false
    private var search = false
    private var privateOpen = false
    private var darkMode = false
    private var lastSize = 0 to 0
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var gesture: HomeGesture
    private val reduced get() = !ValueAnimator.areAnimatorsEnabled() || store.setting("motion") == "reduced"
    private val animateHomePages get() = ValueAnimator.areAnimatorsEnabled() && (!editing || !reduced)

    private val profiles = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed) return
            val removed = intent.action == Intent.ACTION_PROFILE_REMOVED || intent.action == Intent.ACTION_MANAGED_PROFILE_REMOVED
            val available = android.os.Build.VERSION.SDK_INT >= 35 && intent.action == Intent.ACTION_PROFILE_AVAILABLE
            if (!available || !privateOpen || apps.privateUser()?.let(apps::privateUnlocked) != true) {
                invalidatePrivate()
            }
            if (removed && apps.hasHome()) reconcileProfiles()
            refreshApps()
        }
    }

    private val packages = object : LauncherApps.Callback() {
        override fun onPackageAdded(name: String, user: UserHandle) {
            if (closed) return
            if (isPublicProfile(user)) {
                icons.invalidate()
                refreshApps(if (store.settingEnabled("add_new", false)) name else null, packagesChanged = true)
            } else invalidatePrivate()
        }
        override fun onPackageRemoved(name: String, user: UserHandle) {
            if (closed) return
            if (!isPublicProfile(user)) { invalidatePrivate(); return }
            icons.invalidate()
            val manager = getSystemService(UserManager::class.java)
            val serial = manager.getSerialNumberForUser(user)
            val remaining = layout.tiles.filterNot {
                it.key?.profile == serial && packageOf(it.key) == name
            }
            if (remaining.size != layout.tiles.size) commit(Layout.removeEmptyFolders(remaining))
            refreshApps(packagesChanged = true)
        }
        override fun onPackageChanged(name: String, user: UserHandle) = changed(user)
        override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = changed(user)
        override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = changed(user)
        private fun changed(user: UserHandle) {
            if (closed) return
            if (isPublicProfile(user)) {
                icons.invalidate()
                refreshApps(packagesChanged = true)
            } else invalidatePrivate()
        }
    }

    private fun isPublicProfile(user: UserHandle): Boolean {
        val type = apps.profileType(user)
        return type != null && type != UserManager.USER_TYPE_PROFILE_PRIVATE
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = Workspace(this)
        apps = Apps(this)
        widgets = Widgets(this, store, ::reload)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        widgets.restore(state)
        page = state?.getInt("page") ?: 0
        root = FrameLayout(this).apply { clipToPadding = false }
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        wallpaperLayer = View(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        setContentView(FrameLayout(this).apply {
            addView(wallpaperLayer, FrameLayout.LayoutParams(-1, -1))
            addView(root, FrameLayout.LayoutParams(-1, -1))
        })
        gesture = HomeGesture(this)
        content.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val size = (r - l) to (b - t)
            if (size != lastSize && size.first > 0 && size.second > 0) {
                lastSize = size
                if ((!search && !library) || browser == null) handler.post(::render)
            }
        }
        registerReceiver(profiles, IntentFilter().apply {
            addAction(Intent.ACTION_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_PROFILE_ADDED)
            addAction(Intent.ACTION_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
        }, RECEIVER_EXPORTED)
        runCatching { apps.launcher.registerCallback(packages, handler) }
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(0, ::back)
    }

    override fun onStart() {
        super.onStart()
        windowManager.addCrossWindowBlurEnabledListener(mainExecutor, dockBackdropStateListener)
        widgets.start()
        val presentation = homeReturn ?: return
        if (!canSettleHomeReturn(presentation)) {
            finishHomeReturn()
            return
        }
        val startedAt = SystemClock.uptimeMillis()
        val start = Runnable { settleHomeReturn(presentation, startedAt) }
        presentation.start = start
        if (root.isHardwareAccelerated) {
            root.viewTreeObserver.registerFrameCommitCallback { handler.post(start) }
        } else {
            // Software-rendered windows do not produce a frame commit callback.
            root.postOnAnimation(start)
        }
        val scaledDuration = (presentation.duration * homeReturnDurationScale()).toLong()
        handler.postAtTime(start, startedAt + minOf(RETURN_FRAME_WAIT_MS, scaledDuration))
    }

    override fun onResume() {
        super.onResume()
        dockBackdropResumed = true
        root.viewTreeObserver.addOnPreDrawListener(dockBackdropPreDraw)
        root.invalidate()
        externalLaunchPending = false
        val before = darkMode
        darkMode = dark(store)
        if (before != darkMode && browser != null) render()
        systemBars(root, darkMode && (search || library) || !search && !library,
            keepStatusInset = true, padForIme = false)
        layout = store.read()
        hiddenPages = store.hiddenPages()
        refreshApps()
        BadgeListener.changed = { if (!editing && !search && !library && !gesture.active) render() }
    }

    override fun onPause() {
        dockBackdropResumed = false
        root.viewTreeObserver.removeOnPreDrawListener(dockBackdropPreDraw)
        hideSystemDockBackdrop()
        BadgeListener.changed = null
        browser?.cancelPendingKeyboard()
        finishHomeReturn()
        gesture.cancel()
        invalidatePrivate()
        libraryMotion?.let { finishLibraryMotion(it, library) }
        super.onPause()
    }

    override fun onStop() {
        windowManager.removeCrossWindowBlurEnabledListener(dockBackdropStateListener)
        widgets.stop()
        super.onStop()
        val launched = externalLaunchPending
        externalLaunchPending = false
        if (!launched || !ValueAnimator.areAnimatorsEnabled() || !apps.hasHome()) return
        val backdrop = homeLayer ?: return
        val fade = reduced
        // Stage only Home content. Android composes the other app and the wallpaper.
        if (fade) backdrop.alpha = RETURN_FADE_START
        else backdrop.setRenderEffect(returnBlurEffects.last())
        homeReturn = HomeReturn(backdrop, fade)
    }

    private fun canSettleHomeReturn(presentation: HomeReturn): Boolean =
        ValueAnimator.areAnimatorsEnabled() && reduced == presentation.fade && apps.hasHome()

    private fun homeReturnDurationScale(): Float =
        if (android.os.Build.VERSION.SDK_INT >= 33) ValueAnimator.getDurationScale()
        else Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

    private fun settleHomeReturn(presentation: HomeReturn, startedAt: Long) {
        if (homeReturn !== presentation || presentation.animator != null) return
        clearHomeReturnStart(presentation)
        if (!canSettleHomeReturn(presentation)) {
            finishHomeReturn()
            return
        }
        // Waiting for a frame uses part of the effect's time. Don't replay it after a stall.
        val scale = homeReturnDurationScale()
        val elapsed = SystemClock.uptimeMillis() - startedAt
        if (scale <= 0f || elapsed >= presentation.duration * scale) {
            finishHomeReturn()
            return
        }
        presentation.animator = (if (presentation.fade) {
            ValueAnimator.ofFloat(RETURN_FADE_START, 1f).apply {
                addUpdateListener { presentation.backdrop.alpha = it.animatedValue as Float }
            }
        } else {
            ValueAnimator.ofInt(returnBlurEffects.lastIndex, 0).apply {
                var lastStep = returnBlurEffects.lastIndex
                addUpdateListener {
                    val step = it.animatedValue as Int
                    if (step != lastStep) {
                        lastStep = step
                        presentation.backdrop.setRenderEffect(returnBlurEffects[step])
                    }
                }
            }
        }).apply {
            duration = presentation.duration
            currentPlayTime = (elapsed / scale).toLong()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    presentation.animator = null
                    finishHomeReturn()
                }
            })
        }
        presentation.animator?.start()
    }

    private fun finishHomeReturn() {
        val presentation = homeReturn ?: return
        homeReturn = null
        clearHomeReturnStart(presentation)
        presentation.animator?.removeAllListeners()
        presentation.animator?.cancel()
        if (presentation.fade) presentation.backdrop.alpha = 1f
        else presentation.backdrop.setRenderEffect(null)
    }

    private fun clearHomeReturnStart(presentation: HomeReturn) {
        val start = presentation.start ?: return
        presentation.start = null
        root.removeCallbacks(start)
        handler.removeCallbacks(start)
    }

    override fun onDestroy() {
        hideSystemDockBackdrop()
        closed = true
        finishHomeReturn()
        stopSearchAnimation()
        libraryMotion?.let(::stopLibraryAnimation)
        appGeneration++
        appWorker.shutdownNow()
        icons.close()
        handler.removeCallbacksAndMessages(null)
        runCatching { apps.launcher.unregisterCallback(packages) }
        unregisterReceiver(profiles)
        store.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("page", page)
        widgets.save(out)
        super.onSaveInstanceState(out)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            val alreadyAtHome = renderedHome != null && page == visiblePages.first() &&
                !editing && pageVisibilityDraft == null && folder == null && folderPresentation == null &&
                !search && !library && !gesture.active && !pageIndicatorVisible &&
                previewView == null && (!::workspace.isInitialized || workspace.translationX == 0f)
            closeKeyboard()
            gesture.cancel()
            page = visiblePages.first()
            folder = null
            editing = false
            pageVisibilityDraft = null
            library = false
            search = false
            resetPageIndicator()
            invalidatePrivate()
            if (!alreadyAtHome) render()
        }
    }

    @Deprecated("Platform widget activity results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        widgets.result(requestCode, resultCode)
    }

    private fun reconcileProfiles() {
        val users = getSystemService(UserManager::class.java)
        val remaining = layout.tiles.filter { it.key == null || users.getUserForSerialNumber(it.key.profile) != null }
        if (remaining.size != layout.tiles.size) commit(Layout.removeEmptyFolders(remaining))
    }

    private fun refreshApps(newPackage: String? = null, packagesChanged: Boolean = false) {
        if (closed) return
        if (newPackage != null) newPackages.add(newPackage)
        packageRefreshPending = packageRefreshPending || packagesChanged
        val token = ++appGeneration
        appWorker.execute {
            val snapshot = AppBrowser.Catalog(apps.entries())
            handler.post {
                if (!closed && token == appGeneration) publishApps(snapshot)
            }
        }
    }

    private fun publishApps(nextCatalog: AppBrowser.Catalog) {
        val snapshot = nextCatalog.entries
        val firstCatalog = entries.isEmpty() && snapshot.isNotEmpty()
        val catalogChanged = entries.size != snapshot.size || entries.indices.any { index ->
            val previous = entries[index]
            val next = snapshot[index]
            previous.key != next.key || previous.title != next.title || previous.work != next.work
        }
        val packagesChanged = packageRefreshPending
        packageRefreshPending = false
        if (catalogChanged) icons.invalidate()
        catalog = nextCatalog
        if (!store.settingEnabled("seeded", false) && entries.isNotEmpty()) {
            val personal = entries.filter { it.key.profile == apps.personalSerial }
            val dockEntries = listOf("dialer", "messaging", "chrome", "camera").mapNotNull { term ->
                personal.firstOrNull { packageOf(it.key).contains(term) }
            }.distinct()
            val seeds = dockEntries.mapIndexed { i, item -> appTile(item.key, Slot(Slot.DOCK, i)) } +
                personal.filterNot { it in dockEntries }.take(24).mapIndexed { i, item -> appTile(item.key, Slot(0, i)) }
            if (store.save(layout.revision, seeds)) store.put("seeded", "true")
            layout = store.read()
        }
        val installed = newPackages.toSet()
        newPackages.clear()
        entries.filter { packageOf(it.key) in installed && it.key.profile == apps.personalSerial }.forEach {
            if (layout.tiles.none { tile -> tile.key == it.key }) addApp(it.key)
        }
        val current = browser
        // An unchanged resume can finish after a new gesture starts. Keep its views attached.
        if ((search || library) && current != null) {
            if (catalogChanged || packagesChanged) {
                if (firstCatalog && !packagesChanged && renderedHome == homeDisplay()) {
                    homeLayer?.let(::refreshTileCatalog)
                } else {
                    renderedHome = null
                }
                current.updateEntries(catalog)
            } else if (library) {
                current.updateEntries(catalog)
            }
        } else if (firstCatalog && !packagesChanged && renderedHome == homeDisplay()) {
            // An initial catalog can arrive while a folder or page is moving.
            refreshTileCatalog(content)
            libraryMotion?.panel?.updateEntries(catalog)
        } else if (catalogChanged || packagesChanged || renderedHome != homeDisplay()) {
            if (gesture.active) gesture.cancel()
            render()
        }
    }

    private fun homeDisplay(): HomeDisplay {
        val settings = store.homeDisplaySettings()
        return HomeDisplay(layout.revision, hiddenPages, content.width, content.height,
            darkMode, ValueAnimator.areAnimatorsEnabled(),
            settings, if (settings["badges"] == "false") emptyMap() else BadgeListener.counts)
    }

    private fun invalidatePrivate() {
        privateOpen = false
        browser?.clearPrivate()
    }

    private fun commit(tiles: List<Tile>, revision: Long = layout.revision): Boolean {
        if (!store.save(revision, tiles)) { reload(); return false }
        reload()
        return true
    }

    private fun reload() {
        reloadWorkspace()
        render()
    }

    private fun reloadWorkspace() {
        layout = store.read()
        hiddenPages = store.hiddenPages()
        if (layout.tiles.none { it.id == folder }) folder = null
        widgets.reconcile()
    }

    private fun packageOf(key: AppKey) = key.component.substringBefore('/')
    private fun appTile(key: AppKey, slot: Slot, name: String = "") =
        Tile(java.util.UUID.randomUUID().toString(), if (key.shortcut.isEmpty()) TileKind.APP else TileKind.SHORTCUT,
            slot, key, name)

    private fun addApp(key: AppKey, name: String = "") {
        val slot = layout.firstFree(page.coerceAtMost(layout.pages - 1), excludedPages = hiddenPages - visiblePages.toSet())
            ?: return message("Home is full.")
        if (commit(layout.tiles + appTile(key, slot, name)) && key.shortcut.isNotEmpty()) apps.updatePinned(layout.tiles, listOf(key))
    }

    private fun render() {
        if (closed || !::gesture.isInitialized || content.width == 0 || isFinishing) return
        finishHomeReturn()
        if (searchClosing) search = false
        libraryMotion?.let(::stopLibraryAnimation)
        libraryMotion = null
        val backdrop = homeLayer?.takeIf { (search || library) && folderPresentation == null && editToolbar == null }
        val openingSearch = search && backdrop != null && searchPanel == null
        stopSearchAnimation()
        searchPanel = null
        searchClosing = false
        if (backdrop == null) {
            hideSystemDockBackdrop()
            renderedHome = null
        }
        if (::workspace.isInitialized) workspace.animate().cancel()
        folderPresentation?.let {
            it.card.animate().cancel()
            it.title.animate().cancel()
            it.backdropAnimator?.cancel()
        }
        folderPresentation = null
        wallpaperLayer.setRenderEffect(null)
        if (folder == null) folderAnchor = null
        homeLayer = backdrop
        openedFolderTile = null
        if (backdrop == null) content.removeAllViews()
        else for (index in content.childCount - 1 downTo 0) {
            if (content.getChildAt(index) !== backdrop) content.removeViewAt(index)
        }
        editToolbar?.let(root::removeView)
        editToolbar = null
        browser = null
        if (backdrop == null) homeFooter = null
        folderGrid = null
        folderPager = null
        val systemWallpaper = store.setting("wallpaper", "tide") == "system"
        dockUsesSystemWallpaper = systemWallpaper
        if (systemWallpaper) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        wallpaperLayer.background = if (systemWallpaper) null
            else CuperaWallpaper(store.setting("wallpaper") == "dusk" || darkMode)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        val isPanel = library || search
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(0, lightBars)
        window.insetsController?.let { controller ->
            if (editing) controller.hide(WindowInsets.Type.statusBars())
            else controller.show(WindowInsets.Type.statusBars())
        }
        if (pageVisibilityDraft != null) {
            renderedHome = homeDisplay()
            drawPageEditor()
            drawEditToolbar()
            return
        }
        if (isPanel) {
            val panel = newBrowser()
            browser = panel
            val libraryBackground = if (library && backdrop != null) FrameLayout(this) else null
            val view = panel.view(search, useHomeBackdrop = search && backdrop != null,
                focusOnOpen = !gesture.previewingSearch && (search || backdrop == null),
                libraryBackground = libraryBackground)
            content.addView(view, FrameLayout.LayoutParams(-1, -1))
            if (backdrop != null) {
                backdrop.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                backdrop.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                workspace.translationX = 0f
                previewView?.let(workspace::removeView)
                previewView = null
                previewPage = -1
                if (library) {
                    backdrop.setRenderEffect(null)
                    val background = checkNotNull(libraryBackground)
                    content.addView(background, content.indexOfChild(backdrop), FrameLayout.LayoutParams(-1, -1))
                    libraryMotion = LibraryMotion(panel, view, backdrop, background).also { finishLibraryMotion(it, true) }
                } else {
                    backdrop.visibility = View.VISIBLE
                    searchPanel = view
                    drawSearchProgress(if (openingSearch && !reduced) 0f else 1f)
                }
            }
            if (openingSearch && !reduced && !gesture.previewingSearch) animateSearch(1f)
            return
        }
        previewPage = -1
        previewView = null
        if (!editing && page !in visiblePages) {
            page = visiblePages.lastOrNull { it < page } ?: visiblePages.first()
        }
        renderedHome = homeDisplay()
        val homeLayer = FrameLayout(this).apply { clipChildren = false }
        this.homeLayer = homeLayer
        content.addView(homeLayer, FrameLayout.LayoutParams(-1, -1))
        workspace = FrameLayout(this).apply { clipChildren = false }
        homeLayer.addView(workspace, FrameLayout.LayoutParams(-1, -1))
        val totalWidth = content.width
        val totalHeight = content.height
        val horizontalMargin = dp(18)
        val dockHeight = if (store.settingEnabled("dock")) min(dp(114), totalHeight / 4) else 0
        val footerHeight = dp(44)
        val gridTop = dp(26)
        val gridHeight = maxOf(dp(64) * 6, totalHeight - dockHeight - footerHeight - gridTop - dp(30))
        val gridWidth = maxOf(dp(220), totalWidth - horizontalMargin * 2)
        val gridView = TileGrid(this, page, "", gridWidth, gridHeight, 4, 6, ::tileView)
        gridView.populate(layout.tiles)
        grid = gridView
        val viewportHeight = totalHeight - dockHeight - dp(42) - dp(8) - gridTop
        workspace.addView(PageViewport(this, gridView), FrameLayout.LayoutParams(gridWidth, maxOf(dp(72), viewportHeight)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = gridTop
        })
        dock = FrameLayout(this)
        dockWallpaper = null
        dockGrid = null
        if (dockHeight > 0) {
            val radius = dp(34).toFloat()
            if (systemWallpaper) {
                dock.background = rounded(0x2effffff, radius, 0x64ffffff)
            } else {
                val tint = rounded(0x50edf5ff, radius, 0x64ffffff)
                val surface = DockWallpaper(this, store.setting("wallpaper") == "dusk" || darkMode,
                    totalWidth, totalHeight, dp(12).toFloat())
                dockWallpaper = surface
                dock.background = rounded(Color.TRANSPARENT, radius)
                dock.clipToOutline = true
                dock.addView(surface, FrameLayout.LayoutParams(totalWidth, surface.sourceHeight(dockHeight), Gravity.TOP or Gravity.LEFT))
                dock.addView(View(this).apply { background = tint }, FrameLayout.LayoutParams(-1, -1))
                val frame = dock
                frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> surface.alignTo(frame) }
            }
            val width = totalWidth - dp(48)
            val tiles = TileGrid(this, Slot.DOCK, "", width, dockHeight, 4, 1, ::tileView)
            dockGrid = tiles
            tiles.populate(layout.tiles)
            dock.addView(tiles, FrameLayout.LayoutParams(width, dockHeight, Gravity.CENTER))
            homeLayer.addView(dock, FrameLayout.LayoutParams(totalWidth - dp(32), dockHeight, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(5)
            })
        }
        footer(homeLayer, dockHeight)
        if (editing) drawEditToolbar()
        folder?.let { drawFolder(it, homeLayer) }
        gesture.draw()
    }

    private fun drawEditToolbar() {
        val buttonHeight = dp(48)
        val edgeMargin = (content.width * .115f).toInt()
        val toolbar = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(edgeMargin, 0, edgeMargin, 0)
        }
        fun button(title: String) = label(title, 13f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(64)
            setPadding(dp(10), 0, dp(10), 0)
            background = InsetDrawable(rounded(0x30ffffff, dp(14).toFloat(), 0x55ffffff), 0, dp(11), 0, dp(11))
        }
        val edit = button("Edit")
        edit.setOnClickListener {
            PopupMenu(this, edit).apply {
                menu.add("Add Widget").setOnMenuItemClickListener { widgets.choose(page); true }
                menu.add("Customize").setOnMenuItemClickListener { openEditSettings("Icons & Dock"); true }
                menu.add("Edit Wallpaper").setOnMenuItemClickListener { openEditSettings("Wallpaper & Appearance"); true }
                menu.add("Edit Pages").setOnMenuItemClickListener {
                    pageVisibilityDraft = hiddenPages - visiblePages.toSet()
                    render()
                    true
                }
                show()
            }
        }
        if (pageVisibilityDraft == null) toolbar.addView(edit, LinearLayout.LayoutParams(-2, buttonHeight))
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        toolbar.addView(button("Done").apply { setOnClickListener {
            pageVisibilityDraft?.let(store::setHiddenPages)
            pageVisibilityDraft = null
            editing = false
            reload()
        } }, LinearLayout.LayoutParams(-2, buttonHeight))
        root.addView(toolbar, FrameLayout.LayoutParams(-1, buttonHeight, Gravity.TOP).apply {
            topMargin = dp(9) - root.paddingTop
        })
        editToolbar = toolbar
    }

    private fun drawPageEditor() {
        val width = min((content.width * .35f).toInt(), dp(180))
        val height = min((width * 1.74f).toInt(), content.height / 2)
        val margin = min((content.width * .05f).toInt(), dp(20))
        val pages = vertical().apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(36), 0, dp(36) + (root.paddingTop - root.paddingBottom).coerceAtLeast(0))
        }
        (0 until layout.pages).chunked(2).forEach { pair ->
            val row = horizontal().apply { gravity = Gravity.CENTER }
            pages.addView(row)
            pair.forEach { index ->
                val column = vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
                row.addView(column, LinearLayout.LayoutParams(width, -2).apply { setMargins(margin, dp(8), margin, dp(8)) })
                val preview = FrameLayout(this).apply {
                    background = rounded(0x44def7ff, dp(20).toFloat(), 0x36ffffff)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
                column.addView(preview, LinearLayout.LayoutParams(width, height))
                val padding = width / 12
                val cellWidth = (width - padding * 2) / 4
                val cellHeight = (height - padding * 2) / 6
                layout.tiles.filter { it.slot.page == index && it.slot.folder.isEmpty() }.forEach { tile ->
                    val icon = if (tile.isApp) appIcon(tile).apply {
                        background = rounded(0x30ffffff, dp(4).toFloat())
                        clipToOutline = true
                    } else View(this).apply {
                        background = rounded(0x88ffffff.toInt(), dp(3).toFloat())
                    }
                    val inset = if (tile.isApp) cellWidth / 7 else 1
                    preview.addView(icon, FrameLayout.LayoutParams(
                        cellWidth * tile.width - inset * 2, cellHeight * tile.height - inset * 2
                    ).apply {
                        leftMargin = padding + tile.slot.cell % 4 * cellWidth + inset
                        topMargin = padding + tile.slot.cell / 4 * cellHeight + inset
                    })
                }
                val check = CheckBox(this).apply {
                    contentDescription = "Show page ${index + 1}"
                    buttonDrawable = null
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 0)
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    background = InsetDrawable(StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_checked), rounded(0x70e4ffff, dp(16).toFloat()))
                        addState(intArrayOf(), rounded(0x20ffffff, dp(16).toFloat(), 0xaaffffff.toInt()))
                    }, dp(8))
                    isChecked = index !in pageVisibilityDraft.orEmpty()
                    text = if (isChecked) "✓" else ""
                }
                preview.alpha = if (check.isChecked) 1f else .45f
                check.setOnClickListener {
                    val draft = pageVisibilityDraft ?: return@setOnClickListener
                    val next = if (check.isChecked) draft - index else draft + index
                    if ((0 until layout.pages).all { it in next }) {
                        check.isChecked = true
                        message("Keep at least one Home page visible.")
                    } else {
                        pageVisibilityDraft = next
                        preview.alpha = if (check.isChecked) 1f else .45f
                        check.text = if (check.isChecked) "✓" else ""
                    }
                }
                column.addView(check, LinearLayout.LayoutParams(dp(48), dp(48)))
            }
        }
        content.addView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(pages, FrameLayout.LayoutParams(-1, -2))
        }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openEditSettings(section: String) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, section))
    }

    private class PageViewport(context: Context, val tiles: TileGrid) : ScrollView(context) {
        var interactive = true
            set(value) {
                field = value
                importantForAccessibility = if (value) IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                if (!value) findFocus()?.clearFocus()
            }

        init {
            isVerticalScrollBarEnabled = false
            addView(tiles, FrameLayout.LayoutParams(tiles.gridWidth, tiles.gridHeight))
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean =
            !interactive || super.dispatchTouchEvent(event)

        override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
            if (interactive) super.addFocusables(views, direction, focusableMode)
        }
    }

    private fun pageViewportContains(x: Float, y: Float): Boolean {
        val current = grid ?: return false
        if (current.viewportContains(x, y) || previewView?.tiles?.viewportContains(x, y) == true) return true
        if (previewView == null || workspace.translationX == 0f) return false
        // The gap between sliding pages remains part of the gesture area.
        val viewport = current.parent as? View ?: return false
        val position = IntArray(2).also(viewport::getLocationOnScreen)
        val left = position[0] - workspace.translationX
        return x >= left && x < left + viewport.width &&
            y >= position[1] && y < position[1] + viewport.height
    }

    private fun followPage(dx: Float) {
        if (!animateHomePages) return
        val direction = if (dx < 0) 1 else -1
        val adjacent = pageInDirection(direction)
        if (!editing && direction > 0 && adjacent >= layout.pages) {
            val motion = libraryMotion ?: stageLibrary()
            if (motion != null) {
                drawLibraryProgress(motion, -dx / content.width)
                return
            }
        }
        libraryMotion?.let {
            if (adjacent in 0 until layout.pages) finishLibraryMotion(it, false)
            else drawLibraryProgress(it, 0f)
        }
        showPagePreview(adjacent, direction)
        val width = content.width.toFloat()
        val travel = dx.coerceIn(-width, width)
        workspace.translationX = if (previewView != null) travel else if (reduced) 0f else travel * .24f
    }

    private fun showPagePreview(adjacent: Int, direction: Int) {
        if (adjacent == previewPage) return
        val current = grid ?: return
        previewView?.let(workspace::removeView)
        previewView = null
        previewPage = adjacent
        if (adjacent !in 0 until layout.pages) return
        val neighbor = TileGrid(this, adjacent, "", current.gridWidth, current.gridHeight, 4, 6, ::tileView)
        neighbor.populate(layout.tiles)
        val primary = current.parent as View
        previewView = PageViewport(this, neighbor).apply {
            translationX = (direction * content.width).toFloat()
            interactive = false
        }
        workspace.addView(previewView, FrameLayout.LayoutParams(primary.layoutParams as FrameLayout.LayoutParams))
    }

    private fun beginPageDrag(): Float {
        workspace.animate().cancel()
        libraryMotion?.let { motion ->
            stopLibraryAnimation(motion)
            if (motion.progress > 0f) return -motion.progress * content.width
        }
        return workspace.translationX / if (previewView == null) .24f else 1f
    }

    private fun settlePage(duration: Long = if (animateHomePages) 160L else 0L) {
        libraryMotion?.let { motion ->
            if (motion.progress > 0f) {
                settleLibraryMotion(motion, open = false)
                return
            }
            finishLibraryMotion(motion, false)
        }
        val settling = workspace
        settling.animate().translationX(0f).setDuration(duration).withEndAction {
            if (workspace === settling) {
                previewView?.let(settling::removeView)
                previewView = null
                previewPage = -1
            }
        }.start()
        scheduleSearch(duration)
    }

    private fun footer(homeLayer: FrameLayout, dockHeight: Int) {
        val bar = horizontal().apply { gravity = Gravity.CENTER }
        homeFooter = bar
        fillFooter(bar)
        homeLayer.addView(bar, FrameLayout.LayoutParams(-1, dp(42), Gravity.BOTTOM).apply {
            bottomMargin = dockHeight + dp(8)
        })
    }

    private fun fillFooter(bar: LinearLayout) {
        bar.removeAllViews()
        if (editing) {
            bar.addView(label("${page + 1}  /  ${maxOf(layout.pages, page + 1)}", 14f, Color.WHITE).apply { gravity = Gravity.CENTER })
        } else {
            val visible = visiblePages
            val searchButton = store.settingEnabled("search_button")
            val dots = store.settingEnabled("page_dots") && visible.size > 1 && (pageIndicatorVisible || !searchButton)
            if (dots) {
                bar.addView(footerPill(visible.take(8).joinToString(" ") { if (it == page) "●" else "·" }) {
                    switchPage(visiblePages.firstOrNull { it > page } ?: visiblePages.first())
                }.apply { contentDescription = "Page ${visible.indexOf(page) + 1} of ${visible.size}. Next page" })
            }
            if (!searchButton) bar.addView(footerPill("Library") { showBrowser(false) })
            else if (!dots) bar.addView(footerPill("⌕  Search") { showBrowser(true) }.apply {
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.isScrollable = visiblePages.size > 1
                        if (pageInDirection(-1) >= 0) info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        if (pageInDirection(1) < this@HomeActivity.layout.pages) {
                            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        }
                    }

                    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                        val next = when (action) {
                            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> pageInDirection(-1)
                            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> pageInDirection(1)
                            else -> return super.performAccessibilityAction(host, action, args)
                        }
                        if (next !in 0 until this@HomeActivity.layout.pages) return false
                        switchPage(next)
                        return true
                    }
                }
            })
        }
    }

    private fun revealPageIndicator() {
        handler.removeCallbacks(restoreSearch)
        if (editing || pageIndicatorVisible || visiblePages.size <= 1 || !store.settingEnabled("page_dots") ||
            !store.settingEnabled("search_button")) return
        pageIndicatorVisible = true
        homeFooter?.let(::fillFooter)
    }

    private fun scheduleSearch(settleDuration: Long) {
        if (pageIndicatorVisible && !isDestroyed) {
            handler.removeCallbacks(restoreSearch)
            handler.postDelayed(restoreSearch, settleDuration + 550)
        }
    }

    private fun resetPageIndicator() {
        handler.removeCallbacks(restoreSearch)
        pageIndicatorVisible = false
    }

    private fun footerPill(title: String, action: () -> Unit) = pill(title, action).apply {
        minWidth = dp(74)
        textSize = 12f
        setPadding(dp(10), 0, dp(10), 0)
        background = InsetDrawable(rounded(0x42ffffff, dp(14).toFloat()), 0, dp(7), 0, dp(7))
    }

    private fun pill(title: String, action: () -> Unit) = label(title, 14f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(42)
        minWidth = dp(48)
        setPadding(dp(13), 0, dp(13), 0)
        background = rounded(0x42ffffff, dp(21).toFloat())
        layoutParams = LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(6) }
        setOnClickListener { action() }
    }

    private class CatalogBinding(val refresh: () -> Unit)

    private fun refreshTileCatalog(view: View) {
        (view.tag as? CatalogBinding)?.refresh?.invoke()
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) refreshTileCatalog(view.getChildAt(index))
        }
    }

    private fun tileView(tile: Tile, cellWidth: Int, cellHeight: Int): View {
        if (tile.isWidget) {
            val view = widgets.view(tile, cellWidth * tile.width - dp(8), cellHeight * tile.height - dp(8)) {
                widgets.choose(page, tile)
            }
            if (!editing) view.setOnLongClickListener { tileMenu(view, tile); true }
            return view
        }
        val name = title(tile)
        val frame = vertical().apply { gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false }
        val icon = FrameLayout(this)
        val baseSize = when (store.setting("icon_size", "regular")) { "small" -> 53; "large" -> 68; else -> 62 }
        val showLabel = tile.slot.page != Slot.DOCK && store.settingEnabled("labels")
        val size = min(dp(baseSize), min(cellWidth - dp(12), cellHeight - if (showLabel) dp(22) else dp(7))).coerceAtLeast(dp(25))
        icon.background = rounded(0x68d2eaff, size * .25f, 0x70ffffff)
        icon.clipToOutline = true
        if (!editing && tile.key != null) {
            icon.foreground = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(0x66000000))
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
        }
        if (tile.kind == TileKind.FOLDER) {
            val folderTiles = layout.tiles.filter { it.slot.folder == tile.id }.sortedBy { it.slot.cell }.take(9)
            val mini = size / 5
            folderTiles.forEachIndexed { i, child ->
                icon.addView(appIcon(child), FrameLayout.LayoutParams(mini, mini).apply {
                    leftMargin = size / 8 + (i % 3) * (mini + size / 22)
                    topMargin = size / 8 + (i / 3) * (mini + size / 22)
                })
            }
        } else icon.addView(appIcon(tile), FrameLayout.LayoutParams(-1, -1))
        val iconOverlay = FrameLayout(this).apply {
            clipChildren = false
            addView(icon, FrameLayout.LayoutParams(size, size))
        }
        val count = tile.key?.takeIf { it.profile == apps.personalSerial }?.let { BadgeListener.counts[packageOf(it)] } ?: 0
        if (count > 0 && store.settingEnabled("badges")) {
            iconOverlay.addView(label(if (count > 99) "99+" else count.toString(), 10f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = rounded(0xffd73851.toInt(), dp(14).toFloat())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(22), dp(19), Gravity.TOP or Gravity.END))
        }
        val remove = if (editing && tile.kind != TileKind.FOLDER) {
            label("−", 15f, ink, true).apply {
                gravity = Gravity.CENTER
                background = rounded(0xffe1eaf5.toInt(), dp(13).toFloat())
                contentDescription = "Remove $name from Home"
                setOnClickListener { removeTile(tile) }
            }.also { iconOverlay.addView(it, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.START)) }
        } else null
        frame.addView(iconOverlay, LinearLayout.LayoutParams(size, size))
        val caption = if (showLabel) label(name, 12f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(4f, 0f, 2f, 0xb0000000.toInt())
        }.also { frame.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }) } else null
        fun describe(name: String) {
            frame.contentDescription = if (count == 0) name else "$name, $count notifications"
        }
        describe(name)
        if (tile.key != null && entries.isEmpty()) frame.tag = CatalogBinding {
            val title = title(tile)
            describe(title)
            caption?.text = title
            remove?.contentDescription = "Remove $title from Home"
        }
        frame.setOnClickListener {
            when {
                editing && tile.kind != TileKind.FOLDER -> tileMenu(frame, tile)
                tile.kind == TileKind.FOLDER -> openFolder(tile, frame, icon)
                tile.key != null -> launch(tile.key, icon, null)
            }
        }
        frame.setOnLongClickListener { tileMenu(frame, tile); true }
        if (tile.id == folder) {
            frame.alpha = 0f
            openedFolderTile = frame
        }
        if (editing && !reduced) frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            private val shake = ObjectAnimator.ofFloat(icon, View.ROTATION, -1.35f, 1.35f).apply {
                duration = 165
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                startDelay = (tile.slot.cell % 4) * 22L
            }
            override fun onViewAttachedToWindow(v: View) = shake.start()
            override fun onViewDetachedFromWindow(v: View) = shake.cancel()
        })
        return frame
    }

    private fun appIcon(tile: Tile) = ImageView(this).apply {
        bindAppIcon(this, tile)
        if (entries.isEmpty()) tag = CatalogBinding { bindAppIcon(this, tile) }
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun bindAppIcon(view: ImageView, tile: Tile) {
        val entry = entries.firstOrNull { it.key.component == tile.key?.component && it.key.profile == tile.key.profile }
        val shortcut = entry?.takeIf { tile.kind == TileKind.SHORTCUT }?.let { app ->
            apps.shortcuts(app).firstOrNull { it.id == tile.key?.shortcut }
        }
        view.setImageDrawable(getDrawable(R.drawable.ic_cupera))
        if (shortcut != null) {
            val drawable = runCatching {
                apps.launcher.getShortcutBadgedIconDrawable(shortcut, resources.displayMetrics.densityDpi)
            }.getOrNull()
            if (drawable != null) view.setImageDrawable(drawable)
        } else if (entry != null) icons.show(view, entry, entry.key.profile == apps.personalSerial)
    }

    private fun title(tile: Tile): String = when (tile.kind) {
        TileKind.FOLDER -> tile.name
        TileKind.CLOCK -> "Clock"
        TileKind.DATE -> "Date"
        TileKind.WIDGET -> "Widget"
        else -> tile.name.ifBlank {
            entries.firstOrNull { it.key == tile.key }?.title ?: tile.key?.let(::packageOf)?.substringAfterLast('.') ?: "App"
        }
    }

    private fun launch(key: AppKey, anchor: View, user: UserHandle?) {
        closeKeyboard()
        if (apps.launch(key, anchor, !reduced, user)) {
            externalLaunchPending = !editing && !search && !library && folderPresentation == null &&
                key.component.substringBefore('/') != packageName
        } else message("This app is unavailable.")
    }

    private fun closeKeyboard() {
        browser?.cancelPendingKeyboard()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun newBrowser() = AppBrowser(this, apps, catalog, darkMode, store, privateOpen,
        onBack = ::back, onLibrary = { showBrowser(false) }, onAdd = ::addApp, onPrivate = { privateOpen = it },
        onLaunch = { key, anchor, user -> launch(key, anchor, user) })

    private fun stageLibrary(): LibraryMotion? {
        val home = homeLayer ?: return null
        val panel = newBrowser()
        val background = FrameLayout(this)
        android.os.Trace.beginSection("Cupera library preview build")
        val view = try {
            panel.view(search = false, focusOnOpen = false, libraryBackground = background)
        } finally {
            android.os.Trace.endSection()
        }
        previewView?.let(workspace::removeView)
        previewView = null
        previewPage = -1
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        content.addView(background, content.indexOfChild(home), FrameLayout.LayoutParams(-1, -1))
        content.addView(view, FrameLayout.LayoutParams(-1, -1))
        return LibraryMotion(panel, view, home, background).also { libraryMotion = it }
    }

    private fun drawLibraryProgress(motion: LibraryMotion, progress: Float) {
        motion.progress = progress.coerceIn(0f, 1f)
        motion.home.visibility = if (motion.progress == 1f) View.INVISIBLE else View.VISIBLE
        motion.view.visibility = if (motion.progress == 0f) View.INVISIBLE else View.VISIBLE
        motion.panel.drawLibraryProgress(motion.progress, content.width)
        workspace.translationX = -content.width * motion.progress
        dock.translationX = workspace.translationX
        dockWallpaper?.alignTo(dock)
        homeFooter?.alpha = 1f - motion.progress
    }

    private fun stopLibraryAnimation(motion: LibraryMotion) {
        motion.animator?.removeAllListeners()
        motion.animator?.cancel()
        motion.animator = null
    }

    private fun finishLibraryMotion(motion: LibraryMotion, open: Boolean) {
        stopLibraryAnimation(motion)
        motion.draggingReturn = false
        drawLibraryProgress(motion, if (open) 1f else 0f)
        if (open) {
            motion.view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            motion.panel.focusLibrary()
            return
        }
        motion.trackingReturn = false
        libraryMotion = null
        if (browser === motion.panel) browser = null
        content.removeView(motion.view)
        content.removeView(motion.background)
        if (renderedHome != homeDisplay()) {
            render()
        } else {
            motion.home.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            motion.home.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            scheduleSearch(0)
        }
    }

    private fun settleLibraryMotion(motion: LibraryMotion, open: Boolean, fromReturnSwipe: Boolean = false) {
        stopLibraryAnimation(motion)
        val target = if (open) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled() || motion.progress == target) {
            finishLibraryMotion(motion, open)
            return
        }
        motion.animator = ValueAnimator.ofFloat(motion.progress, target).apply {
            val distance = abs(target - motion.progress)
            if (fromReturnSwipe && !open && !reduced) {
                // Follow the measured deceleration from the released position.
                duration = (340 * (distance / .4f).coerceAtMost(1f)).toLong()
                interpolator = PathInterpolator(.28f, .1f, .25f, 1f)
            } else {
                duration = ((if (open) 300 else 240) * distance).toLong()
            }
            addUpdateListener { drawLibraryProgress(motion, it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    finishLibraryMotion(motion, open)
                }
            })
            start()
        }
    }

    private fun showBrowser(forSearch: Boolean) {
        if (closed || isFinishing) return
        if (!forSearch && search) closeKeyboard()
        resetPageIndicator()
        editing = false
        folder = null
        library = !forSearch
        search = forSearch
        privateOpen = false
        render()
    }

    private fun canPreviewSearch(): Boolean =
        !editing && !reduced && folder == null && folderPresentation == null &&
            homeLayer != null && store.settingEnabled("search_gesture") &&
            (grid?.parent as? PageViewport)?.canScrollVertically(-1) == false

    private fun settleSearchPreview(open: Boolean) {
        if (open) {
            browser?.focusSearch()
            animateSearch(1f)
        } else back()
    }

    private fun stopSearchAnimation() {
        searchAnimator?.removeAllListeners()
        searchAnimator?.cancel()
        searchAnimator = null
    }

    private fun drawSearchProgress(progress: Float) {
        searchProgress = progress
        browser?.drawSearchProgress(progress)
        val blur = searchBlurEffects[(progress * 8).roundToInt()]
        homeLayer?.setRenderEffect(blur)
        if (wallpaperLayer.background != null) wallpaperLayer.setRenderEffect(blur)
    }

    private fun animateSearch(target: Float) {
        stopSearchAnimation()
        if (searchProgress == target) {
            if (searchClosing) finishSearch()
            return
        }
        searchAnimator = ValueAnimator.ofFloat(searchProgress, target).apply {
            duration = (180 * abs(target - searchProgress)).toLong()
            addUpdateListener { drawSearchProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    searchAnimator = null
                    if (searchClosing) finishSearch()
                }
            })
            start()
        }
    }

    private fun finishSearch() {
        stopSearchAnimation()
        search = false
        reloadWorkspace()
        val backdrop = homeLayer
        val panel = searchPanel
        if (backdrop == null || panel == null || renderedHome != homeDisplay() ||
            backdrop.width != content.width || backdrop.height != content.height) {
            render()
            return
        }
        searchPanel = null
        searchClosing = false
        searchProgress = 0f
        browser = null
        content.removeView(panel)
        backdrop.setRenderEffect(null)
        wallpaperLayer.setRenderEffect(null)
        backdrop.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        backdrop.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        systemBars(root, true, keepStatusInset = true, padForIme = false)
        homeFooter?.let(::fillFooter)
    }

    @Deprecated("Back on Android 12")
    override fun onBackPressed() = back()

    private fun back() {
        finishHomeReturn()
        when {
            library && libraryMotion != null -> {
                closeKeyboard()
                invalidatePrivate()
                library = false
                reloadWorkspace()
                settleLibraryMotion(checkNotNull(libraryMotion), false)
                return
            }
            gesture.previewingSearch -> {
                gesture.cancel()
                return
            }
            searchPanel != null -> {
                if (searchClosing) return
                closeKeyboard()
                invalidatePrivate()
                if (reduced) finishSearch()
                else {
                    searchClosing = true
                    animateSearch(0f)
                }
                return
            }
            search || library -> { closeKeyboard(); invalidatePrivate(); search = false; library = false }
            pageVisibilityDraft != null -> pageVisibilityDraft = null
            folderPresentation != null -> { closeFolder(); return }
            editing -> editing = false
            page != visiblePages.first() -> page = visiblePages.first()
            else -> return
        }
        reload()
    }

    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

    private class FolderPresentation(
        val id: String,
        val overlay: FrameLayout,
        val backdrop: View,
        val card: FrameLayout,
        val title: View,
        val openY: Float,
        val collapsedX: Float,
        val collapsedY: Float,
        val collapsedScale: Float
    ) {
        var blurLevel = -1
        var backdropAnimator: ValueAnimator? = null
        var reopenTap: GestureDetector? = null
    }

    private fun openFolder(tile: Tile, frame: View, icon: View) {
        val homeLayer = homeLayer ?: return
        if (layout.tiles.none { it.id == tile.id }) return
        val origin = IntArray(2).also(icon::getLocationOnScreen)
        folderAnchor = Rect(origin[0], origin[1], origin[0] + icon.width, origin[1] + icon.height)
        folder = tile.id
        folderPage = 0
        openedFolderTile = frame
        frame.alpha = 0f
        drawFolder(tile.id, homeLayer)
        revealFolder()
    }

    private fun drawFolder(id: String, homeLayer: FrameLayout) {
        val tile = layout.tiles.firstOrNull { it.id == id } ?: return
        val overlay = FrameLayout(this).apply {
            setOnClickListener { closeFolder() }
        }
        // The recorded reference folder spans about 64% of the portrait screen.
        val width = min((content.width * .64f).toInt(), dp(400))
        val height = min((width * 1.13f).toInt(), content.height * 3 / 4)
        val inset = dp(14)
        val children = layout.tiles.filter { it.slot.folder == id }
        val pageCount = folderPageCount(id, children, includeEmptyPage = editing)
        val pageWidth = width - inset * 2
        val dotsHeight = if (pageCount > 1) dp(22) else 0
        val gridHeight = 3 * maxOf(dp(64), (height - inset * 2 - dotsHeight) / 3)
        val grids = List(pageCount) { index ->
            TileGrid(this, 0, id, pageWidth, gridHeight, 3, 3, ::tileView, firstCell = index * FOLDER_PAGE_SIZE).apply {
                // Empty rows still have to receive drops.
                minimumHeight = gridHeight
                populate(children)
            }
        }
        folderPage = folderPage.coerceIn(grids.indices)
        folderGrid = grids[folderPage]
        val panel = FrameLayout(this).apply {
            setPadding(inset, inset, inset, inset)
            background = rounded(0x42dcefff, dp(34).toFloat(), 0x64ffffff)
            clipToOutline = true
            isClickable = true
        }
        val pages = grids.map { grid ->
            android.widget.ScrollView(this).apply {
                addView(grid, FrameLayout.LayoutParams(-1, gridHeight))
            }
        }
        if (pageCount == 1) {
            panel.addView(pages.single(), FrameLayout.LayoutParams(-1, -1))
        } else {
            val dots = horizontal().apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, dp(2))
                repeat(pageCount) {
                    addView(View(this@HomeActivity).apply {
                        background = rounded(Color.WHITE, dp(4).toFloat())
                    }, LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                        setMargins(dp(4), 0, dp(4), 0)
                    })
                }
            }
            fun select(index: Int) {
                folderPage = index
                folderGrid = grids[index]
                grids.indices.forEach { dots.getChildAt(it).alpha = if (it == index) 1f else .45f }
                dots.contentDescription = "Folder page ${index + 1} of $pageCount"
            }
            val pager = FolderPager(this, pages, pageWidth, folderPage, { reduced }, ::select)
            folderPager = pager
            select(folderPage)
            dots.setOnClickListener { pager.showPage((folderPage + 1) % pageCount) }
            panel.addView(pager, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dotsHeight })
            panel.addView(dots, FrameLayout.LayoutParams(-1, dotsHeight, Gravity.BOTTOM))
        }
        // Keep the folder above the screen midpoint, independent of system bar insets.
        val centerOffset = (root.paddingBottom - root.paddingTop) / 2f - root.height * .075f
        val card = FrameLayout(this).apply { translationY = centerOffset }
        card.addView(panel, FrameLayout.LayoutParams(width, height, Gravity.CENTER))
        val title = label(tile.name, 22f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setOnClickListener { rename(tile) }
            contentDescription = "${tile.name}. Rename folder"
        }
        card.addView(title, FrameLayout.LayoutParams(width, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = ((content.height - height) / 2 - dp(52)).coerceAtLeast(0)
        })
        overlay.addView(card, FrameLayout.LayoutParams(-1, -1))
        homeLayer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        content.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        val origin = IntArray(2).also(content::getLocationOnScreen)
        val anchor = folderAnchor
        folderPresentation = FolderPresentation(
            id, overlay, homeLayer, card, title, centerOffset.toFloat(),
            if (anchor == null) 0f else anchor.exactCenterX() - origin[0] - content.width / 2f,
            if (anchor == null) centerOffset.toFloat() else anchor.exactCenterY() - origin[1] - content.height / 2f,
            ((anchor?.width() ?: width / 5).toFloat() / width).coerceIn(.12f, .5f)
        ).also { setFolderBackdrop(it, folderBlurEffects.lastIndex) }
    }

    private fun setFolderBackdrop(presentation: FolderPresentation, level: Int) {
        if (presentation.blurLevel == level) return
        presentation.blurLevel = level
        val blur = folderBlurEffects[level]
        presentation.backdrop.setRenderEffect(blur)
        if (wallpaperLayer.background != null) wallpaperLayer.setRenderEffect(blur)
        val opacity = 0x36 * level / folderBlurEffects.lastIndex
        presentation.overlay.setBackgroundColor((opacity shl 24) or 0x00091a34)
    }

    private fun animateFolderBackdrop(
        presentation: FolderPresentation, opening: Boolean, duration: Long, linearFade: Boolean = false
    ) {
        presentation.backdropAnimator?.cancel()
        val initial = presentation.blurLevel
        presentation.backdropAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener {
                val progress = it.animatedFraction
                val level = if (linearFade) {
                    val target = if (opening) folderBlurEffects.lastIndex else 0
                    initial + (target - initial) * progress
                } else if (opening) {
                    val remaining = 1f - (progress / .45f).coerceAtMost(1f)
                    initial + (folderBlurEffects.lastIndex - initial) * (1f - remaining * remaining)
                } else {
                    initial * (1f - progress * progress)
                }
                setFolderBackdrop(presentation, level.roundToInt())
            }
            start()
        }
    }

    private fun closeFolder() {
        val presentation = folderPresentation ?: return
        if (folder == null) return
        folder = null
        if (!ValueAnimator.areAnimatorsEnabled()) {
            dismissFolder(presentation)
            return
        }
        folderAnchor?.let { anchor ->
            presentation.reopenTap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                private var downOnAnchor = false

                override fun onDown(event: MotionEvent): Boolean {
                    downOnAnchor = anchor.contains(event.rawX.toInt(), event.rawY.toInt())
                    return downOnAnchor
                }

                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    if (!downOnAnchor || folderPresentation !== presentation || folder != null ||
                        !anchor.contains(event.rawX.toInt(), event.rawY.toInt())) return false
                    folder = presentation.id
                    presentation.reopenTap = null
                    revealFolder(fromCurrentFrame = true)
                    return true
                }
            })
        }
        val card = presentation.card
        val title = presentation.title
        if (reduced) {
            val duration = (300 * card.alpha).roundToInt().coerceAtLeast(1).toLong()
            card.animate().cancel()
            animateFolderBackdrop(presentation, opening = false, duration = duration, linearFade = true)
            card.animate().alpha(0f).setInterpolator(LinearInterpolator()).setDuration(duration)
                .withEndAction { dismissFolder(presentation) }.start()
            return
        }
        val duration = 205L
        title.animate().cancel()
        title.animate().alpha(0f).setStartDelay(0).setDuration(135).start()
        animateFolderBackdrop(presentation, opening = false, duration = duration)
        card.animate().cancel()
        card.animate().translationX(presentation.collapsedX).translationY(presentation.collapsedY)
            .scaleX(presentation.collapsedScale).scaleY(presentation.collapsedScale)
            .setInterpolator(PathInterpolator(.43f, .02f, .7f, .63f)).setDuration(duration)
            .withEndAction { dismissFolder(presentation) }.start()
    }

    private fun dismissFolder(presentation: FolderPresentation) {
        if (folderPresentation !== presentation) return
        presentation.card.animate().cancel()
        presentation.title.animate().cancel()
        presentation.backdropAnimator?.cancel()
        setFolderBackdrop(presentation, 0)
        presentation.backdrop.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        content.removeView(presentation.overlay)
        openedFolderTile?.alpha = 1f
        openedFolderTile = null
        folderPresentation = null
        folderAnchor = null
        folderGrid = null
        folderPager = null
    }

    private fun revealFolder(fromCurrentFrame: Boolean = false) {
        val presentation = folderPresentation ?: return
        val card = presentation.card
        if (!ValueAnimator.areAnimatorsEnabled() && !fromCurrentFrame) return
        if (fromCurrentFrame) {
            card.animate().cancel()
            presentation.title.animate().cancel()
        }
        if (reduced) {
            if (!fromCurrentFrame) {
                card.alpha = 0f
                setFolderBackdrop(presentation, 0)
            }
            val duration = (335 * (1f - card.alpha)).roundToInt().coerceAtLeast(1).toLong()
            animateFolderBackdrop(presentation, opening = true, duration = duration, linearFade = true)
            card.animate().alpha(1f).setInterpolator(LinearInterpolator()).setDuration(duration).start()
            return
        }
        if (!fromCurrentFrame) {
            card.translationX = presentation.collapsedX
            card.translationY = presentation.collapsedY
            card.scaleX = presentation.collapsedScale
            card.scaleY = presentation.collapsedScale
            presentation.title.alpha = 0f
            setFolderBackdrop(presentation, 0)
        }
        val remaining = ((1f - card.scaleX) / (1f - presentation.collapsedScale)).coerceIn(0f, 1f)
        val duration = (335 * remaining).roundToInt().coerceAtLeast(1).toLong()
        animateFolderBackdrop(presentation, opening = true, duration = duration)
        card.animate().translationX(0f).translationY(presentation.openY).scaleX(1f).scaleY(1f)
            .setInterpolator(PathInterpolator(.22f, .45f, .28f, 1f)).setDuration(duration).start()
        presentation.title.animate().alpha(1f)
            .setStartDelay(if (fromCurrentFrame) 0 else 90)
            .setDuration(minOf(205, duration)).start()
    }

    private fun rename(tile: Tile) {
        val input = EditText(this).apply { setSingleLine(); setText(tile.name); selectAll() }
        AlertDialog.Builder(this).setTitle("Rename folder").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().take(120)
                if (name.isNotEmpty()) commit(layout.tiles.map { if (it.id == tile.id) it.copy(name = name) else it })
            }.show()
    }

    private fun tileMenu(anchor: View, tile: Tile) {
        val popup = PopupMenu(this, anchor)
        fun action(name: String, callback: () -> Unit) {
            popup.menu.add(name).setOnMenuItemClickListener { callback(); true }
        }
        if (!editing) action("Edit Home Screen") { editing = true; render() }
        if (tile.kind == TileKind.FOLDER) action("Rename Folder") { rename(tile) }
        action("Move…") { movePicker(tile) }
        if (tile.isWidget) {
            action("Resize…") { resize(tile) }
            when {
                tile.kind == TileKind.WIDGET && tile.widgetId == 0 -> action("Connect Widget") { widgets.choose(page, tile) }
                tile.kind == TileKind.WIDGET -> action("Configure Widget") { widgets.reconfigure(tile) }
                else -> action("Widget Settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
            }
        }
        if (tile.kind == TileKind.APP) {
            entries.firstOrNull { it.key == tile.key }?.let { entry ->
                apps.shortcuts(entry).take(5).forEach { shortcut ->
                    val key = entry.key.copy(shortcut = shortcut.id)
                    action(shortcut.shortLabel?.toString() ?: "Shortcut") { launch(key, anchor, null) }
                    action("Add ${shortcut.shortLabel ?: "shortcut"} to Home") { addApp(key, shortcut.shortLabel?.toString().orEmpty()) }
                }
                action("Android App Info") {
                    runCatching { apps.launcher.startAppDetailsActivity(entry.info.componentName, entry.info.user, null, null) }
                        .onFailure { message("App information is unavailable.") }
                }
            }
        }
        action("Remove from Home") {
            if (tile.kind == TileKind.FOLDER) {
                AlertDialog.Builder(this).setTitle("Remove folder and its items from Home?")
                    .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ -> removeTile(tile) }.show()
            } else removeTile(tile)
        }
        popup.show()
    }

    private fun removeTile(tile: Tile) {
        val removed = layout.tiles.filter { it.id == tile.id || it.slot.folder == tile.id }
        if (commit(Layout.removeEmptyFolders(layout.tiles - removed.toSet()))) {
            apps.updatePinned(layout.tiles, removed.mapNotNull { it.key }.filter { it.shortcut.isNotEmpty() })
        }
    }

    private fun folderPageCount(id: String, children: List<Tile>, includeEmptyPage: Boolean): Int {
        val occupiedPages = (children.maxOfOrNull { it.slot.cell } ?: 0) / FOLDER_PAGE_SIZE + 1
        return if (includeEmptyPage) min(occupiedPages + 1, Slot(0, 0, id).capacity / FOLDER_PAGE_SIZE) else occupiedPages
    }

    private fun movePicker(tile: Tile) {
        val destinations = mutableListOf<Pair<String, Slot>>()
        val sourceFolder = layout.tiles.firstOrNull { it.id == tile.slot.folder && it.kind == TileKind.FOLDER }
        if (sourceFolder != null) {
            val children = layout.tiles.filter { it.slot.folder == sourceFolder.id }
            val pages = folderPageCount(sourceFolder.id, children, includeEmptyPage = true)
            for (cell in 0 until pages * FOLDER_PAGE_SIZE) {
                val slot = Slot(0, cell, sourceFolder.id)
                if (layout.move(tile.id, slot) != null) {
                    val position = cell % FOLDER_PAGE_SIZE
                    val label = "${sourceFolder.name}, page ${cell / FOLDER_PAGE_SIZE + 1}, " +
                        "row ${position / 3 + 1}, column ${position % 3 + 1}"
                    destinations += label to slot
                }
            }
        }
        for (p in 0..min(layout.pages, Layout.MAX_PAGES - 1)) {
            for (cell in 0 until 24) {
                val s = Slot(p, cell)
                if (layout.move(tile.id, s) != null) destinations += "Page ${p + 1}, row ${cell / 4 + 1}, column ${cell % 4 + 1}" to s
            }
        }
        if (!tile.isWidget && store.settingEnabled("dock")) {
            for (i in 0 until 4) {
                val s = Slot(Slot.DOCK, i)
                if (layout.move(tile.id, s) != null) destinations += "Dock, position ${i + 1}" to s
            }
        }
        if (tile.isApp) {
            layout.tiles.filter { it.kind == TileKind.FOLDER && it.id != sourceFolder?.id }.forEach { item ->
                val cell = (0 until 54).firstOrNull { layout.at(Slot(0, it, item.id)) == null }
                if (cell != null) destinations += "${item.name}, position ${cell + 1}" to Slot(0, cell, item.id)
            }
        }
        if (destinations.isEmpty()) return message("No available positions.")
        AlertDialog.Builder(this).setTitle("Move ${title(tile)}")
            .setItems(destinations.map { it.first }.toTypedArray()) { _, i ->
                val destination = destinations[i].second
                layout.move(tile.id, destination)?.let { tiles ->
                    if (destination.folder.isEmpty() && destination.page >= 0) page = destination.page
                    if (destination.folder == folder) folderPage = destination.cell / FOLDER_PAGE_SIZE
                    commit(tiles)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun resize(tile: Tile) {
        val dimensions = (1..4).flatMap { w -> (1..6).map { h -> w to h } }.filter { (w, h) ->
            Layout.valid(layout.tiles.map { if (it.id == tile.id) it.copy(width = w, height = h) else it })
        }
        AlertDialog.Builder(this).setTitle("Widget Size")
            .setItems(dimensions.map { (w, h) -> "$w × $h" }.toTypedArray()) { _, i ->
                val (w, h) = dimensions[i]
                commit(layout.tiles.map { if (it.id == tile.id) it.copy(width = w, height = h) else it })
            }.setNegativeButton("Cancel", null).show()
    }

    private fun pageInDirection(direction: Int): Int {
        if (editing) return page + direction
        val visible = visiblePages
        return if (direction > 0) visible.firstOrNull { it > page } ?: layout.pages
        else visible.lastOrNull { it < page } ?: -1
    }

    private fun switchPage(index: Int) {
        if (index < 0 || index == page) { settlePage(); return }
        if (!editing && index >= layout.pages) {
            val motion = libraryMotion
            if (motion == null) showBrowser(false) else {
                resetPageIndicator()
                library = true
                browser = motion.panel
                motion.home.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                settleLibraryMotion(motion, true)
            }
            return
        }
        if (index >= Layout.MAX_PAGES || index > layout.pages) return
        revealPageIndicator()
        val previous = page
        val direction = if (index > previous) 1 else -1
        val preview = previewView?.takeIf { previewPage == index }
        val offset = if (preview != null) workspace.translationX + direction * content.width
            else content.width * .26f * direction
        val duration = if (animateHomePages && !gesture.active) 320L else 0L
        page = index
        folder = null
        val outgoing = grid?.parent as? PageViewport
        if (!editing && duration > 0 && preview != null && outgoing != null) {
            // Both pages already occupy their final relative positions in the moving viewport.
            workspace.animate().cancel()
            preview.translationX = 0f
            preview.interactive = true
            outgoing.translationX = (-direction * content.width).toFloat()
            outgoing.interactive = false
            grid = preview.tiles
            previewView = outgoing
            previewPage = previous
            workspace.translationX = offset
            homeFooter?.let(::fillFooter)
            settlePage(duration)
            return
        }
        render()
        if (duration > 0) {
            showPagePreview(previous, -direction)
            workspace.translationX = offset
            settlePage(duration)
        } else scheduleSearch(duration)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) finishHomeReturn()
        if (searchClosing && event.actionMasked == MotionEvent.ACTION_DOWN) finishSearch()
        folderPresentation?.let { presentation ->
            if (folder == null) {
                presentation.reopenTap?.onTouchEvent(event)
                return true
            }
        }
        if (library && libraryMotion?.let { handleLibraryReturn(it, event) } == true) return true
        if (::gesture.isInitialized && (!search || gesture.previewingSearch) &&
            !library && pageVisibilityDraft == null && gesture.handle(event)) return true
        if (libraryMotion?.animator != null) return true
        return super.dispatchTouchEvent(event)
    }

    private fun handleLibraryReturn(motion: LibraryMotion, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                motion.trackingReturn = motion.panel.canReturnToHomeBySwipe() &&
                    root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true
                motion.returnStartX = event.x
                motion.returnStartY = event.y
                motion.returnLastX = event.x
                motion.returnLastMoveAt = event.eventTime
            }
            MotionEvent.ACTION_MOVE -> if (motion.trackingReturn) {
                val dx = event.x - motion.returnStartX
                val dy = abs(event.y - motion.returnStartY)
                if (abs(event.x - motion.returnLastX) >= .5f) {
                    motion.returnLastX = event.x
                    motion.returnLastMoveAt = event.eventTime
                }
                if (!motion.draggingReturn && dy > libraryTouchSlop && dy > abs(dx)) motion.trackingReturn = false
                if (!motion.draggingReturn && dx > libraryTouchSlop && dx > dy * 1.2f) {
                    stopLibraryAnimation(motion)
                    motion.returnProgress = motion.progress
                    motion.draggingReturn = true
                    motion.view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    cancelChild(event)
                }
                if (motion.draggingReturn) {
                    if (ValueAnimator.areAnimatorsEnabled()) {
                        drawLibraryProgress(motion, motion.returnProgress - dx / content.width)
                    }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP -> {
                motion.trackingReturn = false
                if (motion.draggingReturn) {
                    motion.draggingReturn = false
                    library = true
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        val dx = event.x - motion.returnStartX
                        val progress = (motion.returnProgress - dx / content.width).coerceIn(0f, 1f)
                        // Choose the target from UP and let the displayed frame settle toward it.
                        val held = event.eventTime - motion.returnLastMoveAt >= LIBRARY_RETURN_PAUSE_MS
                        library = if (held) progress > 1f - LIBRARY_HELD_RETURN_DISTANCE else dx <= dp(44)
                    }
                    if (!library) {
                        invalidatePrivate()
                        reloadWorkspace()
                    }
                    settleLibraryMotion(motion, library, fromReturnSwipe = true)
                    return true
                }
            }
        }
        return false
    }

    private fun cancelChild(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private class TileGrid(
        context: Context, val page: Int, val folder: String, val gridWidth: Int, val gridHeight: Int,
        private val columns: Int, private val rows: Int,
        private val viewFactory: (Tile, Int, Int) -> View,
        private val firstCell: Int = 0
    ) : FrameLayout(context) {
        private val widthPerCell = gridWidth / columns
        private val heightPerCell = gridHeight / rows
        private val tileViews = mutableMapOf<String, Pair<Tile, View>>()
        private val cells = firstCell until firstCell + columns * rows

        private fun dockStart(count: Int) = (gridWidth - count * widthPerCell) / 2

        private fun dockPositions(tiles: Collection<Tile>): Map<String, Int> {
            if (page != Slot.DOCK) return emptyMap()
            val dock = tiles.filter { it.slot.page == Slot.DOCK }.sortedBy { it.slot.cell }
            return dock.mapIndexed { index, tile -> tile.id to (dockStart(dock.size) + index * widthPerCell) }.toMap()
        }

        fun preview(tiles: List<Tile>, dragging: String?, reduced: Boolean) {
            val destinations = tiles.associateBy { it.id }
            val originalDock = dockPositions(tileViews.values.map { it.first })
            val projectedDock = dockPositions(tiles)
            tileViews.values.forEach { (original, view) ->
                val target = destinations[original.id]?.slot
                val offset = target?.takeIf { it.page == page && it.folder == folder && it.cell in cells }
                val dx = when {
                    offset == null -> 0f
                    page == Slot.DOCK -> (projectedDock.getValue(original.id) - originalDock.getValue(original.id)).toFloat()
                    else -> ((offset.cell % columns - original.slot.cell % columns) * widthPerCell).toFloat()
                }
                val dy = if (offset == null) 0f else
                    (((offset.cell - firstCell) / columns - (original.slot.cell - firstCell) / columns) * heightPerCell).toFloat()
                val alpha = if (original.id == dragging) .25f else 1f
                view.animate().cancel()
                if (reduced) {
                    view.translationX = dx
                    view.translationY = dy
                    view.alpha = alpha
                } else view.animate().translationX(dx).translationY(dy).alpha(alpha).setDuration(145).start()
            }
        }

        fun populate(tiles: List<Tile>) {
            val dock = dockPositions(tiles)
            tiles.filter { it.slot.page == page && it.slot.folder == folder && it.slot.cell in cells }.forEach {
                val gap = if (it.isWidget) context.dp(4) else 0
                val child = viewFactory(it, widthPerCell, heightPerCell)
                tileViews[it.id] = it to child
                addView(child, LayoutParams(widthPerCell * it.width - 2 * gap, heightPerCell * it.height - 2 * gap).apply {
                        leftMargin = (dock[it.id] ?: (it.slot.cell % columns * widthPerCell)) + gap
                        topMargin = (it.slot.cell - firstCell) / columns * heightPerCell + gap
                    })
            }
        }

        fun viewportContains(x: Float, y: Float): Boolean =
            (parent as? View)?.let { contains(it, x, y) } ?: false

        private fun contains(view: View, x: Float, y: Float): Boolean {
            if (!view.isAttachedToWindow) return false
            val visible = Rect()
            if (!view.getGlobalVisibleRect(visible)) return false
            val root = IntArray(2).also(view.rootView::getLocationOnScreen)
            return visible.contains(x.toInt() - root[0], y.toInt() - root[1])
        }

        fun slot(x: Float, y: Float): Slot? {
            if (!contains(this, x, y)) return null
            val window = IntArray(2).also(::getLocationOnScreen)
            val localX = x - window[0]
            val localY = y - window[1]
            if (page == Slot.DOCK) {
                val dock = tileViews.values.map { it.first }.sortedBy { it.slot.cell }
                val offset = localX - dockStart(dock.size)
                return if (offset < 0) null else dock.getOrNull(offset.toInt() / widthPerCell)?.slot
            }
            if (localX < 0 || localY < 0 || localX >= widthPerCell * columns || localY >= heightPerCell * rows) return null
            return Slot(page, firstCell + localY.toInt() / heightPerCell * columns + localX.toInt() / widthPerCell, folder)
        }

        fun dockEdge(x: Float, y: Float): DockEdge? {
            if (page != Slot.DOCK || tileViews.size == columns || !contains(this, x, y)) return null
            val localX = x - IntArray(2).also(::getLocationOnScreen)[0]
            val start = dockStart(tileViews.size)
            return when {
                localX < start -> DockEdge.START
                localX >= start + tileViews.size * widthPerCell -> DockEdge.END
                else -> null
            }
        }
    }

    private class HomeGesture(private val host: HomeActivity) {
        private sealed interface Drop {
            data class Cell(val slot: Slot) : Drop
            data class DockMargin(val edge: DockEdge) : Drop
        }
        private val slop = ViewConfiguration.get(host).scaledTouchSlop
        private val holdSlop = slop / 2f
        private var startX = 0f
        private var startY = 0f
        private var x = 0f
        private var y = 0f
        private var tracking = false
        private var paging = false
        var previewingSearch = false
            private set
        private var pageDragOffset = 0f
        private var verticalScroll = false
        private var emptyHold = false
        private var holdTimeout: Int? = null
        private var revision = 0L
        private var candidate: Drop? = null
        private var hoverTime = 0L
        private var edge = 0
        private var origin: Tile? = null
        private var dragged: Tile? = null
        private var shadow: View? = null
        val active get() = dragged != null || paging || previewingSearch
        private val hold = Runnable { finishEmptyHold() }
        private val turnPage = Runnable {
            if (dragged != null && edge != 0) {
                if (host.folder == null) {
                    host.switchPage(host.page + edge)
                    candidate = null
                } else {
                    host.folderPager?.showPage(host.folderPage + edge, edgeTurn = true)
                    edge = 0
                    updateDrag(edgeDelay = 750)
                }
            }
        }

        private fun stopHold() {
            host.handler.removeCallbacks(hold)
            holdTimeout = null
        }

        private fun finishEmptyHold() {
            stopHold()
            if (!tracking || paging || dragged != null || origin != null || host.folder != null || host.editing) return
            emptyHold = true
            host.editing = true
            host.render()
        }

        private fun slot(x: Float, y: Float): Slot? {
            return if (host.folder != null) host.folderGrid?.slot(x, y)
            else host.dockGrid?.slot(x, y) ?: host.grid?.slot(x, y)
        }

        private fun drop(x: Float, y: Float): Drop? {
            if (host.folder == null) host.dockGrid?.dockEdge(x, y)?.let { return Drop.DockMargin(it) }
            return slot(x, y)?.let { Drop.Cell(it) }
        }

        private fun move(tile: Tile, destination: Drop, merge: Boolean = false): List<Tile>? = when (destination) {
            is Drop.Cell -> host.layout.move(tile.id, destination.slot, merge)
            is Drop.DockMargin -> host.layout.moveToDockEdge(tile.id, destination.edge)
        }

        fun handle(event: MotionEvent): Boolean {
            x = event.rawX
            y = event.rawY
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancel()
                    startX = x
                    startY = y
                    val s = slot(x, y)
                    if (s == null && (host.folder != null || host.editing || !host.pageViewportContains(x, y))) {
                        return false
                    }
                    tracking = true
                    origin = s?.let(host.layout::at)
                    revision = host.layout.revision
                    if (origin == null) {
                        val timeout = ViewConfiguration.getLongPressTimeout()
                        holdTimeout = timeout
                        host.handler.postDelayed(hold, timeout.toLong())
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    if (previewingSearch) {
                        previewingSearch = false
                        cancel()
                        host.settleSearchPreview(false)
                        return true
                    }
                    val wasActive = active
                    cancel()
                    return wasActive
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking || verticalScroll) return false
                    val dx = x - startX
                    val dy = y - startY
                    if (previewingSearch) {
                        host.drawSearchProgress((dy / host.dp(155)).coerceIn(0f, 1f))
                        return true
                    }
                    val distance = abs(dx) + abs(dy)
                    if (distance > holdSlop) stopHold()
                    if (distance <= slop && !active) return emptyHold
                    if (!active) {
                        val tile = origin
                        if (host.editing && tile != null) {
                            dragged = tile
                            host.cancelChild(event)
                            draw()
                        } else if (host.folder == null && abs(dx) > abs(dy) * 1.2f) {
                            paging = true
                            pageDragOffset = host.beginPageDrag()
                            host.revealPageIndicator()
                            host.cancelChild(event)
                        } else if (dy > 0 && abs(dx) < dy * .6f && host.canPreviewSearch()) {
                            previewingSearch = true
                            host.cancelChild(event)
                            host.showBrowser(true)
                            host.drawSearchProgress((dy / host.dp(155)).coerceIn(0f, 1f))
                            return true
                        } else {
                            verticalScroll = true
                            return false
                        }
                    }
                    if (paging) {
                        val travel = Math.copySign((abs(dx) - slop).coerceAtLeast(0f), dx)
                        host.followPage(pageDragOffset + travel)
                        return true
                    }
                    updateDrag()
                    return dragged != null
                }
                MotionEvent.ACTION_UP -> {
                    if (previewingSearch) {
                        val dy = y - startY
                        val open = dy > host.dp(65) && abs(x - startX) < dy * .6f
                        previewingSearch = false
                        cancel()
                        host.settleSearchPreview(open)
                        return true
                    }
                    val timeout = holdTimeout
                    // The main thread can receive both events together after a frame stalls.
                    if (timeout != null && event.eventTime - event.downTime >= timeout &&
                        abs(x - startX) + abs(y - startY) <= holdSlop
                    ) finishEmptyHold()
                    else stopHold()
                    if (paging) {
                        val dx = pageDragOffset + x - startX
                        paging = false
                        val threshold = host.dp(44)
                        when {
                            dx < -threshold -> host.switchPage(host.pageInDirection(1))
                            dx > threshold -> host.switchPage(host.pageInDirection(-1))
                            else -> host.settlePage()
                        }
                        tracking = false
                        return true
                    }
                    val tile = dragged
                    if (tile != null) {
                        val destination = drop(x, y)
                        val merge = destination == candidate && android.os.SystemClock.uptimeMillis() - hoverTime >= 420 &&
                            host.store.settingEnabled("folder_drop")
                        cancel()
                        if (destination != null) {
                            move(tile, destination, merge)?.let { host.commit(it, revision) }
                        }
                        return true
                    }
                    if (verticalScroll && y - startY > host.dp(65) && abs(x - startX) < (y - startY) * .6f &&
                        host.folder == null && !host.editing && host.store.settingEnabled("search_gesture")
                    ) {
                        host.cancelChild(event)
                        host.showBrowser(true)
                        return true
                    }
                    tracking = false
                    return emptyHold
                }
            }
            return false
        }

        private fun updateDrag(edgeDelay: Long = 430) {
            val destination = drop(x, y)
            if (destination != candidate) {
                candidate = destination
                hoverTime = android.os.SystemClock.uptimeMillis()
                preview()
            }
            val location = IntArray(2).also(host.content::getLocationOnScreen)
            val localX = x - location[0]
            if (host.folder != null && destination == null &&
                (localX < host.dp(26) || localX > host.content.width - host.dp(26))) {
                host.folder = null
                host.render()
            }
            val currentEdge = if (host.folder != null) host.folderPager?.edgeAt(x, y) ?: 0 else when {
                localX < host.dp(25) -> -1
                localX > host.content.width - host.dp(25) -> 1
                else -> 0
            }
            if (currentEdge != edge) {
                edge = currentEdge
                host.handler.removeCallbacks(turnPage)
                if (edge != 0) host.handler.postDelayed(turnPage, edgeDelay)
            }
            position()
        }

        private fun preview() {
            val tile = dragged
            val provisional = candidate?.let { drop -> tile?.let { move(it, drop) } }
            listOfNotNull(host.grid, host.dockGrid, host.folderGrid).forEach {
                it.preview(provisional ?: host.layout.tiles, tile?.id, host.reduced)
            }
        }

        fun draw() {
            val tile = dragged ?: return
            preview()
            shadow?.let { (it.parent as? FrameLayout)?.removeView(it) }
            val view = if (tile.isApp) host.appIcon(tile) else host.label(host.title(tile), 15f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = rounded(0xcc273550.toInt(), host.dp(15).toFloat())
            }
            view.alpha = .86f
            view.elevation = host.dp(12).toFloat()
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            host.content.addView(view, FrameLayout.LayoutParams(host.dp(67), host.dp(67)))
            shadow = view
            position()
        }

        private fun position() {
            val location = IntArray(2).also(host.content::getLocationOnScreen)
            shadow?.translationX = x - location[0] - host.dp(33)
            shadow?.translationY = y - location[1] - host.dp(33)
        }

        fun cancel() {
            val cancelSearch = previewingSearch
            previewingSearch = false
            stopHold()
            host.handler.removeCallbacks(turnPage)
            shadow?.let { (it.parent as? FrameLayout)?.removeView(it) }
            if (paging && host::workspace.isInitialized) host.settlePage()
            shadow = null
            val wasDragging = dragged != null
            dragged = null
            origin = null
            candidate = null
            if (wasDragging) preview()
            edge = 0
            tracking = false
            paging = false
            pageDragOffset = 0f
            verticalScroll = false
            emptyHold = false
            if (cancelSearch) host.finishSearch()
        }
    }
}
