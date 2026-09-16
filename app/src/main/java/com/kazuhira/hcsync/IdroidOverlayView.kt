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

    private var vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var noisePaint: Paint? = null

    private val gridSpacing = dpToPx(24f)
    private val dotRadius = dpToPx(1.2f)

    init {
        // Base holographic tint (semi-transparent deep cyan-blue, legible over food while keeping optical feed visible)
        basePaint.color = Color.parseColor("#8003101E")
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
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#4002060C"), Color.parseColor("#88010408")),
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

        // 3. Scanline grid (every 5dp)
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
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
