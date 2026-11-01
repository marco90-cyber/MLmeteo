import com.marco.mlmeteo.Grib
import java.io.File
fun main(args: Array<String>) {
    val f = Grib.decode(Grib.first(File(args[0]).readBytes()))
    println("grid ${f.ni}x${f.nj} south ${f.south} west ${f.west} dlat ${f.dlat} dlon ${f.dlon}")
    var ok = 0; var bad = 0
    File(args[1]).readLines().forEach { l ->
        val p = l.trim().split(Regex("\\s+")); if (p.size < 3) return@forEach
        val lat = p[0].toDoubleOrNull() ?: return@forEach; var lon = p[1].toDouble(); val v = p[2].toDouble()
        val j = Math.round((lat - f.south) / f.dlat).toInt()
        val i = Math.round((((lon - f.west) % 360 + 360) % 360) / f.dlon).toInt()
        val x = f.values[j * f.ni + i]
        if (Math.abs(x - v) <= 1e-3 * maxOf(1.0, Math.abs(v))) ok++ else { if (bad < 5) println("bad $lat $lon $v vs $x"); bad++ }
    }
    println("ok $ok bad $bad")
}
