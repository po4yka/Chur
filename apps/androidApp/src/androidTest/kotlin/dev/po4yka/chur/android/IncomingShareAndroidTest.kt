package dev.po4yka.chur.android

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import dev.po4yka.chur.app.MediaImporter
import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ObjectQuery
import dev.po4yka.chur.imports.AndroidMediaCodec
import dev.po4yka.chur.vault.VaultRepository
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingShareAndroidTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val uri = Uri.parse("content://external.test/document/one")

    @Test
    fun mergesStreamAndClipGrantsWithoutDuplicatingItemsAndPreservesCaption() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri))
            clipData = ClipData.newRawUri("source", uri).apply {
                addItem(ClipData.Item(Uri.parse("content://external.test/document/two")))
            }
            putExtra(Intent.EXTRA_TEXT, "Приватный текст https://example.test/private")
        }
        val payload = requireNotNull(incomingSharePayload(context, intent) { false })
        try {
            assertEquals(3, payload.count)
            assertEquals(2, payload.uris.size)
            assertEquals("Приватный текст https://example.test/private", payload.text!!.decodeToString())
            assertFalse(payload.toString().contains("Приватный"))
            assertFalse(payload.toString().contains("external.test"))
        } finally { payload.clear() }
        assertTrue(payload.text!!.all { it == 0.toByte() })
    }

    @Test
    fun rejectsLocalPathsAndOwnPrivateProvidersRatherThanReadWithHostAuthority() {
        for (source in listOf("file:///data/user/0/dev.po4yka.chur/private", "https://example.test/file")) {
            assertNull(incomingSharePayload(context,
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Uri.parse(source))) { false })
        }
        val ownUri = Uri.parse("content://${context.packageName}.exports/anything")
        assertNull(incomingSharePayload(context,
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, ownUri)))
    }

    @Test
    fun rejectsOverLimitAndMixedInvalidBatchesWithoutTruncation() {
        val uris = ArrayList((0..MAX_INCOMING_ITEMS).map { Uri.parse("content://external.test/$it") })
        assertNull(incomingSharePayload(context,
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)) { false })
        assertNull(incomingSharePayload(context,
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM,
                arrayListOf(uri, Uri.parse("file:///private")))) { false })
        assertNull(incomingSharePayload(context,
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "я".repeat(MAX_INCOMING_TEXT_BYTES / 2 + 1))) { false })
    }

    @Test
    fun acceptsTextUrlsAsExactPlainTextWithoutHtmlOrNetworkFetch() {
        val text = "https://example.test/path?q=secret\nПримечание"
        val payload = requireNotNull(incomingSharePayload(context,
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                .putExtra(Intent.EXTRA_HTML_TEXT, "<script>ignored</script>")) { false })
        try {
            assertEquals(1, payload.count)
            assertArrayEquals(text.encodeToByteArray(), payload.text)
            assertTrue(payload.uris.isEmpty())
        } finally { payload.clear() }
        assertNull(incomingSharePayload(context, Intent(Intent.ACTION_VIEW).setData(uri)) { false })
    }

    @Test
    fun externalNonseekableProviderImportsExactBytesAndKeepsOriginal() = runBlocking {
        val incoming = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, IncomingShareFixtureProvider.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val payload = requireNotNull(incomingSharePayload(context, incoming))
        val source = AndroidIncomingShareSource(context, payload)
        val root = File(context.cacheDir, "incoming-file-test-${System.nanoTime()}").apply { mkdirs() }
        val vault = VaultRepository(File(root, "vault").path, { System.currentTimeMillis() })
        try {
            vault.start()
            vault.create("incoming-file-password".encodeToByteArray(), false)
            val picked = requireNotNull(source.open(0) { _, _ -> true })
            assertFalse(picked.seekable)
            assertEquals("shared-report.bin", picked.originalFilename)
            val outcome = MediaImporter(AndroidMediaCodec(context)).import(vault, picked)
            assertTrue(outcome is MediaImporter.Outcome.Imported)
            val id = (outcome as MediaImporter.Outcome.Imported).objectId
            val output = File(root, "export")
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
                val operation = vault.beginExport(id, it.fd)
                try {
                    var progress = vault.poll(operation)
                    while (!progress.terminal) { delay(20); progress = vault.poll(operation) }
                    assertEquals(0, progress.status)
                } finally { vault.closeOperation(operation) }
            }
            assertArrayEquals(IncomingShareFixtureProvider.fixtureBytes(), output.readBytes())
            context.contentResolver.openInputStream(IncomingShareFixtureProvider.uri)!!.use {
                assertArrayEquals(IncomingShareFixtureProvider.fixtureBytes(), it.readBytes())
            }
        } finally { source.close(); vault.shutdown(); root.deleteRecursively() }
    }

    @Test
    fun validMediaKeepsItsMediaClassAndInvalidMediaStillStoresTheOriginal() = runBlocking {
        val root = File(context.cacheDir, "incoming-media-test-${System.nanoTime()}").apply { mkdirs() }
        val vault = VaultRepository(File(root, "vault").path, { System.currentTimeMillis() })
        try {
            vault.start()
            vault.create("incoming-media-password".encodeToByteArray(), false)
            for (path in listOf("image", "invalid-image")) {
                val uri = IncomingShareFixtureProvider.uri.buildUpon().path(path).build()
                val payload = requireNotNull(incomingSharePayload(context,
                    Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)))
                val source = AndroidIncomingShareSource(context, payload)
                try {
                    val picked = requireNotNull(source.open(0) { _, _ -> true })
                    assertEquals(path != "image", picked.importAsFile)
                    assertEquals(path != "image", picked.mediaPreviewUnavailable)
                    val outcome = MediaImporter(AndroidMediaCodec(context)).import(vault, picked)
                    assertTrue("The source must remain storable: $path", outcome is MediaImporter.Outcome.Imported)
                    val id = (outcome as MediaImporter.Outcome.Imported).objectId
                    assertEquals(path != "image", outcome.storedAsFile)
                    assertEquals(if (path == "image") null else MediaImporter.PreviewWarning.DECODING_FAILED,
                        outcome.previewWarning)
                    assertEquals(if (path == "image") "image/png" else "image/jpeg", vault.detail(id).contentType)
                    val output = File(root, path)
                    ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
                        val operation = vault.beginExport(id, it.fd)
                        try {
                            var progress = vault.poll(operation)
                            while (!progress.terminal) { delay(20); progress = vault.poll(operation) }
                            assertEquals(0, progress.status)
                        } finally { vault.closeOperation(operation) }
                    }
                    assertArrayEquals(IncomingShareFixtureProvider.fixtureBytes(uri), output.readBytes())
                } finally { source.close() }
            }
        } finally { vault.shutdown(); root.deleteRecursively() }
    }

    @Test
    fun activityConsumesColdAndWarmSharesOnceAndDoesNotSavePayload() {
        val host = ChurHost.of(context)
        host.incomingShares.discard()
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "cold incoming secret")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var activity = instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                assertEquals(1, host.incomingShares.state.value!!.count)
                assertNull(activity.intent.extras)
                assertNull(activity.intent.clipData)
            }
            activity = recreateActivity(activity, host)
            instrumentation.runOnMainSync {
                assertEquals(1, host.incomingShares.state.value!!.count)
                host.incomingShares.discard()
                assertNull(host.incomingShares.state.value)
                context.startActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "warm incoming secret")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            instrumentation.waitForIdleSync()
            runBlocking {
                withTimeout(5_000) { host.incomingShares.state.first { it?.count == 1 } }
            }
            instrumentation.runOnMainSync {
                assertEquals(1, host.incomingShares.state.value!!.count)
                assertNull(activity.intent.extras)
                host.incomingShares.discard()
            }
            activity = recreateActivity(activity, host)
            instrumentation.runOnMainSync { assertNull(host.incomingShares.state.value) }
        } finally {
            finishActivity(activity)
            host.incomingShares.discard()
        }
    }

    @Test
    fun sharedTextImportsThroughNativeEncryptionAndExportsExactBytes() = runBlocking {
        val text = "Shared privately: Привет, мир! https://example.test/secret".encodeToByteArray()
        val payload = IncomingSharePayload(emptyList(), text.copyOf())
        val source = AndroidIncomingShareSource(context, payload)
        val root = File(context.cacheDir, "incoming-share-test-${System.nanoTime()}").apply { mkdirs() }
        val vault = VaultRepository(File(root, "vault").path, { System.currentTimeMillis() })
        try {
            vault.start()
            vault.create("incoming-share-password".encodeToByteArray(), false)
            val picked = requireNotNull(source.open(0) { _, _ -> true })
            assertTrue(picked.seekable)
            assertTrue(picked.importAsFile)
            val result = MediaImporter(AndroidMediaCodec(context)).import(vault, picked)
            assertTrue(result is MediaImporter.Outcome.Imported)
            val id = (result as MediaImporter.Outcome.Imported).objectId
            val detail = vault.detail(id)
            assertEquals("shared-text.txt", detail.filename)
            assertEquals("text/plain", detail.contentType)
            assertEquals(text.size.toLong(), detail.plaintextSize)
            val output = File(root, "export")
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
                val operation = vault.beginExport(id, it.fd)
                try {
                    var progress = vault.poll(operation)
                    while (!progress.terminal) { delay(20); progress = vault.poll(operation) }
                    assertEquals(0, progress.status)
                } finally { vault.closeOperation(operation) }
            }
            assertArrayEquals(text, output.readBytes())
            source.close()
            assertNull(source.open(0) { _, _ -> true })
            assertTrue(payload.text!!.all { it == 0.toByte() })
        } finally {
            source.close()
            vault.shutdown()
            root.deleteRecursively()
            text.fill(0)
        }
    }

    @Test
    fun providerEarlyEofCannotCommitATruncatedOriginal() = runBlocking {
        val uri = IncomingShareFixtureProvider.uri.buildUpon().path("truncated").build()
        val payload = requireNotNull(incomingSharePayload(context,
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)))
        val source = AndroidIncomingShareSource(context, payload)
        val root = File(context.cacheDir, "incoming-short-test-${System.nanoTime()}").apply { mkdirs() }
        val vault = VaultRepository(File(root, "vault").path, { System.currentTimeMillis() })
        try {
            vault.start()
            vault.create("incoming-short-password".encodeToByteArray(), false)
            val picked = requireNotNull(source.open(0) { _, _ -> true })
            assertEquals(IncomingShareFixtureProvider.fixtureBytes().size.toLong() + 1, picked.knownLength)
            val outcome = MediaImporter(AndroidMediaCodec(context)).import(vault, picked)
            assertEquals(MediaImporter.Outcome.Refused(ChurStatus.IO_FAILURE), outcome)
            assertTrue(vault.page(ObjectQuery()).objects.isEmpty())
        } finally { source.close(); vault.shutdown(); root.deleteRecursively() }
    }

    @Test
    fun unavailableOrCancelledProviderDoesNotOpenOrRetainAnItem() = runBlocking {
        val source = AndroidIncomingShareSource(context, IncomingSharePayload(
            listOf(Uri.parse("content://no.such.incoming.provider/file")), null))
        try {
            assertNull(source.open(0) { _, _ -> false })
            assertNull(source.open(0) { _, _ -> true })
            source.close()
            assertNull(source.open(0) { _, _ -> true })
        } finally { source.close() }
    }

    @Test
    fun safeNamesStripPathsAndControlCharacters() {
        assertEquals("report.pdf", safeIncomingFilename("../../report\u202e.pdf"))
        assertEquals("report.txt", safeIncomingFilename("C:\\folder\\report\n.txt"))
        assertNull(safeIncomingFilename(".."))
        assertEquals(255, safeIncomingFilename("a".repeat(300))!!.length)
    }

    @Test
    fun sanitizedActivityIntentDropsNestedExternalPayloadAndIdentifier() {
        val external = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setDataAndType(uri, "text/plain").putExtra(Intent.EXTRA_TEXT, "payload")
            .addCategory(Intent.CATEGORY_DEFAULT).apply {
                selector = Intent(Intent.ACTION_VIEW).putExtra("nested", "private selector")
                identifier = "private identifier"
                clipData = ClipData.newRawUri("private name", uri)
            }
        val host = ChurHost.of(context)
        host.incomingShares.discard()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            instrumentation.runOnMainSync {
                // Deliver the nested payload to the production callback: a
                // system launch would resolve the selector before delivery.
                activity.intent = external
                instrumentation.callActivityOnNewIntent(activity, external)
                val sanitized = activity.intent
                assertEquals(Intent.ACTION_MAIN, sanitized.action)
                assertEquals(external.component, sanitized.component)
                assertNull(sanitized.type)
                assertNull(sanitized.categories)
                assertNull(sanitized.`package`)
                assertNull(sanitized.extras)
                assertNull(sanitized.data)
                assertNull(sanitized.clipData)
                assertNull(sanitized.selector)
                assertNull(sanitized.identifier)
            }
        } finally {
            finishActivity(activity)
            host.incomingShares.discard()
        }
    }

    private fun recreateActivity(previous: MainActivity, host: ChurHost): MainActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { previous.recreate() }
        var replacement: MainActivity? = null
        awaitLifecycle {
            replacement = (host.activity as? MainActivity)?.takeIf {
                it !== previous && it.lifecycle.currentState == Lifecycle.State.RESUMED
            }
            replacement != null
        }
        return requireNotNull(replacement)
    }

    private fun finishActivity(activity: MainActivity) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.finish() }
        awaitLifecycle { activity.isDestroyed }
    }

    private fun awaitLifecycle(ready: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var complete = false
            instrumentation.runOnMainSync { complete = ready() }
            if (complete) return
            SystemClock.sleep(40)
        }
        error("The production Activity did not finish its lifecycle transition")
    }

    @Test
    fun manifestRegistersSingleAndMultipleShareForEveryFileType() {
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (type in listOf("text/plain", "image/jpeg", "application/pdf", "application/octet-stream")) {
                val matches = context.packageManager.queryIntentActivities(
                    Intent(action).setType(type).setPackage(context.packageName), 0)
                assertTrue("Missing receiver for $action $type", matches.any {
                    it.activityInfo.name == MainActivity::class.java.name && it.activityInfo.exported
                })
            }
        }
    }
}
