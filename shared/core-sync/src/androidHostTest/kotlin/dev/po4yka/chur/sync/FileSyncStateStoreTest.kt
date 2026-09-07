package dev.po4yka.chur.sync

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FileSyncStateStoreTest {
    private val directory: File =
        File.createTempFile("chur-sync", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }

    private val path: String = File(directory, "sync.json").path

    @AfterTest
    fun clean() {
        directory.deleteRecursively()
    }

    @Test
    fun a_state_round_trips_through_a_new_store() =
        runTest {
            val state =
                SyncState(
                    serverUrl = "https://sync.example",
                    vaultId = ByteArray(16) { 1 },
                    deviceId = ByteArray(16) { 2 },
                    transportToken = ByteArray(32) { 9 },
                    cursors = listOf(DeviceCursor(ByteArray(16) { 3 }, 41u)),
                )
            FileSyncStateStore(path).save(state)

            val reopened = FileSyncStateStore(path).load()

            assertEquals("https://sync.example", reopened?.serverUrl)
            assertContentEquals(state.vaultId, reopened?.vaultId)
            assertContentEquals(state.deviceId, reopened?.deviceId)
            assertContentEquals(state.transportToken, reopened?.transportToken)
            assertEquals(1, reopened?.cursors?.size)
            assertContentEquals(state.cursors.single().deviceId, reopened?.cursors?.single()?.deviceId)
            assertEquals(41uL, reopened?.cursors?.single()?.after)
        }

    @Test
    fun an_absent_file_is_no_state() =
        runTest {
            assertNull(FileSyncStateStore(path).load())
        }

    @Test
    fun clear_removes_the_state() =
        runTest {
            val store = FileSyncStateStore(path)
            store.save(
                SyncState("https://sync.example", ByteArray(16), ByteArray(16), ByteArray(32), emptyList()),
            )

            store.clear()

            assertNull(store.load())
        }

    @Test
    fun a_file_that_does_not_parse_is_an_error_rather_than_an_empty_store() =
        runTest {
            File(directory, "sync.json").writeText("{not json")

            assertFailsWith<IllegalStateException> {
                FileSyncStateStore(path).load()
            }
        }
}
