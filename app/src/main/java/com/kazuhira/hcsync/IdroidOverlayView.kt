package com.kazuhira.hcsync

import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.random.Random

/**
 * Custom overlay view replicating the holographic projection of the MGSV iDroid.
 * Renders:
 * 1. Translucent teal wash over the camera feed.
 * 2. The soft horizontal light band the projector casts across the lower half.
 * 3. Darkened header / footer zones and edge vignette.
 * 4. Faint matrix dot grid.
 * 5. Procedural digital grain.
 * 6. Fine CRT scanlines.
 */
class IdroidOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /**
         * Grades the camera feed like the iDroid projection: desaturated and pushed into teal.
         * Needs a TextureView-backed PreviewView (COMPATIBLE mode) and API 31+; older devices
         * rely on the heavier overlay wash instead.
         */
        fun applyHoloColorGrade(view: View) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val lr = 0.2126f
            val lg = 0.7152f
            val lb = 0.0722f
            // Luminance mapped onto a teal ramp (R low, G/B high), lifted blacks
            val matrix = ColorMatrix(floatArrayOf(
                lr * 0.45f, lg * 0.45f, lb * 0.45f, 0f, 10f,
                lr * 0.80f, lg * 0.80f, lb * 0.80f, 0f, 38f,
                lr * 0.88f, lg * 0.88f, lb * 0.88f, 0f, 50f,
                0f, 0f, 0f, 1f, 0f
            ))
            view.setRenderEffect(
                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
            )
        }
    }

    private val gradedFeed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private val washPaint = Paint().apply {
        // Lighter wash when the feed itself is already colour graded
        color = if (gradedFeed) Color.parseColor("#59104656") else Color.parseColor("#A6134A5C")
    }
    private val scanlinePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.idroid_scanline)
        strokeWidth = 1f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.idroid_grid_dot)
        style = Paint.Style.FILL
    }
    private val gridSpacing = 14f * resources.displayMetrics.density
    private val dotRadius = 0.8f * resources.displayMetrics.density
    private val bandPaint = Paint()
    private val zonePaint = Paint()
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var noisePaint: Paint? = null

    init {
        initNoiseShader()
    }

    private fun initNoiseShader() {
        try {
            val size = 64
            val noiseBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(size * size)
            val random = Random(42) // Consistent grain pattern
            for (i in pixels.indices) {
                val v = random.nextInt(170, 255)
                val alpha = random.nextInt(4, 14)
                pixels[i] = Color.argb(alpha, (v * 0.75f).toInt(), v, v)
            }
            noiseBmp.setPixels(pixels, 0, size, 0, 0, size, size)
            noisePaint = Paint().apply {
                shader = BitmapShader(noiseBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            }
        } catch (e: Exception) {
            noisePaint = null
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val band = ContextCompat.getColor(context, R.color.idroid_glow_band)

        // Horizontal projector light band, brightest a little below centre
        bandPaint.shader = LinearGradient(
            0f, h * 0.42f, 0f, h * 0.82f,
            intArrayOf(Color.TRANSPARENT, band, Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        // Darker header and footer zones keep the HUD text crisp
        zonePaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.parseColor("#80062029"), Color.TRANSPARENT,
                Color.TRANSPARENT, Color.parseColor("#8C062029")
            ),
            floatArrayOf(0f, 0.16f, 0.84f, 1f),
            Shader.TileMode.CLAMP
        )

        val radius = Math.hypot(w.toDouble(), h.toDouble()).toFloat() * 0.6f
        vignettePaint.shader = RadialGradient(
            w / 2f, h * 0.55f, radius,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#3304161D"), ContextCompat.getColor(context, R.color.idroid_holo_vignette)),
            floatArrayOf(0.45f, 0.8f, 1.0f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        canvas.drawRect(0f, 0f, w, h, washPaint)
        canvas.drawRect(0f, 0f, w, h, bandPaint)
        canvas.drawRect(0f, 0f, w, h, zonePaint)
        noisePaint?.let { canvas.drawRect(0f, 0f, w, h, it) }

        var gx = gridSpacing / 2f
        while (gx < w) {
            var gy = gridSpacing / 2f
            while (gy < h) {
                canvas.drawCircle(gx, gy, dotRadius, dotPaint)
                gy += gridSpacing
            }
            gx += gridSpacing
        }

        val step = 3f * resources.displayMetrics.density
        var y = 0f
        while (y < h) {
            canvas.drawLine(0f, y, w, y, scanlinePaint)
            y += step
        }

        canvas.drawRect(0f, 0f, w, h, vignettePaint)
    }
}
