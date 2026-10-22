package dev.po4yka.chur.app.publicshell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.po4yka.chur.app.ChurController
import dev.po4yka.chur.app.PublicShell
import dev.po4yka.chur.app.onboarding.guideTarget
import dev.po4yka.chur.app.onboarding.publicShellLabel

/** Mounted only inside the unlocked Settings route; drafts are never saveable. */
@Composable
fun DiscreetSettings(controller: ChurController) {
    val enabled by controller.publicUtilities.discreet.collectAsState()
    val shell by controller.preferredPublicShell.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = controller::startPrivateGuide) { Text(utilityText("Learn with Chur", "Обучение с Чуром")) }
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.guideTarget("discreet")) { Text(utilityText("Public applications", "Публичные приложения")) }
            if (expanded) {
                PublicShell.entries.forEach { option ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = shell == option, onClick = { controller.setPublicShell(option) })
                        Text(publicShellLabel(option))
                    }
                }
                Text(utilityText("The transition code opens authentication. It does not replace your password. Enter it and press = in Calculator, submit it in Notes search, hold the Camera gallery button, or hold the moon in Chur. If you forget it, hold Version in Settings → About. Then authenticate normally.",
                    "Код открывает авторизацию и не заменяет пароль. В калькуляторе введите код и нажмите =; в заметках отправьте код в поиске; в камере удерживайте кнопку галереи; в игре — луну. Если код забыт, удерживайте версию в Настройки → О приложении, затем пройдите обычную авторизацию."))
                if (enabled) TextButton(onClick = {
                    try { controller.disableDiscreetMode(); code = ""; repeated = ""; failure = null } catch (_: Exception) { failure = "Settings could not be saved." }
                }) { Text(utilityText("Disable discreet mode", "Отключить незаметный режим")) }
                OutlinedTextField(code, { code = it.filter { c -> c in '0'..'9' }.take(8) },
                    label = { Text(utilityText("New 8-digit transition code", "Новый код: 8 цифр")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(repeated, { repeated = it.filter { c -> c in '0'..'9' }.take(8) },
                    label = { Text(utilityText("Try the code again", "Повторите код для проверки")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                Text(utilityText("Start a trial, enter the code in the selected app, then authenticate. The mode is enabled only after both steps succeed. Returning through About cancels the trial.",
                    "Начните проверку, введите код в выбранном приложении и пройдите авторизацию. Режим включится только после обоих шагов. Вход через раздел «О приложении» отменяет проверку."))
                Button(enabled = PublicUtilityStore.validCode(code) && repeated == code, onClick = {
                    try { val candidate = code; code = ""; repeated = ""; failure = null; controller.beginDiscreetSetup(candidate) }
                    catch (_: Exception) { failure = utilityText("Setup could not start.", "Не удалось начать настройку.") }
                }) { Text(utilityText("Test entry and enable", "Проверить вход и включить")) }
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
