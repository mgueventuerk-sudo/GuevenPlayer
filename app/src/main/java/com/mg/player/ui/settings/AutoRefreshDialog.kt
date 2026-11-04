package com.mg.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.domain.model.AutoRefreshSchedule
import com.mg.player.ui.common.ListSurface
import com.mg.player.ui.common.RowColors
import com.mg.player.ui.common.glass
import com.mg.player.ui.common.requestFocusWhenReady
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens
import com.mg.player.ui.theme.StaticIcon
import android.view.KeyEvent as AndroidKeyEvent

/** Text der Einstellungszeile: „alle 3 Tage“ bzw. „Aus“. Der Countdown selbst wird bewusst nicht angezeigt. */
internal fun autoRefreshLabel(days: Int): String = when {
    !AutoRefreshSchedule.isEnabled(days) -> "Aus"
    days == 1 -> "jeden Tag"
    else -> "alle $days Tage"
}

/**
 * Intervall der automatischen Aktualisierung in Tagen eingeben: Ziffernblock per Steuerkreuz oder Zifferntasten der
 * Fernbedienung, ✓ speichert. Leer oder 0 = aus. Höchstens [AutoRefreshSchedule.MAX_DAYS] Tage.
 */
@Composable
internal fun AutoRefreshDialog(name: String, currentDays: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var text by remember { mutableStateOf(if (AutoRefreshSchedule.isEnabled(currentDays)) currentDays.toString() else "") }
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { first.requestFocusWhenReady() }

        fun add(digit: Int) {
            val next = (text + digit).trimStart('0')
            if (next.length <= MAX_DIGITS && (next.toIntOrNull() ?: 0) <= AutoRefreshSchedule.MAX_DAYS) text = next
        }
        fun save() = onSave(text.toIntOrNull() ?: 0)

        Box(
            Modifier.fillMaxSize().background(AppColors.Scrim).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (val code = event.key.nativeKeyCode) {
                    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> add(code - AndroidKeyEvent.KEYCODE_0)
                    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> add(code - AndroidKeyEvent.KEYCODE_NUMPAD_0)
                    AndroidKeyEvent.KEYCODE_DEL -> text = text.dropLast(1)
                    AndroidKeyEvent.KEYCODE_BACK -> onDismiss()
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.glass(AppShapes.Panel, fill = AppColors.Panel).padding(horizontal = 36.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Automatisch aktualisieren", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "$name: Playlist und EPG neu laden, wenn so viele Tage seit der letzten Aktualisierung vergangen sind. " +
                        "Zählt auch, während das Gerät aus ist. 0 = aus.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp).size(width = 420.dp, height = 64.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (text.isEmpty()) "Aus" else autoRefreshLabel(text.toInt()),
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppColors.AccentText,
                )
                Spacer(Modifier.height(14.dp))
                DigitPad(first, ::add, onDelete = { text = text.dropLast(1) }, onSave = ::save)
            }
        }
    }
}

@Composable
private fun DigitPad(first: FocusRequester, onDigit: (Int) -> Unit, onDelete: () -> Unit, onSave: () -> Unit) {
    val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(KEY_DELETE, 0, KEY_SAVE))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { key ->
                    ListSurface(
                        onClick = {
                            when (key) {
                                KEY_DELETE -> onDelete()
                                KEY_SAVE -> onSave()
                                else -> onDigit(key)
                            }
                        },
                        modifier = if (key == 1) Modifier.focusRequester(first) else Modifier,
                        shape = CircleShape,
                        containerColor = if (key == KEY_SAVE) AppColors.AccentSoft else AppColors.GlassRaised,
                        focusedScale = 1.1f,
                    ) {
                        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                            when (key) {
                                KEY_DELETE -> StaticIcon(AppIcons.Back, tint = RowColors.primary, size = 24.dp, contentDescription = "Löschen")
                                KEY_SAVE -> StaticIcon(AppIcons.Check, tint = RowColors.primary, size = 24.dp, contentDescription = "Speichern")
                                else -> Text(key.toString(), style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_DIGITS = 3
private const val KEY_DELETE = -1
private const val KEY_SAVE = -2
