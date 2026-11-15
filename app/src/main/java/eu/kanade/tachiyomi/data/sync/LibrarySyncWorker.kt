package eu.kanade.tachiyomi.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreWorker
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.IOException
import java.text.DateFormat
import java.util.Date

class LibrarySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @Inject internal lateinit var librarySync: LibrarySync

    override suspend fun doWork(): Result {
        val graph = applicationContext.appGraph
        val preferences = graph.syncPreferences
        val target = preferences.target() ?: return Result.success()
        if (BackupRestoreWorker.isRunning(applicationContext.workManager)) return Result.retry()
        graph.inject(this)

        preferences.status.set(applicationContext.stringResource(MR.strings.sync_status_running))
        return try {
            withContext(Dispatchers.IO) { librarySync.sync(target) }
            if (preferences.target() == target) {
                val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
                preferences.status.set(applicationContext.stringResource(MR.strings.sync_status_success, time))
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (preferences.target() != target) return Result.success()
            val message = when (e) {
                is SyncHttpException -> when (e.status) {
                    401, 403 -> applicationContext.stringResource(MR.strings.sync_status_denied)
                    404 -> applicationContext.stringResource(MR.strings.sync_status_version)
                    else -> applicationContext.stringResource(MR.strings.sync_status_http_error, e.status)
                }
                is DriveAuthorizationRequired -> applicationContext.stringResource(MR.strings.sync_drive_reconnect)
                is DriveHttpException -> when (e.status) {
                    401 -> applicationContext.stringResource(MR.strings.sync_drive_reconnect)
                    403 -> applicationContext.stringResource(MR.strings.sync_drive_denied)
                    else -> applicationContext.stringResource(MR.strings.sync_drive_http_error, e.status)
                }
                is OneDriveAuthorizationRequired ->
                    applicationContext.stringResource(MR.strings.sync_onedrive_reconnect)
                is OneDriveHttpException -> when (e.status) {
                    401 -> applicationContext.stringResource(MR.strings.sync_onedrive_reconnect)
                    403 -> applicationContext.stringResource(MR.strings.sync_onedrive_denied)
                    else -> applicationContext.stringResource(MR.strings.sync_onedrive_http_error, e.status)
                }
                is com.microsoft.identity.client.exception.MsalException ->
                    applicationContext.stringResource(MR.strings.sync_onedrive_setup_error, e.errorCode.orEmpty())
                is com.google.android.gms.common.api.ApiException ->
                    applicationContext.stringResource(MR.strings.sync_drive_setup_error, e.statusCode)
                is IOException -> applicationContext.stringResource(MR.strings.sync_status_connection_error)
                else -> applicationContext.stringResource(MR.strings.sync_status_data_error)
            }
            preferences.status.set(message)
            val retry = when (e) {
                is SyncHttpException -> false
                is DriveHttpException -> e.status == 401 || e.status == 429 || e.status >= 500
                is OneDriveHttpException -> e.status == 401 || e.status == 429 || e.status >= 500
                is IOException -> true
                else -> false
            }
            if (retry && runAttemptCount == 0) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        fun enqueue(context: Context) {
            if (context.appGraph.syncPreferences.target() == null) return
            val request = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            context.workManager.enqueueUniqueWork("LibrarySync", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
