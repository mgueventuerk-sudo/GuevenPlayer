package com.mg.player.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mg.player.core.PlayerApp
import com.mg.player.core.device.DeviceTier
import com.mg.player.core.log.AppLog
import com.mg.player.core.log.FrameStats
import com.mg.player.core.log.StartupClock
import com.mg.player.data.reminder.LaunchRequest
import com.mg.player.domain.model.AccentColor
import com.mg.player.domain.model.ThemeMode
import com.mg.player.ui.common.AmbientBackground
import com.mg.player.ui.common.BackdropState
import com.mg.player.ui.common.LocalBackdrop
import com.mg.player.ui.common.LocalFormFactor
import com.mg.player.ui.common.detectFormFactor
import com.mg.player.ui.navigation.AppRoot
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.PlayerTheme
import com.mg.player.ui.theme.isDarkFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupClock.uiCreated()
        super.onCreate(savedInstanceState)
        val container = (application as PlayerApp).container
        // Messkampagne (Baseline): Bildzeiten je Bildschirm und „Inhalt sichtbar“ (TTFD) ins Fehlerprotokoll.
        FrameStats.attach(this)
        fullyDrawnReporter.addOnReportDrawnListener {
            AppLog.log("Messung", StartupClock.fullyDrawnLine())
            StartupClock.contentShown()
        }
        // VPN: Bei jeder Rückkehr in die App eine laufende Wartepause abkürzen bzw. neu starten.
        lifecycleScope.launch {
            // Erst auf IO anlegen: Die VPN-Steuerung liest beim Erzeugen SharedPreferences und lädt WireGuard
            // (StrictMode-Meldung „VpnStore.rawEntries“ auf dem Hauptthread im Emulator-Labor 3.23).
            val vpn = withContext(Dispatchers.IO) { container.vpn }
            repeatOnLifecycle(Lifecycle.State.STARTED) { vpn.onForeground() }
        }
        // Aus einer Benachrichtigung („Jetzt umschalten“) gestartet?
        LaunchRequest.take(intent)
        // Bis die Einstellungen gelesen sind, mit der Voreinstellung (Automatisch) starten – vermeidet ein Aufblitzen.
        AppColors.apply(isDarkFor(ThemeMode.AUTO), AccentColor.BLUE)
        setContent {
            val tier = remember { DeviceTier.of(this) }
            val look by remember {
                container.settingsRepository.settings.map {
                    Look(it.themeMode, it.accent, it.largeText, it.reducedEffects || (it.automaticOptimization && tier == DeviceTier.LOW))
                }
            }
                .collectAsState(initial = null)
            ApplyLook(look?.mode ?: ThemeMode.AUTO, look?.accent ?: AccentColor.BLUE)
            SideEffect { AppColors.reducedEffects = look?.reducedEffects == true }
            val backdrop = remember { BackdropState() }
            val formFactor = remember { detectFormFactor(this) }
            // Große Schrift: nur die Schriftgröße wächst (+15 %), Abstände und Raster bleiben.
            val density = LocalDensity.current
            val scaled = remember(density, look?.largeText) {
                if (look?.largeText == true) Density(density.density, density.fontScale * LARGE_TEXT_SCALE) else density
            }
            PlayerTheme {
                CompositionLocalProvider(LocalBackdrop provides backdrop, LocalFormFactor provides formFactor, LocalDensity provides scaled) {
                    AmbientBackground(artworkUrl = backdrop.url) {
                        AppRoot(container)
                    }
                }
            }
        }
    }

    override fun onStop() {
        FrameStats.flush()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        LaunchRequest.take(intent)
    }
}

private data class Look(val mode: ThemeMode, val accent: AccentColor, val largeText: Boolean, val reducedEffects: Boolean)

private const val LARGE_TEXT_SCALE = 1.15f

/**
 * Hell/Dunkel anwenden. Bei "Automatisch" prüft eine Uhr zur vollen Minute, ob es Abend bzw. Morgen geworden ist –
 * die Oberfläche wechselt dann ohne Neustart.
 */
@Composable
internal fun ApplyLook(mode: ThemeMode, accent: AccentColor) {
    val hour by produceState(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), mode) {
        while (mode == ThemeMode.AUTO) {
            value = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    val dark = isDarkFor(mode, hour)
    SideEffect { AppColors.apply(dark, accent) }
}
