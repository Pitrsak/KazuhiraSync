package com.kazuhira.hcsync

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.min

/**
 * The iDroid description line: each new message types itself out behind a block cursor
 * instead of appearing all at once. Plain `text = ...` assignments animate automatically.
 * Off while system animations are disabled, and screen readers get the full message straight away.
 */
class IdroidTypewriterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var typing = false
    private var animator: ValueAnimator? = null

    override fun setText(text: CharSequence?, type: TextView.BufferType?) {
        // Frames from our own animator, the constructor's initial text, and detached views go straight through
        if (typing || !isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            super.setText(text, type)
            return
        }
        animator?.cancel()
        val full: CharSequence = text ?: ""
        contentDescription = full
        if (full.isEmpty()) {
            super.setText(full, type)
            return
        }
        animator = ValueAnimator.ofInt(0, full.length).apply {
            duration = min(full.length * CHAR_MS, MAX_MS)
            interpolator = LinearInterpolator()
            addUpdateListener {
                val shown = it.animatedValue as Int
                showFrame(if (shown < full.length) "${full.subSequence(0, shown)}$CURSOR" else full)
            }
            start()
        }
    }

    private fun showFrame(frame: CharSequence) {
        typing = true
        try {
            setText(frame)
        } finally {
            typing = false
        }
    }

    override fun onDetachedFromWindow() {
        animator?.end()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val CHAR_MS = 14L
        const val MAX_MS = 650L
        const val CURSOR = "▌"
    }
}
