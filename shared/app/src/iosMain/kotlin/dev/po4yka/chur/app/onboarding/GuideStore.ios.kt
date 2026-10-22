@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package dev.po4yka.chur.app.onboarding

import kotlinx.cinterop.*
import platform.Foundation.*

actual fun platformGuideStore(settingsDirectory: String): GuideStore {
    val path = "$settingsDirectory/guide-status.txt"
    fun checkDirectory() {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(settingsDirectory, null)
        require(attributes == null || attributes[NSFileType] == NSFileTypeDirectory)
    }
    return GuideStore(read = {
        checkDirectory()
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) null else {
            val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: error("Cannot read guide status")
            require(attributes[NSFileType] == NSFileTypeRegular && (attributes[NSFileSize] as NSNumber).longLongValue <= 32)
            val data = NSData.dataWithContentsOfFile(path) ?: error("Cannot read guide status")
            data.bytes?.readBytes(data.length.toInt())?.decodeToString(throwOnInvalidSequence = true) ?: ""
        }
    }, write = { text ->
        checkDirectory()
        check(NSFileManager.defaultManager.createDirectoryAtPath(settingsDirectory, true, null, null))
        check(NSURL.fileURLWithPath(settingsDirectory).setResourceValue(true, NSURLIsExcludedFromBackupKey, null))
        val bytes = text.encodeToByteArray()
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        check(data.writeToFile(path, atomically = true))
    })
}
