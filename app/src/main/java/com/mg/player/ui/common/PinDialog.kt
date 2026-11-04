package com.mg.player.ui.common

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.security.ParentalControl
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens
import com.mg.player.ui.theme.StaticIcon

/**
 * PIN-Eingabe für die Fernbedienung: Ziffernblock per Steuerkreuz oder direkt über die Zifferntasten.
 * Nach der vierten Ziffer wird automatisch bestätigt.
 *
 * Eigenes Fenster: Vorher lag der Ziffernblock nur über dem Inhalt, und das Steuerkreuz konnte den Fokus
 * auf den geschützten Bereich dahinter schieben – mit OK ließ sich die Kindersicherung so umgehen.
 */
@Composable
fun PinDialog(title: String, onPinEntered: (String) -> Unit, onDismiss: () -> Unit, subtitle: String? = null, error: String? = null) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        PinContent(title, onPinEntered, onDismiss, subtitle, error)
    }
}

@Composable
private fun PinContent(title: String, onPinEntered: (String) -> Unit, onDismiss: () -> Unit, subtitle: String?, error: String?) {
    var pin by remember { mutableStateOf("") }
    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstRequester.requestFocusWhenReady() }
    LaunchedEffect(error) { if (error != null) pin = "" }

    fun add(digit: Int) {
        if (pin.length >= ParentalControl.PIN_LENGTH) return
        pin += digit.toString()
        if (pin.length == ParentalControl.PIN_LENGTH) {
            val entered = pin
            pin = ""
            onPinEntered(entered)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(AppColors.Scrim)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = event.key.nativeKeyCode
                when (code) {
                    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
                        add(code - AndroidKeyEvent.KEYCODE_0)
                        true
                    }

                    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> {
                        add(code - AndroidKeyEvent.KEYCODE_NUMPAD_0)
                        true
                    }

                    AndroidKeyEvent.KEYCODE_BACK -> {
                        onDismiss()
                        true
                    }

                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .glass(AppShapes.Panel, fill = AppColors.Panel)
                .padding(horizontal = 36.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(44.dp).glass(CircleShape, raised = true), contentAlignment = Alignment.Center) {
                AnimatedIcon(AppIcons.Lock, tint = AppColors.AccentText, active = pin.isNotEmpty(), size = 22.dp)
            }
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(ParentalControl.PIN_LENGTH) { index ->
                    val filled = index < pin.length
                    val dot by animateDpAsState(if (filled) 18.dp else 14.dp, label = "pinDot")
                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(dot)
                                .background(if (filled) AppColors.Accent else AppColors.ProgressTrack, CircleShape),
                        )
                    }
                }
            }
            Text(
                error ?: " ",
                color = AppColors.Danger,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )
            val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(-1, 0, -2))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { key ->
                            ListSurface(
                                onClick = {
                                    when (key) {
                                        -1 -> pin = pin.dropLast(1)
                                        -2 -> onDismiss()
                                        else -> add(key)
                                    }
                                },
                                modifier = if (key == 1) Modifier.focusRequester(firstRequester) else Modifier,
                                shape = CircleShape,
                                containerColor = AppColors.GlassRaised,
                                focusedScale = 1.1f,
                            ) {
                                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                                    when (key) {
                                        -1 -> StaticIcon(AppIcons.Back, tint = RowColors.primary, size = 24.dp, contentDescription = "Löschen")
                                        -2 -> StaticIcon(AppIcons.Close, tint = RowColors.primary, size = 24.dp, contentDescription = "Abbrechen")
                                        else -> Text(key.toString(), style = MaterialTheme.typography.headlineSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
