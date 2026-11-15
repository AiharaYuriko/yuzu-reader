package eu.kanade.tachiyomi.data.sync

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.pm.PackageInfoCompat
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication
import com.microsoft.identity.client.Prompt
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Inject
@SingleIn(AppScope::class)
class OneDriveAuthorization internal constructor(private val context: Context, private val drive: OneDriveClient) {
    private val clientLock = Mutex()
    private var client: Pair<String, IMultipleAccountPublicClientApplication>? = null

    suspend fun connect(activity: Activity, clientId: String): OneDriveAccount? {
        val application = application(clientId)
        val result = suspendCancellableCoroutine<IAuthenticationResult?> { continuation ->
            application.acquireToken(
                AcquireTokenParameters.Builder()
                    .startAuthorizationFromActivity(activity)
                    .withScopes(scopes)
                    .withPrompt(Prompt.SELECT_ACCOUNT)
                    .withCallback(
                        object : AuthenticationCallback {
                            override fun onSuccess(result: IAuthenticationResult) = continuation.resume(result)
                            override fun onError(error: MsalException) = continuation.resumeWithException(error)
                            override fun onCancel() = continuation.resume(null)
                        },
                    )
                    .build(),
            )
        } ?: return null
        withContext(Dispatchers.IO) { drive.appFolder(result.accessToken) }
        return OneDriveAccount(clientId, result.account.id, result.account.username.orEmpty())
    }

    suspend fun accessToken(target: OneDriveAccount, forceRefresh: Boolean = false): String {
        val application = application(target.clientId)
        return runInterruptible(Dispatchers.IO) {
            try {
                val account = application.getAccount(target.id)
                    ?.takeIf { it.id == target.id } ?: throw OneDriveAuthorizationRequired()
                val result = application.acquireTokenSilent(
                    AcquireTokenSilentParameters.Builder()
                        .forAccount(account)
                        .fromAuthority(AUTHORITY)
                        .withScopes(scopes)
                        .forceRefresh(forceRefresh)
                        .build(),
                )
                if (result.account.id != target.id) throw OneDriveAuthorizationRequired()
                result.accessToken
            } catch (error: MsalUiRequiredException) {
                throw OneDriveAuthorizationRequired(error)
            }
        }
    }

    suspend fun disconnect(target: OneDriveAccount) {
        val application = application(target.clientId)
        runInterruptible(Dispatchers.IO) {
            application.getAccount(target.id)?.takeIf { it.id == target.id }?.let(application::removeAccount)
        }
    }

    private suspend fun application(clientId: String): IMultipleAccountPublicClientApplication = clientLock.withLock {
        require(parseClientId(clientId) == clientId)
        client?.takeIf { it.first == clientId }?.let { return@withLock it.second }
        val application = runInterruptible(Dispatchers.IO) {
            val packageName = context.packageName
            val signature = PackageInfoCompat.getSignatures(context.packageManager, packageName).first()
            val hash = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
            val redirect = Uri.Builder().scheme("msauth").authority(packageName)
                .appendPath(Base64.encodeToString(hash, Base64.NO_WRAP)).build()
            val config = """
                {
                  "client_id": ${JSONObject.quote(clientId)},
                  "redirect_uri": ${JSONObject.quote(redirect.toString())},
                  "authorization_user_agent": "BROWSER",
                  "broker_redirect_uri_registered": false,
                  "account_mode": "MULTIPLE",
                  "authorities": [
                    {"type": "AAD", "audience": {"type": "PersonalMicrosoftAccount"}, "default": true}
                  ]
                }
            """.trimIndent()
            val file = File.createTempFile("yuzu-msal-", ".json", context.cacheDir)
            try {
                file.writeText(config)
                PublicClientApplication.createMultipleAccountPublicClientApplication(context, file)
            } finally {
                file.delete()
            }
        }
        client = clientId to application
        application
    }

    companion object {
        private const val AUTHORITY = "https://login.microsoftonline.com/consumers"
        private val scopes = listOf("https://graph.microsoft.com/Files.ReadWrite.AppFolder")
        private val guid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun parseClientId(value: String): String? = value.trim().lowercase().takeIf(guid::matches)
    }
}

internal class OneDriveAuthorizationRequired(cause: Throwable? = null) :
    IllegalStateException("OneDrive authorization required", cause)
