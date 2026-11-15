package eu.kanade.tachiyomi.data.sync

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID

@OptIn(ExperimentalSerializationApi::class)
@EnabledIfEnvironmentVariable(named = "MIHON_SYNC_TEST_URL", matches = ".+")
class SyncYomiClientTest {
    @Test
    fun `two clients exchange reading progress and removals over SyncYomi v2`() = runBlocking<Unit> {
        val url = requireNotNull(System.getenv("MIHON_SYNC_TEST_URL"))
        val apiKey = requireNotNull(System.getenv("MIHON_SYNC_TEST_KEY"))
        val account = requireNotNull(SyncAccount.parse(url, apiKey))
        val phone = UUID.randomUUID().toString()
        val tablet = UUID.randomUUID().toString()
        val client = SyncYomiClient(ProtoBuf)
        val title = "/sync-test/${UUID.randomUUID()}"
        val category = "Reading ${UUID.randomUUID()}"
        val empty = SyncUpload(Backup(), emptyList())
        val bootstrap = client.merge(account, phone, "Phone", 0, true, empty)
        bootstrap.backup.backupManga.none { it.url == title } shouldBe true
        val visible = Backup(
            backupManga = listOf(
                BackupManga(
                    source = 123,
                    url = title,
                    title = "Sync test",
                    categories = listOf(0),
                    chapters = listOf(BackupChapter(url = "/chapter", name = "Chapter", lastPageRead = 5)),
                ),
            ),
            backupCategories = listOf(BackupCategory(name = category)),
        )
        val firstUpload = prepareSyncUpload(visible, bootstrap.backup, true, 1000)
        val first = client.merge(account, phone, "Phone", bootstrap.cursor, true, firstUpload)
        val blank = client.merge(account, tablet, "Tablet", 0, true, empty)
        val tabletManga = blank.backup.backupManga.single { it.url == title }
        tabletManga.chapters.single().lastPageRead shouldBe 5L
        blank.backup.categoryNames(tabletManga) shouldBe setOf(category)

        visible.backupManga.single().chapters.single().apply {
            lastPageRead = 12
            read = true
        }
        visible.backupManga.single().history = listOf(BackupHistory("/chapter", lastRead = 123456))
        val progress = prepareSyncUpload(visible, blank.backup, false, 2000)
        val fromTablet = client.merge(account, tablet, "Tablet", blank.cursor, false, progress)
        val fromPhone = client.merge(account, phone, "Phone", first.cursor, false, empty)
        val phoneManga = fromPhone.backup.backupManga.single { it.url == title }
        phoneManga.chapters.single().lastPageRead shouldBe 12L
        phoneManga.chapters.single().read shouldBe true
        phoneManga.history.single().lastRead shouldBe 123456L

        val updated = acknowledgedSnapshot(blank.backup, progress.backup, fromTablet.backup, false)
        val removed = prepareSyncUpload(Backup(), updated, false, 3000)
        client.merge(account, tablet, "Tablet", fromTablet.cursor, false, removed)
        val deletion = client.merge(account, phone, "Phone", fromPhone.cursor, false, empty)
        deletion.backup.backupManga.single { it.url == title }.favorite shouldBe false
        deletion.backup.backupCategories.none { it.name == category } shouldBe true
    }
}
