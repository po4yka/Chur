package dev.po4yka.chur.app

import android.os.ParcelFileDescriptor

internal actual fun writeLivePhotoExportBytes(descriptor: Int, bytes: ByteArray) {
    ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.fromFd(descriptor)).use { it.write(bytes) }
}
