@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package dev.po4yka.chur.app.publicshell

import kotlinx.cinterop.*
import platform.CoreCrypto.CC_SHA256
import platform.Foundation.*

internal actual fun publicDecimalSeparator(): String = NSNumberFormatter().decimalSeparator ?: "."

internal actual fun publicCodeDigest(value: String): String {
    val bytes = ("public-entry-v1:" + value).encodeToByteArray()
    val digest = UByteArray(32)
    bytes.usePinned { input -> digest.usePinned { output -> CC_SHA256(input.addressOf(0), bytes.size.toUInt(), output.addressOf(0)) } }
    return digest.joinToString("") { it.toString(16).padStart(2, '0') }
}
actual fun publicUtilityStore(publicDirectory: String, settingsDirectory: String): PublicUtilityStore {
    fun path(key: String): String {
        require(key in setOf("entry", "appearance", "calculator"))
        val directory = if (key == "entry") settingsDirectory else publicDirectory
        val parent = directory.substringBeforeLast('/')
        for (component in listOf(parent, directory)) {
            val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(component, null)
            require(attributes == null || attributes[NSFileType] == NSFileTypeDirectory)
        }
        check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        if (key == "entry") check(NSURL.fileURLWithPath(directory).setResourceValue(true, NSURLIsExcludedFromBackupKey, null))
        return "$directory/utility-$key.json"
    }
    return PublicUtilityStore(read = { key ->
        val name = path(key)
        if (!NSFileManager.defaultManager.fileExistsAtPath(name)) null else {
            val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(name, null) ?: error("Cannot read settings")
            require((attributes[NSFileSize] as NSNumber).longLongValue <= 512 * 1024)
            require(attributes[NSFileType] == NSFileTypeRegular)
            val data = NSData.dataWithContentsOfFile(name) ?: error("Cannot read settings")
            require(data.length <= 512uL * 1024uL)
            data.bytes?.readBytes(data.length.toInt())?.decodeToString(throwOnInvalidSequence = true) ?: ""
        }
    }, write = { key, text ->
        val bytes = text.encodeToByteArray()
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        check(data.writeToFile(path(key), atomically = true)) { "Cannot save settings" }
    })
}
