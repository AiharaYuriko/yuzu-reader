package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga

internal data class DriveSnapshot(
    val backup: Backup = Backup(),
    val deletedCategories: Map<Long, Long> = emptyMap(),
)

/**
 * Each device writes its own Drive file. Merge all files before applying changes,
 * so simultaneous uploads from different devices cannot replace one another.
 */
internal fun mergeDriveSnapshots(snapshots: List<DriveSnapshot>): DriveSnapshot {
    val deleted = mutableMapOf<Long, Long>()
    val categories = mutableMapOf<Long, BackupCategory>()
    val titles = linkedMapOf<MangaKey, MutableList<DriveManga>>()

    snapshots.forEach { snapshot ->
        snapshot.deletedCategories.forEach { (uid, version) ->
            deleted[uid] = maxOf(deleted[uid] ?: 0L, version)
        }
        val categoriesByOrder = snapshot.backup.backupCategories.associate { it.order to it.uid }
        snapshot.backup.backupCategories.forEach { category ->
            if (category.version > (categories[category.uid]?.version ?: -1L)) categories[category.uid] = category
        }
        snapshot.backup.backupManga.forEach { manga ->
            val categoryIds = manga.categories.mapNotNull { categoriesByOrder[it] }
            titles.getOrPut(manga.key, ::mutableListOf).add(DriveManga(manga, categoryIds))
        }
    }

    val liveCategories = categories.values
        .filter { it.version > (deleted[it.uid] ?: -1L) }
        .sortedWith(compareBy({ it.order }, { it.name }, { it.uid }))
        .mapIndexed { index, category ->
            BackupCategory(
                name = category.name,
                order = index.toLong(),
                flags = category.flags,
                version = category.version,
                uid = category.uid,
                lastModifiedAt = category.lastModifiedAt,
            )
        }
    val ordersByUid = liveCategories.associate { it.uid to it.order }
    val merged = titles.values.map { copies ->
        val winner = copies.maxBy { it.manga.version }
        val chapters = copies.flatMap { it.manga.chapters }
            .groupBy { it.url }.values.map { group -> group.maxBy { it.version } }
        val history = copies.flatMap { it.manga.history }
            .groupBy { it.url }.values.map { group -> group.maxWith(compareBy({ it.lastRead }, { it.readDuration })) }
        val manga = winner.manga
        // Unfavoriting may yield a record without source details. Keep known metadata and chapters.
        val details = copies.asSequence().map { it.manga }.filter { it.initialized }.maxByOrNull { it.version } ?: manga
        BackupManga(
            source = manga.source,
            url = manga.url,
            title = manga.title.ifBlank { details.title },
            artist = details.artist,
            author = details.author,
            description = details.description,
            genre = details.genre,
            status = details.status,
            thumbnailUrl = details.thumbnailUrl,
            dateAdded = manga.dateAdded,
            chapters = chapters,
            categories = winner.categoryUids.mapNotNull { ordersByUid[it] },
            favorite = manga.favorite,
            chapterFlags = details.chapterFlags,
            viewer_flags = details.viewer_flags,
            history = history,
            updateStrategy = details.updateStrategy,
            lastModifiedAt = manga.lastModifiedAt,
            excludedScanlators = details.excludedScanlators,
            version = manga.version,
            notes = details.notes,
            initialized = details.initialized,
            memo = details.memo,
            nextUpdate = details.nextUpdate,
        )
    }
    return DriveSnapshot(Backup(merged, liveCategories), deleted)
}

/** A complete Drive snapshot only needs to apply titles whose sync state changed. */
internal fun Backup.driveChangesSince(previous: Backup): Backup {
    val old = previous.backupManga.associateBy { it.key }
    return copy(
        backupManga = backupManga.filter { manga ->
            val baseline = old[manga.key]
            baseline == null ||
                baseline.version != manga.version ||
                baseline.favorite != manga.favorite ||
                categoryNames(manga) != previous.categoryNames(baseline) ||
                manga.chapters.associate { it.url to it.version } !=
                baseline.chapters.associate { it.url to it.version } ||
                manga.history.toSet() != baseline.history.toSet()
        },
    )
}

private data class DriveManga(val manga: BackupManga, val categoryUids: List<Long>)
