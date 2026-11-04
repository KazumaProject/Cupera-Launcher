package io.github.kazumaproject.cuperalauncher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowInsetsController
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.min

class HomeActivity : Activity() {
    private lateinit var store: Workspace
    private lateinit var apps: Apps
    private lateinit var widgets: Widgets
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var workspace: FrameLayout
    private lateinit var dock: FrameLayout
    private var grid: TileGrid? = null
    private var previewPage = -1
    private var homeFooter: LinearLayout? = null
    private var editToolbar: LinearLayout? = null
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
    private var previewView: View? = null
    private var dockGrid: TileGrid? = null
    private var folderGrid: TileGrid? = null
    private var browser: AppBrowser? = null
    private var entries = emptyList<Apps.Entry>()
    internal val icons = AppIcons()
    private val appWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var appGeneration = 0
    private val newPackages = linkedSetOf<String>()
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

    private val profiles = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val removed = intent.action == Intent.ACTION_PROFILE_REMOVED || intent.action == Intent.ACTION_MANAGED_PROFILE_REMOVED
            invalidatePrivate()
            if (removed && apps.hasHome()) reconcileProfiles()
            refreshApps()
        }
    }

    private val packages = object : LauncherApps.Callback() {
        override fun onPackageAdded(name: String, user: UserHandle) {
            if (isPublicProfile(user)) refreshApps(if (store.settingEnabled("add_new", false)) name else null)
            else invalidatePrivate()
        }
        override fun onPackageRemoved(name: String, user: UserHandle) {
            if (!isPublicProfile(user)) { invalidatePrivate(); return }
            val manager = getSystemService(UserManager::class.java)
            val serial = manager.getSerialNumberForUser(user)
            val remaining = layout.tiles.filterNot {
                it.key?.profile == serial && packageOf(it.key) == name
            }
            if (remaining.size != layout.tiles.size) commit(Layout.removeEmptyFolders(remaining))
            refreshApps()
        }
        override fun onPackageChanged(name: String, user: UserHandle) = changed(user)
        override fun onPackagesAvailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = changed(user)
        override fun onPackagesUnavailable(names: Array<out String>, user: UserHandle, replacing: Boolean) = changed(user)
        private fun changed(user: UserHandle) {
            if (isPublicProfile(user)) refreshApps() else invalidatePrivate()
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
        setContentView(root)
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

    override fun onStart() { super.onStart(); widgets.start() }

    override fun onResume() {
        super.onResume()
        val before = darkMode
        darkMode = dark(store)
        if (before != darkMode && browser != null) render()
        systemBars(root, darkMode && (search || library) || !search && !library, keepStatusInset = true)
        layout = store.read()
        refreshApps()
        BadgeListener.changed = { if (!editing && !search && !library && !gesture.active) render() }
    }

    override fun onPause() {
        BadgeListener.changed = null
        gesture.cancel()
        invalidatePrivate()
        super.onPause()
    }

    override fun onStop() { widgets.stop(); super.onStop() }

    override fun onDestroy() {
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
            closeKeyboard()
            gesture.cancel()
            page = 0
            folder = null
            editing = false
            library = false
            search = false
            resetPageIndicator()
            invalidatePrivate()
            render()
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

    private fun refreshApps(newPackage: String? = null) {
        if (newPackage != null) newPackages.add(newPackage)
        val token = ++appGeneration
        appWorker.execute {
            val snapshot = apps.entries()
            handler.post {
                if (token == appGeneration && !isDestroyed) publishApps(snapshot)
            }
        }
    }

    private fun publishApps(snapshot: List<Apps.Entry>) {
        if (entries != snapshot) icons.invalidate()
        entries = snapshot
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
        if (gesture.active) gesture.cancel()
        val current = browser
        if ((search || library) && current != null) current.updateEntries(entries)
        else render()
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
        layout = store.read()
        if (!editing) page = page.coerceIn(0, layout.pages - 1)
        if (layout.tiles.none { it.id == folder }) folder = null
        widgets.reconcile()
        render()
    }

    private fun packageOf(key: AppKey) = key.component.substringBefore('/')
    private fun appTile(key: AppKey, slot: Slot, name: String = "") =
        Tile(java.util.UUID.randomUUID().toString(), if (key.shortcut.isEmpty()) TileKind.APP else TileKind.SHORTCUT,
            slot, key, name)

    private fun addApp(key: AppKey, name: String = "") {
        val slot = layout.firstFree(page.coerceAtMost(layout.pages - 1)) ?: return message("Home is full.")
        if (commit(layout.tiles + appTile(key, slot, name)) && key.shortcut.isNotEmpty()) apps.updatePinned(layout.tiles, listOf(key))
    }

    private fun render() {
        if (!::gesture.isInitialized || content.width == 0 || isFinishing) return
        content.removeAllViews()
        editToolbar?.let(root::removeView)
        editToolbar = null
        browser = null
        homeFooter = null
        folderGrid = null
        val systemWallpaper = store.setting("wallpaper", "tide") == "system"
        if (systemWallpaper) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        root.background = if (systemWallpaper) null else CuperaWallpaper(store.setting("wallpaper") == "dusk" || darkMode)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        val isPanel = library || search
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(0, lightBars)
        window.insetsController?.let { controller ->
            if (editing) controller.hide(WindowInsets.Type.statusBars())
            else controller.show(WindowInsets.Type.statusBars())
        }
        if (isPanel) {
            val panel = AppBrowser(this, apps, entries, darkMode, store, privateOpen,
                onBack = ::back, onLibrary = { showBrowser(false) }, onAdd = ::addApp, onPrivate = { privateOpen = it },
                onLaunch = { key, anchor, user -> launch(key, anchor, user) })
            browser = panel
            content.addView(panel.view(search), FrameLayout.LayoutParams(-1, -1))
            return
        }
        previewPage = -1
        previewView = null
        workspace = FrameLayout(this).apply { clipChildren = false }
        content.addView(workspace, FrameLayout.LayoutParams(-1, -1))
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
        workspace.addView(wrapGrid(gridView), FrameLayout.LayoutParams(gridWidth, maxOf(dp(72), viewportHeight)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = gridTop
        })
        dock = FrameLayout(this)
        dockGrid = null
        if (dockHeight > 0) {
            dock.background = rounded(0x50edf5ff, dp(34).toFloat(), 0x64ffffff)
            val width = totalWidth - dp(48)
            val tiles = TileGrid(this, Slot.DOCK, "", width, dockHeight, 4, 1, ::tileView)
            dockGrid = tiles
            tiles.populate(layout.tiles)
            dock.addView(tiles, FrameLayout.LayoutParams(width, dockHeight, Gravity.CENTER))
            content.addView(dock, FrameLayout.LayoutParams(totalWidth - dp(32), dockHeight, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(5)
            })
        }
        footer(dockHeight)
        if (editing) drawEditToolbar()
        folder?.let(::drawFolder)
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
                show()
            }
        }
        toolbar.addView(edit, LinearLayout.LayoutParams(-2, buttonHeight))
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        toolbar.addView(button("Done").apply { setOnClickListener { editing = false; reload() } }, LinearLayout.LayoutParams(-2, buttonHeight))
        root.addView(toolbar, FrameLayout.LayoutParams(-1, buttonHeight, Gravity.TOP).apply {
            topMargin = dp(9) - root.paddingTop
        })
        editToolbar = toolbar
    }

    private fun openEditSettings(section: String) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, section))
    }

    private fun wrapGrid(tiles: TileGrid) = android.widget.ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(tiles, FrameLayout.LayoutParams(tiles.gridWidth, tiles.gridHeight))
    }

    private fun followPage(dx: Float) {
        if (reduced) return
        val adjacent = page + if (dx < 0) 1 else -1
        val current = grid ?: return
        workspace.animate().cancel()
        if (adjacent != previewPage) {
            previewView?.let(workspace::removeView)
            previewView = null
            previewPage = adjacent
            if (adjacent in 0 until layout.pages) {
                val neighbor = TileGrid(this, adjacent, "", current.gridWidth, current.gridHeight, 4, 6, ::tileView)
                neighbor.populate(layout.tiles)
                val primary = workspace.getChildAt(0)
                previewView = wrapGrid(neighbor).apply { translationX = ((adjacent - page) * content.width).toFloat() }
                workspace.addView(previewView, FrameLayout.LayoutParams(primary.layoutParams as FrameLayout.LayoutParams))
            }
        }
        workspace.translationX = dx * if (previewView == null) .24f else 1f
    }

    private fun settlePage() {
        val duration = if (reduced) 0L else 160L
        workspace.animate().translationX(0f).setDuration(duration).withEndAction {
            previewView?.let(workspace::removeView)
            previewView = null
            previewPage = -1
        }.start()
        scheduleSearch(duration)
    }

    private fun footer(dockHeight: Int) {
        val bar = horizontal().apply { gravity = Gravity.CENTER }
        homeFooter = bar
        fillFooter(bar)
        content.addView(bar, FrameLayout.LayoutParams(-1, dp(42), Gravity.BOTTOM).apply {
            bottomMargin = dockHeight + dp(8)
        })
    }

    private fun fillFooter(bar: LinearLayout) {
        bar.removeAllViews()
        if (editing) {
            bar.addView(label("${page + 1}  /  ${maxOf(layout.pages, page + 1)}", 14f, Color.WHITE).apply { gravity = Gravity.CENTER })
        } else {
            val searchButton = store.settingEnabled("search_button")
            val dots = store.settingEnabled("page_dots") && layout.pages > 1 && (pageIndicatorVisible || !searchButton)
            if (dots) {
                bar.addView(footerPill((0 until min(layout.pages, 8)).joinToString(" ") { if (it == page) "●" else "·" }) {
                    switchPage(if (page + 1 == layout.pages) 0 else page + 1)
                }.apply { contentDescription = "Page ${page + 1} of ${this@HomeActivity.layout.pages}. Next page" })
            }
            if (!searchButton) bar.addView(footerPill("Library") { showBrowser(false) })
            else if (!dots) bar.addView(footerPill("⌕  Search") { showBrowser(true) }.apply {
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.isScrollable = this@HomeActivity.layout.pages > 1
                        if (page > 0) info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                        if (page + 1 < this@HomeActivity.layout.pages) {
                            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        }
                    }

                    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                        val next = when (action) {
                            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> page - 1
                            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> page + 1
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
        if (editing || pageIndicatorVisible || layout.pages <= 1 || !store.settingEnabled("page_dots") ||
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
        if (editing && tile.kind != TileKind.FOLDER) {
            iconOverlay.addView(label("−", 15f, ink, true).apply {
                gravity = Gravity.CENTER
                background = rounded(0xffe1eaf5.toInt(), dp(13).toFloat())
                contentDescription = "Remove $name from Home"
                setOnClickListener { removeTile(tile) }
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.START))
        }
        frame.addView(iconOverlay, LinearLayout.LayoutParams(size, size))
        if (showLabel) frame.addView(label(name, 12f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(4f, 0f, 2f, 0xb0000000.toInt())
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        frame.contentDescription = if (count == 0) name else "$name, $count notifications"
        frame.setOnClickListener {
            when {
                editing && tile.kind != TileKind.FOLDER -> tileMenu(frame, tile)
                tile.kind == TileKind.FOLDER -> { folder = tile.id; render(); revealFolder() }
                tile.key != null -> launch(tile.key, icon, null)
            }
        }
        frame.setOnLongClickListener { tileMenu(frame, tile); true }
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
        val entry = entries.firstOrNull { it.key.component == tile.key?.component && it.key.profile == tile.key.profile }
        val shortcut = entry?.takeIf { tile.kind == TileKind.SHORTCUT }?.let { app ->
            apps.shortcuts(app).firstOrNull { it.id == tile.key?.shortcut }
        }
        setImageDrawable(getDrawable(R.drawable.ic_cupera))
        if (shortcut != null) {
            val drawable = runCatching {
                apps.launcher.getShortcutBadgedIconDrawable(shortcut, resources.displayMetrics.densityDpi)
            }.getOrNull()
            if (drawable != null) setImageDrawable(drawable)
        } else if (entry != null) icons.show(this, entry, entry.key.profile == apps.personalSerial)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
        if (!apps.launch(key, anchor, !reduced, user)) message("This app is unavailable.")
    }

    private fun closeKeyboard() { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0) }

    private fun showBrowser(forSearch: Boolean) {
        resetPageIndicator()
        editing = false
        folder = null
        library = !forSearch
        search = forSearch
        privateOpen = false
        render()
    }

    @Deprecated("Back on Android 12")
    override fun onBackPressed() = back()

    private fun back() {
        when {
            search || library -> { closeKeyboard(); invalidatePrivate(); search = false; library = false }
            folder != null -> { closeFolder(); return }
            editing -> editing = false
            page != 0 -> page = 0
            else -> return
        }
        reload()
    }

    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }

    private fun drawFolder(id: String) {
        val tile = layout.tiles.firstOrNull { it.id == id } ?: return
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x88091a34.toInt())
            setOnClickListener { closeFolder() }
        }
        val card = vertical().apply {
            setPadding(dp(12), dp(7), dp(12), dp(12))
            background = rounded(0xe6334b68.toInt(), dp(30).toFloat(), 0x64ffffff)
            isClickable = true
        }
        val title = label(tile.name, 22f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(52)
            setOnClickListener { rename(tile) }
            contentDescription = "${tile.name}. Rename folder"
        }
        card.addView(title)
        val width = min(content.width - dp(36), dp(400))
        val rows = maxOf(2, (layout.tiles.filter { it.slot.folder == id }.maxOfOrNull { it.slot.cell } ?: 0) / 4 + 1)
        val height = rows * dp(84)
        val tiles = TileGrid(this, 0, id, width - dp(24), height, 4, rows, ::tileView)
        tiles.populate(layout.tiles)
        folderGrid = tiles
        card.addView(android.widget.ScrollView(this).apply { addView(tiles, FrameLayout.LayoutParams(-1, height)) },
            LinearLayout.LayoutParams(-1, min(height, content.height / 2)))
        overlay.addView(card, FrameLayout.LayoutParams(width, -2, Gravity.CENTER))
        content.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun closeFolder() {
        val id = folder ?: return
        val overlay = content.getChildAt(content.childCount - 1) as? FrameLayout
        val card = overlay?.getChildAt(0)
        if (card == null || reduced) {
            folder = null
            render()
            return
        }
        card.animate().cancel()
        card.animate().scaleX(.74f).scaleY(.74f).alpha(.3f).setDuration(160).withEndAction {
            if (overlay.parent === content && folder == id) {
                folder = null
                render()
            }
        }.start()
    }

    private fun revealFolder() {
        if (reduced) return
        val card = (content.getChildAt(content.childCount - 1) as? FrameLayout)?.getChildAt(0) ?: return
        card.scaleX = .72f
        card.scaleY = .72f
        card.alpha = .4f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240).start()
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

    private fun movePicker(tile: Tile) {
        val destinations = mutableListOf<Pair<String, Slot>>()
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
            layout.tiles.filter { it.kind == TileKind.FOLDER }.forEach { item ->
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

    private fun switchPage(index: Int) {
        if (index < 0 || index == page) { settlePage(); return }
        if (!editing && index >= layout.pages) { showBrowser(false); return }
        if (index >= Layout.MAX_PAGES || index > layout.pages) return
        revealPageIndicator()
        val direction = if (index > page) 1 else -1
        val offset = if (previewPage == index && previewView != null) workspace.translationX + direction * content.width
            else content.width * .26f * direction
        page = index
        folder = null
        render()
        val duration = if (!reduced && !gesture.active) 225L else 0L
        if (duration > 0) {
            workspace.translationX = offset
            workspace.animate().translationX(0f).setDuration(duration).start()
        }
        scheduleSearch(duration)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::gesture.isInitialized && !search && !library && gesture.handle(event)) return true
        return super.dispatchTouchEvent(event)
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
        private val viewFactory: (Tile, Int, Int) -> View
    ) : FrameLayout(context) {
        private val widthPerCell = gridWidth / columns
        private val heightPerCell = gridHeight / rows
        private val tileViews = mutableMapOf<String, Pair<Tile, View>>()

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
                val offset = target?.takeIf { it.page == page && it.folder == folder }
                val dx = when {
                    offset == null -> 0f
                    page == Slot.DOCK -> (projectedDock.getValue(original.id) - originalDock.getValue(original.id)).toFloat()
                    else -> ((offset.cell % columns - original.slot.cell % columns) * widthPerCell).toFloat()
                }
                val dy = if (offset == null) 0f else ((offset.cell / columns - original.slot.cell / columns) * heightPerCell).toFloat()
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
            tiles.filter { it.slot.page == page && it.slot.folder == folder }.forEach {
                val gap = if (it.isWidget) context.dp(4) else 0
                val child = viewFactory(it, widthPerCell, heightPerCell)
                tileViews[it.id] = it to child
                addView(child, LayoutParams(widthPerCell * it.width - 2 * gap, heightPerCell * it.height - 2 * gap).apply {
                        leftMargin = (dock[it.id] ?: (it.slot.cell % columns * widthPerCell)) + gap
                        topMargin = it.slot.cell / columns * heightPerCell + gap
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
            return Slot(page, localY.toInt() / heightPerCell * columns + localX.toInt() / widthPerCell, folder)
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
        private var verticalScroll = false
        private var emptyHold = false
        private var revision = 0L
        private var candidate: Drop? = null
        private var hoverTime = 0L
        private var edge = 0
        private var origin: Tile? = null
        private var dragged: Tile? = null
        private var shadow: View? = null
        val active get() = dragged != null || paging
        private val hold = Runnable {
            if (tracking && !paging && dragged == null && origin == null && host.folder == null && !host.editing) {
                emptyHold = true
                host.editing = true
                host.render()
            }
        }
        private val turnPage = Runnable {
            if (dragged != null && edge != 0 && host.folder == null) {
                host.switchPage(host.page + edge)
                candidate = null
            }
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
                    if (s == null && (host.folder != null || host.editing || host.grid?.viewportContains(x, y) != true)) {
                        return false
                    }
                    tracking = true
                    origin = s?.let(host.layout::at)
                    revision = host.layout.revision
                    if (origin == null) host.handler.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    val wasActive = active
                    cancel()
                    return wasActive
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking || verticalScroll) return false
                    val dx = x - startX
                    val dy = y - startY
                    val distance = abs(dx) + abs(dy)
                    if (distance > holdSlop) host.handler.removeCallbacks(hold)
                    if (distance <= slop && !active) return emptyHold
                    if (!active) {
                        val tile = origin
                        if (host.editing && tile != null) {
                            dragged = tile
                            host.cancelChild(event)
                            draw()
                        } else if (host.folder == null && abs(dx) > abs(dy) * 1.2f) {
                            paging = true
                            host.revealPageIndicator()
                            host.cancelChild(event)
                        } else {
                            verticalScroll = true
                            if (host.folder == null && dy > host.dp(54) && host.store.settingEnabled("search_gesture")) {
                                host.cancelChild(event)
                                host.showBrowser(true)
                                return true
                            }
                            return false
                        }
                    }
                    if (paging) {
                        val travel = Math.copySign((abs(dx) - slop).coerceAtLeast(0f), dx)
                        host.followPage(travel)
                        return true
                    }
                    updateDrag()
                    return dragged != null
                }
                MotionEvent.ACTION_UP -> {
                    host.handler.removeCallbacks(hold)
                    if (paging) {
                        val dx = x - startX
                        paging = false
                        val threshold = host.dp(44)
                        when {
                            dx < -threshold -> host.switchPage(host.page + 1)
                            dx > threshold -> host.switchPage(host.page - 1)
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

        private fun updateDrag() {
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
            val currentEdge = when {
                localX < host.dp(25) -> -1
                localX > host.content.width - host.dp(25) -> 1
                else -> 0
            }
            if (currentEdge != edge) {
                edge = currentEdge
                host.handler.removeCallbacks(turnPage)
                if (edge != 0) host.handler.postDelayed(turnPage, 430)
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
            host.handler.removeCallbacks(hold)
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
            verticalScroll = false
            emptyHold = false
        }
    }
}
