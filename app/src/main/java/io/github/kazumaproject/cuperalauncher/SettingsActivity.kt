package io.github.kazumaproject.cuperalauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var store: Workspace
    private lateinit var apps: Apps
    private lateinit var content: LinearLayout
    private var section: String? = null
    private var dark = false
    private val profileEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (section == "Private Space") render()
        }
    }
    private val textColor get() = if (dark) Color.WHITE else ink
    private val secondary get() = if (dark) 0xffadb2c4.toInt() else 0xff586276.toInt()
    private val surface get() = if (dark) 0xff252632.toInt() else Color.WHITE

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = Workspace(this)
        apps = Apps(this)
        section = state?.getString("section")
        registerReceiver(profileEvents, IntentFilter().apply {
            addAction(Intent.ACTION_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
        }, RECEIVER_EXPORTED)
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(0, ::back)
    }

    override fun onResume() { super.onResume(); render() }
    override fun onSaveInstanceState(out: Bundle) { out.putString("section", section); super.onSaveInstanceState(out) }
    override fun onDestroy() {
        unregisterReceiver(profileEvents)
        store.close()
        super.onDestroy()
    }

    @Deprecated("Back on Android 12")
    override fun onBackPressed() = back()

    private fun back() { if (section == null) finish() else open(null) }

    private fun open(value: String?) {
        val forward = value != null
        section = value
        render()
        if (store.setting("motion") != "reduced" && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            content.translationX = dp(if (forward) 28 else -18).toFloat()
            content.alpha = .75f
            content.animate().translationX(0f).alpha(1f).setDuration(210).start()
        }
    }

    private fun render() {
        dark = dark(store)
        val root = vertical().apply { setBackgroundColor(if (dark) 0xff171821.toInt() else 0xffeef0f6.toInt()) }
        systemBars(root, dark)
        val current = section
        val toolbar = horizontal().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        toolbar.addView(label(if (current == null) "‹ Close" else "‹ Settings", 16f, accent).apply {
            minHeight = dp(48)
            setOnClickListener { if (current == null) finish() else open(null) }
        })
        root.addView(toolbar)
        content = vertical().apply { setPadding(dp(16), 0, dp(16), dp(24)) }
        content.addView(label(current ?: "Cupera Settings", 34f, textColor, true).apply {
            setPadding(dp(6), dp(8), dp(6), dp(14))
            isAccessibilityHeading = true
        })
        root.addView(ScrollView(this).apply { addView(content) })
        setContentView(root)
        when (current) {
            null -> {
                group {
                    row("Default Home", if (apps.hasHome()) "Cupera is active" else "Choose Cupera Launcher", ::chooseHome)
                }
                sections.chunked(4).forEach { names ->
                    group { names.forEach { title -> row(title, "", { open(title) }) } }
                }
            }
            "Home Screen" -> group {
                toggle("Show page indicators", "page_dots")
                toggle("Add newly installed apps to Home", "add_new", false)
                row("Android default Home", if (apps.hasHome()) "Cupera is active" else "", ::chooseHome)
            }
            "Icons & Dock" -> group {
                choices("Icon size", "icon_size", listOf("small", "regular", "large"), "regular")
                toggle("Icon labels", "labels")
                toggle("Show Dock", "dock")
            }
            "Wallpaper & Appearance" -> group {
                choices("Appearance", "appearance", listOf("system", "light", "dark"), "system")
                choices("Home wallpaper", "wallpaper", listOf("tide", "dusk", "system"), "tide")
                row("Android wallpaper picker", "Android setting") {
                    external(Intent(Intent.ACTION_SET_WALLPAPER))
                }
            }
            "Folders" -> group { toggle("Create folders by hovering over an app", "folder_drop") }
            "Gestures" -> group {
                toggle("Swipe down for search", "search_gesture")
                row("Android navigation", "Android setting") { external(Intent(Settings.ACTION_SETTINGS)) }
            }
            "Widgets" -> group {
                toggle("Show seconds in Cupera Clock", "widget_seconds", false)
                row("Add a widget", "Open Home, touch and hold an empty area, then select Add Widget.")
            }
            "Search & App Library" -> group {
                toggle("Show Search on Home", "search_button")
                toggle("Group library apps", "library_groups")
            }
            "Notification Badges" -> group {
                toggle("Show badges", "badges")
                val notifications = getSystemService(NotificationManager::class.java)
                val granted = notifications.isNotificationListenerAccessGranted(ComponentName(this@SettingsActivity, BadgeListener::class.java))
                row("Notification access", if (granted) "Allowed by Android" else "Off · Android setting") {
                    external(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                row("On-device use", "Cupera counts eligible personal notifications for badges.")
            }
            "Private Space" -> group {
                if (android.os.Build.VERSION.SDK_INT < 35) row("Requires Android 15 or later")
                else {
                    toggle("Show in App Library", "private_container")
                    val detail = when {
                        !apps.hasHome() -> "Choose Cupera as your default Home first."
                        apps.privateUser() == null -> "Set up a space in Android if supported by this device."
                        else -> "Android manages your space and authentication."
                    }
                    row("Android Private Space settings", detail) { runCatching { apps.privateSettings(this@SettingsActivity) }.onFailure { unavailable() } }
                    apps.privateUser()?.let { user ->
                        val unlocked = apps.privateUnlocked(user)
                        row(if (unlocked) "Lock Private Space" else "Unlock Private Space", "Android control") {
                            if (!apps.requestPrivate(user, unlocked)) unavailable()
                            content.post { if (!isFinishing) render() }
                        }
                    }
                }
            }
            "Accessibility" -> group {
                choices("Motion", "motion", listOf("system", "reduced"), "system")
                row("Android accessibility settings", "Android setting") { external(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            "Backup & Restore" -> group {
                row("Export layout", "Includes personal app identifiers and Cupera preferences.") {
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "Cupera-layout.json")
                    }, EXPORT)
                }
                row("Restore layout", "Replaces the current layout and Cupera preferences.") {
                    AlertDialog.Builder(this@SettingsActivity).setTitle("Restore a layout?")
                        .setMessage("Restore replaces your current Home layout. Widgets may need to be connected again.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Choose file") { _, _ ->
                            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "application/json"
                            }, IMPORT)
                        }.show()
                }
            }
            "About Cupera" -> group {
                row("Cupera Launcher", "Version ${packageManager.getPackageInfo(packageName, 0).versionName}")
                row("Third-party licenses", "Kotlin standard library") {
                    val notice = assets.open("licenses/kotlin-NOTICE.txt").bufferedReader().use { it.readText() }
                    val license = assets.open("licenses/kotlin-LICENSE.txt").bufferedReader().use { it.readText() }
                    AlertDialog.Builder(this@SettingsActivity).setTitle("Third-party licenses")
                        .setMessage(notice + "\n\n" + license).setPositiveButton("Close", null).show()
                }
                row("App information", "Android setting") {
                    external(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                }
            }
        }
    }

    private fun group(build: LinearLayout.() -> Unit) {
        val group = vertical().apply {
            background = rounded(surface, dp(22).toFloat())
            build()
        }
        content.addView(group, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }

    private fun LinearLayout.row(title: String, subtitle: String = "", action: (() -> Unit)? = null) {
        val row = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(16), dp(9), dp(16), dp(9))
        }
        val labels = vertical().apply {
            addView(label(title, 17f, textColor))
            if (subtitle.isNotEmpty()) addView(label(subtitle, 13f, secondary))
        }
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) {
            row.addView(label("›", 23f, secondary))
            row.setOnClickListener { action() }
        }
        addView(row)
        divider()
    }

    private fun LinearLayout.toggle(title: String, key: String, default: Boolean = true) {
        addView(Switch(this@SettingsActivity).apply {
            text = title
            textSize = 17f
            setTextColor(textColor)
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            trackTintList = android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, secondary)
            )
            minHeight = dp(52)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            isChecked = store.settingEnabled(key, default)
            setOnCheckedChangeListener { _, value -> store.put(key, value.toString()) }
        })
        divider()
    }

    private fun LinearLayout.divider() {
        addView(View(this@SettingsActivity).apply {
            setBackgroundColor(if (dark) 0xff373946.toInt() else 0xffe5e7ed.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(16); marginEnd = dp(16) })
    }

    private fun LinearLayout.choices(title: String, key: String, values: List<String>, default: String) {
        val current = store.setting(key, default)
        fun display(value: String) = value.replaceFirstChar { it.titlecase() }
        row(title, display(current)) {
            AlertDialog.Builder(this@SettingsActivity).setTitle(title).setSingleChoiceItems(
                values.map(::display).toTypedArray(), values.indexOf(current)
            ) { dialog, index ->
                store.put(key, values[index])
                dialog.dismiss()
                render()
            }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun chooseHome() {
        if (apps.hasHome()) external(Intent(Settings.ACTION_HOME_SETTINGS))
        else external(apps.roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
    }

    private fun external(intent: Intent) { runCatching { startActivity(intent) }.onFailure { unavailable() } }
    private fun unavailable() { Toast.makeText(this, "This Android setting is unavailable.", Toast.LENGTH_SHORT).show() }

    @Deprecated("Platform document activity results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val result = runCatching {
            when (requestCode) {
                EXPORT -> requireNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use {
                    it.write(store.export(apps.personalSerial))
                }
                IMPORT -> {
                    val bytes = requireNotNull(contentResolver.openInputStream(uri)).use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val length = input.read(chunk)
                            if (length == -1) break
                            require(output.size() + length <= 2_000_000)
                            output.write(chunk, 0, length)
                        }
                        output.toByteArray()
                    }
                    require(bytes.size <= 2_000_000)
                    store.restore(bytes.toString(Charsets.UTF_8), apps.personalSerial)
                }
                else -> return
            }
        }
        Toast.makeText(this, if (result.isSuccess) "Backup operation completed." else "Could not complete the backup operation.", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXPORT = 201
        private const val IMPORT = 202
        private val sections = listOf(
            "Home Screen", "Icons & Dock", "Wallpaper & Appearance", "Folders", "Gestures", "Widgets",
            "Search & App Library", "Notification Badges", "Private Space", "Accessibility", "Backup & Restore", "About Cupera"
        )
    }
}
