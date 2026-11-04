package com.mg.player.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.mg.player.core.security.ParentalControl
import com.mg.player.domain.model.Category
import kotlinx.coroutines.launch

/** Fragt bei gesperrten Inhalten die PIN ab und führt die Aktion danach aus. */
@Stable
class ParentalGateState(private val control: ParentalControl) {

    var pendingAction by mutableStateOf<(() -> Unit)?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun runIfAllowed(category: Category, action: () -> Unit) {
        if (control.isAccessible(category)) action() else ask(action)
    }

    /** Für geschützte Einstellungen: fragt immer nach der PIN, sofern eine gesetzt ist. */
    fun runWithPin(action: () -> Unit) {
        if (!control.pinRequired()) action() else ask(action)
    }

    private fun ask(action: () -> Unit) {
        error = null
        pendingAction = action
    }

    suspend fun submit(pin: String) {
        if (control.verify(pin)) {
            val action = pendingAction
            pendingAction = null
            action?.invoke()
        } else {
            val wait = control.secondsBlocked()
            error = if (wait > 0) "Zu viele Fehlversuche – bitte $wait Sekunden warten" else "Falsche PIN – bitte erneut versuchen"
        }
    }

    fun dismiss() {
        pendingAction = null
    }
}

val LocalParentalGate = staticCompositionLocalOf<ParentalGateState> { error("ParentalGate fehlt") }

/** Zeigt den PIN-Dialog, solange eine geschützte Aktion wartet. Gehört ganz nach oben in den Screen-Baum. */
@Composable
fun ParentalGateHost(state: ParentalGateState) {
    if (state.pendingAction == null) return
    val scope = rememberCoroutineScope()
    // "Zurück" schließt das Dialogfenster selbst (onDismiss) – kein zusätzlicher BackHandler nötig.
    PinDialog(
        title = "PIN eingeben",
        subtitle = "Dieser Bereich ist durch die Kindersicherung geschützt.",
        error = state.error,
        onPinEntered = { pin -> scope.launch { state.submit(pin) } },
        onDismiss = state::dismiss,
    )
}
