package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.isVisible
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

internal data class PagerSpread(val first: ReaderPage, val second: ReaderPage, val stitched: Boolean)

@SuppressLint("ViewConstructor")
internal class PagerSpreadView(
    context: Context,
    private val viewer: PagerViewer,
    override val item: PagerSpread,
) : FrameLayout(context), ViewPagerAdapter.PositionableView {
    private val showSeparateImages = if (item.stitched) MutableStateFlow(false) else null
    private val first = PagerPageHolder(context, viewer, item.first, paired = true, showSeparateImage = showSeparateImages)
    private val second = PagerPageHolder(context, viewer, item.second, paired = true, showSeparateImage = showSeparateImages)
    private val isRtl = viewer is R2LPagerViewer
    private val stitchedImage = if (item.stitched) ReaderPageImageView(context) else null
    private var stitchedImageLoaded = false
    private var seamX = 0
    private val scope = MainScope()
    private var stitchJob: Job? = null

    private val separatePages = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        // Keep the order tied to the manga direction even when the device locale is RTL.
        layoutDirection = LAYOUT_DIRECTION_LTR
        val ordered = if (isRtl) listOf(second, first) else listOf(first, second)
        ordered.forEach { addView(it, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)) }
    }

    val imagesLoaded: Boolean
        get() = stitchedImageLoaded || (first.imageLoaded && second.imageLoaded)

    init {
        addView(separatePages, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        stitchedImage?.let { image ->
            image.isVisible = false
            image.onImageLoaded = {
                stitchedImageLoaded = true
                separatePages.isVisible = false
                viewer.onSpreadImageLoaded(this)
            }
            image.onImageLoadError = { fallBackToSeparatePages() }
            image.onScaleChanged = { viewer.activity.hideMenu() }
            addView(image, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val image = stitchedImage ?: return
        val separate = checkNotNull(showSeparateImages)
        stitchJob = scope.launch {
            combine(item.first.statusFlow, item.second.statusFlow, ::Pair).collectLatest { (firstState, secondState) ->
                clearStitchedImage()
                if (firstState != Page.State.Ready || secondState != Page.State.Ready) {
                    separate.value = firstState is Page.State.Error || secondState is Page.State.Error
                    return@collectLatest
                }

                separate.value = false
                val result = try {
                    val (left, right) = if (isRtl) item.second to item.first else item.first to item.second
                    stitchPages(context, left, right, viewer.config.imageCropBorders, viewer.config.automaticBackground)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e)
                    null
                } catch (e: OutOfMemoryError) {
                    logcat(LogPriority.WARN, e)
                    null
                }
                if (result == null) {
                    fallBackToSeparatePages()
                    return@collectLatest
                }

                seamX = result.seamX
                image.pageBackground = result.background
                image.setImage(
                    result.source,
                    isAnimated = false,
                    config = ReaderPageImageView.Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = viewer.config.imageScaleType,
                        zoomStartPosition = viewer.config.imageZoomType,
                    ),
                )
                image.isVisible = true
            }
        }
    }

    override fun onDetachedFromWindow() {
        stitchJob?.cancel()
        stitchJob = null
        clearStitchedImage()
        showSeparateImages?.value = false
        super.onDetachedFromWindow()
    }

    private fun clearStitchedImage() {
        stitchedImageLoaded = false
        stitchedImage?.let {
            it.isVisible = false
            it.recycle()
        }
        separatePages.isVisible = true
    }

    private fun fallBackToSeparatePages() {
        clearStitchedImage()
        showSeparateImages?.value = true
    }

    fun imageFor(page: ReaderPage): ReaderPageImageView? {
        val holder = when (page) {
            item.first -> first
            item.second -> second
            else -> return null
        }
        return if (stitchedImageLoaded) stitchedImage else holder
    }

    fun pageAt(screenX: Float): ReaderPage {
        if (stitchedImageLoaded) {
            stitchedImage?.sourceXAt(screenX)?.let { x ->
                return if ((x < seamX) != isRtl) item.first else item.second
            }
        }
        val location = IntArray(2)
        first.getLocationOnScreen(location)
        return if (screenX >= location[0] && screenX < location[0] + first.width) item.first else item.second
    }
}
