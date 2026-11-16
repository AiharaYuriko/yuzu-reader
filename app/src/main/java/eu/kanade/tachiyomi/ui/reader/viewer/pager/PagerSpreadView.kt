package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.widget.LinearLayout
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.widget.ViewPagerAdapter

internal data class PagerSpread(val first: ReaderPage, val second: ReaderPage)

@SuppressLint("ViewConstructor")
internal class PagerSpreadView(
    context: Context,
    viewer: PagerViewer,
    override val item: PagerSpread,
) : LinearLayout(context), ViewPagerAdapter.PositionableView {
    val first = PagerPageHolder(context, viewer, item.first, paired = true)
    val second = PagerPageHolder(context, viewer, item.second, paired = true)

    init {
        orientation = HORIZONTAL
        // Keep the order tied to the manga direction, including when the device locale is RTL.
        layoutDirection = LAYOUT_DIRECTION_LTR
        val ordered = if (viewer is R2LPagerViewer) listOf(second, first) else listOf(first, second)
        ordered.forEach { addView(it, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)) }
    }

    fun pageAt(screenX: Float): ReaderPage {
        val location = IntArray(2)
        first.getLocationOnScreen(location)
        return if (screenX >= location[0] && screenX < location[0] + first.width) item.first else item.second
    }
}
