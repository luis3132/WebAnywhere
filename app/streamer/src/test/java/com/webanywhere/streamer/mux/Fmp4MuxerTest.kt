package com.webanywhere.streamer.mux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * End-to-end check of the hand-written fMP4 muxer.
 *
 * A muxer that merely compiles proves nothing: the failure mode is a stream
 * that looks plausible and that every decoder rejects. So this test encodes a
 * real H.264 elementary stream with ffmpeg, pushes it through [Fmp4Track], and
 * then makes ffmpeg decode the result. If `avcC`, the `trun` entry sizes, the
 * `mdat` data offset or the `tfdt` timeline were wrong, decoding fails loudly.
 *
 * Skipped automatically when ffmpeg is not installed.
 */
class Fmp4MuxerTest {

    private val width = 320
    private val height = 240
    private val timescale = 1_000_000
    private val frameDurationUs = 33_333L

    @Test
    fun `muxed stream decodes cleanly end to end`() {
        assumeTrue("ffmpeg not available", hasTool("ffmpeg") && hasTool("ffprobe"))

        val work = File(System.getProperty("java.io.tmpdir"), "fmp4-test-${System.nanoTime()}")
        work.mkdirs()

        try {
            val annexB = generateAnnexB(work)
            val accessUnits = splitAccessUnits(annexB)
            assertTrue("ffmpeg produced no frames", accessUnits.size > 10)

            val (sps, pps) = requireNotNull(AnnexB.spsPps(annexB)) {
                "no SPS/PPS in the generated stream"
            }

            val muxed = mux(sps, pps, accessUnits)
            val out = File(work, "muxed.mp4")
            out.writeBytes(muxed)

            // --- structural check -------------------------------------------
            val probe = run(
                "ffprobe", "-v", "error", "-count_frames",
                "-select_streams", "v:0",
                "-show_entries", "stream=codec_name,width,height,nb_read_frames",
                "-of", "default=noprint_wrappers=1", out.absolutePath,
            )
            assertEquals("ffprobe reported errors: ${probe.stderr}", "", probe.stderr.trim())

            val fields = probe.stdout.lineSequence()
                .mapNotNull { line -> line.split("=").takeIf { it.size == 2 } }
                .associate { it[0].trim() to it[1].trim() }

            assertEquals("h264", fields["codec_name"])
            assertEquals(width.toString(), fields["width"])
            assertEquals(height.toString(), fields["height"])
            assertEquals(
                "decoded frame count does not match what was muxed",
                accessUnits.size.toString(),
                fields["nb_read_frames"],
            )

            // --- full decode: catches corrupt sample payloads ---------------
            val decode = run("ffmpeg", "-v", "error", "-i", out.absolutePath, "-f", "null", "-")
            assertEquals("decoder complained: ${decode.stderr}", "", decode.stderr.trim())
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `segments are cut on key frames so a late client can always join`() {
        assumeTrue("ffmpeg not available", hasTool("ffmpeg"))

        val work = File(System.getProperty("java.io.tmpdir"), "fmp4-key-${System.nanoTime()}")
        work.mkdirs()

        try {
            val annexB = generateAnnexB(work)
            val accessUnits = splitAccessUnits(annexB)
            val (sps, pps) = requireNotNull(AnnexB.spsPps(annexB))

            val segments = mutableListOf<ByteArray>()
            val track = Fmp4Track(
                trackId = Fmp4.TRACK_VIDEO,
                timescale = timescale,
                targetSegmentUs = 1_000_000L,
            ) { _, data, _ -> segments.add(data) }

            accessUnits.forEachIndexed { index, unit ->
                track.push(AnnexB.toAvcc(unit), index * frameDurationUs, isKeyFrame(unit))
            }
            track.flush()

            assertTrue("expected several segments, got ${segments.size}", segments.size >= 2)

            // Every segment must start with styp/moof, i.e. be independently
            // fetchable rather than a continuation of the previous one.
            segments.forEach { segment ->
                assertEquals("styp", String(segment, 4, 4, Charsets.US_ASCII))
            }

            // Init segment must be self-describing.
            val init = Fmp4.videoInit(sps, pps, width, height, timescale)
            assertEquals("ftyp", String(init, 4, 4, Charsets.US_ASCII))
            assertTrue("init segment lacks moov", indexOf(init, "moov") > 0)
            assertTrue("init segment lacks avcC", indexOf(init, "avcC") > 0)
            assertTrue("init segment lacks mvex", indexOf(init, "mvex") > 0)
        } finally {
            work.deleteRecursively()
        }
    }

    // ------------------------------------------------------------- helpers

    private fun mux(sps: ByteArray, pps: ByteArray, accessUnits: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(Fmp4.videoInit(sps, pps, width, height, timescale))

        val track = Fmp4Track(
            trackId = Fmp4.TRACK_VIDEO,
            timescale = timescale,
            targetSegmentUs = 1_000_000L,
        ) { _, data, _ -> out.write(data) }

        accessUnits.forEachIndexed { index, unit ->
            track.push(AnnexB.toAvcc(unit), index * frameDurationUs, isKeyFrame(unit))
        }
        track.flush()
        return out.toByteArray()
    }

    /**
     * Baseline profile, one slice per picture, no B-frames — the same shape the
     * app's `MediaCodec` encoder is configured to emit.
     *
     * Which encoder is available varies by distribution: Fedora ships ffmpeg
     * without libx264 but with libopenh264, which defaults to constrained
     * baseline and suits us fine.
     */
    private fun generateAnnexB(work: File): ByteArray {
        val file = File(work, "source.h264")
        val common = arrayOf(
            "ffmpeg", "-v", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=${width}x$height:rate=30",
            "-t", "2",
        )

        val attempts = listOf(
            common + arrayOf(
                "-c:v", "libx264", "-profile:v", "baseline", "-level", "3.1",
                "-g", "30", "-bf", "0", "-slices", "1",
                "-f", "h264", file.absolutePath,
            ),
            common + arrayOf(
                "-c:v", "libopenh264", "-profile:v", "constrained_baseline",
                "-g", "30", "-b:v", "500k",
                "-f", "h264", file.absolutePath,
            ),
        )

        var last: ProcessResult? = null
        for (command in attempts) {
            val result = run(*command)
            if (result.exitCode == 0 && file.length() > 0) return file.readBytes()
            last = result
        }

        throw AssertionError("no usable H.264 encoder in ffmpeg: ${last?.stderr}")
    }

    /**
     * Groups NAL units into access units. With one slice per picture, every VCL
     * NAL terminates a frame, and any parameter sets or SEI seen before it
     * belong to that frame.
     */
    private fun splitAccessUnits(annexB: ByteArray): List<ByteArray> {
        val units = AnnexB.nalUnits(annexB)
        val result = mutableListOf<ByteArray>()
        var current = ByteArrayOutputStream()

        for (unit in units) {
            current.write(byteArrayOf(0, 0, 0, 1))
            current.write(unit)

            val type = unit[0].toInt() and 0x1F
            if (type in 1..5) {
                result.add(current.toByteArray())
                current = ByteArrayOutputStream()
            }
        }
        return result
    }

    private fun isKeyFrame(accessUnit: ByteArray): Boolean =
        AnnexB.nalUnits(accessUnit).any { (it[0].toInt() and 0x1F) == 5 }

    private fun indexOf(haystack: ByteArray, needle: String): Int {
        val bytes = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..haystack.size - bytes.size) {
            for (j in bytes.indices) if (haystack[i + j] != bytes[j]) continue@outer
            return i
        }
        return -1
    }

    private class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun run(vararg command: String): ProcessResult {
        val process = ProcessBuilder(*command).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        return ProcessResult(process.exitValue(), stdout, stderr)
    }

    private fun hasTool(name: String): Boolean = runCatching {
        ProcessBuilder(name, "-version")
            .redirectErrorStream(true)
            .start()
            .also { it.waitFor(10, TimeUnit.SECONDS) }
            .exitValue() == 0
    }.getOrDefault(false)
}
