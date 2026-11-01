package com.marco.mlmeteo

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/** Coordinate Web Mercator normalizzate (0..1). */
fun lonToX(lon: Double) = (lon + 180.0) / 360.0
fun latToY(lat: Double): Double {
    val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
    return (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2
}
fun xToLon(x: Double) = x * 360.0 - 180.0
fun yToLat(y: Double) = Math.toDegrees(atan(kotlin.math.sinh(PI * (1 - 2 * y))))

@Stable
class MapState(lat: Double, lon: Double, zoom: Double) {
    var x by mutableDoubleStateOf(lonToX(lon))
    var y by mutableDoubleStateOf(latToY(lat))
    var zoom by mutableDoubleStateOf(zoom)
    val lat get() = yToLat(y)
    val lon get() = xToLon(x)
}

/** Cache delle tessere: si ridisegna quando ne arriva una nuova. */
object Tiles {
    private val cache = LruCache<String, ImageBitmap>(300)
    private val pending = Collections.synchronizedSet(HashSet<String>())
    private val failed = Collections.synchronizedSet(HashSet<String>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var version by mutableIntStateOf(0)
        private set

    fun get(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        if (url in failed || !pending.add(url)) return null
        scope.launch {
            val img = withContext(Dispatchers.IO) {
                Net.getBytes(url)?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }
            }
            pending.remove(url)
            if (img != null) cache.put(url, img) else failed.add(url)
            version++
        }
        return null
    }
}

class TileLayer(
    val maxZoom: Int,
    val alpha: Float = 1f,
    /** Livelli di dettaglio in più rispetto allo zoom (tessere più nitide sugli schermi densi). */
    val zoomBoost: Int = 0,
    val colorFilter: ColorFilter? = null,
    val url: (z: Int, x: Int, y: Int) -> String,
)

/**
 * Mappa a tessere con pan e zoom.
 * [extra] disegna sopra la mappa: riceve una funzione che converte (lat, lon) in punto sullo schermo.
 */
@Composable
fun TileMap(
    state: MapState,
    layers: List<TileLayer>,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    minZoom: Double = 2.0,
    maxZoom: Double = 12.0,
    extra: DrawScope.(project: (Double, Double) -> Offset, worldPx: Double) -> Unit = { _, _ -> },
) {
    val mod = if (interactive) modifier.pointerInput(Unit) {
        detectTransformGestures { _, pan, zoomChange, _ ->
            val world = 256.0 * density * 2.0.pow(state.zoom)
            state.x = (state.x - pan.x / world).let { it - floor(it) }
            state.y = (state.y - pan.y / world).coerceIn(0.0, 1.0)
            state.zoom = (state.zoom + log2(zoomChange.toDouble())).coerceIn(minZoom, maxZoom)
        }
    } else modifier
    Canvas(mod.fillMaxSize()) {
        @Suppress("UNUSED_VARIABLE") val v = Tiles.version // ridisegna quando arrivano tessere
        val world = 256.0 * density * 2.0.pow(state.zoom)
        val cx = state.x * world
        val cy = state.y * world
        val left = cx - size.width / 2
        val top = cy - size.height / 2
        for (layer in layers) {
            val lz = (floor(state.zoom).toInt() + layer.zoomBoost).coerceIn(1, layer.maxZoom)
            val n = 1 shl lz
            val ts = world / n
            val x0 = floor(left / ts).toInt()
            val x1 = floor((left + size.width) / ts).toInt()
            val y0 = floor(top / ts).toInt().coerceAtLeast(0)
            val y1 = floor((top + size.height) / ts).toInt().coerceAtMost(n - 1)
            for (ty in y0..y1) for (tx in x0..x1) {
                val wx = ((tx % n) + n) % n
                val img = Tiles.get(layer.url(lz, wx, ty)) ?: continue
                val dx = (tx * ts - left)
                val dy = (ty * ts - top)
                val sz = ts.roundToInt() + 1
                drawImage(
                    img,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset(dx.roundToInt(), dy.roundToInt()),
                    dstSize = IntSize(sz, sz),
                    alpha = layer.alpha,
                    colorFilter = layer.colorFilter,
                    filterQuality = FilterQuality.Low,
                )
            }
        }
        val project: (Double, Double) -> Offset = { lat, lon ->
            var px = lonToX(lon) * world - left
            // scegli la copia del mondo più vicina al centro
            if (px < -world / 2) px += world
            if (px > world / 2 + size.width) px -= world
            Offset(px.toFloat(), (latToY(lat) * world - top).toFloat())
        }
        extra(project, world)
    }
}

/**
 * Mappa di base OpenStreetMap (gratis, con User-Agent dell'app).
 * In tema scuro i colori vengono invertiti e scuriti.
 */
private val darkMapFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -0.22f, -0.43f, -0.085f, 0f, 205f,
            -0.22f, -0.43f, -0.085f, 0f, 207f,
            -0.22f, -0.43f, -0.085f, 0f, 218f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

fun baseLayer(dark: Boolean) = TileLayer(19, zoomBoost = 1, colorFilter = if (dark) darkMapFilter else null) { z, x, y ->
    "https://tile.openstreetmap.org/$z/$x/$y.png"
}

fun exp2(x: Double) = exp(x * ln(2.0))
