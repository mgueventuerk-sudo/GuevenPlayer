package com.mg.player.ui.common

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mg.player.core.di.AppContainer
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.BrandLogo
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * App-Sperre: Deckt alles ab, bis die PIN stimmt (dieselbe PIN wie die Kindersicherung). Gesperrte Kategorien bleiben
 * danach gesperrt – die App-PIN schaltet nur die App frei. In „Zuletzt verwendet“ ist der Inhalt verdeckt.
 */
@Composable
fun AppLockGate(container: AppContainer, initiallyEnabled: Boolean) {
    val enabled by remember(container) { container.settingsRepository.settings.map { it.appLock } }.collectAsState(initiallyEnabled)
    val locked by container.appLock.locked.collectAsStateWithLifecycle()
    // Ohne App-Sperre bleibt die App offen – sonst sperrte das Einschalten sofort den eigenen Bildschirm.
    LaunchedEffect(enabled, locked) { if (!enabled && locked) container.appLock.unlock() }
    HideFromRecents(enabled)
    if (!enabled || !locked) return
    val activity = LocalContext.current.findActivity()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(AppColors.Background), contentAlignment = Alignment.Center) {
        BrandLogo(markSize = 64.dp, fontSize = 36.sp)
    }
    PinDialog(
        title = "GuevenPlayer ist gesperrt",
        subtitle = "PIN eingeben, um fortzufahren.",
        error = error,
        onPinEntered = { pin ->
            scope.launch {
                val control = container.parentalControl
                if (control.verify(pin)) {
                    control.lockAgain()
                    error = null
                    container.appLock.unlock()
                } else {
                    val wait = control.secondsBlocked()
                    error = if (wait > 0) "Zu viele Fehlversuche – bitte $wait Sekunden warten" else "Falsche PIN – bitte erneut versuchen"
                }
            }
        },
        onDismiss = { activity?.finish() },
    )
}

/** Vorschaubild in „Zuletzt verwendet“ verdecken (Android 13+ gezielt, darunter am Handy per Bildschirmschutz). */
@Composable
private fun HideFromRecents(enabled: Boolean) {
    if (!enabled) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val activity = LocalContext.current.findActivity() ?: return
        DisposableEffect(activity) {
            activity.setRecentsScreenshotEnabled(false)
            onDispose { activity.setRecentsScreenshotEnabled(true) }
        }
    } else if (isTouch) {
        // Am Fernseher nicht: FLAG_SECURE kann dort an Geräten ohne HDCP das Bild schwärzen.
        SecureContent()
    }
}
