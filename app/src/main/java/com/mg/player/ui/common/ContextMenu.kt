package com.mg.player.ui.common

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcon
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AppText
import com.mg.player.ui.theme.Spacing
import com.mg.player.ui.theme.StaticIcon

/** Ein Eintrag im Kontextmenü. [destructive] = rot (Löschen, Entfernen). */
@Immutable
data class ContextAction(val label: String, val icon: AppIcon? = null, val destructive: Boolean = false, val onClick: () -> Unit)

@Immutable
data class ContextMenuSpec(val title: String, val subtitle: String? = null, val actions: List<ContextAction>)

/**
 * Einheitliches Kontextmenü (ab 3.41): OK gedrückt halten öffnet überall dasselbe Menü mit allem, was mit dem Element
 * geht (Favorit, Aufnehmen, Mosaik, Details …) – statt versteckter Sofort-Aktionen. Ein Menü für die ganze App, gezeigt
 * vom [ContextMenuHost] in der Wurzel.
 */
@Stable
class ContextMenuState {
    var menu by mutableStateOf<ContextMenuSpec?>(null)
        private set

    fun show(title: String, actions: List<ContextAction>, subtitle: String? = null) {
        if (actions.isNotEmpty()) menu = ContextMenuSpec(title, subtitle, actions)
    }

    fun dismiss() {
        menu = null
    }
}

val LocalContextMenu = staticCompositionLocalOf { ContextMenuState() }

@Composable
fun ContextMenuHost(state: ContextMenuState) {
    val spec = state.menu ?: return
    // Eigenes Fenster wie beim Bestätigungsdialog: Das Steuerkreuz kann den Fokus nicht hinter das Menü schieben.
    Dialog(onDismissRequest = state::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ContextMenuContent(spec, onDismiss = state::dismiss)
    }
}

@Composable
private fun ContextMenuContent(spec: ContextMenuSpec, onDismiss: () -> Unit) {
    val firstRequester = remember(spec) { FocusRequester() }
    LaunchedEffect(spec) { firstRequester.requestFocusWhenReady() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(horizontal = Spacing.M)
                .widthIn(min = 320.dp, max = 440.dp)
                .liquidGlass(AppShapes.Panel)
                .padding(Spacing.M)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(Spacing.Xxs),
        ) {
            Text(spec.title, style = AppText.title, color = AppColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            spec.subtitle?.let { Text(it, style = AppText.caption, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.padding(top = Spacing.Xs))
            spec.actions.forEachIndexed { index, action ->
                ListSurface(
                    onClick = {
                        onDismiss()
                        action.onClick()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(firstRequester) else Modifier),
                ) {
                    Row(Modifier.padding(horizontal = Spacing.M, vertical = Spacing.S), verticalAlignment = Alignment.CenterVertically) {
                        // Ohne eigene Farbe folgt der Eintrag der Fläche (im Fokus dunkle Schrift auf hellem Grund).
                        val tint = if (action.destructive) AppColors.Danger else LocalContentColor.current
                        action.icon?.let {
                            StaticIcon(it, tint = tint, size = 22.dp)
                            Spacer(Modifier.width(Spacing.S))
                        }
                        Text(action.label, style = AppText.label, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
