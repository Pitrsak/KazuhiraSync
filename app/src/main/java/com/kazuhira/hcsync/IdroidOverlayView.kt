package com.kazuhira.hcsync

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.random.Random

/**
 * Custom overlay view replicating the holographic augmented-reality display of the MGSV iDroid.
 * Renders:
 * 1. Deep translucent holographic cyan/navy tint over the camera feed.
 * 2. Subtle radial vignette darkening towards edges.
 * 3. Matrix dot grid matching the in-game display.
 * 4. Procedural digital grain / noise shader.
 * 5. CRT scanline texture.
 * 6. Tactical corner HUD reticles.
 */
class IdroidOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.idroid_grid_dot)
        style = Paint.Style.FILL
    }
    private val scanlinePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.idroid_scanline)
        strokeWidth = 1f
    }
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.idroid_border_glow)
        strokeWidth = dpToPx(1.5f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }

    private var vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var noisePaint: Paint? = null

    private val gridSpacing = dpToPx(24f)
    private val dotRadius = dpToPx(1.2f)
    private val bracketLen = dpToPx(14f)
    private val bracketMargin = dpToPx(8f)

    init {
        // Base holographic tint (semi-transparent deep blue)
        basePaint.color = Color.parseColor("#6604101E")
        initNoiseShader()
    }

    private fun initNoiseShader() {
        try {
            val size = 64
            val noiseBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(size * size)
            val random = Random(42) // Consistent grain pattern
            for (i in pixels.indices) {
                // Subtle random cyan/grey grain with very low alpha
                val v = random.nextInt(180, 255)
                val alpha = random.nextInt(6, 18)
                pixels[i] = Color.argb(alpha, (v * 0.7f).toInt(), v, v)
            }
            noiseBmp.setPixels(pixels, 0, size, 0, 0, size, size)
            val shader = BitmapShader(noiseBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            noisePaint = Paint().apply {
                this.shader = shader
            }
        } catch (e: Exception) {
            noisePaint = null
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            // Radial vignette to darken outer corners like the iDroid projector
            val radius = Math.hypot(w.toDouble(), h.toDouble()).toFloat() * 0.55f
            val vignetteShader = RadialGradient(
                w / 2f,
                h / 2f,
                radius,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#3302060C"), Color.parseColor("#77010408")),
                floatArrayOf(0.4f, 0.75f, 1.0f),
                Shader.TileMode.CLAMP
            )
            vignettePaint.shader = vignetteShader
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Base translucent holographic blue wash
        canvas.drawRect(0f, 0f, w, h, basePaint)

        // 2. Procedural noise texture
        noisePaint?.let {
            canvas.drawRect(0f, 0f, w, h, it)
        }

        // 3. Scanline grid (every 6dp)
        val step = dpToPx(5f)
        var y = 0f
        while (y < h) {
            canvas.drawLine(0f, y, w, y, scanlinePaint)
            y += step
        }

        // 4. Matrix dot grid
        var gx = gridSpacing
        while (gx < w) {
            var gy = gridSpacing
            while (gy < h) {
                canvas.drawCircle(gx, gy, dotRadius, dotPaint)
                gy += gridSpacing
            }
            gx += gridSpacing
        }

        // 5. Vignette gradient
        canvas.drawRect(0f, 0f, w, h, vignettePaint)

        // 6. Tactical corner HUD brackets
        drawCornerBrackets(canvas, w, h)
    }

    private fun drawCornerBrackets(canvas: Canvas, w: Float, h: Float) {
        val m = bracketMargin
        val len = bracketLen

        // Top-Left
        canvas.drawLine(m, m, m + len, m, bracketPaint)
        canvas.drawLine(m, m, m, m + len, bracketPaint)

        // Top-Right
        canvas.drawLine(w - m - len, m, w - m, m, bracketPaint)
        canvas.drawLine(w - m, m, w - m, m + len, bracketPaint)

        // Bottom-Left
        canvas.drawLine(m, h - m, m + len, h - m, bracketPaint)
        canvas.drawLine(m, h - m - len, m, h - m, bracketPaint)

        // Bottom-Right
        canvas.drawLine(w - m - len, h - m, w - m, h - m, bracketPaint)
        canvas.drawLine(w - m, h - m - len, w - m, h - m, bracketPaint)
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
