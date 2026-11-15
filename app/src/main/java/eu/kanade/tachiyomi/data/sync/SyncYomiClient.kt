package eu.kanade.tachiyomi.data.sync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.backup.BackupReader
import eu.kanade.tachiyomi.data.backup.BackupWriter
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.network.await
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.gzip
import java.io.IOException
import java.util.concurrent.TimeUnit

@Inject
@SingleIn(AppScope::class)
internal class SyncYomiClient(private val parser: ProtoBuf) {
    // Redirects can carry a custom authentication header to a different host.
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun merge(
        account: SyncAccount,
        deviceId: String,
        deviceName: String,
        cursor: Long,
        full: Boolean,
        upload: SyncUpload,
    ): SyncResponse {
        val endpoint = account.url.toString().trimEnd('/') + "/api/sync/v2/merge"
        val request = Request.Builder()
            .url(endpoint)
            .header("X-API-Token", account.key)
            .header("X-Device-ID", deviceId)
            .header("X-Device-Name", deviceName.filter { it.code in 32..126 }.ifBlank { "Yuzu" })
            .header("X-Sync-Cursor", cursor.toString())
            .header("X-Sync-Full", full.toString())
            .header("Content-Encoding", "gzip")
            .apply {
                if (upload.deletedCategories.isNotEmpty()) {
                    header("X-Sync-Deleted-Categories", upload.deletedCategories.joinToString(","))
                }
            }
            .post(object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()

                override fun writeTo(sink: BufferedSink) {
                    sink.gzip().buffer().use { writeSyncBackup(it, parser, upload.backup) }
                }
            })
            .build()

        return client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw SyncHttpException(response.code)
            val nextCursor = response.header("X-Sync-Cursor")?.toLongOrNull()
                ?.takeIf { it >= 0 }
                ?: throw IOException("Missing SyncYomi cursor")
            SyncResponse(
                backup = readSyncBackup(response.body.source(), parser),
                cursor = nextCursor,
                fullRequested = response.header("X-Sync-Full-Requested") == "true",
            )
        }
    }
}

internal data class SyncResponse(val backup: Backup, val cursor: Long, val fullRequested: Boolean)

internal class SyncHttpException(val status: Int) : IOException("SyncYomi HTTP $status")

internal fun readSyncBackup(source: BufferedSource, parser: ProtoBuf): Backup {
    val reader = BackupReader(source, parser)
    val manga = buildList {
        while (reader.nextEntry()) add(reader.readEntry())
    }
    return reader.readMetadata().copy(backupManga = manga)
}

internal fun writeSyncBackup(sink: BufferedSink, parser: ProtoBuf, backup: Backup) {
    val writer = BackupWriter(sink, parser)
    backup.backupManga.forEach(writer::writeEntry)
    writer.writeMetadata(backup.copy(backupManga = emptyList()))
}
