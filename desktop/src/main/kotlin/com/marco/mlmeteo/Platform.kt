package com.marco.mlmeteo

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.onPointerEvent
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/** Funzioni che su Android e su Windows si fanno in modo diverso (stessi nomi nei due moduli). */
fun imageFromPixels(px: IntArray, w: Int, h: Int): ImageBitmap {
    val bytes = ByteArray(px.size * 4)
    var j = 0
    for (c in px) {
        // ARGB (Android) -> BGRA (Skia N32 su Windows/Linux)
        bytes[j++] = (c and 0xFF).toByte()
        bytes[j++] = ((c shr 8) and 0xFF).toByte()
        bytes[j++] = ((c shr 16) and 0xFF).toByte()
        bytes[j++] = ((c ushr 24) and 0xFF).toByte()
    }
    val bmp = Bitmap()
    bmp.allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
    bmp.installPixels(bytes)
    return bmp.asComposeImageBitmapCompat()
}

private fun Bitmap.asComposeImageBitmapCompat(): ImageBitmap = Image.makeFromBitmap(this).toComposeImageBitmap()

fun decodeImage(bytes: ByteArray): ImageBitmap? = try { Image.makeFromEncoded(bytes).toComposeImageBitmap() } catch (e: Exception) { null }

fun radarBack() { com.marco.mlmeteo.desktop.Nav.page = com.marco.mlmeteo.desktop.Page.METEO }

fun currentPlace(): Place? = AppState.selected()

/** Zoom della mappa con la rotellina del mouse. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun androidx.compose.ui.Modifier.wheelZoom(onSteps: (Float) -> Unit): androidx.compose.ui.Modifier =
    this.onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Scroll) { e ->
        val d = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
        if (d != 0f) onSteps(d)
    }
