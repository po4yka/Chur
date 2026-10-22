package dev.po4yka.chur.app.publicshell

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal actual fun publicDecimalSeparator(): String = java.text.DecimalFormatSymbols.getInstance().decimalSeparator.toString()

internal actual fun publicCodeDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(("public-entry-v1:" + value).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

actual fun publicUtilityStore(publicDirectory: String, settingsDirectory: String): PublicUtilityStore {
    fun file(key: String): File {
        require(key in setOf("entry", "appearance", "calculator"))
        val parent = File(if (key == "entry") settingsDirectory else publicDirectory)
        // Android's trusted files anchor can use a /data/data alias. Reject links below it.
        check(!Files.isSymbolicLink(parent.toPath()))
        check(parent.isDirectory || parent.mkdirs())
        check(!Files.isSymbolicLink(parent.toPath()))
        return File(parent, "utility-$key.json")
    }
    return PublicUtilityStore(read = { key ->
        val source = file(key)
        if (!source.exists()) null else {
            require(!Files.isSymbolicLink(source.toPath()) && source.length() <= 512 * 1024)
            source.readText()
        }
    }, write = { key, text ->
        val destination = file(key)
        val temporary = File.createTempFile("utility-", ".tmp", destination.parentFile)
        try {
            FileOutputStream(temporary).use { it.write(text.toByteArray()); it.fd.sync() }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    })
}
