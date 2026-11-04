package io.github.kazumaproject.cuperalauncher

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

@SuppressLint("ViewConstructor") // HomeActivity supplies the folder's pages at construction.
internal class FolderPager(
    context: Context,
    private val pages: List<View>,
    private val pageWidth: Int,
    initialPage: Int,
    private val reducedMotion: () -> Boolean,
    private val onPageChanged: (Int) -> Unit
) : HorizontalScrollView(context) {
    private var selected = initialPage
    private var gesturePage = initialPage
    private var flung = false
    private var gestureStartX = 0f
    private var gestureStartScroll = 0
    private var multiplePointers = false
    private var settle: ValueAnimator? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val pageGap = (pageWidth * .105f).roundToInt()
    private val pageStride = pageWidth + pageGap

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            pages.forEachIndexed { index, page ->
                addView(page, LinearLayout.LayoutParams(pageWidth, -1).apply {
                    if (index < pages.lastIndex) rightMargin = pageGap
                })
            }
        }, LayoutParams(-2, -1))
        updateAccessibility()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (changed) scrollTo(selected * pageStride, 0)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settle?.cancel()
                settle = null
                gesturePage = selected
                gestureStartX = event.x
                gestureStartScroll = scrollX
                flung = false
                multiplePointers = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> multiplePointers = true
        }
        val handled = super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> if (!flung && scrollX != selected * pageStride) {
                // A batched MOVE may miss the frame before UP. Use the finger's final position.
                val travel = gestureStartX - event.x
                val position = if (multiplePointers) scrollX.toFloat() else
                    gestureStartScroll + (abs(travel) - touchSlop).coerceAtLeast(0f) * sign(travel)
                val nearest = (position / pageStride).roundToInt()
                showPage(nearest.coerceIn(gesturePage - 1, gesturePage + 1))
            }
            MotionEvent.ACTION_CANCEL -> showPage(gesturePage)
        }
        return handled
    }

    override fun fling(velocityX: Int) {
        flung = true
        showPage(gesturePage + if (velocityX > 0) 1 else -1)
    }

    fun showPage(page: Int, edgeTurn: Boolean = false) {
        val next = page.coerceIn(pages.indices)
        if (next != selected) {
            selected = next
            updateAccessibility()
            onPageChanged(next)
        }
        val target = next * pageStride
        settle?.cancel()
        settle = null
        if (reducedMotion() || target == scrollX) {
            scrollTo(target, 0)
            return
        }
        val returning = !edgeTurn && next == gesturePage
        settle = ValueAnimator.ofInt(scrollX, target).apply {
            duration = when {
                edgeTurn -> 260
                returning -> 265
                else -> 280
            }
            interpolator = if (returning) PathInterpolator(.30f, 0f, .23f, 1f)
                else PathInterpolator(.27f, .60f, .38f, 1f)
            addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        settle?.cancel()
        settle = null
        super.onDetachedFromWindow()
    }

    fun edgeAt(x: Float, y: Float): Int {
        val bounds = Rect()
        if (!getGlobalVisibleRect(bounds)) return 0
        val root = IntArray(2).also(rootView::getLocationOnScreen)
        bounds.offset(root[0], root[1])
        if (!bounds.contains(x.toInt(), y.toInt())) return 0
        val edge = context.dp(20)
        return when {
            x < bounds.left + edge && selected > 0 -> -1
            x > bounds.right - edge && selected < pages.lastIndex -> 1
            else -> 0
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = true
        if (selected > 0) info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        if (selected < pages.lastIndex) info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val next = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> selected + 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> selected - 1
            else -> return super.performAccessibilityAction(action, arguments)
        }
        if (next !in pages.indices) return false
        showPage(next)
        return true
    }

    private fun updateAccessibility() {
        pages.forEachIndexed { index, view ->
            view.importantForAccessibility = if (index == selected) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            (view as? ViewGroup)?.descendantFocusability = if (index == selected)
                ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }
}
