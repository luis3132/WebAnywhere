package com.webanywhere.streamer.mux

/**
 * Fragmented-MP4 writer, the transport that makes this whole project work.
 *
 * Two kinds of output:
 *  - an **init segment** (`ftyp` + `moov`) describing the track, fetched once;
 *  - **media segments** (`moof` + `mdat`), one per GOP, fetched forever.
 *
 * This is exactly the shape YouTube feeds to MSE, which is why an ancient
 * infotainment WebView can play it without understanding HLS or DASH.
 *
 * `MediaMuxer` cannot produce this: it only writes complete, seekable MP4
 * files, and it needs to rewind to patch the `moov` on stop. Hence by hand.
 */
internal object Fmp4 {

    /** One encoded frame, ready to be placed in an `mdat`. */
    class Sample(
        val data: ByteArray,
        val durationTicks: Int,
        val isSync: Boolean,
    )

    // Sample flags per ISO/IEC 14496-12. `sample_depends_on = 2` means "I-frame,
    // depends on nothing"; the non-sync variant also sets is_non_sync_sample.
    private const val FLAGS_SYNC = 0x02000000
    private const val FLAGS_NON_SYNC = 0x01010000

    // ---------------------------------------------------------------- init

    fun videoInit(sps: ByteArray, pps: ByteArray, width: Int, height: Int, timescale: Int): ByteArray {
        val ftyp = box(
            "ftyp",
            Buf(24).fourcc("iso5").u32(512)
                .fourcc("iso5").fourcc("iso6").fourcc("mp41").fourcc("avc1").fourcc("dash")
                .build(),
        )
        val avcc = avcC(sps, pps)
        val entry = box("avc1", visualSampleEntry(width, height), avcc)
        val trak = trak(
            trackId = TRACK_VIDEO,
            timescale = timescale,
            handler = "vide",
            handlerName = "VideoHandler",
            mediaHeader = fullBox("vmhd", 0, 1, Buf(8).u16(0).u16(0).u16(0).u16(0).build()),
            sampleEntry = entry,
            width = width,
            height = height,
            volume = 0,
        )
        return ftyp + box("moov", mvhd(timescale), trak, mvex(TRACK_VIDEO))
    }

    fun audioInit(audioSpecificConfig: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val ftyp = box(
            "ftyp",
            Buf(24).fourcc("iso5").u32(512)
                .fourcc("iso5").fourcc("iso6").fourcc("mp41").fourcc("dash")
                .build(),
        )
        val entry = box("mp4a", audioSampleEntry(sampleRate, channels), esds(audioSpecificConfig))
        val trak = trak(
            trackId = TRACK_AUDIO,
            timescale = sampleRate,
            handler = "soun",
            handlerName = "SoundHandler",
            mediaHeader = fullBox("smhd", 0, 0, Buf(4).u16(0).u16(0).build()),
            sampleEntry = entry,
            width = 0,
            height = 0,
            volume = 0x0100,
        )
        return ftyp + box("moov", mvhd(sampleRate), trak, mvex(TRACK_AUDIO))
    }

    // ------------------------------------------------------------- segment

    /**
     * Builds one media segment.
     *
     * `mdat` offsets are computed analytically rather than by patching bytes
     * afterwards: every box in a `moof` has a fixed size once the sample count
     * is known, so the arithmetic below is exact and asserted at the end.
     */
    fun segment(
        sequenceNumber: Long,
        trackId: Int,
        baseMediaDecodeTime: Long,
        samples: List<Sample>,
    ): ByteArray {
        require(samples.isNotEmpty()) { "refusing to write an empty segment" }

        val n = samples.size
        val trunSize = 8 + 4 + 4 + 4 + 12 * n   // header + fullbox + count + dataOffset + entries
        val tfhdSize = 8 + 4 + 4
        val tfdtSize = 8 + 4 + 8
        val trafSize = 8 + tfhdSize + tfdtSize + trunSize
        val mfhdSize = 8 + 4 + 4
        val moofSize = 8 + mfhdSize + trafSize
        val dataOffset = moofSize + 8               // first byte of mdat payload

        val trunBody = Buf(8 + 12 * n).u32(n).u32(dataOffset)
        for (s in samples) {
            trunBody.u32(s.durationTicks)
            trunBody.u32(s.data.size)
            trunBody.u32(if (s.isSync) FLAGS_SYNC else FLAGS_NON_SYNC)
        }

        val trun = fullBox("trun", 0, TRUN_FLAGS, trunBody.build())
        // default-base-is-moof: sample offsets are relative to this moof, which
        // is what lets segments be served standalone with no file context.
        val tfhd = fullBox("tfhd", 0, 0x020000, Buf(4).u32(trackId).build())
        val tfdt = fullBox("tfdt", 1, 0, Buf(8).u64(baseMediaDecodeTime).build())
        val traf = box("traf", tfhd, tfdt, trun)
        val mfhd = fullBox("mfhd", 0, 0, Buf(4).u32(sequenceNumber).build())
        val moof = box("moof", mfhd, traf)

        check(moof.size == moofSize) { "moof size mismatch: ${moof.size} != $moofSize" }

        var payload = 0
        for (s in samples) payload += s.data.size
        val mdat = Buf(8 + payload).u32(8 + payload).fourcc("mdat")
        for (s in samples) mdat.raw(s.data)

        val styp = box("styp", Buf(16).fourcc("msdh").u32(0).fourcc("msdh").fourcc("msix").build())
        return styp + moof + mdat.build()
    }

    /** RFC 6381 codec string, e.g. `avc1.42E01F`. The client needs it for MSE. */
    fun avcCodecString(sps: ByteArray): String {
        if (sps.size < 4) return "avc1.42E01F"
        // Locale.ROOT: a locale with non-ASCII digits would produce a codec
        // string that isTypeSupported() rejects.
        return String.format(java.util.Locale.ROOT, "avc1.%02X%02X%02X", sps[1], sps[2], sps[3])
    }

    const val TRACK_VIDEO = 1
    const val TRACK_AUDIO = 2

    // data-offset | sample-duration | sample-size | sample-flags
    private const val TRUN_FLAGS = 0x000001 or 0x000100 or 0x000200 or 0x000400

    // ------------------------------------------------------------ internals

    private fun mvhd(timescale: Int): ByteArray = fullBox(
        "mvhd", 0, 0,
        Buf(96)
            .u32(0).u32(0)          // creation / modification time
            .u32(timescale)
            .u32(0)                 // duration: unknown, this is live
            .u32(0x00010000)        // rate 1.0
            .u16(0x0100)            // volume 1.0
            .u16(0).zeros(8)        // reserved
            .raw(UNITY_MATRIX)
            .zeros(24)              // pre_defined
            .u32(0xFFFFFFFFL)       // next_track_ID
            .build(),
    )

    private fun mvex(trackId: Int): ByteArray = box(
        "mvex",
        fullBox(
            "trex", 0, 0,
            Buf(20).u32(trackId).u32(1).u32(0).u32(0).u32(0).build(),
        ),
    )

    private fun trak(
        trackId: Int,
        timescale: Int,
        handler: String,
        handlerName: String,
        mediaHeader: ByteArray,
        sampleEntry: ByteArray,
        width: Int,
        height: Int,
        volume: Int,
    ): ByteArray {
        val tkhd = fullBox(
            "tkhd", 0, 0x000007,    // enabled | in movie | in preview
            Buf(84)
                .u32(0).u32(0)      // creation / modification
                .u32(trackId)
                .u32(0)             // reserved
                .u32(0)             // duration
                .zeros(8)           // reserved
                .u16(0)             // layer
                .u16(0)             // alternate_group
                .u16(volume)
                .u16(0)             // reserved
                .raw(UNITY_MATRIX)
                .u32(width shl 16)  // 16.16 fixed point
                .u32(height shl 16)
                .build(),
        )

        val mdhd = fullBox(
            "mdhd", 0, 0,
            Buf(20)
                .u32(0).u32(0)
                .u32(timescale)
                .u32(0)             // duration
                .u16(0x55C4)        // language: "und"
                .u16(0)             // pre_defined
                .build(),
        )

        val hdlr = fullBox(
            "hdlr", 0, 0,
            Buf(32)
                .u32(0)
                .fourcc(handler)
                .zeros(12)
                .raw(handlerName.toByteArray(Charsets.UTF_8))
                .u8(0)
                .build(),
        )

        val dinf = box("dinf", box("dref", Buf(8).u8(0).u24(0).u32(1).build(), fullBox("url ", 0, 1)))

        val stbl = box(
            "stbl",
            fullBox("stsd", 0, 0, Buf(4).u32(1).build(), sampleEntry),
            fullBox("stts", 0, 0, Buf(4).u32(0).build()),
            fullBox("stsc", 0, 0, Buf(4).u32(0).build()),
            fullBox("stsz", 0, 0, Buf(8).u32(0).u32(0).build()),
            fullBox("stco", 0, 0, Buf(4).u32(0).build()),
        )

        val minf = box("minf", mediaHeader, dinf, stbl)
        val mdia = box("mdia", mdhd, hdlr, minf)
        return box("trak", tkhd, mdia)
    }

    private fun visualSampleEntry(width: Int, height: Int): ByteArray {
        val compressorName = ByteArray(32) // 1 length byte + 31 padding, all zero is legal
        return Buf(78)
            .zeros(6).u16(1)            // reserved + data_reference_index
            .u16(0).u16(0).zeros(12)    // pre_defined / reserved
            .u16(width).u16(height)
            .u32(0x00480000)            // 72 dpi horizontal
            .u32(0x00480000)            // 72 dpi vertical
            .u32(0)                     // reserved
            .u16(1)                     // frame_count
            .raw(compressorName)
            .u16(0x0018)                // depth: 24-bit colour
            .u16(0xFFFF)                // pre_defined: -1
            .build()
    }

    private fun audioSampleEntry(sampleRate: Int, channels: Int): ByteArray = Buf(28)
        .zeros(6).u16(1)                // reserved + data_reference_index
        .u16(0).u16(0).u32(0)           // version / revision / vendor
        .u16(channels).u16(16)          // channel count / sample size
        .u16(0).u16(0)                  // compression id / packet size
        .u32(sampleRate.toLong() shl 16)
        .build()

    private fun avcC(sps: ByteArray, pps: ByteArray): ByteArray = box(
        "avcC",
        Buf(16 + sps.size + pps.size)
            .u8(1)                      // configurationVersion
            .u8(sps[1].toInt() and 0xFF) // AVCProfileIndication
            .u8(sps[2].toInt() and 0xFF) // profile_compatibility
            .u8(sps[3].toInt() and 0xFF) // AVCLevelIndication
            .u8(0xFF)                   // 6 bits reserved + lengthSizeMinusOne = 3
            .u8(0xE1)                   // 3 bits reserved + numOfSequenceParameterSets = 1
            .u16(sps.size).raw(sps)
            .u8(1)                      // numOfPictureParameterSets
            .u16(pps.size).raw(pps)
            .build(),
    )

    /**
     * MPEG-4 elementary stream descriptor wrapping the AAC AudioSpecificConfig.
     * Descriptor lengths use the 4-byte relaxed encoding that ffmpeg emits and
     * every decoder accepts.
     */
    private fun esds(audioSpecificConfig: ByteArray): ByteArray {
        val dsi = descriptor(0x05, audioSpecificConfig)
        val dcd = descriptor(
            0x04,
            Buf(14 + dsi.size)
                .u8(0x40)               // objectTypeIndication: MPEG-4 audio
                .u8(0x15)               // streamType audio(5)<<2 | upStream 0 | reserved 1
                .u24(0)                 // bufferSizeDB
                .u32(0)                 // maxBitrate
                .u32(0)                 // avgBitrate
                .raw(dsi)
                .build(),
        )
        val sl = descriptor(0x06, byteArrayOf(0x02))
        val es = descriptor(
            0x03,
            Buf(3 + dcd.size + sl.size).u16(0).u8(0).raw(dcd).raw(sl).build(),
        )
        return fullBox("esds", 0, 0, es)
    }

    private fun descriptor(tag: Int, payload: ByteArray): ByteArray = Buf(5 + payload.size)
        .u8(tag)
        .u8(0x80).u8(0x80).u8(0x80).u8(payload.size)
        .raw(payload)
        .build()
}
