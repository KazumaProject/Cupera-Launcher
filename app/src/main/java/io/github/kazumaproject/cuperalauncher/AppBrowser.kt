package io.github.kazumaproject.cuperalauncher

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.UserHandle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
    private val entries: List<Apps.Entry>,
    private val dark: Boolean,
    private val store: Workspace,
    private var showingPrivate: Boolean,
    private val onBack: () -> Unit,
    private val onLibrary: () -> Unit,
    private val onAdd: (AppKey, String) -> Unit,
    private val onPrivate: (Boolean) -> Unit,
    private val onLaunch: (AppKey, View, UserHandle?) -> Unit
) {
    private val foreground = Color.WHITE
    private val secondary = 0xffd4e0ef.toInt()
    private val surface get() = if (dark || isSearch) 0xe5253247.toInt() else 0x66e9f5ff
    private lateinit var results: LinearLayout
    private var privateContent: LinearLayout? = null
    private var generation = 0
    private var query = ""
    private var selectedGroup: String? = null
    private var isSearch = false
    private var indexed = index(entries)

    private fun index(apps: List<Apps.Entry>) = apps.map {
        it to normalized(it.title + " " + it.info.componentName.packageName)
    }

    fun updateEntries(entries: List<Apps.Entry>) {
        indexed = index(entries)
        drawResults()
    }

    fun view(search: Boolean): View {
        isSearch = search
        val root = host.vertical().apply {
            setPadding(host.dp(16), 0, host.dp(16), 0)
            isFocusableInTouchMode = true
        }
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
        root.addView(toolbar)
        root.accessibilityPaneTitle = if (search) "Search" else "App Library"
        val input = EditText(host).apply {
            hint = if (search) "Search apps" else "App Library"
            contentDescription = "Search apps"
            gravity = if (search) Gravity.CENTER_VERTICAL else Gravity.CENTER
            setTextColor(this@AppBrowser.foreground)
            setHintTextColor(secondary)
            setSingleLine()
            textSize = 16f
            minHeight = host.dp(48)
            setPadding(host.dp(15), 0, host.dp(15), 0)
            background = rounded(surface, host.dp(15).toFloat())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = normalized(s.toString())
                    if (query.isNotEmpty()) {
                        selectedGroup = null
                        clearPrivate()
                    }
                    drawResults()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        if (!search) root.addView(input)
        results = host.vertical().apply { setPadding(0, host.dp(10), 0, host.dp(20)) }
        root.addView(ScrollView(host).apply { addView(results) }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (search) root.addView(input, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = host.dp(6) })
        drawResults()
        if (search) {
            input.requestFocus()
            input.postDelayed({
                if (input.isAttachedToWindow && input.hasFocus()) {
                    host.getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            }, 120)
        } else root.requestFocus()
        return android.widget.FrameLayout(host).apply {
            addView(View(host).apply {
                background = CuperaWallpaper(dark || store.setting("wallpaper") == "dusk")
                val radius = host.dp(19).toFloat()
                setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP))
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
            if (search) addView(View(host).apply { setBackgroundColor(0x6a101725) }, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(root, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun drawResults() {
        results.removeAllViews()
        privateContent = null
        val filtered = indexed.filter { (_, key) -> query.isEmpty() || query.split(' ').all(key::contains) }.map { it.first }
        val grouped = !isSearch && query.isEmpty() && store.settingEnabled("library_groups")
        if (grouped) {
            val groups = filtered.groupBy(::category).toSortedMap()
            val selected = selectedGroup
            if (selected != null && selected in groups) {
                results.addView(command("‹  All categories") { selectedGroup = null; drawResults() })
                results.addView(heading(selected))
                val card = card()
                groups.getValue(selected).forEach { card.addView(appRow(it)) }
                results.addView(card)
            } else {
                groups.entries.chunked(2).forEach { pair ->
                    val row = host.horizontal()
                    pair.forEach { (name, apps) ->
                        row.addView(categoryCard(name, apps), LinearLayout.LayoutParams(0, -2, 1f).apply {
                            setMargins(host.dp(4), 0, host.dp(4), host.dp(12))
                        })
                    }
                    if (pair.size == 1) row.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
                    results.addView(row)
                }
            }
        } else {
            val card = card()
            filtered.forEach { card.addView(appRow(it)) }
            if (filtered.isEmpty()) card.addView(host.label("No apps found", 16f, secondary).apply { minHeight = host.dp(48) })
            results.addView(card)
        }
        if (!isSearch && query.isEmpty() && selectedGroup == null && store.settingEnabled("private_container")) {
            apps.privateUser()?.let(::drawPrivate)
        }
    }

    private fun categoryCard(name: String, apps: List<Apps.Entry>): View = card().apply {
        setPadding(host.dp(8), host.dp(8), host.dp(8), host.dp(8))
        for (rowNumber in 0..1) {
            val row = host.horizontal()
            for (column in 0..1) {
                val entry = apps.getOrNull(rowNumber * 2 + column)
                val cell = android.widget.FrameLayout(host)
                if (entry != null) {
                    val size = host.dp(46)
                    val image = ImageView(host).apply {
                        background = rounded(if (dark) 0xff393e50.toInt() else 0xffe4e9f4.toInt(), size * .24f)
                        clipToOutline = true
                        host.icons.show(this, entry, !entry.work)
                        contentDescription = entry.title
                        setOnClickListener { onLaunch(entry.key, this, null) }
                    }
                    cell.addView(image, android.widget.FrameLayout.LayoutParams(size, size, Gravity.CENTER))
                }
                row.addView(cell, LinearLayout.LayoutParams(0, host.dp(54), 1f))
            }
            addView(row)
        }
        addView(command(name) { selectedGroup = name; drawResults() }.apply {
            gravity = Gravity.CENTER
            minHeight = host.dp(42)
            setPadding(0, 0, 0, 0)
            textSize = 13f
        })
    }

    private fun drawPrivate(user: UserHandle) {
        val panel = card()
        privateContent = panel
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
                val token = ++generation
                host.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                panel.post {
                    if (token != generation || !panel.isAttachedToWindow || !apps.privateUnlocked(user)) return@post
                    val privateApps = apps.privateEntries(user)
                    if (token != generation || !apps.privateUnlocked(user)) return@post
                    privateApps.forEach { panel.addView(appRow(it, user)) }
                    if (!apps.privateUnlocked(user)) clearPrivate()
                }
            }
        } else {
            panel.addView(command("Unlock Private Space") { requestState(user, false) })
        }
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
        row.contentDescription = if (entry.work && user == null) "${entry.title}, Work" else entry.title
        row.setOnClickListener { onLaunch(entry.key, icon, user) }
        if (user == null) row.setOnLongClickListener {
            PopupMenu(host, row).apply {
                menu.add("Add to Home").setOnMenuItemClickListener { onAdd(entry.key, ""); true }
                show()
            }
            true
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

    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim()
}
