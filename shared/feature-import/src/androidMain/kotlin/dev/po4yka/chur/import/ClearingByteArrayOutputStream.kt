package dev.po4yka.chur.imports

import java.io.ByteArrayOutputStream

/** Clear encoder/IPC bytes, including arrays replaced when the stream grows. */
internal class ClearingByteArrayOutputStream : ByteArrayOutputStream() {
    override fun write(value: Int) {
        val previous = buf
        try { super.write(value) } finally { if (previous !== buf) previous.fill(0) }
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        val previous = buf
        try { super.write(bytes, offset, length) } finally { if (previous !== buf) previous.fill(0) }
    }

    override fun close() {
        buf.fill(0)
        super.close()
    }
}
