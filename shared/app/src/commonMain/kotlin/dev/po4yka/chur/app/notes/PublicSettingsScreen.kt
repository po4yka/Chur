package dev.po4yka.chur.app.notes

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.PublicShell
import dev.po4yka.chur.app.publicshell.*
import dev.po4yka.chur.app.theme.BackGlyph

/** Ordinary utility settings. Recovery still uses the normal authentication gate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicSettingsScreen(
    onBack: () -> Unit,
    onOpenVault: () -> Unit,
    selectedShell: PublicShell = PublicShell.NOTES,
    onSelectShell: (PublicShell) -> Unit = {},
    error: String? = null,
    onEncryptExternalFiles: (() -> Unit)? = null,
    discreet: Boolean = false,
) {
    val store = LocalPublicUtilities.current
    val appearance by store.appearance.collectAsState()
    var failure by remember { mutableStateOf<String?>(null) }
    var about by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text(utilityText("Settings", "Настройки")) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(BackGlyph, utilityText("Back", "Назад")) }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(utilityText("Appearance", "Оформление"), style = MaterialTheme.typography.titleMedium)
            listOf("system" to utilityText("System", "Системная"), "light" to utilityText("Light", "Светлая"), "dark" to utilityText("Dark", "Тёмная")).forEach { (key, label) ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = appearance == key, onClick = {
                        try { store.setAppearance(key); failure = null } catch (_: Exception) { failure = utilityText("Settings could not be saved.", "Не удалось сохранить настройки.") }
                    })
                    Text(label)
                }
            }
            HorizontalDivider()
            Text(utilityText("Data storage", "Хранение данных"), style = MaterialTheme.typography.titleMedium)
            Text(utilityText(Disclosure.SETTINGS, "Заметки, история вычислений, фото и видео не зашифрованы приложением. Их может просматривать любой, у кого есть доступ к разблокированному устройству. Эти данные входят в системную резервную копию."))
            if (!discreet) {
                // Existing installations keep their entry until the user tests and enables discreet mode.
                TextButton(onClick = onOpenVault) { Text(utilityText(Disclosure.VAULT_ENTRY, "Приватное хранилище")) }
            }
            ListItem(headlineContent = { Text(utilityText("About", "О приложении")) },
                modifier = Modifier.combinedClickable(onClick = { about = !about }))
            if (about) {
                Text(utilityText(selectedShell.label, when (selectedShell) { PublicShell.NOTES -> "Заметки"; PublicShell.CALCULATOR -> "Калькулятор"; PublicShell.CAMERA -> "Камера" }), style = MaterialTheme.typography.titleMedium)
                Text(utilityText("Version ", "Версия ") + PublicPresentation.version, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .combinedClickable(onClick = {}, onLongClickLabel = utilityText("Open access", "Открыть доступ"), onLongClick = onOpenVault)
                    .padding(vertical = 12.dp))
            }
            (failure ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
