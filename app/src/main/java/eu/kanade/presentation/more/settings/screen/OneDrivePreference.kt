package eu.kanade.presentation.more.settings.screen

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.microsoft.identity.client.exception.MsalException
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.sync.LibrarySyncWorker
import eu.kanade.tachiyomi.data.sync.OneDriveAuthorization
import eu.kanade.tachiyomi.data.sync.OneDriveHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

internal data class OneDrivePreference(val preference: Preference.PreferenceItem.TextPreference, val connected: Boolean)

@Composable
internal fun oneDrivePreference(status: String): OneDrivePreference {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val sync = context.appGraph.syncPreferences
    val authorization = context.appGraph.oneDriveAuthorization
    val credentials by sync.oneDriveCredentials.collectAsState()
    val account = remember(credentials) { sync.oneDriveAccount() }
    val scope = rememberCoroutineScope()
    var manageAccount by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    fun updateConnection(block: suspend () -> Unit) {
        working = true
        scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = when (error) {
                    is MsalException -> context.stringResource(
                        MR.strings.sync_onedrive_setup_error,
                        error.errorCode.orEmpty(),
                    )
                    is OneDriveHttpException -> when (error.status) {
                        401 -> context.stringResource(MR.strings.sync_onedrive_reconnect)
                        403 -> context.stringResource(MR.strings.sync_onedrive_denied)
                        else -> context.stringResource(MR.strings.sync_onedrive_http_error, error.status)
                    }
                    else -> context.stringResource(MR.strings.sync_status_connection_error)
                }
                sync.status.set(message)
            } finally {
                working = false
            }
        }
    }

    fun signIn() {
        val hostActivity = activity ?: return
        sync.status.delete()
        updateConnection {
            val connected = authorization.connect(
                hostActivity,
                account?.clientId ?: OneDriveAuthorization.DEFAULT_CLIENT_ID,
            )
            if (connected != null) {
                sync.oneDriveCredentials.set("${connected.clientId}\n${connected.id}\n${connected.name}")
                manageAccount = false
                LibrarySyncWorker.enqueue(context)
            }
        }
    }

    if (manageAccount && account != null) {
        AlertDialog(
            onDismissRequest = { if (!working) manageAccount = false },
            title = { Text(stringResource(MR.strings.sync_onedrive_account)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(account.name.ifBlank { account.id })
                    if (status.isNotBlank()) Text(status)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = ::signIn,
                    enabled = activity != null && !working,
                ) {
                    Text(stringResource(MR.strings.sync_onedrive_sign_in))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        updateConnection {
                            authorization.disconnect(account)
                            manageAccount = false
                            sync.oneDriveCredentials.delete()
                            sync.status.delete()
                        }
                    },
                    enabled = !working,
                ) {
                    Text(stringResource(MR.strings.sync_disconnect))
                }
            },
        )
    }

    return OneDrivePreference(
        preference = Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.sync_onedrive_account),
            subtitle = account?.name?.ifBlank { account.id } ?: status.ifBlank {
                stringResource(MR.strings.sync_onedrive_sign_in)
            },
            onClick = {
                if (!working) {
                    if (account == null) signIn() else manageAccount = true
                }
            },
        ),
        connected = account != null,
    )
}
