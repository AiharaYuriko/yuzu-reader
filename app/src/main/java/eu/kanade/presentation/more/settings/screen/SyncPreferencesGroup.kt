package eu.kanade.presentation.more.settings.screen

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.ApiException
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.sync.DriveAuthorizationRequired
import eu.kanade.tachiyomi.data.sync.DriveHttpException
import eu.kanade.tachiyomi.data.sync.LibrarySyncWorker
import eu.kanade.tachiyomi.data.sync.SyncAccount
import eu.kanade.tachiyomi.data.sync.SyncBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
internal fun syncPreferencesGroup(): Preference.PreferenceGroup {
    val context = LocalContext.current
    val sync = context.appGraph.syncPreferences
    val credentials by sync.credentials.collectAsState()
    val driveCredentials by sync.driveCredentials.collectAsState()
    val backendKey by sync.backend.collectAsState()
    val backend = sync.selectedBackend(backendKey)
    val useDrive = backend == SyncBackend.GoogleDrive
    val status by sync.status.collectAsState()
    val account = remember(credentials) { sync.account() }
    val driveAccount = remember(driveCredentials) { sync.driveAccount() }
    val driveAuthorization = context.appGraph.googleDriveAuthorization
    val scope = rememberCoroutineScope()
    var editConnection by remember { mutableStateOf(false) }
    var linkingDrive by remember { mutableStateOf(false) }
    var editDrive by remember { mutableStateOf(false) }

    suspend fun finishDriveConnection(result: AuthorizationResult) {
        val connected = driveAuthorization.connectedAccount(result)
        sync.driveCredentials.set("${connected.email}\n${connected.id}")
        sync.status.delete()
        LibrarySyncWorker.enqueue(context)
    }

    fun connectDrive(block: suspend () -> Unit) {
        scope.launch {
            linkingDrive = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = when (e) {
                    is ApiException -> context.stringResource(MR.strings.sync_drive_setup_error, e.statusCode)
                    is DriveHttpException -> context.stringResource(MR.strings.sync_drive_http_error, e.status)
                    is DriveAuthorizationRequired -> context.stringResource(MR.strings.sync_drive_reconnect)
                    else -> context.stringResource(MR.strings.sync_status_connection_error)
                }
                sync.status.set(message)
            } finally {
                linkingDrive = false
            }
        }
    }

    val driveConsent =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            linkingDrive = false
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                connectDrive { finishDriveConnection(driveAuthorization.resultFromIntent(data)) }
            }
        }
    val startDriveConnection: () -> Unit = {
        if (driveAuthorization.availability() != ConnectionResult.SUCCESS) {
            sync.status.set(context.stringResource(MR.strings.sync_drive_play_services))
        } else {
            connectDrive {
                val result = driveAuthorization.authorize(driveAccount?.email)
                val consent = result.pendingIntent
                if (consent != null) {
                    driveConsent.launch(IntentSenderRequest.Builder(consent).build())
                } else {
                    finishDriveConnection(result)
                }
            }
        }
    }

    if (editDrive && useDrive && driveAccount != null) {
        AlertDialog(
            onDismissRequest = { editDrive = false },
            title = { Text(stringResource(MR.strings.sync_drive_account)) },
            text = { Text(driveAccount.email) },
            confirmButton = {
                TextButton(
                    onClick = {
                        editDrive = false
                        startDriveConnection()
                    },
                ) {
                    Text(stringResource(MR.strings.sync_drive_sign_in_again))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        sync.driveCredentials.delete()
                        sync.status.delete()
                        editDrive = false
                    },
                ) {
                    Text(stringResource(MR.strings.sync_disconnect))
                }
            },
        )
    }

    if (editConnection && backend == SyncBackend.SyncYomi) {
        var server by remember { mutableStateOf(account?.url?.toString().orEmpty()) }
        var key by remember { mutableStateOf(account?.key.orEmpty()) }
        val enteredAccount = SyncAccount.parse(server, key)
        AlertDialog(
            onDismissRequest = { editConnection = false },
            title = { Text(stringResource(MR.strings.sync_connection)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text(stringResource(MR.strings.sync_server)) },
                        placeholder = { Text("https://sync.example.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text(stringResource(MR.strings.sync_api_key)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    if (account != null) {
                        TextButton(
                            onClick = {
                                sync.credentials.delete()
                                sync.status.delete()
                                editConnection = false
                            },
                        ) {
                            Text(stringResource(MR.strings.sync_disconnect))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        enteredAccount?.let {
                            sync.credentials.set("${it.url}\n${it.key}")
                            sync.status.delete()
                            editConnection = false
                        }
                    },
                    enabled = enteredAccount != null,
                ) {
                    Text(stringResource(MR.strings.action_save))
                }
            },
        )
    }

    val oneDrive = if (backend == SyncBackend.OneDrive) oneDrivePreference(status) else null
    return Preference.PreferenceGroup(
        title = stringResource(MR.strings.sync_library),
        preferenceItems = buildList {
            add(
                Preference.PreferenceItem.BasicListPreference(
                    value = backend.key,
                    entries = SyncBackend.entries.associate { it.key to it.label },
                    title = stringResource(MR.strings.sync_backend),
                    onValueChanged = {
                        sync.status.delete()
                        sync.backend.set(it)
                    },
                ),
            )
            if (useDrive) {
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(
                            if (driveAccount == null) MR.strings.sync_drive_sign_in else MR.strings.sync_drive_account,
                        ),
                        subtitle = driveAccount?.email ?: status.ifBlank { null },
                        onClick = {
                            if (!linkingDrive) {
                                if (driveAccount == null) startDriveConnection() else editDrive = true
                            }
                        },
                    ),
                )
            } else if (oneDrive != null) {
                add(oneDrive.preference)
            } else {
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.sync_connection),
                        subtitle = account?.url?.toString() ?: stringResource(MR.strings.sync_connection_hint),
                        onClick = { editConnection = true },
                    ),
                )
            }
            val connected = when (backend) {
                SyncBackend.GoogleDrive -> driveAccount != null
                SyncBackend.OneDrive -> oneDrive?.connected == true
                SyncBackend.SyncYomi -> account != null
            }
            if (connected) {
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sync.onOpen,
                        title = stringResource(MR.strings.sync_on_open),
                    ),
                )
                add(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sync.onRead,
                        title = stringResource(MR.strings.sync_on_read),
                    ),
                )
                add(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.sync_now),
                        subtitle = status.ifBlank { null },
                        onClick = { LibrarySyncWorker.enqueue(context) },
                    ),
                )
            }
        },
    )
}
