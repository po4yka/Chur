package dev.po4yka.chur.app.vault

import dev.po4yka.chur.core.model.ChurStatus
import dev.po4yka.chur.ffi.ChurFailure
import dev.po4yka.chur.vault.VaultState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Container declarations only. They do not establish native decoder support. */
internal data class PlaybackContainerFacts(
    val video: List<PlaybackVideoDetails> = emptyList(),
    val audio: List<PlaybackAudioFormat> = emptyList(),
    val complete: Boolean = false,
)

/** Inspect only authenticated headers and bounded moov bytes, including compound-resource offsets. */
internal suspend fun inspectPlaybackContainer(source: PlaybackSource, expectedGeneration: Long? = null): PlaybackContainerFacts =
    withContext(Dispatchers.Default) {
        val generation = expectedGeneration ?: (source.vault.state.value as? VaultState.Unlocked)?.generation
            ?: throw ChurFailure(ChurStatus.VAULT_LOCKED, "the playback session has ended")
        val reader = source.vault.leaseReader(source.objectId, source.kind, generation)
        try {
            val info = source.vault.readerContentInfo(reader)
            if (!info.complete) throw ChurFailure(ChurStatus.OBJECT_INCOMPLETE, "the playback source is incomplete")
            if (source.offset < 0 || source.length < 0 || source.offset > info.plaintextSize ||
                source.length > info.plaintextSize - source.offset) {
                throw ChurFailure(ChurStatus.INVALID_INPUT, "the playback resource range is invalid")
            }
            val result = inspectPlaybackContainerRanges(source.length) { offset, size ->
                source.vault.readLeased(reader, source.offset + offset, size)
            }
            if ((source.vault.state.value as? VaultState.Unlocked)?.generation != generation) {
                throw ChurFailure(ChurStatus.SESSION_EXPIRED, "the playback session changed")
            }
            result
        } finally { source.vault.releaseReader(reader) }
    }

private const val MAX_MOOV_BYTES = 1024 * 1024
private const val MAX_TOP_BOXES = 128
private const val MAX_BOXES = 1024
private const val MAX_DEPTH = 8
private const val MAX_TRACKS = 8
private const val MAX_DESCRIPTIONS = 8
private class InspectionStopped : Exception()
private fun bounded(condition: Boolean) { if (!condition) throw InspectionStopped() }

/** A short authenticated read is unavailable data, not evidence of a malformed container. */
private fun exactRead(read: (Long, Int) -> ByteArray, offset: Long, size: Int): ByteArray {
    val bytes = read(offset, size)
    if (bytes.size != size) {
        bytes.fill(0)
        throw ChurFailure(ChurStatus.IO_FAILURE, "the playback header could not be read")
    }
    return bytes
}

/** Testable range boundary. Every returned read buffer is wiped, including rejected headers. */
internal fun inspectPlaybackContainerRanges(length: Long, read: (Long, Int) -> ByteArray): PlaybackContainerFacts {
    var moov: ByteArray? = null
    try {
        bounded(length >= 0)
        var offset = 0L
        var count = 0
        while (offset < length) {
            bounded(++count <= MAX_TOP_BOXES && length - offset >= 8)
            val header = exactRead(read, offset, 8)
            var headerSize = 8
            val type: Int
            var size: Long
            try { size = u32(header, 0); type = fourcc(header, 4) } finally { header.fill(0) }
            if (size == 1L) {
                bounded(length - offset >= 16)
                val extended = exactRead(read, offset + 8, 8)
                try {
                    bounded(extended[0].toInt() and 0x80 == 0)
                    size = (u32(extended, 0) shl 32) or u32(extended, 4)
                } finally { extended.fill(0) }
                headerSize = 16
            } else if (size == 0L) size = length - offset
            bounded(size >= headerSize && size <= length - offset)
            if (type == MOOV) {
                bounded(moov == null && size <= MAX_MOOV_BYTES)
                moov = exactRead(read, offset + headerSize, (size - headerSize).toInt())
            }
            offset += size
        }
        return moov?.let { Declarations(it).parse() } ?: PlaybackContainerFacts()
    } catch (_: InspectionStopped) {
        return PlaybackContainerFacts()
    } finally { moov?.fill(0) }
}

private data class Box(val type: Int, val body: Int, val end: Int)

private class Declarations(private val bytes: ByteArray) {
    private var boxCount = 0
    private var descriptorCount = 0

    fun parse(): PlaybackContainerFacts {
        val tracks = boxes(0, bytes.size, 0).filter { it.type == TRAK }
        bounded(tracks.size <= MAX_TRACKS)
        val videoTracks = mutableListOf<PlaybackVideoDetails>()
        val audioTracks = mutableListOf<PlaybackAudioFormat>()
        for (track in tracks) {
            val mdia = only(boxes(track.body, track.end, 1), MDIA) ?: continue
            val children = boxes(mdia.body, mdia.end, 2)
            val handler = only(children, HDLR) ?: continue
            bounded(handler.end - handler.body >= 12)
            val kind = fourcc(bytes, handler.body + 8)
            if (kind != VIDE && kind != SOUN) continue
            val minf = only(children, MINF) ?: throw InspectionStopped()
            val stbl = only(boxes(minf.body, minf.end, 3), STBL) ?: throw InspectionStopped()
            val stsd = only(boxes(stbl.body, stbl.end, 4), STSD) ?: throw InspectionStopped()
            bounded(stsd.end - stsd.body >= 8 && bytes[stsd.body].toInt() == 0)
            val count = u32(bytes, stsd.body + 4)
            bounded(count in 1..MAX_DESCRIPTIONS.toLong())
            val entries = boxes(stsd.body + 8, stsd.end, 5)
            bounded(entries.size.toLong() == count)
            entries.forEach { entry ->
                if (kind == VIDE) videoTracks += video(entry) else audioTracks += audio(entry)
            }
        }
        return PlaybackContainerFacts(videoTracks.toList(), audioTracks.toList(), complete = true)
    }

    private fun video(entry: Box): PlaybackVideoDetails {
        bounded(entry.end - entry.body >= 78)
        val width = u16(bytes, entry.body + 24).takeIf { it in 1..7680 }
        val height = u16(bytes, entry.body + 26).takeIf { it in 1..7680 }
        val children = boxes(entry.body + 78, entry.end, 6, sampleEntryExtensions = true)
        var format = when (entry.type) {
            HVC1, HEV1 -> PlaybackVideoFormat.HEVC
            DVH1, DVHE -> PlaybackVideoFormat.DOLBY_VISION
            AVC1, AVC3 -> PlaybackVideoFormat.H264
            AV01 -> PlaybackVideoFormat.AV1
            VP09 -> PlaybackVideoFormat.VP9
            else -> PlaybackVideoFormat.UNKNOWN
        }
        if (entry.type == HVC1 || entry.type == HEV1) {
            val configuration = only(children, HVCC)
            if (configuration != null && configuration.end - configuration.body >= 23 && bytes[configuration.body].toInt() == 1) {
                format = when (bytes[configuration.body + 1].toInt() and 31) {
                    1 -> PlaybackVideoFormat.HEVC_MAIN
                    2 -> PlaybackVideoFormat.HEVC_MAIN10
                    else -> if (bytes[configuration.body + 17].toInt() and 7 == 2) PlaybackVideoFormat.HEVC_10_BIT else PlaybackVideoFormat.HEVC
                }
            }
            if (children.any { it.type in listOf(DVCC, DVVC) && it.end - it.body >= 4 }) format = PlaybackVideoFormat.DOLBY_VISION
        }
        return PlaybackVideoDetails(format, width, height)
    }

    private fun audio(entry: Box): PlaybackAudioFormat {
        bounded(entry.end - entry.body >= 28)
        val version = u16(bytes, entry.body + 8)
        val header = when (version) { 0 -> 28; 1 -> 44; 2 -> 64; else -> return PlaybackAudioFormat.UNKNOWN }
        bounded(entry.end - entry.body >= header)
        return when (entry.type) {
            DTSC, DTSH, DTSL, DTSE -> PlaybackAudioFormat.DTS
            AC3 -> PlaybackAudioFormat.AC3
            EC3 -> PlaybackAudioFormat.EAC3
            MLPA -> PlaybackAudioFormat.TRUEHD
            FLAC -> PlaybackAudioFormat.FLAC
            OPUS -> PlaybackAudioFormat.OPUS
            LPCM, SOWT, TWOS -> PlaybackAudioFormat.PCM
            MP4A -> {
                val children = boxes(entry.body + header, entry.end, 6, sampleEntryExtensions = true)
                val configuration = only(children, ESDS) ?: only(children, WAVE)?.let { only(boxes(it.body, it.end, 7, sampleEntryExtensions = true), ESDS) }
                configuration?.let { esds(it) } ?: PlaybackAudioFormat.UNKNOWN
            }
            else -> PlaybackAudioFormat.UNKNOWN
        }
    }

    private fun esds(box: Box): PlaybackAudioFormat {
        bounded(box.end - box.body >= 4 && bytes[box.body].toInt() == 0)
        return descriptors(box.body + 4, box.end, 0) ?: PlaybackAudioFormat.UNKNOWN
    }

    private fun descriptors(start: Int, end: Int, depth: Int, audioSpecificConfig: Boolean = false): PlaybackAudioFormat? {
        bounded(depth <= 4)
        var at = start
        var result: PlaybackAudioFormat? = null
        while (at < end) {
            bounded(++descriptorCount <= 64 && end - at >= 2)
            val tag = bytes[at++].toInt() and 255
            var size = 0
            var done = false
            repeat(4) {
                if (!done) {
                    bounded(at < end)
                    val byte = bytes[at++].toInt() and 255
                    size = (size shl 7) or (byte and 127)
                    done = byte and 128 == 0
                }
            }
            bounded(done && size <= end - at)
            val limit = at + size
            val format = when (tag) {
                3 -> {
                    bounded(limit - at >= 3)
                    val flags = bytes[at + 2].toInt() and 255
                    var child = at + 3
                    if (flags and 128 != 0) child += 2
                    if (flags and 64 != 0) {
                        bounded(child < limit)
                        child += 1 + (bytes[child].toInt() and 255)
                    }
                    if (flags and 32 != 0) child += 2
                    bounded(child <= limit)
                    descriptors(child, limit, depth + 1)
                }
                4 -> {
                    bounded(limit - at >= 13)
                    if ((bytes[at + 1].toInt() ushr 2) and 63 != 5) null else when (bytes[at].toInt() and 255) {
                        in 0xa9..0xac -> PlaybackAudioFormat.DTS
                        0x66, 0x67, 0x68 -> PlaybackAudioFormat.AAC
                        0x40 -> descriptors(at + 13, limit, depth + 1, audioSpecificConfig = true)
                        else -> PlaybackAudioFormat.UNKNOWN
                    }
                }
                5 -> {
                    if (!audioSpecificConfig) null else {
                        bounded(size >= 1)
                        val first = bytes[at].toInt() and 255
                        var objectType = first ushr 3
                        if (objectType == 31) {
                            bounded(size >= 2)
                            objectType = 32 + ((first and 7) shl 3) + ((bytes[at + 1].toInt() and 255) ushr 5)
                        }
                        if (objectType in listOf(1, 2, 3, 4, 5, 6, 17, 19, 20, 21, 22, 23, 29, 39)) PlaybackAudioFormat.AAC else PlaybackAudioFormat.UNKNOWN
                    }
                }
                else -> null
            }
            if (format != null) {
                bounded(result == null || result == format)
                result = format
            }
            at = limit
        }
        return result
    }

    private fun boxes(start: Int, end: Int, depth: Int, sampleEntryExtensions: Boolean = false): List<Box> {
        bounded(depth <= MAX_DEPTH)
        val result = mutableListOf<Box>()
        var at = start
        while (at < end) {
            // QuickTime sample-description extensions can end with one zero32 terminator.
            if (sampleEntryExtensions && end - at == 4 && u32(bytes, at) == 0L) break
            bounded(++boxCount <= MAX_BOXES && end - at >= 8)
            var size = u32(bytes, at)
            var header = 8
            if (size == 1L) {
                bounded(end - at >= 16 && bytes[at + 8].toInt() and 128 == 0)
                size = (u32(bytes, at + 8) shl 32) or u32(bytes, at + 12)
                header = 16
            } else if (size == 0L) size = (end - at).toLong()
            bounded(size >= header && size <= (end - at).toLong())
            result += Box(fourcc(bytes, at + 4), at + header, at + size.toInt())
            at += size.toInt()
        }
        return result
    }

    private fun only(boxes: List<Box>, type: Int): Box? {
        val matches = boxes.filter { it.type == type }
        bounded(matches.size <= 1)
        return matches.firstOrNull()
    }
}

private fun u16(bytes: ByteArray, at: Int): Int = ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
private fun u32(bytes: ByteArray, at: Int): Long = ((u16(bytes, at).toLong() shl 16) or u16(bytes, at + 2).toLong())
private fun fourcc(bytes: ByteArray, at: Int): Int = u32(bytes, at).toInt()
private const val MOOV = 0x6d6f6f76
private const val TRAK = 0x7472616b
private const val MDIA = 0x6d646961
private const val HDLR = 0x68646c72
private const val MINF = 0x6d696e66
private const val STBL = 0x7374626c
private const val STSD = 0x73747364
private const val VIDE = 0x76696465
private const val SOUN = 0x736f756e
private const val HVC1 = 0x68766331
private const val HEV1 = 0x68657631
private const val HVCC = 0x68766343
private const val DVH1 = 0x64766831
private const val DVHE = 0x64766865
private const val DVCC = 0x64766343
private const val DVVC = 0x64767643
private const val AVC1 = 0x61766331
private const val AVC3 = 0x61766333
private const val AV01 = 0x61763031
private const val VP09 = 0x76703039
private const val MP4A = 0x6d703461
private const val ESDS = 0x65736473
private const val WAVE = 0x77617665
private const val DTSC = 0x64747363
private const val DTSH = 0x64747368
private const val DTSL = 0x6474736c
private const val DTSE = 0x64747365
private const val AC3 = 0x61632d33
private const val EC3 = 0x65632d33
private const val MLPA = 0x4d4c5041
private const val FLAC = 0x664c6143
private const val OPUS = 0x4f707573
private const val LPCM = 0x6c70636d
private const val SOWT = 0x736f7774
private const val TWOS = 0x74776f73
