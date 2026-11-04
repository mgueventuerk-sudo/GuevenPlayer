package com.mg.player.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mg.player.core.log.AppLog
import com.mg.player.core.log.ExitReasons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Nach einem Absturz (oder "App reagiert nicht") fragt die App beim nächsten Start, ob sie einen Bericht schicken
 * soll – am Handy übers Teilen-Menü, am Fernseher über das Fehlerprotokoll (QR-Code). Ohne Bericht bleibt so ein
 * Fehler sonst unsichtbar.
 */
@Composable
fun CrashReportHost(onOpenLog: () -> Unit) {
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        problem = withContext(Dispatchers.IO) {
            // Beide abfragen: Ein Absturz steht in beiden – so wird er nur einmal gemeldet.
            val crash = AppLog.takeLastCrash()
            val exit = runCatching { ExitReasons.takeUnexpectedExit(context) }.getOrNull()
            crash ?: exit
        }
    }
    val text = problem ?: return
    val touch = isTouch
    ConfirmDialog(
        title = "GuevenPlayer wurde unerwartet beendet",
        confirmText = if (touch) "Bericht teilen" else "Protokoll ansehen",
        dismissText = "Schließen",
        onConfirm = {
            if (touch) context.shareText("GuevenPlayer – Fehlerbericht", crashReportText(text, AppLog.report())) else onOpenLog()
            problem = null
        },
        onDismiss = { problem = null },
        message = text.lineSequence().first().take(160) + "\n\nMit dem Bericht lässt sich der Fehler finden und beheben.",
    )
}

/** Was geteilt wird: die Ursache ganz oben, darunter Gerät, Version und das Protokoll. */
internal fun crashReportText(problem: String, report: String): String = "Ursache:\n$problem\n\n----- Protokoll -----\n$report"
