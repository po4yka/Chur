package dev.po4yka.chur.app.onboarding

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual fun platformGuideStore(settingsDirectory: String): GuideStore {
    val file = File(settingsDirectory, "guide-status.txt")
    fun checkDirectory() {
        check(!Files.isSymbolicLink(file.parentFile!!.toPath()))
        check(!Files.isSymbolicLink(file.toPath()))
    }
    return GuideStore(read = {
        checkDirectory()
        if (!file.exists()) null else { require(file.length() <= 32); file.readText() }
    }, write = { text ->
        checkDirectory()
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temp = File.createTempFile("guide-", ".tmp", file.parentFile)
        try {
            FileOutputStream(temp).use { it.write(text.toByteArray()); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    })
}
