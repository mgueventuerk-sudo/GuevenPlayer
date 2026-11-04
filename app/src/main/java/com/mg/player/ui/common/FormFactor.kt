package com.mg.player.ui.common

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mg.player.ui.theme.Dimens

/**
 * Eine App, zwei Oberflächen: Fernseher (Steuerkreuz, Querformat, Navigation oben) und
 * Handy/Tablet (Antippen, Hochformat, Navigation unten). Daten, Suche und Player sind dieselben.
 */
enum class FormFactor { TV, TOUCH }

val LocalFormFactor = staticCompositionLocalOf { FormFactor.TV }

/** Seitenrand: am Fernseher großzügig (Overscan), am Handy schmal. */
val edgePadding: Dp
    @Composable get() = if (isTouch) 16.dp else Dimens.ScreenPadding

/** true auf Handy und Tablet. */
val isTouch: Boolean
    @Composable get() = LocalFormFactor.current == FormFactor.TOUCH

/**
 * Fernseher erkennt man am TV-Modus bzw. an der Leanback-Oberfläche; Geräte ganz ohne Touchscreen
 * (Boxen, Sticks) werden ebenfalls wie ein Fernseher bedient.
 */
fun detectFormFactor(context: Context): FormFactor {
    val uiMode = context.getSystemService(UiModeManager::class.java)?.currentModeType
    val pm = context.packageManager
    val configMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
    val tv = uiMode == Configuration.UI_MODE_TYPE_TELEVISION || configMode == Configuration.UI_MODE_TYPE_TELEVISION ||
        pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    return if (tv) FormFactor.TV else FormFactor.TOUCH
}

/**
 * Macht eine TV-Fläche antippbar: Die Flächen aus tv-material reagieren nur auf OK, nicht auf Berührung.
 * Am Handy übernimmt diese Geste Tippen und Gedrückthalten; der Druck wird als "gedrückt" angezeigt
 * (gleiche Farbe wie der Fokus am Fernseher). Fokussierbar sind die Flächen am Handy nicht – so leuchtet
 * nichts dauerhaft auf, nur weil ein Bildschirm beim Öffnen den Fokus setzt.
 */
@Composable
fun Modifier.touchClick(interaction: MutableInteractionSource, onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier {
    if (!isTouch) return this
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val hasLong = onLongClick != null
    return this
        .focusProperties { canFocus = false }
        .pointerInput(interaction, hasLong) {
            detectTapGestures(
                onPress = { offset ->
                    val press = PressInteraction.Press(offset)
                    interaction.emit(press)
                    val released = tryAwaitRelease()
                    interaction.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                },
                onLongPress = if (hasLong) ({ longClick?.invoke() }) else null,
                onTap = { click() },
            )
        }
}
