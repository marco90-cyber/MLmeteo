package com.marco.mlmeteo

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Lettore GRIB2 generico per griglie regolari lat/lon.
 * Compressioni supportate: simple packing (5.0), complex packing con differenze spaziali (5.3)
 * e CCSDS/AEC (5.42, usata da DWD ed ECMWF).
 */
object Grib {

    /** Campo decodificato. Righe da sud a nord, colonne da ovest a est (longitudini -180..180). */
    class Field(
        val ni: Int, val nj: Int,
        val south: Double, val west: Double, val dlat: Double, val dlon: Double,
        val values: FloatArray,
    )

    private class Bits(val b: ByteArray, var pos: Long) {
        fun read(n: Int): Long {
            var v = 0L
            for (k in 0 until n) {
                val byte = b[(pos ushr 3).toInt()].toInt()
                v = (v shl 1) or ((byte shr (7 - (pos and 7).toInt())) and 1).toLong()
                pos++
            }
            return v
        }
        fun fs(): Int { var c = 0; while (read(1) == 0L) c++; return c }
        fun align() { pos = (pos + 7) / 8 * 8 }
    }

    private fun smag(v: Long, bytes: Int): Long {
        val sign = 1L shl (8 * bytes - 1)
        return if (v and sign != 0L) -(v and (sign - 1)) else v
    }

    /** Primo messaggio GRIB contenuto in [bytes]. */
    fun first(bytes: ByteArray): ByteArray {
        if (bytes.size < 16) throw IOException("GRIB vuoto")
        val len = ByteBuffer.wrap(bytes, 8, 8).order(ByteOrder.BIG_ENDIAN).long.toInt()
        return if (len in 16..bytes.size) bytes.copyOf(len) else bytes
    }

    fun decode(b: ByteArray): Field {
        fun u(pos: Int, n: Int): Long { var v = 0L; for (k in 0 until n) v = (v shl 8) or (b[pos + k].toLong() and 0xFF); return v }
        var pos = 16
        val sec = HashMap<Int, Pair<Int, Int>>()
        while (pos + 5 <= b.size) {
            if (b[pos] == '7'.code.toByte() && b[pos + 1] == '7'.code.toByte() && b[pos + 2] == '7'.code.toByte()) break
            val len = u(pos, 4).toInt()
            if (len <= 0) break
            sec[b[pos + 4].toInt()] = pos to len
            pos += len
        }
        // --- griglia
        val (p3, _) = sec[3] ?: throw IOException("GRIB senza griglia")
        fun o(k: Int) = p3 + k - 1
        if (u(o(13), 2).toInt() != 0) throw IOException("Griglia non regolare")
        val ni = u(o(31), 4).toInt()
        val nj = u(o(35), 4).toInt()
        val la1 = smag(u(o(47), 4), 4) / 1e6
        var lo1 = u(o(51), 4) / 1e6
        val la2 = smag(u(o(56), 4), 4) / 1e6
        val di = u(o(64), 4) / 1e6
        val dj = u(o(68), 4) / 1e6
        val scan = b[o(72)].toInt() and 0xFF
        if (lo1 > 180) lo1 -= 360
        val northFirst = (scan and 0x40) == 0
        // --- valori
        val (p5, _) = sec[5] ?: throw IOException("GRIB senza dati")
        val n = u(p5 + 5, 4).toInt()
        val tmpl = u(p5 + 9, 2).toInt()
        val r = ByteBuffer.wrap(b, p5 + 11, 4).order(ByteOrder.BIG_ENDIAN).float.toDouble()
        val e = smag(u(p5 + 15, 2), 2).toInt()
        val d = smag(u(p5 + 17, 2), 2).toInt()
        val nb = b[p5 + 19].toInt() and 0xFF
        val se = 2.0.pow(e)
        val sd = 10.0.pow(-d)
        val (p7, l7) = sec[7] ?: throw IOException("GRIB senza dati")
        val start = p7 + 5
        val packed: LongArray = when (tmpl) {
            0 -> {
                val br = Bits(b, start.toLong() * 8)
                LongArray(n) { if (nb > 0) br.read(nb) else 0L }
            }
            3 -> complex(b, p5, start, n, nb)
            42 -> {
                val flags = b[p5 + 21].toInt() and 0xFF
                val block = b[p5 + 22].toInt() and 0xFF
                val rsi = u(p5 + 23, 2).toInt()
                if (nb == 0) LongArray(n) else aec(b, start, l7 - 5, n, nb, flags, block, rsi)
            }
            else -> throw IOException("Compressione GRIB non supportata ($tmpl)")
        }
        val vals = FloatArray(ni * nj) { Float.NaN }
        // mappa di bit (punti mancanti)
        val bitmap: BooleanArray? = sec[6]?.let { (p6, l6) ->
            if ((b[p6 + 5].toInt() and 0xFF) != 0) null else {
                val br = Bits(b, (p6 + 6).toLong() * 8)
                BooleanArray(ni * nj) { br.read(1) == 1L }
            }
        }
        var k = 0
        for (idx in 0 until ni * nj) {
            if (bitmap != null && !bitmap[idx]) continue
            if (k >= packed.size) break
            val v = ((r + packed[k++] * se) * sd).toFloat()
            // riordina: righe da sud a nord
            val row = idx / ni
            val col = idx % ni
            val outRow = if (northFirst) nj - 1 - row else row
            vals[outRow * ni + col] = v
        }
        val south = if (northFirst) la2 else la1
        return Field(ni, nj, south, lo1, dj, di, vals)
    }

    private fun complex(b: ByteArray, p5: Int, start: Int, n: Int, nb: Int): LongArray {
        fun u(pos: Int, len: Int): Long { var v = 0L; for (k in 0 until len) v = (v shl 8) or (b[pos + k].toLong() and 0xFF); return v }
        fun q(k: Int) = p5 + k - 1
        val miss = b[q(23)].toInt() and 0xFF
        val ng = u(q(32), 4).toInt()
        val wref = b[q(36)].toInt() and 0xFF
        val wbits = b[q(37)].toInt() and 0xFF
        val lref = u(q(38), 4).toInt()
        val linc = b[q(42)].toInt() and 0xFF
        val llast = u(q(43), 4).toInt()
        val lbits = b[q(47)].toInt() and 0xFF
        val order = b[q(48)].toInt() and 0xFF
        val nyb = b[q(49)].toInt() and 0xFF
        if (miss != 0) throw IOException("Valori mancanti non supportati")
        val br = Bits(b, start.toLong() * 8)
        val ival1 = smag(br.read(8 * nyb), nyb)
        val ival2 = if (order == 2) smag(br.read(8 * nyb), nyb) else 0L
        val minsd = smag(br.read(8 * nyb), nyb)
        val refs = LongArray(ng) { br.read(nb) }; br.align()
        val widths = IntArray(ng) { br.read(wbits).toInt() + wref }; br.align()
        val lens = IntArray(ng) { br.read(lbits).toInt() * linc + lref }; br.align()
        lens[ng - 1] = llast
        val x = LongArray(n)
        var k = 0
        for (g in 0 until ng) {
            val w = widths[g]
            for (t in 0 until lens[g]) {
                if (k >= n) break
                x[k++] = refs[g] + if (w > 0) br.read(w) else 0L
            }
        }
        if (order == 0) return x
        if (n > 0) x[0] = ival1
        if (order == 2) {
            if (n > 1) x[1] = ival2
            for (i in 2 until n) x[i] = x[i] + minsd + 2 * x[i - 1] - x[i - 2]
        } else {
            for (i in 1 until n) x[i] = x[i] + minsd + x[i - 1]
        }
        return x
    }

    /** Decompressione CCSDS 121.0 (Adaptive Entropy Coding), come libaec. */
    private fun aec(b: ByteArray, start: Int, len: Int, n: Int, nbits: Int, flags: Int, J: Int, rsi: Int): LongArray {
        val out = LongArray(n)
        var o = 0
        val br = Bits(b, start.toLong() * 8)
        val endBit = (start + len).toLong() * 8
        val pp = flags and 8 != 0
        val pad = flags and 32 != 0
        val restricted = flags and 16 != 0
        val idLen = when {
            restricted && nbits <= 2 -> 1
            restricted && nbits <= 4 -> 2
            nbits <= 8 -> 3
            nbits <= 16 -> 4
            else -> 5
        }
        val xmax = (1L shl nbits) - 1
        val buf = LongArray(rsi * J + J)
        while (o < n && br.pos < endBit) {
            var cnt = 0
            var blocksLeft = rsi
            var first = true
            while (blocksLeft > 0 && o + cnt < n) {
                val ref = pp && first
                val id = br.read(idLen).toInt()
                if (id == 0) {
                    val bit = br.read(1)
                    if (ref) buf[cnt++] = br.read(nbits)
                    if (bit == 0L) {
                        val z = br.fs()
                        val nz = when {
                            z == 4 -> minOf(blocksLeft, 64 - ((rsi - blocksLeft) % 64))
                            z < 4 -> z + 1
                            else -> z
                        }
                        val zeros = nz * J - (if (ref) 1 else 0)
                        for (t in 0 until zeros) if (cnt < buf.size) buf[cnt++] = 0L
                        blocksLeft -= nz
                        first = false
                        continue
                    } else {
                        var i = if (ref) 1 else 0
                        while (i < J) {
                            val m = br.fs()
                            var beta = 0
                            while ((beta + 1) * (beta + 2) / 2 <= m) beta++
                            val x1 = m - beta * (beta + 1) / 2
                            val x0 = beta - x1
                            if (i % 2 == 0) { buf[cnt++] = x0.toLong(); i++ }
                            buf[cnt++] = x1.toLong(); i++
                        }
                    }
                } else if (id == (1 shl idLen) - 1) {
                    if (ref) buf[cnt++] = br.read(nbits)
                    repeat(J - if (ref) 1 else 0) { buf[cnt++] = br.read(nbits) }
                } else {
                    val k = id - 1
                    if (ref) buf[cnt++] = br.read(nbits)
                    val m = J - if (ref) 1 else 0
                    val hi = IntArray(m) { br.fs() }
                    for (t in 0 until m) buf[cnt++] = (hi[t].toLong() shl k) or (if (k > 0) br.read(k) else 0L)
                }
                blocksLeft--
                first = false
            }
            // inversione del pre-processing (predittore a ritardo unitario)
            var prev = 0L
            for (t in 0 until cnt) {
                if (o >= n) break
                val v = buf[t]
                val x = if (!pp || t == 0) v else {
                    val p = prev
                    val theta = minOf(p, xmax - p)
                    if (v <= 2 * theta) p + (if (v % 2 == 0L) v / 2 else -((v + 1) / 2))
                    else if (theta == p) v else xmax - v
                }
                out[o++] = x
                prev = x
            }
            if (pad) br.align()
        }
        return out
    }

    /** Ritaglia il campo sull'area richiesta (stesso passo della griglia originale). */
    fun crop(f: Field, south: Double, north: Double, west: Double, east: Double): Field {
        val j0 = Math.floor((south - f.south) / f.dlat + 1e-6).toInt().coerceIn(0, f.nj - 1)
        val j1 = Math.ceil((north - f.south) / f.dlat - 1e-6).toInt().coerceIn(j0, f.nj - 1)
        val global = f.dlon * f.ni >= 359.0
        val off = if (global) ((west - f.west) % 360 + 360) % 360 else (west - f.west).coerceAtLeast(0.0)
        val i0 = Math.floor(off / f.dlon + 1e-6).toInt().coerceIn(0, f.ni - 1)
        var ni = Math.ceil((east - west) / f.dlon - 1e-6).toInt() + 1
        if (!global) ni = ni.coerceAtMost(f.ni - i0)
        ni = ni.coerceIn(1, f.ni)
        val nj = j1 - j0 + 1
        val out = FloatArray(ni * nj)
        for (j in 0 until nj) {
            val src = (j0 + j) * f.ni
            for (i in 0 until ni) {
                var si = i0 + i
                if (si >= f.ni) si -= f.ni
                out[j * ni + i] = f.values[src + si]
            }
        }
        var w = f.west + i0 * f.dlon
        if (w >= 180) w -= 360
        return Field(ni, nj, f.south + j0 * f.dlat, w, f.dlat, f.dlon, out)
    }
}
