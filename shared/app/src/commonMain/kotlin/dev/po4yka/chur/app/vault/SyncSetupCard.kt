package dev.po4yka.chur.app.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import dev.po4yka.chur.app.privateKeyboardOptions
import dev.po4yka.chur.app.secretKeyboardOptions
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.LocalChurColors
import dev.po4yka.chur.app.theme.churOutlinedTextFieldColors

/**
 * What the setup form says before the user connects.
 *
 * `DESIGN.md` §27 asks copy to be "precise about local versus external
 * copies", and this is the one statement a user reads before deciding. Media
 * objects leave this device only through sharing publication today, so the
 * limit and the fact that sync is not a backup are stated here, not first
 * after connecting. The server is one the user runs, ADR-0033. It is a
 * constant so a test can hold it to that; it changes when own-device object
 * sync ships.
 */
internal object SyncSetupCopy {
    const val INTRO: String =
        "Connect this vault to a sync server you run. Photos, videos, and audio " +
            "are not copied to your other devices yet. Sync is not a backup."
}

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
            Text(SyncSetupCopy.INTRO, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                colors = churOutlinedTextFieldColors(),
                value = serverUrl,
                onValueChange = { serverUrl = it },
                singleLine = true,
                label = { Text("Server address") },
                placeholder = { Text("https://") },
                keyboardOptions = privateKeyboardOptions(KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                colors = churOutlinedTextFieldColors(),
                value = secret,
                onValueChange = { secret = it },
                singleLine = true,
                label = { Text("Bootstrap secret") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = secretKeyboardOptions(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onConfigure(serverUrl, secret) },
                enabled = serverUrl.isNotBlank() && secret.isNotBlank(),
            ) {
                Text("Connect")
            }
            // The one-line reason for the local network request that
            // connecting can bring, before it comes: Android asks before it
            // connects and iOS as it does, `ANDROID.md` §24 and `IOS.md` §27.
            Text(
                "The address is the https address of your server. The secret " +
                    "comes from whoever runs it, and it is used once to enroll " +
                    "this device. If the server is on your local network, allow " +
                    "local network access when you are asked.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
            )
        }
    }
}
