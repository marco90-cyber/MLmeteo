package com.marco.mlmeteo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Piccoli elementi dell'interfaccia usati sia su Android sia su Windows. */

/** Cache LRU semplice (sostituisce android.util.LruCache, uguale su Android e Windows). */
class LruCache<K, V>(private val max: Int) {
    private val map = object : java.util.LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    }
    @Synchronized fun get(k: K): V? = map[k]
    @Synchronized fun put(k: K, v: V): V? = map.put(k, v)
    @Synchronized fun remove(k: K): V? = map.remove(k)
    @Synchronized fun evictAll() = map.clear()
}

@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    val c = LocalAppColors.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.card2)) {
        options.forEach { (id, label) ->
            val sel = id == selected
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp))
                    .background(if (sel) c.accentContainer else c.card2)
                    .clickable { onPick(id) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (sel) c.onAccentContainer else c.inkDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun Hint(text: String) {
    Text(text, color = LocalAppColors.current.inkDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
fun Gap(w: Int) = Spacer(Modifier.width(w.dp))
