package com.saywhat.app.view

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * 一个能限制最大高度的 ScrollView。
 *
 * 悬浮窗用 WRAP_CONTENT 高度时，ScrollView 会一路撑到内容的全高，
 * 分析内容一长卡片就会超出屏幕、被系统裁掉。
 * 这里在 onMeasure 里把高度夹在 maxHeightDp 以内，超出的部分改为内部滚动。
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    /** 最大高度（dp）。0 表示不限制。 */
    var maxHeightDp: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxHeightDp > 0) {
            val maxPx = (maxHeightDp * resources.displayMetrics.density).toInt()
            val capped = MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, capped)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
