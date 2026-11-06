package com.zenlabs.filemanager.screen.main.tab.nfile_tools.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.zenlabs.filemanager.screen.main.tab.files.service.remote.NetworkConnectionModel
import com.zenlabs.filemanager.screen.main.tab.files.service.remote.NetworkConnectionsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Prompts for a password when Android Keystore can no longer decrypt it. */
@Composable
fun CredentialRecoveryDialog(
    connection: NetworkConnectionModel,
    onDismissRequest: () -> Unit,
    onRecovered: (NetworkConnectionModel) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember(connection.id) { mutableStateOf("") }
    var isSaving by remember(connection.id) { mutableStateOf(false) }
    var errorMessage by remember(connection.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismissRequest() },
        title = { Text("Re-enter Server Password") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    "The saved password for ${connection.host}:${connection.port} could not be " +
                        "decrypted. This can happen after a device restore or a system security " +
                        "change. Enter the password again to reconnect."
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                errorMessage?.let {
                    Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isSaving) return@Button
                    isSaving = true
                    errorMessage = null
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            NetworkConnectionsService.updateCredential(context, connection, password)
                        }
                        isSaving = false
                        if (saved) {
                            onRecovered(
                                connection.copy(
                                    password = password,
                                    needsCredentialRecovery = false
                                )
                            )
                        } else {
                            errorMessage = "Unable to secure the password. Please try again."
                        }
                    }
                },
                enabled = !isSaving
            ) {
                Text(if (isSaving) "Saving..." else "Save & Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest, enabled = !isSaving) {
                Text("Cancel")
            }
        }
    )
}
