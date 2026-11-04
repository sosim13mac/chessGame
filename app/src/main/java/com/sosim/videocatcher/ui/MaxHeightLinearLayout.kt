package com.sosim.videocatcher.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/** A LinearLayout that never grows taller than [maxHeightPx]. */
class MaxHeightLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var maxHeightPx: Int = 0
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var heightSpec = heightMeasureSpec
        if (maxHeightPx > 0) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val limit = if (mode == MeasureSpec.UNSPECIFIED) maxHeightPx else minOf(size, maxHeightPx)
            heightSpec = MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, heightSpec)
    }
}
