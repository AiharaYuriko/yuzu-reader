package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.buffer
import okio.source
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.decoder.ImageDecoder
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt
import eu.kanade.tachiyomi.data.coil.ImageDecoder as CoilImageDecoder

internal data class StitchedPages(
    val source: Buffer,
    val seamX: Int,
    val background: Drawable?,
)

internal data class StitchedPageLayout(val leftWidth: Int, val rightWidth: Int, val height: Int) {
    val width: Int get() = leftWidth + rightWidth
}

internal suspend fun stitchPages(
    context: Context,
    left: ReaderPage,
    right: ReaderPage,
    cropBorders: Boolean,
    automaticBackground: Boolean,
): StitchedPages? = withContext(Dispatchers.IO) {
    // The pager preloads adjacent spreads. Compose one at a time to bound transient bitmap memory.
    spreadDecodes.withPermit {
        val leftDecoder = openPageDecoder(left, cropBorders) ?: return@withPermit null
        try {
            val rightDecoder = openPageDecoder(right, cropBorders) ?: return@withPermit null
            try {
                composeSpread(context, leftDecoder, rightDecoder, automaticBackground)
            } finally {
                rightDecoder.recycle()
            }
        } finally {
            leftDecoder.recycle()
        }
    }
}

private fun openPageDecoder(page: ReaderPage, cropBorders: Boolean): ImageDecoder? {
    val stream = page.stream ?: return null
    return stream().source().buffer().use { source ->
        if (ImageUtil.isAnimatedAndSupported(source) || ImageUtil.isWideImage(source)) return null
        ImageDecoder.newInstance(source.inputStream(), cropBorders, CoilImageDecoder.displayProfile)
    }
}

private suspend fun composeSpread(
    context: Context,
    left: ImageDecoder,
    right: ImageDecoder,
    automaticBackground: Boolean,
): StitchedPages? {
    val layout = stitchedPageLayout(left.width, left.height, right.width, right.height) ?: return null
    val (leftWidth, _, height) = layout
    val width = layout.width

    coroutineContext.ensureActive()
    val bitmap = createBitmap(width, height)
    try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        drawPage(canvas, paint, left, Rect(0, 0, leftWidth, height))
        coroutineContext.ensureActive()
        drawPage(canvas, paint, right, Rect(leftWidth, 0, width, height))
        coroutineContext.ensureActive()

        val background = if (automaticBackground) ImageUtil.chooseBackground(context, bitmap) else null
        val source = Buffer()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, source.outputStream())) return null
        return StitchedPages(source, leftWidth, background)
    } finally {
        bitmap.recycle()
    }
}

internal fun stitchedPageLayout(
    leftWidth: Int,
    leftHeight: Int,
    rightWidth: Int,
    rightHeight: Int,
): StitchedPageLayout? {
    if (minOf(leftWidth, leftHeight, rightWidth, rightHeight) < 1) return null
    val leftAspect = leftWidth.toDouble() / leftHeight
    val rightAspect = rightWidth.toDouble() / rightHeight
    val aspect = leftAspect + rightAspect
    val height = minOf(
        maxOf(leftHeight, rightHeight),
        sqrt(MAX_PIXELS / aspect).toInt(),
        (MAX_SIDE / aspect).toInt(),
        MAX_SIDE,
    )
    if (height < 1) return null

    val layout = StitchedPageLayout(
        leftWidth = (leftAspect * height).toInt().coerceAtLeast(1),
        rightWidth = (rightAspect * height).toInt().coerceAtLeast(1),
        height = height,
    )
    return layout.takeIf { it.width <= MAX_SIDE && it.width.toLong() * height <= MAX_PIXELS }
}

private fun drawPage(canvas: Canvas, paint: Paint, decoder: ImageDecoder, destination: Rect) {
    var sampleSize = 1
    // Match the display height where possible while bounding the decoded bitmap's dimensions.
    while (decoder.height / (sampleSize * 2) >= destination.height() ||
        decoder.width.toLong() * decoder.height / sampleSize / sampleSize > MAX_PIXELS
    ) {
        sampleSize *= 2
    }
    val bitmap = checkNotNull(decoder.decode(sampleSize = sampleSize))
    try {
        canvas.drawBitmap(bitmap, null, destination, paint)
    } finally {
        bitmap.recycle()
    }
}

private const val MAX_PIXELS = 6L * 1024 * 1024
private const val MAX_SIDE = 8192
private val spreadDecodes = Semaphore(1)
