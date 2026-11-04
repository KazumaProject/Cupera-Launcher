package io.github.kazumaproject.cuperalauncher

import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

class AppIcons {
    private data class Key(val app: AppKey, val density: Int, val square: Boolean)
    private data class SourceKey(val app: AppKey, val density: Int, val generation: Int)
    private val cache = LruCache<Key, Drawable.ConstantState>(160)
    private val sources = LruCache<SourceKey, Drawable.ConstantState>(160)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var closed = false

    fun show(view: ImageView, entry: Apps.Entry, square: Boolean = false) {
        if (closed) return
        val density = view.resources.displayMetrics.densityDpi
        val key = Key(entry.key, density, square)
        val hit = cache.get(key)
        if (hit != null) {
            view.setImageDrawable(hit.newDrawable(view.resources).mutate())
            return
        }
        val token = generation
        worker.execute {
            val drawable = runCatching {
                // Home and Search can use different shapes for the same source icon.
                // A worker finishing after invalidation can only populate its old generation.
                val source = SourceKey(entry.key, density, token)
                val original = sources.get(source)?.newDrawable(view.resources)?.mutate()
                    ?: entry.info.getBadgedIcon(density).also {
                        it.constantState?.let { state -> sources.put(source, state) }
                    }
                if (square && original is AdaptiveIconDrawable) {
                    LayerDrawable(listOfNotNull(original.background, original.foreground)
                        .map { InsetDrawable(it, -.25f) }.toTypedArray())
                } else original
            }.getOrNull() ?: return@execute
            main.post {
                if (token != generation) return@post
                val state = drawable.constantState
                if (state != null) cache.put(key, state)
                if (view.isAttachedToWindow) view.setImageDrawable(state?.newDrawable(view.resources)?.mutate() ?: drawable)
            }
        }
    }

    fun invalidate() {
        generation++
        cache.evictAll()
        sources.evictAll()
    }

    fun close() {
        closed = true
        invalidate()
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }
}
