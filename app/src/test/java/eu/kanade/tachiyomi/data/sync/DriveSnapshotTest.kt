package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import org.junit.jupiter.api.Test

@OptIn(ExperimentalSerializationApi::class)
class DriveSnapshotTest {
    @Test
    fun `merges progress from both devices and keeps concurrent favorites`() {
        val phone = title(
            "/shared",
            version = 20,
            chapters = listOf(chapter("/one", 7, 40), chapter("/two", 2, 10)),
            history = listOf(BackupHistory("/one", 111)),
        )
        val tablet = title(
            "/shared",
            version = 20,
            chapters = listOf(chapter("/one", 1, 10), chapter("/two", 5, 50)),
            history = listOf(BackupHistory("/one", 105), BackupHistory("/two", 122)),
        )
        val merged = mergeDriveSnapshots(
            listOf(
                DriveSnapshot(Backup(listOf(phone, title("/phone-only", 60)))),
                DriveSnapshot(Backup(listOf(tablet, title("/tablet-only", 60)))),
            ),
        ).backup

        merged.backupManga.map { it.url }.toSet() shouldBe setOf("/shared", "/phone-only", "/tablet-only")
        val title = merged.backupManga.single { it.url == "/shared" }
        title.chapters.associate { it.url to it.lastPageRead } shouldBe mapOf("/one" to 7L, "/two" to 5L)
        title.history.associate { it.url to it.lastRead } shouldBe mapOf("/one" to 111L, "/two" to 122L)
    }

    @Test
    fun `a stale device cannot restore a deleted category or favorite`() {
        val category = BackupCategory(name = "Reading", order = 4, uid = 21, version = 20)
        val favorite = title("/comic", version = 20, chapters = listOf(chapter("/one", 9, 10)))
        favorite.categories = listOf(4)
        val removed = title("/comic", version = 40)
        removed.favorite = false
        val merged = mergeDriveSnapshots(
            listOf(
                DriveSnapshot(Backup(listOf(removed)), deletedCategories = mapOf(21L to 40L)),
                DriveSnapshot(Backup(listOf(favorite), listOf(category))),
            ),
        ).backup

        merged.backupCategories shouldBe emptyList()
        val manga = merged.backupManga.single()
        manga.favorite shouldBe false
        manga.categories shouldBe emptyList()
        manga.chapters.single().lastPageRead shouldBe 9L
    }

    @Test
    fun `complete snapshot comparison ignores different category order numbers`() {
        val title = title("/comic", version = 20)
        title.categories = listOf(8)
        val sent = Backup(listOf(title), listOf(BackupCategory(name = "Reading", order = 8, uid = 90, version = 10)))
        val normalized = mergeDriveSnapshots(listOf(DriveSnapshot(sent))).backup

        normalized.driveChangesSince(sent).backupManga shouldBe emptyList()
        normalized.categoryNames(normalized.backupManga.single()) shouldBe setOf("Reading")
    }

    @Test
    fun `drive snapshot file round trip retains chapter versions and removals`() {
        val manga = title("/comic", version = 30, chapters = listOf(chapter("/one", 7, 20)))
        val before = DriveSnapshot(Backup(listOf(manga)), mapOf(4L to 99L))
        val buffer = Buffer()
        writeDriveSnapshot(buffer, ProtoBuf, before)
        val after = readDriveSnapshot(buffer, ProtoBuf)

        after.deletedCategories shouldBe before.deletedCategories
        after.backup.backupManga.single().chapters.single().version shouldBe 20L
        after.backup.backupManga.single().chapters.single().lastPageRead shouldBe 7L
    }

    private fun title(
        url: String,
        version: Long,
        chapters: List<BackupChapter> = emptyList(),
        history: List<BackupHistory> = emptyList(),
    ) = BackupManga(source = 123, url = url, version = version, chapters = chapters, history = history)

    private fun chapter(url: String, page: Long, version: Long) = BackupChapter(
        url = url,
        name = url,
        lastPageRead = page,
        version = version,
    )
}
