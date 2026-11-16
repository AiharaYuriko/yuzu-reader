package eu.kanade.tachiyomi.ui.reader.viewer.pager

internal fun <T : Any> pairPages(
    pages: List<T>,
    firstPageAlone: Boolean,
    isWide: (T) -> Boolean,
): List<Pair<T, T?>> = buildList {
    var index = 0
    while (index < pages.size) {
        val first = pages[index]
        val next = pages.getOrNull(index + 1)
        val second = next?.takeUnless {
            (index == 0 && firstPageAlone) || isWide(first) || isWide(it)
        }
        add(first to second)
        index += if (second == null) 1 else 2
    }
}
