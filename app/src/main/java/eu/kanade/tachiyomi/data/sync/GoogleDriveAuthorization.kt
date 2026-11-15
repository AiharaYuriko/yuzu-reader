package eu.kanade.tachiyomi.data.sync

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Inject
@SingleIn(AppScope::class)
class GoogleDriveAuthorization(private val context: Context, private val drive: GoogleDriveClient) {
    fun availability() = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)

    suspend fun authorize(email: String? = null): AuthorizationResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(Scopes.DRIVE_APPFOLDER)))
            .apply { email?.let { setAccount(Account(it, "com.google")) } }
            .build()
        return Identity.getAuthorizationClient(context).authorize(request).awaitTask()
    }

    fun resultFromIntent(intent: Intent) = Identity.getAuthorizationClient(
        context,
    ).getAuthorizationResultFromIntent(intent)

    suspend fun connectedAccount(result: AuthorizationResult): DriveAccount =
        withContext(Dispatchers.IO) { drive.account(requireDriveToken(result)) }

    internal suspend fun accessToken(account: DriveAccount): String = requireDriveToken(authorize(account.email))

    internal suspend fun clearToken(token: String) {
        Identity.getAuthorizationClient(context)
            .clearToken(ClearTokenRequest.builder().setToken(token).build())
            .awaitTask()
    }

    private fun requireDriveToken(result: AuthorizationResult): String {
        val token = result.accessToken
        if (result.hasResolution() || token.isNullOrBlank() || Scopes.DRIVE_APPFOLDER !in result.grantedScopes) {
            throw DriveAuthorizationRequired()
        }
        return token
    }
}

internal class DriveAuthorizationRequired : IllegalStateException("Google Drive authorization required")

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        when {
            task.isCanceled -> continuation.cancel()
            task.isSuccessful -> continuation.resume(task.result)
            else -> continuation.resumeWithException(
                task.exception ?: IllegalStateException("Google Play services failed"),
            )
        }
    }
}
