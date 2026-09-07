package dev.po4yka.chur.app.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.LocalChurColors

/**
 * The bootstrap form of `SYNC_PROTOCOL_V1.md` §6.
 *
 * It is the whole first-run flow of sync: a server address and the operator's
 * bootstrap secret, both typed once. The secret is shown as it is typed
 * nowhere — it is a credential that enrolls this device — and both fields are
 * cleared by the caller's guarded reset rather than kept in state.
 */
@Composable
internal fun SyncSetupCard(onConfigure: (serverUrl: String, bootstrapSecret: String) -> Unit) {
    val colors = LocalChurColors.current
    var serverUrl by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(ChurSpacing.three),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.two),
        ) {
            Text(
                "Keep this vault current on your other devices by connecting " +
                    "it to your own server.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                singleLine = true,
                label = { Text("Server address") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                singleLine = true,
                label = { Text("Bootstrap secret") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onConfigure(serverUrl, secret) },
                enabled = serverUrl.isNotBlank() && secret.isNotBlank(),
            ) {
                Text("Connect")
            }
            Text(
                "The address is the https address of your server. The secret " +
                    "comes from whoever runs it, and it is used once to enroll " +
                    "this device.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
            )
        }
    }
}
