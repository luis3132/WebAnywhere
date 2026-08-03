package com.webanywhere.streamer.mux

import java.io.ByteArrayOutputStream

/**
 * Minimal big-endian writer for ISO-BMFF box construction.
 *
 * Everything in the MP4 world is big-endian and byte-exact, so this stays
 * deliberately dumb: no reflection, no allocation games, just append.
 */
internal class Buf(initialCapacity: Int = 256) {

    private val out = ByteArrayOutputStream(initialCapacity)

    val size: Int get() = out.size()

    fun u8(v: Int): Buf {
        out.write(v and 0xFF)
        return this
    }

    fun u16(v: Int): Buf {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
        return this
    }

    fun u24(v: Int): Buf {
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
        return this
    }

    fun u32(v: Long): Buf {
        out.write(((v ushr 24) and 0xFF).toInt())
        out.write(((v ushr 16) and 0xFF).toInt())
        out.write(((v ushr 8) and 0xFF).toInt())
        out.write((v and 0xFF).toInt())
        return this
    }

    fun u32(v: Int): Buf = u32(v.toLong() and 0xFFFFFFFFL)

    fun u64(v: Long): Buf {
        u32((v ushr 32) and 0xFFFFFFFFL)
        u32(v and 0xFFFFFFFFL)
        return this
    }

    /** Four-character box/brand code. Always exactly 4 ASCII bytes. */
    fun fourcc(s: String): Buf {
        require(s.length == 4) { "fourcc must be 4 chars, got '$s'" }
        for (c in s) out.write(c.code and 0xFF)
        return this
    }

    fun raw(b: ByteArray): Buf {
        out.write(b, 0, b.size)
        return this
    }

    fun zeros(n: Int): Buf {
        repeat(n) { out.write(0) }
        return this
    }

    fun build(): ByteArray = out.toByteArray()
}

/** `[size][type][payload...]` */
internal fun box(type: String, vararg parts: ByteArray): ByteArray {
    var len = 8
    for (p in parts) len += p.size
    val b = Buf(len).u32(len).fourcc(type)
    for (p in parts) b.raw(p)
    return b.build()
}

/** `[size][type][version][flags][payload...]` */
internal fun fullBox(type: String, version: Int, flags: Int, vararg parts: ByteArray): ByteArray {
    val head = Buf(4).u8(version).u24(flags).build()
    val all = ArrayList<ByteArray>(parts.size + 1)
    all.add(head)
    all.addAll(parts)
    return box(type, *all.toTypedArray())
}

/** The identity transform, as 3x3 fixed-point. Required in `mvhd` and `tkhd`. */
internal val UNITY_MATRIX: ByteArray = Buf(36)
    .u32(0x00010000).u32(0).u32(0)
    .u32(0).u32(0x00010000).u32(0)
    .u32(0).u32(0).u32(0x40000000)
    .build()
