package dev.po4yka.chur.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

internal object PublicLauncherPresentation {
    private val names = listOf("DefaultLauncher", "NotesLauncher", "CalculatorLauncher", "CameraLauncher")
    fun enabled(context: Context): Boolean = component(context).className.substringAfterLast('.') != "DefaultLauncher"
    fun component(context: Context): ComponentName = names.drop(1).map { ComponentName(context.packageName, "${MainActivity::class.java.packageName}.$it") }
        .firstOrNull { context.packageManager.getComponentEnabledSetting(it) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
        ?: ComponentName(context.packageName, "${MainActivity::class.java.packageName}.DefaultLauncher")
    fun apply(context: Context, enabled: Boolean, shell: String) {
        val selected = if (!enabled) "DefaultLauncher" else when (shell) { "calculator" -> "CalculatorLauncher"; "camera" -> "CameraLauncher"; else -> "NotesLauncher" }
        // Enable the replacement first: an interrupted switch always leaves a launcher entry.
        val target = ComponentName(context.packageName, "${MainActivity::class.java.packageName}.$selected")
        if (context.packageManager.getComponentEnabledSetting(target) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            context.packageManager.setComponentEnabledSetting(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        names.filter { it != selected }.forEach {
            val other = ComponentName(context.packageName, "${MainActivity::class.java.packageName}.$it")
            if (context.packageManager.getComponentEnabledSetting(other) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                context.packageManager.setComponentEnabledSetting(other, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        LauncherShortcuts.refresh(context)
    }
}
