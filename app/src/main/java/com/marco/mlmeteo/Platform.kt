package com.marco.mlmeteo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Funzioni che su Android e su Windows si fanno in modo diverso (stessi nomi nei due moduli). */
fun imageFromPixels(px: IntArray, w: Int, h: Int): ImageBitmap =
    Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()

fun decodeImage(bytes: ByteArray): ImageBitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

fun radarBack() = Nav.back()

/** Su Android non c'è la rotellina del mouse. */
fun androidx.compose.ui.Modifier.wheelZoom(onSteps: (Float) -> Unit): androidx.compose.ui.Modifier = this
