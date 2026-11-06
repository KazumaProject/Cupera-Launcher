package io.github.kazumaproject.cuperalauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.UserHandle
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Toast
import java.text.Normalizer
import java.util.Locale

class AppBrowser(
    private val host: HomeActivity,
    private val apps: Apps,
    private var catalog: Catalog,
    private val dark: Boolean,
    private val store: Workspace,
    private var showingPrivate: Boolean,
    private val onBack: () -> Unit,
    private val onLibrary: () -> Unit,
    private val onAdd: (AppKey, String) -> Unit,
    private val onPrivate: (Boolean) -> Unit,
    private val onLaunch: (AppKey, View, UserHandle?) -> Unit
) {
    private companion object {
        val COMBINING_MARKS = Regex("\\p{M}+")

        fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "").lowercase(Locale.ROOT).trim()
    }

    class Catalog(val entries: List<Apps.Entry>) {
        private val keys = entries.map { normalized(it.title + " " + it.info.componentName.packageName) }

        fun matching(query: String): List<Apps.Entry> {
            if (query.isEmpty()) return entries
            val words = query.split(' ')
            return entries.filterIndexed { index, _ -> words.all(keys[index]::contains) }
        }
    }

    private val foreground = Color.WHITE
    private val secondary = 0xffd4e0ef.toInt()
    private val surface get() = if (isSearch) 0x58323a4b else if (dark) 0xe5253247.toInt() else 0x66e9f5ff
    private lateinit var results: LinearLayout
    private var privateContent: LinearLayout? = null
    private var privatePanel: View? = null
    private var generation = 0
    private var query = ""
    private class Category(
        val overlay: FrameLayout,
        val card: View,
        val scrim: View,
        val title: View,
        val trigger: View,
        val sourceX: Float,
        val sourceY: Float,
        val scrollY: Int
    ) {
        var progress = 0f
        var closing = false
        var animator: ValueAnimator? = null
    }

    private lateinit var browserFrame: FrameLayout
    private var category: Category? = null
    private var isSearch = false
    private var searchInput: EditText? = null
    private var searchControls: View? = null
    private var searchTint: View? = null
    private var searchResultsToolbar: View? = null
    private var libraryControls: View? = null
    private val libraryBackdrop = mutableListOf<View>()
    private val showSearchKeyboard = Runnable {
        val input = searchInput ?: return@Runnable
        if (input.isAttachedToWindow && input.hasFocus() && input.hasWindowFocus()) {
            host.getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }
    fun updateEntries(catalog: Catalog, changed: Boolean = true) {
        this.catalog = catalog
        if (category != null && !changed) drawPrivatePanel() else drawResults()
    }

    fun view(
        search: Boolean,
        useHomeBackdrop: Boolean = false,
        focusOnOpen: Boolean = true,
        libraryBackground: FrameLayout? = null
    ): View {
        isSearch = search
        val root = host.vertical().apply {
            setPadding(host.dp(16), 0, host.dp(16), 0)
            isFocusableInTouchMode = true
        }
        if (search) searchControls = root
        val toolbar = host.horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        val back = host.label("‹ Home", 15f, foreground, true).apply {
            minHeight = host.dp(52)
            setOnClickListener { onBack() }
        }
        toolbar.addView(back, LinearLayout.LayoutParams(0, -2, 1f))
        val settings = host.label(if (search) "Library" else "Settings", 15f, foreground, true).apply {
            minHeight = host.dp(52)
            setOnClickListener {
                if (search) onLibrary() else host.startActivity(Intent(host, SettingsActivity::class.java))
            }
        }
        toolbar.addView(settings)
        if (!search) root.addView(toolbar)
        searchResultsToolbar = toolbar.takeIf { search }
        root.accessibilityPaneTitle = if (search) "Search" else "App Library"
        val input = EditText(host).apply {
            hint = if (search) "Search apps" else "App Library"
            contentDescription = "Search apps"
            gravity = if (search) Gravity.CENTER_VERTICAL else Gravity.CENTER
            setTextColor(this@AppBrowser.foreground)
            setHintTextColor(secondary)
            setSingleLine()
            textSize = 16f
            minHeight = host.dp(if (search) 44 else 48)
            setPadding(host.dp(15), 0, host.dp(15), 0)
            background = rounded(surface, host.dp(if (search) 22 else 15).toFloat())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = normalized(s.toString())
                    if (query.isNotEmpty()) {
                        clearPrivate()
                    }
                    drawResults()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        searchInput = input.takeIf { search }
        if (!search) root.addView(input)
        results = host.vertical().apply { setPadding(0, host.dp(10), 0, host.dp(20)) }
        root.addView(ScrollView(host).apply { addView(results) }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (search) root.addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = host.dp(6) })
        drawResults()
        if (search && focusOnOpen) focusSearch() else if (search || focusOnOpen) root.requestFocus()
        if (!search) libraryControls = root
        return FrameLayout(host).apply {
            browserFrame = this
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    category?.let { removeCategory(it, restoreFocus = false) }
                }
            })
            isClickable = useHomeBackdrop
            val wallpaperHost = libraryBackground ?: this
            val systemWallpaper = store.setting("wallpaper", "tide") == "system"
            if (!useHomeBackdrop && !systemWallpaper) {
                val wallpaper = View(host).apply {
                    background = CuperaWallpaper(dark || store.setting("wallpaper") == "dusk")
                    val radius = host.dp(19).toFloat()
                    setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP))
                }
                if (!search) libraryBackdrop.add(wallpaper)
                wallpaperHost.addView(wallpaper, FrameLayout.LayoutParams(-1, -1))
            }
            if (search || systemWallpaper) {
                val tint = View(host).apply { setBackgroundColor(0x6a101725) }
                if (search) searchTint = tint else libraryBackdrop.add(tint)
                wallpaperHost.addView(tint, FrameLayout.LayoutParams(-1, -1))
            }
            addView(root, FrameLayout.LayoutParams(-1, -1))
            // Resize the browser controls without moving the blurred home underneath.
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val bottom = (insets.getInsets(WindowInsets.Type.ime()).bottom - bars.bottom).coerceAtLeast(0)
                if (root.paddingBottom != bottom) root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, bottom)
                insets
            }
        }
    }

    fun canReturnToHomeBySwipe(): Boolean = query.isEmpty() && category == null && !showingPrivate

    fun drawSearchProgress(progress: Float) {
        searchTint?.alpha = progress
        // A short pull softens Home before revealing the search controls.
        searchControls?.alpha = ((progress - .25f) / .75f).coerceIn(0f, 1f)
    }

    fun drawLibraryProgress(progress: Float, width: Int) {
        libraryControls?.translationX = width * (1f - progress)
        libraryBackdrop.forEach { it.alpha = progress }
    }

    fun focusLibrary() {
        libraryControls?.requestFocus()
    }

    fun focusSearch() {
        val input = searchInput ?: return
        input.requestFocus()
        input.removeCallbacks(showSearchKeyboard)
        input.postDelayed(showSearchKeyboard, 120)
    }

    fun cancelPendingKeyboard() {
        searchInput?.removeCallbacks(showSearchKeyboard)
    }

    private fun drawResults() {
        category?.let { removeCategory(it, restoreFocus = false) }
        results.removeAllViews()
        privatePanel = null
        privateContent = null
        val filtered = catalog.matching(query)
        if (isSearch && query.isEmpty()) {
            drawSuggestions()
            return
        }
        if (isSearch) searchResultsToolbar?.let(results::addView)
        val grouped = !isSearch && query.isEmpty() && store.settingEnabled("library_groups")
        if (grouped) {
            val groups = filtered.groupBy(::category).toSortedMap()
            groups.entries.chunked(2).forEach { pair ->
                val row = host.horizontal()
                pair.forEach { (name, entries) ->
                    row.addView(categoryCard(name, entries), LinearLayout.LayoutParams(0, -2, 1f).apply {
                        setMargins(host.dp(4), 0, host.dp(4), host.dp(12))
                    })
                }
                if (pair.size == 1) row.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
                results.addView(row)
            }
        } else {
            val card = card()
            filtered.forEach { card.addView(appRow(it)) }
            if (filtered.isEmpty()) card.addView(host.label("No apps found", 16f, secondary).apply { minHeight = host.dp(48) })
            results.addView(card)
        }
        drawPrivatePanel()
    }

    private fun drawPrivatePanel() {
        privatePanel?.let(results::removeView)
        privatePanel = null
        privateContent = null
        if (!isSearch && query.isEmpty() && store.settingEnabled("private_container")) {
            apps.privateUser()?.let(::drawPrivate)
        }
    }

    private fun drawSuggestions() {
        val heading = host.horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(host.label("Suggested apps", 13f, secondary, true).apply {
            isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(0, host.dp(32), 1f))
        heading.addView(host.label("Library", 13f, secondary).apply {
            minHeight = host.dp(48)
            minWidth = host.dp(48)
            gravity = Gravity.CENTER
            setOnClickListener { onLibrary() }
        })
        results.addView(heading)
        // Home placement supplies real, local candidates without requiring usage access.
        // Catalog contains only currently available public-profile apps.
        val byKey = catalog.entries.associateBy { it.key }
        val placed = store.read().tiles.mapNotNull { it.key?.let(byKey::get) }
        val suggestions = (placed + catalog.entries).distinctBy { it.key }.take(4)
        if (suggestions.isEmpty()) {
            results.addView(host.label("No apps available", 16f, secondary))
            return
        }
        val row = host.horizontal()
        suggestions.forEach { entry ->
            val cell = host.vertical().apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
            val icon = ImageView(host).apply {
                background = rounded(0x42dcefff, host.dp(12).toFloat())
                clipToOutline = true
                host.icons.show(this, entry)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            cell.addView(icon, LinearLayout.LayoutParams(host.dp(48), host.dp(48)))
            cell.addView(host.label(entry.title, 11f, this@AppBrowser.foreground).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, host.dp(34)).apply { topMargin = host.dp(5) })
            cell.contentDescription = entry.title
            cell.setOnClickListener { onLaunch(entry.key, icon, null) }
            row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        repeat(4 - suggestions.size) { row.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f)) }
        results.addView(row)
    }

    private fun categoryCard(name: String, entries: List<Apps.Entry>): View = host.vertical().apply {
        val windowWidth = host.resources.displayMetrics.widthPixels
        val cellSize = (windowWidth * .183f).toInt().coerceIn(host.dp(52), host.dp(80))
        val iconSize = (cellSize * .89f).toInt()
        val preview = card().apply {
            val padding = host.dp(9)
            setPadding(padding, padding, padding, padding)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val expandable = entries.size > 4
        addView(preview)
        for (rowNumber in 0..1) {
            val row = host.horizontal()
            for (column in 0..1) {
                val index = rowNumber * 2 + column
                val cell = FrameLayout(host)
                if (index == 3 && expandable) {
                    val trigger = host.vertical().apply {
                        contentDescription = "Open $name category"
                        isFocusableInTouchMode = true
                        background = rounded(0x32ffffff, iconSize * .24f)
                        setPadding(host.dp(3), host.dp(3), host.dp(3), host.dp(3))
                        setOnClickListener { openCategory(name, entries, this) }
                    }
                    repeat(2) { rowIndex ->
                        val mini = host.horizontal()
                        repeat(2) { columnIndex ->
                            val entry = entries.getOrNull(3 + rowIndex * 2 + columnIndex)
                            val thumb = if (entry == null) View(host) else libraryIcon(entry, iconSize / 2)
                            thumb.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            mini.addView(thumb, LinearLayout.LayoutParams(0, -1, 1f).apply {
                                setMargins(host.dp(1), host.dp(1), host.dp(1), host.dp(1))
                            })
                        }
                        trigger.addView(mini, LinearLayout.LayoutParams(-1, 0, 1f))
                    }
                    cell.addView(trigger, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
                } else entries.getOrNull(index)?.let { entry ->
                    val image = libraryIcon(entry, iconSize)
                    bindApp(image, entry, image)
                    cell.addView(image, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
                }
                row.addView(cell, LinearLayout.LayoutParams(0, cellSize, 1f))
            }
            preview.addView(row)
        }
        addView(host.label(name, 13f, this@AppBrowser.foreground, true).apply {
            gravity = Gravity.CENTER
            minHeight = host.dp(26)
            isAccessibilityHeading = true
        })
    }

    private fun libraryIcon(entry: Apps.Entry, size: Int) = ImageView(host).apply {
        background = rounded(if (dark) 0xff393e50.toInt() else 0xffe4e9f4.toInt(), size * .24f)
        clipToOutline = true
        host.icons.show(this, entry, !entry.work)
    }

    private fun bindApp(view: View, entry: Apps.Entry, icon: View) {
        view.contentDescription = if (entry.work) "${entry.title}, Work" else entry.title
        view.setOnClickListener { onLaunch(entry.key, icon, null) }
        view.setOnLongClickListener {
            PopupMenu(host, view).apply {
                menu.add("Add to Home").setOnMenuItemClickListener { onAdd(entry.key, ""); true }
                show()
            }
            true
        }
    }

    private fun openCategory(name: String, entries: List<Apps.Entry>, trigger: View) {
        if (category != null || !trigger.isAttachedToWindow) return
        val root = checkNotNull(libraryControls)
        val location = IntArray(2).also(trigger::getLocationOnScreen)
        val frameLocation = IntArray(2).also(browserFrame::getLocationOnScreen)
        // The expanded view uses the same four-column grid proportions as the reference.
        val width = (browserFrame.width * .915f).toInt().coerceIn(1, host.dp(480))
        val cellWidth = width / 4
        val iconSize = (cellWidth * .715f).toInt()
        val rowHeight = maxOf((cellWidth * 1.08f).toInt(), iconSize + host.dp(32))
        val top = (browserFrame.height * .202f).toInt()
        val titleHeight = maxOf(host.dp(56), (width * .205f).toInt())
        val title = host.label(name, 32f, foreground, true).apply {
            gravity = Gravity.TOP or Gravity.START
            setPadding((cellWidth - iconSize) / 2, 0, 0, 0)
            isAccessibilityHeading = true
            isFocusableInTouchMode = true
        }
        val scrim = View(host).apply {
            setBackgroundColor(if (dark) 0xcc080a10.toInt() else 0xb51c2736.toInt())
            contentDescription = "Close $name category"
            setOnClickListener { closeCategory() }
        }
        val card = host.vertical().apply {
            addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = host.dp(14) })
            title.minimumHeight = titleHeight - host.dp(14)
        }
        val rows = host.vertical()
        for (batch in entries.chunked(4)) {
            val row = host.horizontal()
            for (entry in batch) {
                val icon = libraryIcon(entry, iconSize).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                val item = host.vertical().apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
                    addView(host.label(entry.title, 11f, this@AppBrowser.foreground).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = host.dp(4) })
                }
                bindApp(item, entry, icon)
                row.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
            }
            repeat(4 - batch.size) { row.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f)) }
            rows.addView(row, LinearLayout.LayoutParams(-1, rowHeight))
        }
        val maxGridHeight = (browserFrame.height - top - titleHeight - host.dp(26)).coerceAtLeast(1)
        val height = minOf(rows.childCount * rowHeight, maxGridHeight)
        card.addView(ScrollView(host).apply { addView(rows) }, LinearLayout.LayoutParams(-1, height))
        val overlay = FrameLayout(host).apply {
            accessibilityPaneTitle = name
            addView(scrim, FrameLayout.LayoutParams(-1, -1))
            addView(card, FrameLayout.LayoutParams(width, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = top
            })
        }
        val motion = Category(overlay, card, scrim, title, trigger,
            location[0] - frameLocation[0] + trigger.width / 2f,
            location[1] - frameLocation[1] + trigger.height / 2f,
            (results.parent as ScrollView).scrollY)
        category = motion
        root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        drawCategory(motion, 0f)
        browserFrame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        card.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int,
                                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                card.removeOnLayoutChangeListener(this)
                if (category === motion && !motion.closing) {
                    drawCategory(motion, 0f)
                    settleCategory(motion, 1f)
                }
            }
        })
    }

    fun closeCategory(): Boolean {
        val motion = category ?: return false
        if (!motion.closing) {
            motion.closing = true
            settleCategory(motion, 0f)
        }
        return true
    }

    private fun drawCategory(motion: Category, progress: Float) {
        motion.progress = progress
        motion.scrim.alpha = progress
        val card = motion.card
        card.alpha = progress
        if (!host.reduced && card.width > 0 && card.height > 0) {
            val remaining = 1f - progress
            card.translationX = (motion.sourceX - (card.left + card.width / 2f)) * remaining
            card.translationY = (motion.sourceY - (card.top + card.height / 2f)) * remaining
            card.scaleX = 1f + (motion.trigger.width.toFloat() / card.width - 1f) * remaining
            card.scaleY = 1f + (motion.trigger.height.toFloat() / card.height - 1f) * remaining
        }
        motion.title.alpha = if (host.reduced) 1f else ((progress - .25f) / .75f).coerceIn(0f, 1f)
        libraryControls?.alpha = if (host.reduced) 1f - progress else (1f - progress / .68f).coerceAtLeast(0f)
    }

    private fun settleCategory(motion: Category, target: Float) {
        motion.animator?.removeAllListeners()
        motion.animator?.cancel()
        motion.animator = null
        fun finish() {
            if (target == 0f) removeCategory(motion, restoreFocus = true)
            else motion.title.requestFocus()
        }
        if (!ValueAnimator.areAnimatorsEnabled() || motion.progress == target) {
            drawCategory(motion, target)
            finish()
            return
        }
        motion.animator = ValueAnimator.ofFloat(motion.progress, target).apply {
            val fullDuration = if (host.reduced) 160 else if (target == 1f) 755 else 600
            duration = (fullDuration * kotlin.math.abs(target - motion.progress)).toLong()
            if (!host.reduced) interpolator = PathInterpolator(.28f, .48f, .36f, 1f)
            addUpdateListener { drawCategory(motion, it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    motion.animator = null
                    finish()
                }
            })
            start()
        }
    }

    private fun removeCategory(motion: Category, restoreFocus: Boolean) {
        category = null
        motion.animator?.removeAllListeners()
        motion.animator?.cancel()
        (motion.overlay.parent as? ViewGroup)?.removeView(motion.overlay)
        libraryControls?.apply {
            alpha = 1f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        if (restoreFocus) {
            motion.trigger.requestFocus()
            (results.parent as ScrollView).scrollTo(0, motion.scrollY)
        }
    }

    private fun drawPrivate(user: UserHandle) {
        val panel = card()
        val unlocked = apps.privateUnlocked(user)
        panel.addView(heading("Private Space"))
        if (unlocked) {
            panel.addView(command(if (showingPrivate) "Hide apps" else "Show apps") {
                showingPrivate = !showingPrivate
                onPrivate(showingPrivate)
                if (!showingPrivate) clearPrivate()
                drawResults()
            })
            panel.addView(command("Lock Private Space") {
                clearPrivate()
                requestState(user, true)
            })
            if (showingPrivate) {
                val items = host.vertical()
                privateContent = items
                panel.addView(items)
                val token = ++generation
                host.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                panel.post {
                    if (token != generation || !panel.isAttachedToWindow || !apps.privateUnlocked(user)) return@post
                    val privateApps = apps.privateEntries(user)
                    if (token != generation || !apps.privateUnlocked(user)) return@post
                    privateApps.forEach { items.addView(appRow(it, user)) }
                    if (!apps.privateUnlocked(user)) clearPrivate()
                }
            }
        } else {
            panel.addView(command("Unlock Private Space") { requestState(user, false) })
        }
        privatePanel = panel
        results.addView(panel)
    }

    fun clearPrivate() {
        generation++
        showingPrivate = false
        privateContent?.removeAllViews()
        privateContent = null
        host.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onPrivate(false)
    }

    private fun requestState(user: UserHandle, locked: Boolean) {
        if (!apps.requestPrivate(user, locked)) {
            Toast.makeText(host, "Android could not start this operation.", Toast.LENGTH_SHORT).show()
        }
        if (::results.isInitialized) results.post { if (results.isAttachedToWindow) drawResults() }
    }

    private fun card() = host.vertical().apply {
        background = rounded(surface, host.dp(20).toFloat())
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = host.dp(10) }
    }

    private fun heading(text: String) = host.label(text, 19f, foreground, true).apply {
        setPadding(host.dp(12), host.dp(9), host.dp(8), host.dp(9))
        isAccessibilityHeading = true
    }

    private fun command(text: String, action: () -> Unit) = host.label(text, 16f, foreground, true).apply {
        minHeight = host.dp(50)
        setPadding(host.dp(14), 0, host.dp(10), 0)
        setOnClickListener { action() }
    }

    private fun appRow(entry: Apps.Entry, user: UserHandle? = null): View {
        val row = host.horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = host.dp(57)
            setPadding(host.dp(12), host.dp(6), host.dp(12), host.dp(6))
        }
        val icon = ImageView(host).apply {
            if (user == null) host.icons.show(this, entry)
            else setImageDrawable(runCatching { entry.info.getBadgedIcon(resources.displayMetrics.densityDpi) }.getOrNull())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(icon, LinearLayout.LayoutParams(host.dp(38), host.dp(38)).apply { marginEnd = host.dp(12) })
        row.addView(host.label(entry.title, 16f, foreground).apply { maxLines = 2 })
        if (user == null) bindApp(row, entry, icon)
        else {
            row.contentDescription = entry.title
            row.setOnClickListener { onLaunch(entry.key, icon, user) }
        }
        return row
    }

    private fun category(entry: Apps.Entry): String {
        if (entry.work) return "Work"
        return when (entry.info.applicationInfo.category) {
            ApplicationInfo.CATEGORY_GAME -> "Games"
            ApplicationInfo.CATEGORY_AUDIO -> "Music & Audio"
            ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> "Media"
            ApplicationInfo.CATEGORY_SOCIAL -> "Social"
            ApplicationInfo.CATEGORY_NEWS -> "News"
            ApplicationInfo.CATEGORY_MAPS -> "Travel"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
            else -> "Utilities & Other"
        }
    }

}
