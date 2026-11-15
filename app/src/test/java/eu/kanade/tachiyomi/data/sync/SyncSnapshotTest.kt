package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import org.junit.jupiter.api.Test

@OptIn(ExperimentalSerializationApi::class)
class SyncSnapshotTest {
    @Test
    fun `chapter progress changes without replacing manga version`() {
        val previous = library(page = 7, order = 4)
        val first = prepareSyncUpload(previous, Backup(), full = true, epochMillis = 1000)
        val local = library(page = 9, order = 0)
        val next = prepareSyncUpload(local, first.backup, full = false, epochMillis = 2000)

        val entry = next.backup.backupManga.single()
        entry.version shouldBe 1000L
        entry.chapters.single().version shouldBe 2000L
        entry.chapters.single().lastPageRead shouldBe 9L
        next.backup.backupCategories.single().uid shouldBe first.backup.backupCategories.single().uid
    }

    @Test
    fun `reordered category numbers do not upload an unchanged title`() {
        val before = prepareSyncUpload(library(page = 7, order = 4), Backup(), true, 1000).backup
        val delta = prepareSyncUpload(library(page = 7, order = 0), before, false, 2000)
        delta.backup.backupManga shouldBe emptyList()
        delta.deletedCategories shouldBe emptyList()
    }

    @Test
    fun `an unfavorite and category deletion are explicit`() {
        val before = prepareSyncUpload(library(page = 7, order = 0), Backup(), true, 1000).backup
        val delta = prepareSyncUpload(Backup(), before, false, 2000)
        delta.deletedCategories shouldBe listOf(before.backupCategories.single().uid)
        delta.backup.backupManga.single().favorite shouldBe false
        delta.backup.backupManga.single().version shouldBe 2000L
    }

    @Test
    fun `acknowledgement keeps only accepted versions and remaps category orders`() {
        val previous = library(page = 7, order = 4)
        val retry = library(page = 3, order = 0)
        val server = library(page = 11, order = 8)
        val acknowledged = acknowledgedSnapshot(previous, retry, server, full = false)
        val manga = acknowledged.backupManga.single()
        manga.chapters.single().lastPageRead shouldBe 11L
        acknowledged.categoryNames(manga) shouldBe setOf("Reading")
        manga.categories shouldBe listOf(8L)
    }

    @Test
    fun `sync backup stream retains versions and an explicit unfavorite`() {
        val backup = library(page = 19, order = 0)
        backup.backupManga.single().favorite = false
        backup.backupManga.single().version = 1200
        val buffer = Buffer()
        writeSyncBackup(buffer, ProtoBuf, backup)

        val entry = readSyncBackup(buffer, ProtoBuf).backupManga.single()
        entry.favorite shouldBe false
        entry.version shouldBe 1200L
        entry.chapters.single().lastPageRead shouldBe 19L
    }

    private fun library(page: Long, order: Long) = Backup(
        backupManga = listOf(
            BackupManga(
                source = 123,
                url = "/comic",
                categories = listOf(order),
                chapters = listOf(BackupChapter(url = "/chapter", lastPageRead = page)),
            ),
        ),
        backupCategories = listOf(BackupCategory(name = "Reading", order = order)),
    )
}
