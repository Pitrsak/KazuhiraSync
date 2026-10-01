package com.kazuhira.hcsync

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
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
        set(value) { field = value; invalidate() }

    var activeIndex = 1
        set(value) { field = value; invalidate() }

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
    private val activeTextColor = ContextCompat.getColor(context, R.color.idroid_text_main)
    private val idleTextColor = ContextCompat.getColor(context, R.color.idroid_text_dim)

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
            val active = i == activeIndex
            val top = if (active) activeTop else idleTop
            val legLen = if (active) legBottom - top else 6f * density

            // Open-bottomed frame: top rule with short legs (full height on the active tab)
            path.reset()
            path.moveTo(left, top + legLen)
            path.lineTo(left, top)
            path.lineTo(right, top)
            path.lineTo(right, top + legLen)
            if (active) {
                rect.set(left, top, right, top + legLen)
                canvas.drawRect(rect, activeFillPaint)
            }
            canvas.drawPath(path, if (active) linePaint else idleLinePaint)

            labelPaint.color = if (active) activeTextColor else idleTextColor
            val textY = (top + legBottom) / 2f + labelPaint.textSize * 0.36f
            canvas.drawText(label, (left + right) / 2f, textY, labelPaint)
        }

        // Baseline under the strip
        canvas.drawLine(0f, h - 1f * density, w, h - 1f * density, tickPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x
            val index = (0 until tabs.size).firstOrNull { x >= tabLeft(it) && x < tabLeft(it + 1) } ?: -1
            if (index in tabs.indices) onTabClick?.invoke(index)
        }
        return super.onTouchEvent(event)
    }
}
