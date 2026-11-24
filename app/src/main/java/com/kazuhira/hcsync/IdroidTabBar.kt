package com.kazuhira.hcsync

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * The iDroid top navigation strip ("MOTHER BASE | MAP | MISSIONS"):
 * each tab is an open-bottomed hairline frame, the active one raised and filled.
 */
class IdroidTabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var tabs: List<String> = listOf("INTEL FILE", "RATIONS", "CONFIG")
        set(value) {
            field = value
            lift = FloatArray(value.size) { if (it == activeIndex) 1f else 0f }
            invalidate()
        }

    /** The raised tab. Changing it animates the old tab down and the new one up. */
    var activeIndex = 1
        set(value) {
            if (field == value) return
            field = value
            animateLift()
        }

    /** 0 = idle, 1 = fully raised, per tab; animated between the two. */
    private var lift = FloatArray(tabs.size) { if (it == activeIndex) 1f else 0f }
    private var liftAnimator: ValueAnimator? = null

    /** Brief highlight on the tapped tab, fading out after release. */
    private var pressedIndex = -1
    private var pressGlow = 0f
    private var pressAnimator: ValueAnimator? = null

    /** Invoked with the tapped tab index. */
    var onTabClick: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density

    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.idroid_border_glow)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val idleLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.idroid_border)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.idroid_border_dim)
        strokeWidth = 1f * density
    }
    private val activeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.idroid_cyan_badge_bg)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.rajdhani_semibold)
        textSize = sp(12f)
        letterSpacing = 0.14f
        textAlign = Paint.Align.CENTER
    }
    private val pressFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.idroid_cyan_dim)
        style = Paint.Style.FILL
    }
    private val activeTextColor = ContextCompat.getColor(context, R.color.idroid_text_main)
    private val idleTextColor = ContextCompat.getColor(context, R.color.idroid_text_dim)
    private val argb = ArgbEvaluator()
    private val activeFillAlpha = activeFillPaint.alpha
    private val pressFillAlpha = pressFillPaint.alpha

    private val path = Path()
    private val rect = RectF()

    init {
        isClickable = true
        contentDescription = "iDroid navigation"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (40f * density).toInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec)
        )
    }

    private fun tabLeft(i: Int): Float = width.toFloat() * i / tabs.size

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tabs.isEmpty() || width == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = 4f * density
        val activeTop = 2f * density
        val idleTop = 9f * density
        val legBottom = h - 6f * density

        tabs.forEachIndexed { i, label ->
            val left = tabLeft(i) + gap / 2f
            val right = tabLeft(i + 1) - gap / 2f
            val f = lift.getOrElse(i) { 0f }
            val top = idleTop + (activeTop - idleTop) * f
            val idleLeg = 6f * density
            val legLen = idleLeg + (legBottom - top - idleLeg) * f

            // Open-bottomed frame: top rule with short legs (full height on the raised tab)
            path.reset()
            path.moveTo(left, top + legLen)
            path.lineTo(left, top)
            path.lineTo(right, top)
            path.lineTo(right, top + legLen)
            if (f > 0f) {
                rect.set(left, top, right, top + legLen)
                activeFillPaint.alpha = (activeFillAlpha * f).toInt()
                canvas.drawRect(rect, activeFillPaint)
            }
            if (i == pressedIndex && pressGlow > 0f) {
                rect.set(left, top, right, legBottom)
                pressFillPaint.alpha = (pressFillAlpha * pressGlow).toInt()
                canvas.drawRect(rect, pressFillPaint)
            }
            canvas.drawPath(path, if (f >= 0.5f) linePaint else idleLinePaint)

            labelPaint.color = argb.evaluate(f, idleTextColor, activeTextColor) as Int
            val textY = (top + legBottom) / 2f + labelPaint.textSize * 0.36f
            canvas.drawText(label, (left + right) / 2f, textY, labelPaint)
        }

        // Baseline under the strip
        canvas.drawLine(0f, h - 1f * density, w, h - 1f * density, tickPaint)
    }

    private fun tabAt(x: Float): Int =
        (0 until tabs.size).firstOrNull { x >= tabLeft(it) && x < tabLeft(it + 1) } ?: -1

    private fun animateLift() {
        val from = lift.copyOf()
        val target = FloatArray(tabs.size) { if (it == activeIndex) 1f else 0f }
        liftAnimator?.cancel()
        liftAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                lift = FloatArray(target.size) { i -> from.getOrElse(i) { 0f } + (target[i] - from.getOrElse(i) { 0f }) * t }
                invalidate()
            }
            start()
        }
    }

    private fun releaseGlow() {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressGlow, 0f).apply {
            duration = 240
            addUpdateListener { a ->
                pressGlow = a.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressAnimator?.cancel()
                pressedIndex = tabAt(event.x)
                pressGlow = 1f
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val index = tabAt(event.x)
                releaseGlow()
                if (index in tabs.indices) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onTabClick?.invoke(index)
                }
            }
            MotionEvent.ACTION_CANCEL -> releaseGlow()
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        liftAnimator?.cancel()
        pressAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
