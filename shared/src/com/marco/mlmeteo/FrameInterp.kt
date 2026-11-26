package com.marco.mlmeteo

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.readPixels
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Immagine intermedia (mezz'ora) tra due immagini della mappa.
 * Invece di sovrapporre le due ore al 50% (effetto fantasma) si cerca di quanto si è spostata
 * la pioggia tra le due immagini e la si disegna a metà strada.
 */
object FrameInterp {

    fun mid(a: ImageBitmap, b: ImageBitmap): ImageBitmap? {
        val w = a.width
        val h = a.height
        if (b.width != w || b.height != h || w < 32 || h < 32) return null
        val pa = IntArray(w * h)
        val pb = IntArray(w * h)
        a.readPixels(pa, 0, 0, w, h, 0, w)
        b.readPixels(pb, 0, 0, w, h, 0, w)

        // versione ridotta (solo trasparenza): serve a cercare lo spostamento
        val d = max(1, (max(w, h) + 159) / 160)
        val cw = w / d
        val ch = h / d
        val ca = IntArray(cw * ch)
        val cb = IntArray(cw * ch)
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                var sa = 0
                var sb = 0
                for (yy in 0 until d) {
                    val row = (y * d + yy) * w + x * d
                    for (xx in 0 until d) {
                        sa += pa[row + xx] ushr 24
                        sb += pb[row + xx] ushr 24
                    }
                }
                ca[y * cw + x] = sa / (d * d)
                cb[y * cw + x] = sb / (d * d)
            }
        }

        // spostamento per blocchi (in pixel ridotti)
        val bs = 8
        val bw = (cw + bs - 1) / bs
        val bh = (ch + bs - 1) / bs
        val vx = FloatArray(bw * bh)
        val vy = FloatArray(bw * bh)
        val ok = BooleanArray(bw * bh)
        val r = 10
        for (by in 0 until bh) {
            for (bx in 0 until bw) {
                val x0 = bx * bs
                val y0 = by * bs
                val x1 = min(x0 + bs, cw)
                val y1 = min(y0 + bs, ch)
                var sig = 0
                for (y in y0 until y1) for (x in x0 until x1) sig += ca[y * cw + x]
                if (sig < (x1 - x0) * (y1 - y0) * 6) continue // niente da seguire in questo blocco
                var best = Int.MAX_VALUE
                var bdx = 0
                var bdy = 0
                for (dy in -r..r) {
                    for (dx in -r..r) {
                        var sad = 0
                        for (y in y0 until y1) {
                            val ty = y + dy
                            for (x in x0 until x1) {
                                val tx = x + dx
                                val v = if (tx in 0 until cw && ty in 0 until ch) cb[ty * cw + tx] else 0
                                sad += abs(ca[y * cw + x] - v)
                            }
                        }
                        sad += (dx * dx + dy * dy) * 2 // a parità di somiglianza meglio lo spostamento piccolo
                        if (sad < best) { best = sad; bdx = dx; bdy = dy }
                    }
                }
                val i = by * bw + bx
                vx[i] = bdx.toFloat()
                vy[i] = bdy.toFloat()
                ok[i] = true
            }
        }
        // i blocchi senza dati prendono la media dei vicini
        for (pass in 0 until 6) {
            val nok = ok.copyOf()
            for (by in 0 until bh) {
                for (bx in 0 until bw) {
                    val i = by * bw + bx
                    if (ok[i]) continue
                    var sx = 0f
                    var sy = 0f
                    var n = 0
                    for (oy in -1..1) {
                        for (ox in -1..1) {
                            val x = bx + ox
                            val y = by + oy
                            if (x in 0 until bw && y in 0 until bh && ok[y * bw + x]) {
                                sx += vx[y * bw + x]
                                sy += vy[y * bw + x]
                                n++
                            }
                        }
                    }
                    if (n > 0) { vx[i] = sx / n; vy[i] = sy / n; nok[i] = true }
                }
            }
            for (i in ok.indices) ok[i] = nok[i]
        }

        // immagine a metà strada: A spostata in avanti di metà, B indietro di metà
        val out = IntArray(w * h)
        val fw = d.toFloat()
        for (y in 0 until h) {
            val gy = (y / fw) / bs - 0.5f
            val y0 = floor(gy).toInt()
            val ty = gy - y0
            val ya = y0.coerceIn(0, bh - 1)
            val yb = (y0 + 1).coerceIn(0, bh - 1)
            for (x in 0 until w) {
                val gx = (x / fw) / bs - 0.5f
                val x0 = floor(gx).toInt()
                val tx = gx - x0
                val xa = x0.coerceIn(0, bw - 1)
                val xb = (x0 + 1).coerceIn(0, bw - 1)
                val fx = bil(vx, bw, xa, xb, ya, yb, tx, ty) * fw * 0.5f
                val fy = bil(vy, bw, xa, xb, ya, yb, tx, ty) * fw * 0.5f
                val ax = (x - fx).roundToInt()
                val ay = (y - fy).roundToInt()
                val bx2 = (x + fx).roundToInt()
                val by2 = (y + fy).roundToInt()
                val p1 = if (ax in 0 until w && ay in 0 until h) pa[ay * w + ax] else 0
                val p2 = if (bx2 in 0 until w && by2 in 0 until h) pb[by2 * w + bx2] else 0
                out[y * w + x] = mix(p1, p2)
            }
        }
        return imageFromPixels(out, w, h)
    }

    private fun bil(v: FloatArray, bw: Int, xa: Int, xb: Int, ya: Int, yb: Int, tx: Float, ty: Float): Float =
        (v[ya * bw + xa] * (1 - tx) + v[ya * bw + xb] * tx) * (1 - ty) +
            (v[yb * bw + xa] * (1 - tx) + v[yb * bw + xb] * tx) * ty

    /** Media di due pixel ARGB pesata sulla trasparenza (il trasparente non scurisce il colore). */
    private fun mix(p: Int, q: Int): Int {
        val ap = p ushr 24
        val aq = q ushr 24
        val s = ap + aq
        if (s == 0) return 0
        val r = (((p shr 16) and 255) * ap + ((q shr 16) and 255) * aq) / s
        val g = (((p shr 8) and 255) * ap + ((q shr 8) and 255) * aq) / s
        val b = ((p and 255) * ap + (q and 255) * aq) / s
        return ((s / 2) shl 24) or (r shl 16) or (g shl 8) or b
    }
}
