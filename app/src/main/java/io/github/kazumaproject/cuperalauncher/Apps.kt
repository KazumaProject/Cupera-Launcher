package io.github.kazumaproject.cuperalauncher

import android.app.Activity
import android.app.ActivityOptions
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.view.View
import java.text.Collator

class Apps(private val context: Context) {
    val launcher: LauncherApps = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    val roles: RoleManager = context.getSystemService(RoleManager::class.java)
    val personal: UserHandle = Process.myUserHandle()
    val personalSerial = users.getSerialNumberForUser(personal)

    data class Entry(val key: AppKey, val info: LauncherActivityInfo, val work: Boolean, val title: String)

    fun hasHome() = roles.isRoleAvailable(RoleManager.ROLE_HOME) && roles.isRoleHeld(RoleManager.ROLE_HOME)

    fun profileType(user: UserHandle): String? {
        if (user == personal) return "personal"
        if (Build.VERSION.SDK_INT < 35) return "work"
        return runCatching { launcher.getLauncherUserInfo(user)?.userType }.getOrNull()
    }

    private fun profiles(): List<UserHandle> = runCatching { launcher.profiles }.getOrDefault(emptyList())

    fun privateUser(): UserHandle? {
        if (Build.VERSION.SDK_INT < 35 || !hasHome()) return null
        return profiles().firstOrNull { profileType(it) == UserManager.USER_TYPE_PROFILE_PRIVATE }
    }

    fun privateUnlocked(user: UserHandle): Boolean =
        user == privateUser() && runCatching { !users.isQuietModeEnabled(user) }.getOrDefault(false)

    fun requestPrivate(user: UserHandle, locked: Boolean): Boolean {
        if (user != privateUser()) return false
        return runCatching { users.requestQuietModeEnabled(locked, user) }.isSuccess
    }

    fun entries(): List<Entry> = (listOf(personal) + profiles()).distinct().flatMap { user ->
        val type = profileType(user)
        if (type == null || type == UserManager.USER_TYPE_PROFILE_PRIVATE ||
            runCatching { users.isQuietModeEnabled(user) }.getOrDefault(true)
        ) emptyList() else entries(user)
    }.sortedWith { a, b -> Collator.getInstance().compare(a.title, b.title) }

    fun privateEntries(user: UserHandle): List<Entry> {
        if (!privateUnlocked(user)) return emptyList()
        val entries = entries(user).sortedBy { it.title.lowercase() }
        return if (privateUnlocked(user)) entries else emptyList()
    }

    private fun entries(user: UserHandle): List<Entry> = runCatching {
        val serial = users.getSerialNumberForUser(user)
        if (serial < 0) return@runCatching emptyList()
        launcher.getActivityList(null, user).map {
            Entry(AppKey(it.componentName.flattenToString(), serial), it, user != personal, it.label.toString())
        }
    }.getOrDefault(emptyList())

    fun shortcuts(entry: Entry): List<ShortcutInfo> {
        if (!hasHome() || profileType(entry.info.user) == UserManager.USER_TYPE_PROFILE_PRIVATE) return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(entry.info.componentName.packageName)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        return runCatching { launcher.getShortcuts(query, entry.info.user).orEmpty() }.getOrDefault(emptyList())
    }

    fun updatePinned(tiles: List<Tile>, affected: Collection<AppKey>) {
        if (!hasHome()) return
        affected.map { it.profile to ComponentName.unflattenFromString(it.component)?.packageName }
            .distinct().forEach { (serial, pkg) ->
                val user = users.getUserForSerialNumber(serial)
                val type = user?.let(::profileType)
                if (pkg != null && user != null && type != null && type != UserManager.USER_TYPE_PROFILE_PRIVATE) {
                    val ids = tiles.mapNotNull { it.key }
                        .filter { it.profile == serial && ComponentName.unflattenFromString(it.component)?.packageName == pkg }
                        .map { it.shortcut }.filter { it.isNotEmpty() }.distinct()
                    runCatching { launcher.pinShortcuts(pkg, ids, user) }
                }
            }
    }

    fun launch(key: AppKey, anchor: View, animated: Boolean, privateUser: UserHandle? = null): Boolean {
        val component = ComponentName.unflattenFromString(key.component) ?: return false
        val user = users.getUserForSerialNumber(key.profile) ?: return false
        val type = profileType(user) ?: return false
        if (type == UserManager.USER_TYPE_PROFILE_PRIVATE && (user != privateUser || !privateUnlocked(user))) return false
        if (privateUser != null && user != privateUser) return false
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        val bounds = Rect(location[0], location[1], location[0] + anchor.width, location[1] + anchor.height)
        val options: Bundle? = if (animated && anchor.isAttachedToWindow) {
            ActivityOptions.makeClipRevealAnimation(anchor, 0, 0, anchor.width, anchor.height).toBundle()
        } else null
        return runCatching {
            if (key.shortcut.isEmpty()) launcher.startMainActivity(component, user, bounds, options)
            else launcher.startShortcut(component.packageName, key.shortcut, bounds, options, user)
        }.isSuccess
    }

    fun privateSettings(activity: Activity) {
        val launched = Build.VERSION.SDK_INT >= 36 && hasHome() && runCatching {
            launcher.privateSpaceSettingsIntent?.let {
                activity.startIntentSender(it, null, 0, 0, 0)
                true
            } ?: false
        }.getOrDefault(false)
        if (!launched) activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
    }
}
