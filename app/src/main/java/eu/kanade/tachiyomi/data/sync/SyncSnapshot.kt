package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import okio.ByteString.Companion.encodeUtf8

internal data class MangaKey(val source: Long, val url: String)

internal val BackupManga.key get() = MangaKey(source, url)

internal fun Backup.categoryNames(manga: BackupManga): Set<String> {
    val namesByOrder = backupCategories.associate { it.order to it.name }
    return manga.categories.mapNotNullTo(mutableSetOf()) { namesByOrder[it] }
}

internal fun BackupChapter.progress() = Triple(read, bookmark, lastPageRead)

internal data class SyncUpload(val backup: Backup, val deletedCategories: List<Long>)

internal fun prepareSyncUpload(local: Backup, previous: Backup, full: Boolean, epochMillis: Long): SyncUpload {
    val previousManga = previous.backupManga.associateBy { it.key }
    val previousCategories = previous.backupCategories.associateBy { it.name }
    val localKeys = local.backupManga.mapTo(hashSetOf()) { it.key }
    val categories = local.backupCategories
    val deletedCategories = (previousCategories.keys - categories.map { it.name }.toSet())
        .mapNotNull { name -> previousCategories[name]?.uid?.takeIf { it != 0L } }

    categories.forEach { category ->
        val old = previousCategories[category.name]
        category.uid = old?.uid?.takeIf { it != 0L } ?: categoryUid(category.name)
        val unchanged = old != null && old.flags == category.flags
        category.version = if (unchanged) old.version else nextVersion(old?.version, epochMillis)
        category.lastModifiedAt = if (unchanged) old.lastModifiedAt else epochMillis / 1000
    }

    val changed = local.backupManga.filter { manga ->
        val old = previousManga[manga.key]
        val oldChapters = old?.chapters.orEmpty().associateBy { it.url }
        val mangaChanged = old == null || old.favorite != manga.favorite ||
            previous.categoryNames(old) != local.categoryNames(manga)
        manga.version = if (mangaChanged) nextVersion(old?.version, epochMillis) else old.version
        manga.lastModifiedAt = if (mangaChanged) epochMillis / 1000 else old.lastModifiedAt

        var chaptersChanged = false
        manga.chapters.forEach { chapter ->
            val previousChapter = oldChapters[chapter.url]
            if (previousChapter != null && previousChapter.progress() == chapter.progress()) {
                chapter.version = previousChapter.version
                chapter.lastModifiedAt = previousChapter.lastModifiedAt
            } else {
                chaptersChanged = true
                chapter.version = nextVersion(previousChapter?.version, epochMillis)
                chapter.lastModifiedAt = epochMillis / 1000
            }
        }
        val hasNewHistory = old?.history.orEmpty().let { history ->
            val historyByUrl = history.associateBy { it.url }
            manga.history.any { it != historyByUrl[it.url] }
        }
        full || mangaChanged || chaptersChanged || hasNewHistory
    }

    val removedFavorites = previous.backupManga.filter { it.favorite && it.key !in localKeys }.map { old ->
        BackupManga(
            source = old.source,
            url = old.url,
            title = old.title,
            favorite = false,
            lastModifiedAt = epochMillis / 1000,
            version = nextVersion(old.version, epochMillis),
        )
    }
    return SyncUpload(Backup(changed + removedFavorites, categories), deletedCategories)
}

/**
 * Persist an acknowledgement only after the database has applied the server response.
 * Category orders are wire-local, so remap references by name when combining backups.
 */
internal fun acknowledgedSnapshot(previous: Backup, sent: Backup, remote: Backup, full: Boolean): Backup {
    val categories = remote.backupCategories
    val orderByName = categories.associate { it.name to it.order }
    val entries = linkedMapOf<MangaKey, BackupManga>()
    val sources = if (full) listOf(remote) else listOf(previous, sent, remote)
    sources.forEach { backup ->
        backup.backupManga.forEach { manga ->
            val mappedCategories = backup.categoryNames(manga).mapNotNull { orderByName[it] }
            val previousChapters = if (backup === sent) entries[manga.key]?.chapters.orEmpty() else emptyList()
            entries[manga.key] = syncState(
                manga,
                mappedCategories,
                (previousChapters + manga.chapters).associateBy { it.url }.values.toList(),
            )
        }
    }
    return Backup(entries.values.toList(), categories)
}

// The local acknowledgement holds only the fields used to detect subsequent changes.
private fun syncState(manga: BackupManga, categories: List<Long>, chapters: List<BackupChapter>) = BackupManga(
    source = manga.source,
    url = manga.url,
    title = manga.title,
    favorite = manga.favorite,
    version = manga.version,
    lastModifiedAt = manga.lastModifiedAt,
    categories = categories,
    chapters = chapters.map {
        BackupChapter(
            url = it.url,
            read = it.read,
            bookmark = it.bookmark,
            lastPageRead = it.lastPageRead,
            version = it.version,
            lastModifiedAt = it.lastModifiedAt,
        )
    },
    history = manga.history,
)

private fun categoryUid(name: String): Long = name.encodeUtf8().sha256().hex().take(15).toLong(16)

private fun nextVersion(previous: Long?, epochMillis: Long): Long = maxOf((previous ?: 0L) + 1, epochMillis)
