package eu.kanade.presentation.more.settings.screen

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
    var edit by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf("") }

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

    if (edit) {
        val enteredId = OneDriveAuthorization.parseClientId(clientId)
        AlertDialog(
            onDismissRequest = { if (!working) edit = false },
            title = { Text(stringResource(MR.strings.sync_onedrive_account)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(MR.strings.sync_onedrive_setup_hint))
                    OutlinedTextField(
                        value = clientId,
                        onValueChange = { clientId = it },
                        label = { Text(stringResource(MR.strings.sync_onedrive_client_id)) },
                        enabled = !working,
                        singleLine = true,
                    )
                    if (status.isNotBlank()) Text(status)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (enteredId != null && activity != null) {
                            sync.status.delete()
                            updateConnection {
                                val connected = authorization.connect(activity, enteredId)
                                if (connected != null) {
                                    sync.oneDriveCredentials.set(
                                        "${connected.clientId}\n${connected.id}\n${connected.name}",
                                    )
                                    edit = false
                                    LibrarySyncWorker.enqueue(context)
                                }
                            }
                        }
                    },
                    enabled = enteredId != null && activity != null && !working,
                ) {
                    Text(stringResource(MR.strings.sync_onedrive_sign_in))
                }
            },
            dismissButton = {
                if (account != null) {
                    TextButton(
                        onClick = {
                            updateConnection {
                                authorization.disconnect(account)
                                sync.oneDriveCredentials.delete()
                                sync.status.delete()
                                edit = false
                            }
                        },
                        enabled = !working,
                    ) {
                        Text(stringResource(MR.strings.sync_disconnect))
                    }
                }
            },
        )
    }

    return OneDrivePreference(
        preference = Preference.PreferenceItem.TextPreference(
            title = stringResource(MR.strings.sync_onedrive_account),
            subtitle = account?.name?.ifBlank { account.id } ?: status.ifBlank {
                stringResource(MR.strings.sync_onedrive_setup_hint)
            },
            onClick = {
                if (!working) {
                    clientId = account?.clientId.orEmpty()
                    edit = true
                }
            },
        ),
        connected = account != null,
    )
}
