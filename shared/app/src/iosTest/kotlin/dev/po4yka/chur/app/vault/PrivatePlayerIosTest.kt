@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.po4yka.chur.app.vault

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ImportRequest
import dev.po4yka.chur.vault.VaultRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.AVFoundation.currentTime
import platform.AVFoundation.play
import platform.AVFoundation.muted
import platform.AVFoundation.seekToTime
import platform.AVFoundation.AVErrorDecoderNotFound
import platform.AVFoundation.AVFoundationErrorDomain
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetReferenceRestrictionForbidAll
import platform.AVFoundation.AVMediaCharacteristicContainsOnlyForcedSubtitles
import platform.AVFoundation.AVMediaCharacteristicAudible
import platform.AVFoundation.AVMediaCharacteristicLegible
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.currentMediaSelection
import platform.AVFoundation.mediaSelectionGroupForMediaCharacteristic
import platform.AVFoundation.referenceRestrictions
import platform.AVFoundation.pause
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.allowsExternalPlayback
import platform.AVFoundation.rate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dateByAddingTimeInterval
import platform.Foundation.runUntilDate
import platform.Foundation.writeToFile
import platform.Foundation.NSURL
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PrivatePlayerIosTest {
    @Test
    fun reader_errors_keep_typed_codes_and_do_not_become_codec_failures() {
        val corrupt = iosReaderPlaybackError(ChurStatus.OBJECT_CORRUPT)
        assertEquals("dev.po4yka.chur.playback", corrupt.domain)
        assertEquals(ChurStatus.OBJECT_CORRUPT.value.toLong(), corrupt.code)
        assertEquals(PlaybackProblem.UNAVAILABLE, iosNativePlaybackProblem(corrupt))
        assertEquals(PlaybackProblem.INTEGRITY, playbackProblem(ChurStatus.OBJECT_CORRUPT))
    }

    @Test
    fun native_decoder_failure_is_unsupported_but_an_unknown_error_is_unavailable() {
        val codec = NSError(domain = AVFoundationErrorDomain, code = AVErrorDecoderNotFound, userInfo = null)
        assertEquals(PlaybackProblem.UNSUPPORTED, iosNativePlaybackProblem(codec))
        assertEquals(PlaybackProblem.UNAVAILABLE, iosNativePlaybackProblem(null))
    }

    @Test
    fun real_encrypted_ranges_reach_AVPlayer_and_support_play_and_seek(): Unit = runBlocking {
        val root = NSTemporaryDirectory() + "chur-encrypted-player-" + NSUUID().UUIDString
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null))
        val path = "$root/source.mp4"
        val data = assertNotNull(NSData.create(base64EncodedString = MULTITRACK_MP4, options = 0u))
        assertTrue(data.writeToFile(path, atomically = true))
        val vault = VaultRepository("$root/vault", { 1_700_000_000_000L })
        var loader: ChurResourceLoader? = null
        var player: platform.AVFoundation.AVPlayer? = null
        try {
            vault.start()
            vault.create("Encrypted-iOS-player-test".encodeToByteArray(), false)
            val fd = platform.posix.open(path, platform.posix.O_RDONLY)
            assertTrue(fd >= 0)
            val objectId = try {
                val operation = vault.beginImport(fd, ImportRequest(
                    "video/mp4", true, 2, width = 96, height = 48, durationMs = 500,
                    knownLength = data.length.toLong(), originalFilename = "Synthetic video.mp4",
                ))
                try {
                    val result = withTimeout(60_000) {
                        var progress = vault.poll(operation)
                        while (!progress.terminal) { delay(10); progress = vault.poll(operation) }
                        progress
                    }
                    assertEquals(0, result.status)
                    assertNotNull(result.importedObjectId())
                } finally { vault.closeOperation(operation) }
            } finally { platform.posix.close(fd) }
            // Playback must succeed after the picker copy has gone away.
            assertEquals(0, unlink(path))
            val lease = vault.leaseReader(objectId)
            val info = try { vault.readerContentInfo(lease) } finally { vault.releaseReader(lease) }
            assertTrue(info.complete && info.byteRangeSupported)
            val nativeLoader = ChurResourceLoader(PlaybackSource(vault, objectId, info.contentType, info.plaintextSize))
                .also { loader = it }
            val item = privatePlayerItem(nativeLoader)
            val nativePlayer = privatePlayer(item).also { player = it; it.muted = true }
            waitForNativePlayer { item.status == AVPlayerItemStatusReadyToPlay || item.error != null }
            assertNull(item.error, "Encrypted range playback must load a real native asset")
            assertEquals(AVPlayerItemStatusReadyToPlay, item.status)
            nativePlayer.play()
            waitForNativePlayer { CMTimeGetSeconds(nativePlayer.currentTime()) >= 0.12 }
            assertTrue(CMTimeGetSeconds(nativePlayer.currentTime()) >= 0.12)
            nativePlayer.pause()
            nativePlayer.seekToTime(CMTimeMakeWithSeconds(0.0, 600))
            waitForNativePlayer { CMTimeGetSeconds(nativePlayer.currentTime()) < 0.05 }
            assertTrue(CMTimeGetSeconds(nativePlayer.currentTime()) < 0.05)
            nativePlayer.play()
            waitForNativePlayer { CMTimeGetSeconds(nativePlayer.currentTime()) >= 0.12 }
            assertTrue(CMTimeGetSeconds(nativePlayer.currentTime()) >= 0.12)
            assertNull(item.error)
            assertFalse(nativePlayer.allowsExternalPlayback)
        } finally {
            player?.pause()
            player?.replaceCurrentItemWithPlayerItem(null)
            loader?.release()
            runCatching { vault.shutdown() }
            NSFileManager.defaultManager.removeItemAtPath(root, null)
        }
    }

    private fun waitForNativePlayer(condition: () -> Boolean) {
        repeat(200) { if (!condition()) pumpNativePlayer() }
    }

    @Test
    fun native_embedded_audio_and_subtitles_can_be_selected_and_subtitles_can_be_disabled() {
        val path = NSTemporaryDirectory() + NSUUID().UUIDString + ".mp4"
        val data = assertNotNull(NSData.create(base64EncodedString = MULTITRACK_MP4, options = 0u))
        assertTrue(data.writeToFile(path, atomically = true))
        val asset = privatePlaybackAsset(NSURL.fileURLWithPath(path))
        val item = AVPlayerItem(asset as AVAsset)
        val player = privatePlayer(item)
        try {
            assertEquals(AVAssetReferenceRestrictionForbidAll, asset.referenceRestrictions)
            repeat(100) {
                if (item.status != AVPlayerItemStatusReadyToPlay) pumpNativePlayer()
            }
            assertEquals(AVPlayerItemStatusReadyToPlay, item.status)
            val audio = assertNotNull(asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicAudible))
            val subtitles = assertNotNull(asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicLegible))
            assertEquals(2, audio.options.size)
            // AVFoundation adds an "English Forced" option for this single
            // mov_text track. Select its actual full-subtitle presentation.
            val regularSubtitles = subtitles.options.filterIsInstance<AVMediaSelectionOption>()
                .filterNot { it.hasMediaCharacteristic(AVMediaCharacteristicContainsOnlyForcedSubtitles) }
            assertEquals(1, regularSubtitles.size)
            val french = audio.options.filterIsInstance<AVMediaSelectionOption>().last()
            val subtitle = regularSubtitles.single()
            assertTrue(french.displayName.isNotBlank())
            assertTrue(selectMediaTrack(item, audio, french))
            pumpNativePlayer()
            assertEquals(french, item.currentMediaSelection.selectedMediaOptionInMediaSelectionGroup(audio))
            assertTrue(selectMediaTrack(item, subtitles, subtitle))
            pumpNativePlayer()
            assertEquals(subtitle, item.currentMediaSelection.selectedMediaOptionInMediaSelectionGroup(subtitles))
            assertTrue(selectMediaTrack(item, subtitles, null))
            pumpNativePlayer()
            assertNull(item.currentMediaSelection.selectedMediaOptionInMediaSelectionGroup(subtitles))
            assertFalse(selectMediaTrack(item, audio, subtitle), "Do not select an option from another group")
        } finally {
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
            unlink(path)
        }
    }

    private fun pumpNativePlayer() {
        NSRunLoop.currentRunLoop.runUntilDate(NSDate().dateByAddingTimeInterval(0.02))
    }

    @Test
    fun native_type_registry_resolves_concrete_audio_and_video_containers() {
        assertEquals("public.mp3", utTypeOf("audio/mpeg"))
        assertEquals("public.mpeg-4", utTypeOf("video/mp4"))
        assertEquals("com.apple.quicktime-movie", utTypeOf("video/quicktime"))
    }

    @Test
    fun opening_private_media_does_not_start_or_allow_external_playback() {
        val item = AVPlayerItem(uRL = NSURL(string = "chur://vault/object"))
        val player = privatePlayer(item)
        assertEquals(0f, player.rate)
        assertFalse(player.allowsExternalPlayback)
    }
    private companion object {
        // FFmpeg: synthetic 96 x 48 H.264, two AAC tones (English/French), one mov_text subtitle.
        const val MULTITRACK_MP4 =
            "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAtbbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAGkkgQA0kkCAAAQAAAQAAAAAAAAAAAA" +
            "AAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABQAAArh0cmFrAAAAXHRraGQA" +
            "AAADAAAAAAAAAAAAAAABAAAAAA0kkCAAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAGAAAAAwAA" +
            "AAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAENJJAgAAAIAAABAAAAAAIwbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAAAoAAAAFABVxAAAAAAALWhk" +
            "bHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAAB221pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZW" +
            "YAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAZtzdGJsAAAAv3N0c2QAAAAAAAAAAQAAAK9hdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAGAAMABI" +
            "AAAASAAAAAAAAAABFExhdmM2My4xLjEwMiBsaWJ4MjY0AAAAAAAAAAAAAAAAGP//AAAANWF2Y0MBZAAK/+EAGGdkAAqs2UZ7ARAAAAMAEAAAAw" +
            "FA8SJZYAEABmjr48siwP34+AAAAAAQcGFzcAAAAAEAAAABAAAAFGJ0cnQAAAAAAAAxEAAAAAAAAAAYc3R0cwAAAAAAAAABAAAABQAABAAAAAAU" +
            "c3RzcwAAAAAAAAABAAAAAQAAADhjdHRzAAAAAAAAAAUAAAABAAAIAAAAAAEAABQAAAAAAQAACAAAAAABAAAAAAAAAAEAAAQAAAAAKHN0c2MAAA" +
            "AAAAAAAgAAAAEAAAACAAAAAQAAAAIAAAABAAAAAQAAAChzdHN6AAAAAAAAAAAAAAAFAAAC3wAAAA4AAAAMAAAADAAAAAwAAAAgc3RjbwAAAAAA" +
            "AAAEAAALiwAAD5kAABLxAAAVqwAAAu10cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAACAAAAAA0kkCAAAAAAAAAAAAAAAAEBAAAAAAEAAAAAAA" +
            "AAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAENJJAgAAAEAAABAAAAAAJlbWRpYQAA" +
            "ACBtZGhkAAAAAAAAAAAAAAAAAACsRAAAWiIVxwAAAAAALWhkbHIAAAAAAAAAAHNvdW4AAAAAAAAAAAAAAABTb3VuZEhhbmRsZXIAAAACEG1pbm" +
            "YAAAAQc21oZAAAAAAAAAAAAAAAJGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAAB1HN0YmwAAAB+c3RzZAAAAAAAAAABAAAAbm1w" +
            "NGEAAAAAAAAAAQAAAAAAAAAAAAEAEAAAAACsRAAAAAAANmVzZHMAAAAAA4CAgCUAAgAEgICAF0AVAAAAAAB+2wAAftsFgICABRIIVuUABoCAgA" +
            "ECAAAAFGJ0cnQAAAAAAAB+2wAAftsAAAAgc3R0cwAAAAAAAAACAAAAFgAABAAAAAABAAACIgAAABxzdHNjAAAAAAAAAAEAAAABAAAAAQAAAAEA" +
            "AABwc3RzegAAAAAAAAAAAAAAFwAAAJcAAACiAAAAPAAAAD4AAABBAAAAQgAAAF0AAABdAAAASwAAAFQAAABcAAAAcQAAAEgAAABkAAAASAAAAF" +
            "cAAAB1AAAAewAAAE0AAABWAAAAagAAAEIAAABmAAAAbHN0Y28AAAAAAAAAFwAADngAAA+lAAAQ8gAAEWwAABHsAAASbgAAEv0AABOnAAAUVwAA" +
            "FPoAABW3AAAWbgAAFzkAABfcAAAYmAAAGTsAABn7AAAawAAAG6UAABxMAAAc/wAAHcMAAB5eAAAAGnNncGQBAAAAcm9sbAAAAAIAAAAB//8AAA" +
            "Acc2JncAAAAAByb2xsAAAAAQAAABcAAAABAAAC7XRyYWsAAABcdGtoZAAAAAIAAAAAAAAAAAAAAAMAAAAADSSQIAAAAAAAAAAAAAAAAQEAAAAA" +
            "AQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAACRlZHRzAAAAHGVsc3QAAAAAAAAAAQ0kkCAAAAQAAAEAAAAAAm" +
            "VtZGlhAAAAIG1kaGQAAAAAAAAAAAAAAAAAAKxEAABaIhpBAAAAAAAtaGRscgAAAAAAAAAAc291bgAAAAAAAAAAAAAAAFNvdW5kSGFuZGxlcgAA" +
            "AAIQbWluZgAAABBzbWhkAAAAAAAAAAAAAAAkZGluZgAAABxkcmVmAAAAAAAAAAEAAAAMdXJsIAAAAAEAAAHUc3RibAAAAH5zdHNkAAAAAAAAAA" +
            "EAAABubXA0YQAAAAAAAAABAAAAAAAAAAAAAQAQAAAAAKxEAAAAAAA2ZXNkcwAAAAADgICAJQADAASAgIAXQBUAAAAAAH0AAAB8tQWAgIAFEghW" +
            "5QAGgICAAQIAAAAUYnRydAAAAAAAAH0AAAB8tQAAACBzdHRzAAAAAAAAAAIAAAAWAAAEAAAAAAEAAAIiAAAAHHN0c2MAAAAAAAAAAQAAAAEAAA" +
            "ABAAAAAQAAAHBzdHN6AAAAAAAAAAAAAAAXAAAAigAAAJwAAAA+AAAAQgAAAEEAAABBAAAATQAAAFMAAABYAAAAXQAAAFsAAABaAAAAWwAAAFgA" +
            "AABbAAAAaQAAAFAAAABqAAAAWgAAAF0AAABaAAAAWQAAAFsAAABsc3RjbwAAAAAAAAAXAAAPDwAAEEcAABEuAAARqgAAEi0AABKwAAATWgAAFA" +
            "QAABSiAAAVTgAAFhMAABbfAAAXgQAAGEAAABjgAAAZkgAAGnAAABs7AAAb8gAAHKIAAB1pAAAeBQAAHsQAAAAac2dwZAEAAAByb2xsAAAAAgAA" +
            "AAH//wAAABxzYmdwAAAAAHJvbGwAAAABAAAAFwAAAAEAAAH0dHJhawAAAFx0a2hkAAAAAwAAAAAAAAAAAAAABAAAAAANJJAgAAAAAAAAAAAAAA" +
            "ADAAAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAJGVkdHMAAAAcZWxzdAAAAAAAAAABDSSQIAAAAAAA" +
            "AQAAAAABbG1kaWEAAAAgbWRoZAAAAAAAAAAAAAAAAAAPQkAAB6EgFccAAAAAADBoZGxyAAAAAAAAAABzYnRsAAAAAAAAAAAAAAAAU3VidGl0bG" +
            "VIYW5kbGVyAAAAARRtaW5mAAAADG5taGQAAAAAAAAAJGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAAA3HN0YmwAAABkc3RzZAAA" +
            "AAAAAAABAAAAVHR4M2cAAAAAAAAAAQAAAAAB/wAAAP8AAAAAAAAAAAAAAAAAAQAQ/////wAAABJmdGFiAAEAAQVBcmlhbAAAABRidHJ0AAAAAA" +
            "AAARAAAAEQAAAAIHN0dHMAAAAAAAAAAgAAAAEAB6EgAAAAAQAAAAAAAAAcc3RzYwAAAAAAAAABAAAAAQAAAAEAAAABAAAAHHN0c3oAAAAAAAAA" +
            "AAAAAAIAAAAPAAAAAgAAABhzdGNvAAAAAAAAAAIAABDjAAAfHwAAAGF1ZHRhAAAAWW1ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAG1kaXJhcHBsAA" +
            "AAAAAAAAAAAAAALGlsc3QAAAAkqXRvbwAAABxkYXRhAAAAAQAAAABMYXZmNjMuMS4xMDIAAAAIZnJlZQAAE55tZGF0AAACrgYF//+q3EXpvebZ" +
            "SLeWLNgg2SPu73gyNjQgLSBjb3JlIDE2NSByMzIyMiBiMzU2MDVhIC0gSC4yNjQvTVBFRy00IEFWQyBjb2RlYyAtIENvcHlsZWZ0IDIwMDMtMj" +
            "AyNSAtIGh0dHA6Ly93d3cudmlkZW9sYW4ub3JnL3gyNjQuaHRtbCAtIG9wdGlvbnM6IGNhYmFjPTEgcmVmPTMgZGVibG9jaz0xOjA6MCBhbmFs" +
            "eXNlPTB4MzoweDExMyBtZT1oZXggc3VibWU9NyBwc3k9MSBwc3lfcmQ9MS4wMDowLjAwIG1peGVkX3JlZj0xIG1lX3JhbmdlPTE2IGNocm9tYV" +
            "9tZT0xIHRyZWxsaXM9MSA4eDhkY3Q9MSBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0X3Bza2lwPTEgY2hyb21hX3FwX29mZnNldD0tMiB0aHJl" +
            "YWRzPTEgbG9va2FoZWFkX3RocmVhZHM9MSBzbGljZWRfdGhyZWFkcz0wIG5yPTAgZGVjaW1hdGU9MSBpbnRlcmxhY2VkPTAgYmx1cmF5X2NvbX" +
            "BhdD0wIGNvbnN0cmFpbmVkX2ludHJhPTAgYmZyYW1lcz0zIGJfcHlyYW1pZD0yIGJfYWRhcHQ9MSBiX2JpYXM9MCBkaXJlY3Q9MSB3ZWlnaHRi" +
            "PTEgb3Blbl9nb3A9MCB3ZWlnaHRwPTIga2V5aW50PTI1MCBrZXlpbnRfbWluPTEwIHNjZW5lY3V0PTQwIGludHJhX3JlZnJlc2g9MCByY19sb2" +
            "9rYWhlYWQ9NDAgcmM9Y3JmIG1idHJlZT0xIGNyZj0yMy4wIHFjb21wPTAuNjAgcXBtaW49MCBxcG1heD02OSBxcHN0ZXA9NCBpcF9yYXRpbz0x" +
            "LjQwIGFxPTE6MS4wMACAAAAAKWWIhAAQ//7mwPmWVQ1Xf/1pPSv4zSLlJeKTDK3bcYp/IpTtD7z4LS5hAAAACkGaJGxDf/6niNzcAExhdmM2My" +
            "4xLjEwMgACYKVUkNpyMvPHp541XnLlquskR5SSL8wkG3cD8F6r2LvuGy2paR/j0vN73q/J4G1MVWNs1prz6yUmmqpiuYqjJpSyUslppSaMlClG" +
            "SjJRJrQ2htDAxs2DAyJEDGzZs2iRIpTZs3FFFFFFFFFFFFFFFEiKKKKKKJERERIRFFERRRIiiiKKKKLg3ABMYXZjNjMuMS4xMDIAAlSlU9E6ki" +
            "Ob5rd/t8a+LW8dbuJI91pJJ7IHGuOz07nWUyUbJTraRx3uq7OPrabkr7+TNstr2Nnp2OkX5iqaxylspZKTQMDfq3wYGBgYGBgYGBgYGBkQMDGz" +
            "YMDIkSI2bNm4ooooooooooooooooooooooooooooooouAAAACEGeQniHfwRNATiU2sldlFOrKdWS6c/z7cabPGr/+tfv1xrjV6//teP588a41e" +
            "v/4vf+fPGutasN/rY0fRQYC6BMsIWfqdZvKY25wGc4DAzuEzAwM7hISDAzuEhIMDAzu4SErBgYGd3CQk5CbCXf4Qqw3U+cuV6UrelCb2ZuUJKk" +
            "swaUJCSV4GlCQklecGNmwkJKg2bgwMbCQkqxy8Q1FFFBqKKKKNxtdFHAAR6U2sldllOrI9WU6f/2v/X24069Tj/x/b/39uNca1f/1/+P/X2411" +
            "q+P/7n/H/v7ca41xYWfs5FFFERtxm+Bm/sqd1M5myWYMDXgaUGzvODGwYlecGBgYGvODAwMDGxxLNwYGBgagzm7myigfqUaeIxijFHYjsItkTY" +
            "/lsIv5bBE9XrsNkXq+HqG5EWs4EhMwSEhISEhISEiQngAA1UZXN0IHN1YnRpdGxlATryixrXjnv9n7+3xbpppeqlyOOSSSIkDuf3zpuzZjZ/ch" +
            "k+DDP7gZPgwz+8I+PgDP7gcPgDP7gZPgDgASbyixrVc+uc3/0/9tPPmal3NXctzLkiEUHYX8nx8Zkb/j4bzD399Af+Gwn3hA+AHfcB/4bFnvCD" +
            "t5g77lwBCDKJ4p1rK+f/7P8f+v/td8XeqvfXv9b8ffbty6lUXmtihdCMCKig1FFBqBQug1o2EsxLNMzrm/NT4O35nAD+MpHinWqzM3/9P+3+P/" +
            "v+s1L3fGX64z2eUyXyipMAMDDhwMOtgYWOBga8OvM3gYGpVi9F9K/PnNcpDiFPbRPVwAEIMooifUCHSMr1//fv/9fxc41xk87rx8V4+Nu4VrKT" +
            "LCedRzqKedU88551Cec9WUqm+lx7qp+V4BzxeJimChVwAPgyieKdMIdM5b5z/6f9P5/+341eqqtRvUp6KrW+SpNh557veeP5wmee/el679/t56" +
            "dZJ1dU/ykudd0UzFAEBI4BCjKKAo0Ih1aq9//7X5/9f/JfGpcnXr193z+NuzwyXirqYPlFEt8xooooooooquKCREVKr1iUv6FNKZC9DtmAX4AA" +
            "/DKKQm0wh0ysrnf/j/9v9P/X/E1JoXlSnlRe8qEwfTXRRRQhBioooooooNRRuadg6HTbNh3lWLpaCdkrFyIQcAAAAAgBnmF0Q38FvAEGMpTjXR" +
            "iHREHQiFVev/6b/1/2l8XNZ1m+/v3v2ndw8JJTALq6MOfwcHp16/y1ym1zzzfSixlzD1VLPYdotXf2/0llgLWiN7cjDTreDXYybrcSccQiAySN" +
            "2RrUcAD+MpICnSiHSiFVZvP/2/6f4/+34mruqla51L5eNNc0IgBrwMOHDrRjCxwMSvA14dQBgbMZff56KHunzqQ5cSl5RpD0CyqaBuFW6lOHAQ" +
            "wyizohDoiDoRFox6//pd/+3/ter1JajU/T98jrby7yolD6fT6SJuL6fT6fSj6fT6UDXBz+cDbcCx7hkDEPDeFANsMTzcp0s6Q90WMUWgJWmO+c" +
            "1LOBe+tNWsXAAPgykqQ9MYdMpXNf/t/4/0/+f5qaVOMqVI9KVrnEq6oWxges9Z/Q+84Hren+h/QwMDAzJEiBkaN2jd3HWYHRF0br0e5oZC1QUl" +
            "WH5YTEPFRNmnABADKc6IFehEOpZnf/93v/v/1ucTV6rfH8fvznTdPLQqTLwImJhpOw9DGwGHjx5cy8M0gi+Gc7RpbbWJr6izUdYSmwfwSgKgxk" +
            "No4A/DKY9CR+mUOmLze/7/9P8/6fvNTcXM1lnmL3QjAzs80k0jSUSB8Cun+Xy/Vpn5T8p0T8p+6aJ/zTTTFUqziRpKsJXm+6PlcwIPnBW8rF3P" +
            "TM9FmAqZpwAQoykJY0OKtEIdEIdEIdIN//Sf7/UdS5IevjX7fzt17qpJVEAGBgbvGU7Ax+Jbj8AYleBiQNmOclX5vdCDmlApTTS+JMvCUL9cBi" +
            "VoKzsD+bdBevAPwykNZCOJ9KJdEIVZed/1/p+//fV1LFzFnkqtVSr3ABkV7W30YcFG6V9s6YKWg2c3NzTzzzzzzbFT3qp7b7+axvh2F64C4jaC" +
            "8ZTRUFdCRu4FAjZU1RNdOtocOAAAAACAGeY2pDfwW9AQQykJYyUJ9CIdCIdCI9K3XP9v0/9vaXrXSzxnw76x4VMzzlyZKANLL7y3kMMtBef0Qi" +
            "iiiKIRIiVxhikREUuU5Hy3NhRL8HfAkFJio6OFLYkUmLk+FfMvCFuLgA9DKQ1EJ2mEOiEOiILucz1/r/X/H/lxLu8krinfl7VUvfJaBjaBgYGB" +
            "xMHjvvGXEOd+/3+/3++Xf3fvlJE89EGEGBiM9Lnfp5gwBKklyxQFCEyTH1CUHiVLfwAQYykJYySKNCIdCJNCI9CQVVv/jv/2l3rU1mj8/rf7fv" +
            "t3KLykyWAm7d9un1zLlAbJIa88888l555PmtKW3rjNEkrGeNchH4bJ9an0uOE4R8thLHRgwxzxnFDuk2zb0td7Fp8E4cK9UU5YSrCDvUEO8A/j" +
            "KYtlYhGMmmUOmEmf/H9P8/7e3BStVfPWHiqtbhUmBnR2eaTHtWnCDU1SuCgw398ltJNJ4STSTSTSTCz0UfSjcZ415liCPdU9/f7Ta2o1ttFFV0" +
            "DIVGrcABCDKIljI4q0Ih1aq+ft+n/zJONXxSc8/fnOKdvaqvJUq8H0ooQs51FLBppwiC6KKKKKKKG9beFsT1ksxLdIs1HRaTvOUgW4AA/DKY1j" +
            "QZKEWmQOlEKtTPH6f9v1/764lSS98ZMjyVV5AUHZ2rpr17vdbebOoNq+ocEK669e/LdtPPOeefmXD6n/AAPsy/3YpEHV1C69BddKuy9zssy1K1" +
            "6cOAAQQyiHZRjoRFohDoRHom1f/Hf/P65d8J7b3m/a/z+FPB6qfFUxBPPOYmbkmQP8qV8gjE3799Pw30venfzUveitLlGlk8iYU5YuuGkrlL51" +
            "qsYqCHM36pftOxbhVrTz85F5RJcAD2MpCWZiErTGHTN331v/j+36/9fxW+KVVpJHqpe8KkwYmQMDSLawthTlgWKb2GaPsrPq6dWc8s6wsLOsco" +
            "nP2mQRAgRMER0FRlH8Op/94jifQ+AxH79+ABCjKQljKOhEOrU9f8b+v95rV3aVr8/e++KdvgTCL2AYGBoEXqVFmba8PlFx+XHjVD8oi+Q2HfOH" +
            "5DATAB91WsCYpjogbGIL8A/jKYtlRgi0xh0ylp/0/8f8/5+Jqky0qVbwqq1vKhMDOzs80mPa6nSFtMXWdjj0F8l6Z+U6J0T8p+U/KUoSlOFYV5" +
            "wzLEtXQ1dW10hXVC0ryr0T6S/DMLX4nAAPoykHZCWKNEIdKIdE7b/+uf4/xd61rLunz989/vUx60uqlARizJqyj8/qYitNQ/f70/vH9pxcJKEp" +
            "cMgqkwK10seJci4rd9iVyPgr3F6rKYIc4zlHEcAPoypUjRFpBLo2+PV+/2/7fz/655rra5z5y45edVxdN3zMGOV1e6637rp+79n8F6c5C9e/1X" +
            "r14d4169mOsrIh2bvmDG8Mnz8iXmxa4MvDf+3R7Fm6k+b9X+UsLilIvCsq01WAR0iGmHAQoymJY0QMtCItCM//DP9/vJq7s1m/Nd+d3nHN3kp2" +
            "roM7OzhkoyiS7BKkeAEhISE3QmmVmKrSrlJ8Dbc160X8nnNcaSRikktGAavNo+H7bFYKmS+5KJxxTZu0/Yo8cp7vl42FzlYjr/4RyneUdejlOU" +
            "iUb8APgyiHZ2GK9MYdMzOGf6/9vr/186m183Jz1nTzrHHe4JgRxiOH1hbWHGGYHZMoJBXR+Us8s8s8s8sSxq1aofmVnJCqE6zKwzBQd5W7iqcn" +
            "ABAjKIdkLGhErmn/x7/+Xtc11fW8mv2/nfj4oK1TyqZAoooBWSfdJdG8foBRRRQZdBl/qfBZgZ2DDyMAtVoU+HACMzQ81GE4h1KzpAX/gZFhPM" +
            "Jz1vAWhoH9GEl4ROk8BKikWinBYXrLajPZTne8o5VCfoM1fbKGLoU4AA/jKY9CYZN0Qh0Qk0RBM1N/+n9/3/8uvbdxJWsrVY8lzeSkQM7OztJj" +
            "249odabh7G51Azs80jP9N30b3ULovo3GBgnz3L1wBK9Zujco6wYAGWlCzXeE2hNKBb62kv2yAigCcQl49tUC+AAQgyiJYyQKdCIdCIdSrPX/xP" +
            "/vV3ri9Kq/n44/P427fDqpkqJg+XNOdl4Fw8eNzJ5551Tzzn2IJ+l/cpkQKXvd+SZSF8CfxSBO0Vj3cA/DKIllYYs0pi0Ih0jdZ5z+/9/5/n2+" +
            "u0zWcUkPGsl3m6q2Cc886m9Pagbwfnutgz8AtQIyPft32b9++m/fRvXFy7Id143pfPuJuKnZ1rH5dr4lTgZKJEZ8ABCDKIljJQp0Yh0Ih0qq+3" +
            "59//a7nHGnFbv3+++fLDupqZKqBxiiWreLUO2/8KMqKIoi4xRfLjFNzIIpqUpcxKp4nsEJatPlqlBJWgT7StSt2qdFFDgAD6MpC2ViEdBCHTKH" +
            "TKzW+P7/+n+f9vvI2rV41DzqZrdVCYMZZfzfyNwP8OqGiXQzKK/PsqXZp2aZ5aJaJZ5YVIoooiRFMjGGwMJUwyn6xcqM37cY528bRRFOWLcAEG" +
            "Moh2QY6Eg6EQ6ESaERaFif9vf/v1bi7u7qvz+H2/nbuKy8SFDneeNYcjz/XysBNHwa9evXPWnObPPXO6XCsSU7nVc6pQfWspFEZQ+t4N6OrpW9" +
            "hMbbsil7yycR4p/lFOqhym2VDLe/AA/jKY1kYop0xh0Rh0ZlcfP7f6/6f9b870yVqVMt4qrXMQUGdnZ2fHuZwsKOvS/yc1fpbOz44TOzs7Ozm7" +
            "P0Ydk5I2ns2Sv0tTlsjMtyLtbx2u1oIaC8p/bV4BTDKTGtSP9e/9uNa4i/PM4qazRxXEkSSAAABi21QAAAAAw48PDw8PW4gQAAAAAO8YeHh593" +
            "gAAAAYd3Hh4eHrd+ABRjKZyK1iBe18eN8/x9ffXC5t8d2kOe4iSRP5CrnnnLKUZZd+X7Xde1fHPNQK7LLLLLLLLKIWAAAAAAH6gYjDw8PDw9t7" +
            "AAAAABh3Hh4eHtu63EAAAAABjgFCMotaUi6FzvfN/HmqmvblxeXbVSzmkk8wnAd9/f394W3ff39/f3Isnx8fHx8AZ/f39/cgPj4+PgwGf39/fQ" +
            "AMnx8fDYDvv7+8IAGf39/cAP/Hx8NgDP7+8IAHx8ZgAM/v7wgAcAE+MosakiOcqq57nxfH1trVS1o6qkkkfhAd9/f39/d33h9/fR7gsnx8fHx8" +
            "Ae/v7+/uQP8fHx8NgZ/f399AA+Pj4d9/f39yA+Pj4+MwCff39/cgPj4+PhsB334AAA=="
    }
}
