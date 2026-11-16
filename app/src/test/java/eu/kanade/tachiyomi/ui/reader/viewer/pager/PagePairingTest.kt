package eu.kanade.tachiyomi.ui.reader.viewer.pager

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PagePairingTest {
    @Test
    fun `first page can be paired or shown alone`() {
        val pages = (1..5).toList()

        pairPages(pages, firstPageAlone = false) { false } shouldBe listOf(1 to 2, 3 to 4, 5 to null)
        pairPages(pages, firstPageAlone = true) { false } shouldBe listOf(1 to null, 2 to 3, 4 to 5)
    }

    @Test
    fun `wide pages occupy their own slot without skipping neighbouring pages`() {
        val pages = (1..8).toList()

        pairPages(pages, firstPageAlone = false) { it in 4..5 } shouldBe
            listOf(1 to 2, 3 to null, 4 to null, 5 to null, 6 to 7, 8 to null)
    }

    @Test
    fun `empty and single-page chapters have no pair`() {
        pairPages(emptyList<Int>(), firstPageAlone = true) { false } shouldBe emptyList()
        pairPages(listOf(1), firstPageAlone = false) { false } shouldBe listOf(1 to null)
    }
}
