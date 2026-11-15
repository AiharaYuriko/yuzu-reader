package eu.kanade.tachiyomi.data.sync

import kotlinx.serialization.protobuf.ProtoBuf
import okio.BufferedSink
import okio.BufferedSource
import java.io.IOException
import kotlin.time.Clock

internal const val CLOUD_SYNC_FILE_PREFIX = "yuzu-sync-v1-"
private const val DRIVE_FORMAT = 0x59555A01
private const val MAX_CATEGORY_DELETIONS = 100_000

internal class CloudSyncSession(
    private var remote: DriveSnapshot,
    val hasRemoteFiles: Boolean,
    private val save: suspend (DriveSnapshot) -> Unit,
) {
    suspend fun merge(upload: SyncUpload): SyncResponse {
        val categories = remote.backup.backupCategories.associateBy { it.uid }
        val updated = upload.backup.backupManga.isNotEmpty() ||
            upload.deletedCategories.isNotEmpty() ||
            upload.backup.backupCategories.any {
                it.version > maxOf(categories[it.uid]?.version ?: 0L, remote.deletedCategories[it.uid] ?: 0L)
            }
        if (updated) {
            val now = Clock.System.now().toEpochMilliseconds()
            val deletions = upload.deletedCategories.associateWith { uid ->
                maxOf(now, (categories[uid]?.version ?: 0L) + 1)
            }
            val merged = mergeDriveSnapshots(listOf(remote, DriveSnapshot(upload.backup, deletions)))
            save(merged)
            remote = merged
        }
        return SyncResponse(remote.backup, cursor = 1, fullRequested = false)
    }
}

internal fun writeDriveSnapshot(sink: BufferedSink, parser: ProtoBuf, snapshot: DriveSnapshot) {
    sink.writeInt(DRIVE_FORMAT)
    sink.writeInt(snapshot.deletedCategories.size)
    snapshot.deletedCategories.forEach { (uid, version) ->
        sink.writeLong(uid)
        sink.writeLong(version)
    }
    writeSyncBackup(sink, parser, snapshot.backup)
}

internal fun readDriveSnapshot(source: BufferedSource, parser: ProtoBuf): DriveSnapshot {
    if (source.readInt() != DRIVE_FORMAT) throw IOException("Unsupported Yuzu Drive sync file")
    val count = source.readInt()
    if (count !in 0..MAX_CATEGORY_DELETIONS) throw IOException("Invalid category count")
    val deletions = buildMap {
        repeat(count) { put(source.readLong(), source.readLong()) }
    }
    return DriveSnapshot(readSyncBackup(source, parser), deletions)
}
