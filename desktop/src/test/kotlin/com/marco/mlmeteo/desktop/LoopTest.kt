package com.marco.mlmeteo.desktop

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.marco.mlmeteo.AppState
import com.marco.mlmeteo.buildColors
import com.marco.mlmeteo.styleColors
import androidx.compose.ui.graphics.Color
import kotlin.test.Test

object Probe { var comp = 0 }

class LoopTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun search() = runComposeUiTest {
        AppState.init()
        Nav.page = Page.PLACES
        setContent {
            SideEffect { Probe.comp++ }
            DesktopTheme(styleColors("fluent", buildColors(false, false, Color(0xFF3D8BFF), null))) { App() }
        }
        waitForIdle()
        val before = Probe.comp
        onNode(hasSetTextAction()).performTextInput("Palermo")
        val t0 = System.currentTimeMillis()
        var last = 0
        try { waitUntil(timeoutMillis = 15000) { onAllNodes(androidx.compose.ui.test.hasText("Aggiungi")).fetchSemanticsNodes().isNotEmpty() } } catch (e: Throwable) { println("PROBE no results ${e}") }
        repeat(12) {
            Thread.sleep(400)
            mainClock.advanceTimeBy(400)
            val n = onAllNodes(androidx.compose.ui.test.hasText("Aggiungi")).fetchSemanticsNodes().size
            println("PROBE t=${System.currentTimeMillis() - t0}ms root=${Probe.comp - before} places=${Dbg.places} rows=${Dbg.rows} nodes=$n changed=${Desktop.changed} mem=${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_000_000}MB")
            last = Probe.comp
        }
    }
}
