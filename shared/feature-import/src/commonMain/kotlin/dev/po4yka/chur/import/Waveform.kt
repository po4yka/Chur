package dev.po4yka.chur.imports

/**
 * The audio waveform record of `docs/interop/MEDIA_PIPELINE.md` §6.1.
 *
 * §1 puts decoding on the platform and everything else in Rust, and a waveform
 * sits awkwardly across that line: the samples come from a platform decoder,
 * but the bytes that reach a container are one format both hosts write and
 * shared code draws. The split here follows the same rule as the rest of the
 * module. A platform supplies samples; this folds them into the record, and it
 * does so in common code so the folding is the same on both hosts and can be
 * tested without a device.
 *
 * Rust validates the result again before it seals it, so nothing here is the
 * only check.
 */
object Waveform {

    /** The `record_version` of a v1 waveform. */
    const val VERSION: Byte = 0x01

    /** The fixed head, before the peaks. */
    const val HEAD_LEN = 8

    /** Largest bucket count one record carries, §6.1. */
    const val BUCKETS_MAX = 4_096

    /**
     * The bucket count a v1 generator produces.
     *
     * It is a generator choice rather than a format constant: the record
     * carries its own count, and a reader resamples to whatever width it draws
     * at. 512 is about one bucket per screen pixel on a phone-width scrubber,
     * which is as fine as a drawing can use.
     */
    const val BUCKETS = 512

    /**
     * Folds a stream of PCM samples into a fixed number of peak buckets.
     *
     * The accumulator holds one bucket count of bytes whatever the recording's
     * length, so a four-hour import costs the same memory as a four-second one.
     * That is the same bound `MEDIA_PIPELINE.md` §12 puts on every other import
     * buffer.
     *
     * Samples arrive interleaved across channels and are folded together: a
     * waveform is drawn as one envelope, so the loudest channel at an instant
     * is what the drawing shows.
     */
    class Accumulator(private val expectedFrames: Long, private val buckets: Int = BUCKETS) {
        init {
            require(buckets in 1..BUCKETS_MAX) { "a waveform carries 1 to $BUCKETS_MAX buckets" }
        }

        private val peaks = IntArray(buckets)
        private var frames = 0L

        /**
         * Samples per bucket, for a recording whose length was not reported.
         *
         * It starts at one and doubles whenever the samples outgrow the
         * buckets, which is what keeps the slices equal without knowing the
         * length in advance.
         */
        private var scale = 1L

        /**
         * Adds one 16-bit signed sample.
         *
         * `Short.MIN_VALUE` has no positive counterpart in a `Short`, so the
         * magnitude is taken in `Int`; taking it in place would wrap it back to
         * itself and record silence for the loudest possible sample.
         */
        fun add(sample: Short) {
            val index = bucketOf(frames)
            val magnitude = if (sample < 0) -sample.toInt() else sample.toInt()
            if (magnitude > peaks[index]) peaks[index] = magnitude
            frames++
        }

        /** Adds a block of little-endian 16-bit samples. */
        fun addAll(pcm: ByteArray, length: Int = pcm.size) {
            var offset = 0
            while (offset + 1 < length) {
                val low = pcm[offset].toInt() and 0xff
                val high = pcm[offset + 1].toInt()
                add(((high shl 8) or low).toShort())
                offset += 2
            }
        }

        /**
         * The bucket a frame falls in.
         *
         * A decoder often yields a few more or fewer frames than the container
         * declared, so the index is clamped rather than trusted.
         *
         * A recording whose length the container did not report is the case
         * that needs care. Both hosts report a duration of zero when the
         * metadata is absent, and a rule that wrapped the index modulo the
         * bucket count would fold the second pass over a recording onto the
         * first: §6.1 calls the record "a peak envelope over equal slices of a
         * recording", and a wrapped envelope is a superposition of several. The
         * accumulator grows its slice instead, doubling [scale] each time the
         * samples run past the buckets, and folding what it already holds in
         * half. That keeps the slices equal, keeps the memory one bucket array,
         * and costs only resolution: a recording of unknown length ends at
         * between half and all of the buckets it would have used.
         */
        private fun bucketOf(frame: Long): Int {
            if (expectedFrames > 0) {
                val index = frame * buckets / expectedFrames
                return index.coerceIn(0L, (buckets - 1).toLong()).toInt()
            }
            while (frame / scale >= buckets) {
                halve()
            }
            return (frame / scale).toInt()
        }

        /**
         * Doubles the slice, folding each pair of buckets into one.
         *
         * The fold takes the louder of the pair, because a peak envelope's
         * value over a wider slice is the peak over that slice.
         */
        private fun halve() {
            for (index in 0 until buckets / 2) {
                peaks[index] = maxOf(peaks[index * 2], peaks[index * 2 + 1])
            }
            for (index in buckets / 2 until buckets) {
                peaks[index] = 0
            }
            scale *= 2
        }

        /** How many samples have been folded in. */
        val sampleCount: Long get() = frames

        /**
         * Encodes the record.
         *
         * The peaks are normalized against the loudest one, so a quiet
         * recording draws at full height rather than as a flat line. A silent
         * recording has no loudest sample and encodes as silence.
         */
        fun encode(durationMs: Long): ByteArray {
            val loudest = peaks.max()
            val scaled = ByteArray(buckets)
            if (loudest > 0) {
                for (index in 0 until buckets) {
                    scaled[index] = (peaks[index] * 255 / loudest).toByte()
                }
            }
            return record(durationMs, scaled)
        }
    }

    /**
     * Encodes one record from a peak envelope.
     *
     * Integers are unsigned big-endian per `CANONICAL_ENCODING_V1.md` §2.
     */
    fun record(durationMs: Long, peaks: ByteArray): ByteArray {
        require(peaks.isNotEmpty() && peaks.size <= BUCKETS_MAX) {
            "a waveform carries 1 to $BUCKETS_MAX buckets"
        }
        require(durationMs in 0..MediaBounds.DURATION_MS_MAX) {
            "a waveform's duration is inside the four-hour bound of §12"
        }
        val out = ByteArray(HEAD_LEN + peaks.size)
        out[0] = VERSION
        out[1] = 0
        out[2] = ((peaks.size shr 8) and 0xff).toByte()
        out[3] = (peaks.size and 0xff).toByte()
        out[4] = ((durationMs shr 24) and 0xff).toByte()
        out[5] = ((durationMs shr 16) and 0xff).toByte()
        out[6] = ((durationMs shr 8) and 0xff).toByte()
        out[7] = (durationMs and 0xff).toByte()
        peaks.copyInto(out, HEAD_LEN)
        return out
    }

    /**
     * Reads a record's peak envelope, for the surface that draws it.
     *
     * It returns `null` for anything that is not a v1 record. A drawing has no
     * useful response to a malformed waveform beyond not drawing one, and Rust
     * refused to seal such a record in the first place.
     */
    fun peaksOf(record: ByteArray): ByteArray? {
        if (record.size < HEAD_LEN || record[0] != VERSION || record[1] != 0.toByte()) return null
        val count = ((record[2].toInt() and 0xff) shl 8) or (record[3].toInt() and 0xff)
        if (record.size != HEAD_LEN + count || count == 0) return null
        return record.copyOfRange(HEAD_LEN, record.size)
    }

    /** A record's declared duration in milliseconds, or `null` when it is not one. */
    fun durationMsOf(record: ByteArray): Long? {
        if (peaksOf(record) == null) return null
        return ((record[4].toLong() and 0xff) shl 24) or
            ((record[5].toLong() and 0xff) shl 16) or
            ((record[6].toLong() and 0xff) shl 8) or
            (record[7].toLong() and 0xff)
    }
}
