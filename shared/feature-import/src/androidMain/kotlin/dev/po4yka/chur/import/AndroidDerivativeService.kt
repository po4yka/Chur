package dev.po4yka.chur.imports

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import dev.po4yka.chur.ffi.StreamKind
import java.io.DataOutputStream

/** Disposable codec process. It receives no vault handle, keys, or storage path. */
class AndroidDerivativeService : Service() {
    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            check(Binder.getCallingUid() == Process.myUid())
            data.enforceInterface(DERIVATIVE_INTERFACE)
            when (code) {
                DERIVATIVE_PID -> requireNotNull(reply).writeInt(Process.myPid())
                DERIVATIVE_DECODE, DERIVATIVE_OCR -> {
                    val source = data.readTypedObject(ParcelFileDescriptor.CREATOR) ?: return false
                    val output = data.readTypedObject(ParcelFileDescriptor.CREATOR)
                    if (output == null) { source.close(); return false }
                    source.use {
                        output.use {
                            val uri = Uri.parse(data.readString())
                            val probe = ProbedMedia(
                                data.readInt(), data.readInt(), data.readInt(), data.readLong(),
                                requireNotNull(data.readString()),
                            )
                            val kindCode = data.readInt()
                            val kind = StreamKind.entries.first { it.code == kindCode }
                            val sourceOffset = data.readLong()
                            val sourceLength = data.readLong().takeIf { it >= 0 }
                            require(sourceOffset >= 0 && (sourceLength == null || sourceOffset <= Long.MAX_VALUE - sourceLength))
                            val media = PickedMedia(
                                source.fd, true, null, probe.contentType, null, null, uri, {},
                            )
                            try {
                                val result = if (code == DERIVATIVE_OCR) AndroidOfflineOcr(this@AndroidDerivativeService).recognize(source, probe.contentType)
                                    else AndroidMediaCodec(contentResolver, source, sourceOffset, sourceLength).derive(media, probe, kind)
                                DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(output)).use { into ->
                                    if (result != null) try {
                                        if (result.bytes.size <= if (code == DERIVATIVE_OCR) OCR_RESULT_MAX else DERIVATIVE_RESULT_MAX) {
                                            into.writeInt(result.width)
                                            into.writeInt(result.height)
                                            into.writeInt(result.bytes.size)
                                            into.write(result.bytes)
                                        }
                                    } finally { result.bytes.fill(0) }
                                }
                            } catch (_: Exception) {
                                // EOF carries an unavailable derivative. The parent
                                // kills this process before it returns any result.
                            }
                        }
                    }
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    override fun onBind(intent: Intent): IBinder = endpoint
}

internal const val DERIVATIVE_INTERFACE = "dev.po4yka.chur.imports.Derivative"
internal const val DERIVATIVE_DECODE = IBinder.FIRST_CALL_TRANSACTION
internal const val DERIVATIVE_PID = IBinder.FIRST_CALL_TRANSACTION + 1
internal const val DERIVATIVE_RESULT_MAX = 32 * 1024 * 1024

internal const val DERIVATIVE_OCR = IBinder.FIRST_CALL_TRANSACTION + 2
internal const val OCR_RESULT_MAX = 256 * 1024
