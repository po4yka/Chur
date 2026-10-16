package dev.po4yka.chur.app.publicshell

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.theme.ChurSpacing
import dev.po4yka.chur.app.theme.LocalChurColors
import dev.po4yka.chur.app.theme.SettingsGlyph

data class PublicCameraPhoto(val id: String, val label: String)

/** Public capture and review controls. Platform hosts own capture files and publication. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    preview: ImageBitmap?,
    hasCapture: Boolean,
    busy: Boolean,
    message: String?,
    showFirstWriteDisclosure: Boolean,
    onAcknowledgeDisclosure: () -> Unit,
    onCapture: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    photos: List<PublicCameraPhoto> = emptyList(),
    selectedPhotoId: String? = null,
    onOpenPhoto: (String) -> Unit = {},
    onClosePhoto: () -> Unit = {},
    onDeletePhoto: (String) -> Unit = {},
) {
    val colors = LocalChurColors.current
    var confirmingPublicCapture by remember { mutableStateOf(false) }
    var deletingPhoto by remember { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = modifier,
        containerColor = colors.canvas,
        topBar = {
            TopAppBar(title = { Text("Camera") }, actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(SettingsGlyph, contentDescription = "Settings")
                }
            })
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(ChurSpacing.gutter),
            verticalArrangement = Arrangement.spacedBy(ChurSpacing.three),
        ) {
            Text(PUBLIC_CAMERA_DISCLOSURE, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
            if (preview != null) Image(
                bitmap = preview,
                contentDescription = "Public photo preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
            )
            if (busy) Box(Modifier.fillMaxWidth().padding(ChurSpacing.three), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            message?.let {
                Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (hasCapture) {
                Button(onClick = onSave, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save photo") }
                OutlinedButton(onClick = onDiscard, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Discard photo") }
            } else {
                if (selectedPhotoId != null) {
                    OutlinedButton(onClick = onClosePhoto, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Close photo") }
                    OutlinedButton(onClick = { deletingPhoto = selectedPhotoId }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Delete photo") }
                }
                Button(
                    onClick = {
                        if (showFirstWriteDisclosure) confirmingPublicCapture = true else onCapture()
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Take photo") }
            }
            Text("Public album (${photos.size})", style = MaterialTheme.typography.titleMedium)
            if (photos.isEmpty()) Text("Saved photos will appear here.", color = colors.inkMuted)
            photos.forEach { photo ->
                Card(
                    onClick = { onOpenPhoto(photo.id) },
                    enabled = !busy && !hasCapture,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(photo.label, modifier = Modifier.padding(ChurSpacing.three))
                }
            }
        }
    }
    if (confirmingPublicCapture) AlertDialog(
        onDismissRequest = { confirmingPublicCapture = false },
        title = { Text("Public camera") },
        text = {
            Text(
                PUBLIC_CAMERA_DISCLOSURE + " The private vault is the protected place; open it from Settings.",
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                confirmingPublicCapture = false
                onAcknowledgeDisclosure()
                onCapture()
            }) { Text("Continue") }
        },
        dismissButton = {
            TextButton(onClick = { confirmingPublicCapture = false }) { Text("Cancel") }
        },
    )
    deletingPhoto?.let { id ->
        AlertDialog(
            onDismissRequest = { deletingPhoto = null },
            title = { Text("Delete public photo?") },
            text = { Text("This removes the photo from the public album on this device.") },
            confirmButton = {
                TextButton(onClick = { deletingPhoto = null; onDeletePhoto(id) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingPhoto = null }) { Text("Cancel") } },
        )
    }
}

const val PUBLIC_CAMERA_DISCLOSURE =
    "Camera photos are not encrypted by Chur. Saved photos stay in Chur's public album " +
        "and are included in the system backup. You choose whether to save each photo."
