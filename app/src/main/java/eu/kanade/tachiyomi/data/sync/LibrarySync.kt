package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.backupCategoryMapper
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.encodeUtf8
import okio.buffer
import okio.sink
import okio.source
import tachiyomi.data.Database
import tachiyomi.domain.category.model.NewCategory
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlin.time.Clock

@Inject
internal class LibrarySync(
    private val context: Context,
    private val preferences: SyncPreferences,
    private val client: SyncYomiClient,
    private val googleDrive: GoogleDriveClient,
    private val driveAuthorization: GoogleDriveAuthorization,
    private val oneDrive: OneDriveClient,
    private val oneDriveAuthorization: OneDriveAuthorization,
    private val parser: ProtoBuf,
    private val database: Database,
    private val mangaRepository: MangaRepository,
    private val categoryRepository: CategoryRepository,
    private val chapterRepository: ChapterRepository,
    private val mangaBackupCreator: MangaBackupCreator,
    private val mangaRestorer: MangaRestorer,
) {
    suspend fun sync(target: SyncTarget) {
        val deviceId = preferences.deviceId.get().ifBlank {
            UUID.randomUUID().toString().also(preferences.deviceId::set)
        }
        when (target) {
            is SyncAccount -> {
                val fileKey = "${target.url.toString().trimEnd('/')}\n${target.key}".encodeUtf8().sha256().hex()
                synchronize(target, "syncyomi-$fileKey.bin") { cursor, full, upload ->
                    client.merge(target, deviceId, Build.MODEL, cursor, full, upload)
                }
            }
            is DriveAccount -> {
                val fileKey = target.id.encodeUtf8().sha256().hex()
                val token = driveAuthorization.accessToken(target)
                try {
                    val session = googleDrive.open(token, deviceId)
                    synchronizeCloud(target, "yuzu-drive-$fileKey.bin", session)
                } catch (e: DriveHttpException) {
                    if (e.status == 401) driveAuthorization.clearToken(token)
                    throw e
                }
            }
            is OneDriveAccount -> {
                val fileKey = "${target.clientId}\n${target.id}".encodeUtf8().sha256().hex()
                val token = oneDriveAuthorization.accessToken(target)
                val session = try {
                    oneDrive.open(token, deviceId)
                } catch (e: OneDriveHttpException) {
                    if (e.status != 401) throw e
                    oneDrive.open(oneDriveAuthorization.accessToken(target, forceRefresh = true), deviceId)
                }
                synchronizeCloud(target, "yuzu-onedrive-$fileKey.bin", session)
            }
        }
    }

    private suspend fun synchronizeCloud(target: CloudSyncTarget, filename: String, session: CloudSyncSession) {
        synchronize(target, filename, emptyRemote = !session.hasRemoteFiles) { _, _, upload ->
            session.merge(upload)
        }
    }

    private suspend fun synchronize(
        target: SyncTarget,
        filename: String,
        emptyRemote: Boolean = false,
        exchange: suspend (cursor: Long, full: Boolean, upload: SyncUpload) -> SyncResponse,
    ) {
        val stateFile = AtomicFile(File(context.noBackupFilesDir, filename))
        var state = if (emptyRemote) SyncState(0, true, Backup()) else readState(stateFile)
        if (state.cursor == 0L) {
            // Start by fetching the server's existing progress. A second device may
            // still have an older copy of the same title from a manual backup.
            val before = database.transactionWithResult { snapshot() }
            val remote = exchange(0, true, SyncUpload(Backup(), emptyList()))
            if (preferences.target() != target) return
            database.transaction { applyRemote(before, remote.backup, initial = true) }
            state = SyncState(
                remote.cursor,
                remote.fullRequested,
                acknowledgedSnapshot(Backup(), Backup(), remote.backup, full = true),
            )
            writeState(stateFile, state)
        }

        val before = database.transactionWithResult { snapshot() }
        val full = state.cursor == 0L || state.fullRequested
        val upload = prepareSyncUpload(before, state.backup, full, Clock.System.now().toEpochMilliseconds())
        if (preferences.target() != target) return
        val remote = exchange(state.cursor, full, upload)
        if (preferences.target() != target) return
        val cloud = target is CloudSyncTarget
        val changes = if (cloud) remote.backup.driveChangesSince(state.backup) else remote.backup
        database.transaction { applyRemote(before, changes, initial = false) }
        writeState(
            stateFile,
            SyncState(
                remote.cursor,
                remote.fullRequested,
                acknowledgedSnapshot(state.backup, upload.backup, remote.backup, full || cloud),
            ),
        )
    }

    private suspend fun snapshot(): Backup {
        val manga = mangaRepository.getFavorites() + mangaRepository.getReadMangaNotInLibrary()
        val categories = categoryRepository.getAll()
            .filter { !it.isSystemCategory }
            .map(backupCategoryMapper)
        return Backup(mangaBackupCreator(manga, backupOptions).toList(), categories)
    }

    private suspend fun applyRemote(before: Backup, remote: Backup, initial: Boolean) {
        val categoriesBefore = before.backupCategories.associateBy { it.name }
        val categoriesFromServer = remote.backupCategories.sortedBy { it.order }.associateBy { it.name }
        val currentCategories = categoryRepository.getAll().filter { !it.isSystemCategory }
        val currentByName = currentCategories.associateBy { it.name }
        categoryRepository.insertAll(
            categoriesFromServer.values.filter { it.name !in currentByName && it.name !in categoriesBefore }
                .map { NewCategory(name = it.name, flags = it.flags) },
        )
        currentCategories.forEach { category ->
            val incoming = categoriesFromServer[category.name]
            val previous = categoriesBefore[category.name]
            if (!initial && previous != null && category.flags == previous.flags) {
                if (incoming == null) {
                    categoryRepository.delete(category.id)
                } else if (incoming.flags != category.flags) {
                    categoryRepository.updateFlags(category.id, incoming.flags)
                }
            }
        }
        if (remote.backupManga.isEmpty()) return

        val entriesBefore = before.backupManga.associateBy { it.key }
        val currentManga = remote.backupManga.mapNotNull { mangaRepository.getMangaByUrlAndSourceId(it.url, it.source) }
        val current = Backup(
            mangaBackupCreator(currentManga, backupOptions).toList(),
            currentCategories.map(backupCategoryMapper),
        )
        val entriesNow = current.backupManga.associateBy { it.key }
        val categoryIds = categoryRepository.getAll().filter { !it.isSystemCategory }.associate { it.name to it.id }

        remote.backupManga.chunked(100).forEach { entries ->
            // Ordinary backup restoration supplies missing manga, chapter metadata and history.
            mangaRestorer.restore(entries, remote.backupCategories)
            entries.forEach entry@{ incoming ->
                val previous = entriesBefore[incoming.key]
                val local = entriesNow[incoming.key]
                val manga = mangaRepository.getMangaByUrlAndSourceId(incoming.url, incoming.source) ?: return@entry
                val changedFavorite = (local?.favorite == true) != (previous?.favorite == true)
                val favorite = when {
                    changedFavorite -> local?.favorite == true
                    initial -> local?.favorite == true || incoming.favorite
                    else -> incoming.favorite
                }
                if (favorite != manga.favorite) {
                    val date = (if (changedFavorite) local else incoming)?.getMangaImpl()?.favoriteAt
                    check(mangaRepository.update(MangaUpdate(manga.id) { favoriteAt = if (favorite) date else null }))
                }

                val localNames = local?.let { current.categoryNames(it) }.orEmpty()
                val previousNames = previous?.let { before.categoryNames(it) }.orEmpty()
                val remoteNames = remote.categoryNames(incoming)
                val names = when {
                    localNames != previousNames -> localNames
                    initial -> localNames + remoteNames
                    else -> remoteNames
                }
                val ids = names.mapNotNull { categoryIds[it] }
                if (categoryRepository.getCategoriesByMangaId(manga.id).map { it.id }.toSet() != ids.toSet()) {
                    mangaRepository.setMangaCategories(manga.id, ids)
                }
                applyProgress(manga.id, incoming, previous, local, initial)
            }
        }
    }

    private suspend fun applyProgress(
        mangaId: Long,
        incoming: BackupManga,
        previous: BackupManga?,
        local: BackupManga?,
        initial: Boolean,
    ) {
        if (incoming.chapters.isEmpty()) return
        val dbChapters = chapterRepository.getChapterByMangaId(mangaId).associateBy { it.url }
        val localChapters = local?.chapters.orEmpty().associateBy { it.url }
        val previousChapters = previous?.chapters.orEmpty().associateBy { it.url }
        val updates = incoming.chapters.mapNotNull { remote ->
            val chapter = dbChapters[remote.url] ?: return@mapNotNull null
            val localChapter = localChapters[remote.url]
            val remoteProgress = remote.progress()
            val localProgress = localChapter?.progress()
            val (read, bookmark, page) = when {
                localProgress == null -> remoteProgress
                localProgress != previousChapters[remote.url]?.progress() -> localProgress
                initial -> Triple(
                    localChapter.read || remote.read,
                    localChapter.bookmark || remote.bookmark,
                    maxOf(localChapter.lastPageRead, remote.lastPageRead),
                )
                else -> remoteProgress
            }
            val pageIndex = page.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            if (chapter.read == read && chapter.bookmark == bookmark && chapter.lastPageRead == pageIndex) {
                return@mapNotNull null
            }
            ChapterUpdate(chapter.id) {
                this.read = read
                this.bookmark = bookmark
                this.lastPageRead = pageIndex
            }
        }
        if (updates.isNotEmpty()) chapterRepository.updateAll(updates)
    }

    private fun readState(file: AtomicFile): SyncState {
        return try {
            file.openRead().source().buffer().use { source ->
                val cursor = source.readLong()
                val fullRequested = source.readByte().toInt() != 0
                SyncState(cursor, fullRequested, readSyncBackup(source, parser))
            }
        } catch (_: FileNotFoundException) {
            SyncState(cursor = 0, fullRequested = true, backup = Backup())
        }
    }

    private fun writeState(file: AtomicFile, state: SyncState) {
        val stream = file.startWrite()
        try {
            val sink = stream.sink().buffer()
            sink.writeLong(state.cursor)
            sink.writeByte(if (state.fullRequested) 1 else 0)
            writeSyncBackup(sink, parser, state.backup)
            sink.flush()
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    private data class SyncState(val cursor: Long, val fullRequested: Boolean, val backup: Backup)

    private companion object {
        val backupOptions = BackupOptions(
            tracking = false,
            appSettings = false,
            extensionStores = false,
            sourceSettings = false,
        )
    }
}
