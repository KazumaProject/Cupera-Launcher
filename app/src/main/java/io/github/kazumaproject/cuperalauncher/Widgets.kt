package io.github.kazumaproject.cuperalauncher

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.SizeF
import android.view.Gravity
import android.view.View
import android.widget.TextClock

class Widgets(
    private val activity: Activity,
    private val store: Workspace,
    private val onChanged: () -> Unit
) {
    private val manager = AppWidgetManager.getInstance(activity)
    private val host = AppWidgetHost(activity, 3601)
    private val owner = android.os.Process.myUserHandle()
    private var pendingId = 0
    private var pendingSlot: Slot? = null
    private var replacing: String? = null
    private var requestedSize: Pair<Int, Int> = 2 to 2

    fun start() {
        runCatching { host.startListening() }
        reconcile()
    }

    fun stop() { runCatching { host.stopListening() } }

    fun reconcile() {
        val referenced = store.read().tiles.mapTo(hashSetOf()) { it.widgetId }
        runCatching { host.appWidgetIds }.getOrDefault(intArrayOf()).filter {
            it != pendingId && it !in referenced
        }.forEach(::remove)
    }

    fun save(out: Bundle) {
        out.putInt("widget.pending", pendingId)
        pendingSlot?.let { out.putInt("widget.page", it.page); out.putInt("widget.cell", it.cell) }
        out.putString("widget.replace", replacing)
        out.putInt("widget.width", requestedSize.first)
        out.putInt("widget.height", requestedSize.second)
    }

    fun restore(state: Bundle?) {
        pendingId = state?.getInt("widget.pending") ?: 0
        if (pendingId == 0 || state == null) return
        pendingSlot = Slot(state.getInt("widget.page"), state.getInt("widget.cell"))
        replacing = state.getString("widget.replace")
        requestedSize = state.getInt("widget.width", 2) to state.getInt("widget.height", 2)
    }

    fun choose(page: Int, replace: Tile? = null) {
        if (pendingId != 0) return
        val providers = manager.getInstalledProvidersForProfile(owner)
            .sortedBy { it.loadLabel(activity.packageManager).lowercase() }
        val options = providers.map { it.loadLabel(activity.packageManager) }.toMutableList()
        if (replace == null) options.addAll(0, listOf("Cupera Clock", "Cupera Date"))
        if (options.isEmpty()) { message("No Android widgets are available."); return }
        AlertDialog.Builder(activity).setTitle(if (replace == null) "Add Widget" else "Connect Widget")
            .setItems(options.toTypedArray()) { _, position ->
                if (replace == null && position < 2) {
                    addBuiltIn(page, if (position == 0) TileKind.CLOCK else TileKind.DATE)
                } else {
                    bind(page, providers[position - if (replace == null) 2 else 0], replace)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun addBuiltIn(page: Int, kind: TileKind) {
        val current = store.read()
        val slot = current.firstFree(page, 2, 2) ?: return message("No room for this widget.")
        val tile = Tile(java.util.UUID.randomUUID().toString(), kind, slot, width = 2, height = 2)
        if (store.save(current.revision, current.tiles + tile)) onChanged()
    }

    private fun bind(page: Int, provider: AppWidgetProviderInfo, replace: Tile?) {
        val width = replace?.width ?: provider.targetCellWidth.takeIf { it > 0 }?.coerceIn(1, 4) ?: 2
        val height = replace?.height ?: provider.targetCellHeight.takeIf { it > 0 }?.coerceIn(1, 6) ?: 2
        val slot = replace?.slot ?: store.read().firstFree(page, width, height) ?: return message("No room for this widget.")
        pendingSlot = slot
        replacing = replace?.id
        requestedSize = width to height
        runCatching {
            pendingId = host.allocateAppWidgetId()
            if (manager.bindAppWidgetIdIfAllowed(pendingId, owner, provider.provider, null)) configure()
            else activity.startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, owner)
            }, BIND)
        }.onFailure { abort(); message("Could not start the widget setup.") }
    }

    fun result(request: Int, result: Int) {
        if (request !in listOf(BIND, CONFIGURE) || pendingId == 0) return
        if (result != Activity.RESULT_OK) { abort(); return }
        if (request == BIND) configure() else finish()
    }

    private fun configure() {
        val provider = manager.getAppWidgetInfo(pendingId) ?: run { abort(); return }
        if (provider.configure == null) finish()
        else runCatching {
            host.startAppWidgetConfigureActivityForResult(activity, pendingId, 0, CONFIGURE, null)
        }.onFailure { abort(); message("Could not open the widget's settings.") }
    }

    fun reconfigure(tile: Tile) {
        if (tile.widgetId <= 0 || manager.getAppWidgetInfo(tile.widgetId)?.configure == null) {
            message("This widget has no Android configuration screen.")
            return
        }
        runCatching { host.startAppWidgetConfigureActivityForResult(activity, tile.widgetId, 0, RECONFIGURE, null) }
            .onFailure { message("Could not open the widget's settings.") }
    }

    private fun finish() {
        val current = store.read()
        val info = manager.getAppWidgetInfo(pendingId)
        val slot = pendingSlot
        if (info == null || slot == null || info.profile != owner) { abort(); return }
        val old = replacing?.let { id -> current.tiles.firstOrNull { it.id == id && it.widgetId == 0 && it.slot == slot } }
        val tile = Tile(old?.id ?: java.util.UUID.randomUUID().toString(), TileKind.WIDGET, slot,
            name = info.provider.flattenToString(), widgetId = pendingId,
            width = requestedSize.first, height = requestedSize.second)
        if (replacing != null && old == null || !store.save(current.revision, current.tiles.filterNot { it.id == old?.id } + tile)) {
            abort()
            message("The layout changed during widget setup.")
            return
        }
        clearPending()
        onChanged()
    }

    private fun abort() { remove(pendingId); clearPending() }
    private fun clearPending() { pendingId = 0; pendingSlot = null; replacing = null }
    fun remove(id: Int) { if (id > 0) runCatching { host.deleteAppWidgetId(id) } }

    fun view(tile: Tile, width: Int, height: Int, reconnect: () -> Unit): View {
        if (tile.kind != TileKind.WIDGET) {
            val format = when (tile.kind) {
                TileKind.CLOCK -> if (store.settingEnabled("widget_seconds", false)) "HH:mm:ss" else "HH:mm"
                else -> "EEE\nMMM d"
            }
            return TextClock(activity).apply {
                format12Hour = format
                format24Hour = format
                gravity = Gravity.CENTER
                textSize = 25f
                setTextColor(Color.WHITE)
                background = rounded(0xe6273650.toInt(), activity.dp(25).toFloat())
            }
        }
        val info = if (tile.widgetId == 0) null else manager.getAppWidgetInfo(tile.widgetId)
        if (info == null || info.profile != owner) return activity.label("Connect widget", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xdd263750.toInt(), activity.dp(25).toFloat())
            setOnClickListener { reconnect() }
        }
        return host.createView(activity, tile.widgetId, info).apply {
            val density = resources.displayMetrics.density
            updateAppWidgetSize(Bundle(), listOf(SizeF(width / density, height / density)))
        }
    }

    private fun message(text: String) {
        android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val BIND = 301
        private const val CONFIGURE = 302
        private const val RECONFIGURE = 303
    }
}
