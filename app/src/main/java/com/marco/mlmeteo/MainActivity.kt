package com.marco.mlmeteo

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

enum class Screen { HOME, SOURCES, PLACES, SETTINGS, APPEARANCE, HOME_ITEMS, UNITS, NOTIFICATIONS, API_KEYS, INFO, RADAR }

object Nav {
    val stack = mutableStateListOf(Screen.HOME)
    /** Pagina della Home da mostrare (indice della località). */
    var page by mutableIntStateOf(0)
    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppState.init(this)
        Notify.createChannels(this)
        Background.schedule(this)
        setContent { Root() }
    }

    override fun onResume() {
        super.onResume()
        AppState.places.forEach { AppState.refreshIfOld(it) }
    }
}

@Composable
fun Root() {
    val s = AppState.settings
    val places = AppState.places
    val current = places.getOrNull(Nav.page)?.let { AppState.forecasts[it.id] }
    MLTheme(s, weatherTintFor(current?.current?.code, current?.current?.isDay ?: true)) {
        val c = LocalAppColors.current
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            AppState.locate()
            AppState.refreshAll()
        }
        val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) {
            val anyNotif = s.notifAlerts || s.notifRain || s.notifMorning || s.notifStatus
            val sp = AppState.app.getSharedPreferences("mlmeteo_notif", android.content.Context.MODE_PRIVATE)
            if (anyNotif && android.os.Build.VERSION.SDK_INT >= 33 && !Notify.canPost(AppState.app) && !sp.getBoolean("asked", false)) {
                sp.edit().putBoolean("asked", true).apply()
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        LaunchedEffect(Unit) {
            if (s.useGps && !AppState.hasLocationPermission(AppState.app)) {
                permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            } else {
                AppState.locate()
            }
            AppState.savedPlaces.forEach { AppState.refreshIfOld(it) }
        }
        BackHandler(enabled = Nav.stack.size > 1) { Nav.back() }
        Box(Modifier.fillMaxSize().background(c.bg)) {
            AnimatedContent(
                targetState = Nav.stack.last(),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "nav",
            ) { screen ->
                when (screen) {
                    Screen.HOME -> HomeScreen(onAskGps = {
                        permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                    })
                    Screen.SOURCES -> SourcesScreen()
                    Screen.PLACES -> PlacesScreen()
                    Screen.SETTINGS -> SettingsScreen()
                    Screen.APPEARANCE -> AppearanceScreen()
                    Screen.HOME_ITEMS -> HomeItemsScreen()
                    Screen.UNITS -> UnitsScreen()
                    Screen.NOTIFICATIONS -> NotificationsScreen()
                    Screen.API_KEYS -> ApiKeysScreen()
                    Screen.INFO -> InfoScreen()
                    Screen.RADAR -> RadarScreen()
                }
            }
            AppUpdateHost()
        }
    }
}
