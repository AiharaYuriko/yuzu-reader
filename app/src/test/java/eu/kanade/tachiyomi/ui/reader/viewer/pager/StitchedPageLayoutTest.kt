package eu.kanade.tachiyomi.ui.reader.viewer.pager

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StitchedPageLayoutTest {
    @Test
    fun `pages of different sizes share a height and meet at the seam`() {
        val layout = checkNotNull(stitchedPageLayout(400, 800, 1200, 1600))
        layout shouldBe StitchedPageLayout(800, 1200, 1600)
        layout.width shouldBe 2000
    }

    @Test
    fun `large scans fit the spread pixel budget`() {
        val layout = checkNotNull(stitchedPageLayout(9000, 12000, 9000, 12000))
        (layout.width.toLong() * layout.height <= 6L * 1024 * 1024) shouldBe true
        layout.leftWidth shouldBe layout.rightWidth
    }

    @Test
    fun `invalid image dimensions cannot produce a spread`() {
        stitchedPageLayout(0, 1600, 1200, 1600) shouldBe null
    }
}
