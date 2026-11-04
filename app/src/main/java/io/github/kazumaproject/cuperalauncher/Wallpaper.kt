package io.github.kazumaproject.cuperalauncher

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

class CuperaWallpaper(private val dusk: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val midnight = if (dusk) 0xff221d3d.toInt() else 0xff112d5e.toInt()
    private val cyan = if (dusk) 0xff70568a.toInt() else 0xff2878a5.toInt()
    private val sea = if (dusk) 0xff322746.toInt() else 0xff174968.toInt()
    private val mint = if (dusk) 0xff976a99.toInt() else 0xff58c7ba.toInt()
    private val pale = if (dusk) 0xffb58caf.toInt() else 0xff8ad5d1.toInt()

    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width().toFloat(), bounds.height().toFloat())
        paint.shader = LinearGradient(0f, 0f, 1f, 1f, midnight, cyan, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 1f, 1f, paint)
        path.reset()
        path.moveTo(0f, 0f)
        path.lineTo(.51f, 0f)
        path.cubicTo(.43f, .19f, .91f, .25f, .92f, .42f)
        path.cubicTo(.92f, .61f, .35f, .71f, .11f, 1f)
        path.lineTo(0f, 1f)
        path.close()
        paint.shader = LinearGradient(.04f, 0f, .92f, .93f, pale, sea, Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        path.reset()
        path.moveTo(0f, .42f)
        path.cubicTo(.4f, .36f, .59f, .47f, .61f, .62f)
        path.cubicTo(.64f, .75f, .19f, .84f, 0f, 1f)
        path.close()
        paint.shader = LinearGradient(.08f, .4f, .63f, .96f, mint, sea, Shader.TileMode.CLAMP)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }

    internal fun applyToAndroidHome(manager: WallpaperManager, display: Rect): Int {
        val scale = minOf(1f, 2400f / maxOf(display.width(), display.height()))
        val width = (display.width() * scale).roundToInt().coerceAtLeast(1)
        val height = (display.height() * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            setBounds(0, 0, width, height)
            draw(Canvas(bitmap))
            return manager.setBitmap(bitmap, null, false, WallpaperManager.FLAG_SYSTEM)
        } finally {
            bitmap.recycle()
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.OPAQUE
}
