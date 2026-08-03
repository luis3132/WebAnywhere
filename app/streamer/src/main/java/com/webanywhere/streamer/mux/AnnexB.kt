package com.webanywhere.streamer.mux

/**
 * MediaCodec hands us H.264 in Annex-B framing (`00 00 00 01` start codes).
 * MP4 wants AVCC framing (4-byte big-endian length prefix per NAL unit).
 * This is the whole difference, and forgetting it is the classic reason a
 * hand-rolled fMP4 muxer produces a file every player rejects.
 */
internal object AnnexB {

    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    /** Splits an Annex-B buffer into raw NAL units (start codes stripped). */
    fun nalUnits(data: ByteArray): List<ByteArray> {
        val result = ArrayList<ByteArray>(4)
        var i = 0
        var start = -1

        while (i < data.size) {
            val code = startCodeLengthAt(data, i)
            if (code > 0) {
                if (start >= 0 && i > start) result.add(data.copyOfRange(start, i))
                i += code
                start = i
            } else {
                i++
            }
        }
        if (start in 0 until data.size) result.add(data.copyOfRange(start, data.size))
        return result
    }

    /**
     * Converts an Annex-B access unit to AVCC.
     *
     * SPS/PPS are dropped because they already live in the `avcC` box of the
     * init segment; access-unit delimiters are dropped because they carry no
     * information a browser decoder needs.
     */
    fun toAvcc(data: ByteArray): ByteArray {
        val units = nalUnits(data).filter {
            when (it[0].toInt() and 0x1F) {
                NAL_SPS, NAL_PPS, NAL_AUD -> false
                else -> true
            }
        }
        if (units.isEmpty()) return ByteArray(0)

        var total = 0
        for (u in units) total += 4 + u.size
        val buf = Buf(total)
        for (u in units) buf.u32(u.size).raw(u)
        return buf.build()
    }

    /**
     * Same conversion as [toAvcc], but rewriting the start codes into length
     * prefixes **inside the buffer it was given**, returning null when that is
     * not possible.
     *
     * [toAvcc] allocates roughly four times the frame: one copy per NAL unit, a
     * list to hold them, the output buffer and its build. At 60 fps and a
     * double-digit megabit bitrate that is megabytes per second of garbage,
     * which shows up as GC pauses in the middle of the encode loop.
     *
     * A start code and a length prefix are both exactly four bytes, so when
     * every start code is the long form and nothing has to be dropped, the
     * conversion is a handful of byte writes and no allocation at all. It bails
     * on key frames, which usually carry SPS/PPS inline — those are rare enough
     * to leave on the slow path.
     */
    fun toAvccInPlace(data: ByteArray): ByteArray? {
        if (data.size < 5 || startCodeLengthAt(data, 0) != 4) return null

        // Validate before mutating: a half-converted buffer is unrecoverable.
        var i = 0
        while (i < data.size) {
            when (startCodeLengthAt(data, i)) {
                3 -> return null
                4 -> {
                    if (i + 4 >= data.size) return null
                    when (data[i + 4].toInt() and 0x1F) {
                        NAL_SPS, NAL_PPS, NAL_AUD -> return null
                    }
                    i += 4
                }
                else -> i++
            }
        }

        var pos = 0
        while (pos < data.size) {
            var next = pos + 4
            while (next < data.size && startCodeLengthAt(data, next) != 4) next++

            val length = next - (pos + 4)
            if (length <= 0) return null

            data[pos] = (length ushr 24).toByte()
            data[pos + 1] = (length ushr 16).toByte()
            data[pos + 2] = (length ushr 8).toByte()
            data[pos + 3] = length.toByte()
            pos = next
        }
        return data
    }

    /** Pulls the first SPS and PPS out of an Annex-B buffer (typically `csd-0`). */
    fun spsPps(data: ByteArray): Pair<ByteArray, ByteArray>? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (u in nalUnits(data)) {
            when (u[0].toInt() and 0x1F) {
                NAL_SPS -> if (sps == null) sps = u
                NAL_PPS -> if (pps == null) pps = u
            }
        }
        val s = sps ?: return null
        val p = pps ?: return null
        return s to p
    }

    private fun startCodeLengthAt(d: ByteArray, i: Int): Int {
        if (i + 3 < d.size &&
            d[i].toInt() == 0 && d[i + 1].toInt() == 0 &&
            d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1
        ) return 4
        if (i + 2 < d.size &&
            d[i].toInt() == 0 && d[i + 1].toInt() == 0 && d[i + 2].toInt() == 1
        ) return 3
        return 0
    }
}
