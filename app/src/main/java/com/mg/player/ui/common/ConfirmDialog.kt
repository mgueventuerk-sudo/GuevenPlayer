package com.mg.player.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.Dimens

/**
 * Bestätigungsdialog für die Fernbedienung (z. B. "GuevenPlayer beenden?"). Als eigenes Fenster umgesetzt:
 * Das Steuerkreuz kann den Fokus so nicht auf den Inhalt dahinter schieben (vorher landete man mit
 * "unten" unbemerkt in der Liste hinter dem Dialog). "Zurück" schließt ihn.
 */
@Composable
fun ConfirmDialog(title: String, confirmText: String, dismissText: String, onConfirm: () -> Unit, onDismiss: () -> Unit, message: String? = null) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ConfirmContent(title, confirmText, dismissText, onConfirm, onDismiss, message)
    }
}

@Composable
private fun ConfirmContent(title: String, confirmText: String, dismissText: String, onConfirm: () -> Unit, onDismiss: () -> Unit, message: String?) {
    val confirmRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { confirmRequester.requestFocusWhenReady() }
    Box(
        Modifier
            .fillMaxSize()
            .background(AppColors.Scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .widthIn(max = 540.dp)
                .glass(AppShapes.Panel, fill = AppColors.Panel)
                .padding(horizontal = if (isTouch) 24.dp else 40.dp, vertical = 32.dp)
                .focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (message != null) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ActionButton(confirmText, onClick = onConfirm, modifier = Modifier.focusRequester(confirmRequester), primary = true)
                ActionButton(dismissText, onClick = onDismiss)
            }
        }
    }
}
