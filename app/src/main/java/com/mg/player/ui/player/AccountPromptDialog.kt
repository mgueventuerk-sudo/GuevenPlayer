package com.mg.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.mg.player.ui.accounts.rememberConnections
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.glass
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.common.requestFocusWhenReady
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens

/**
 * Nachfrage „Zugang belegt“ bzw. „Stream ausgefallen“. Die Knöpfe stehen untereinander (passen so auch ins
 * Hochformat); der Fokus liegt auf dem ersten Angebot eines anderen Zugangs, sonst auf „Trotzdem starten“.
 * „Zurück“ bricht ab.
 */
@Composable
fun AccountPromptDialog(prompt: AccountPrompt, onChoice: (AccountChoice) -> Unit) {
    val connections = rememberConnections()
    Dialog(onDismissRequest = { onChoice(AccountChoice.Cancel) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val firstRequester = remember { FocusRequester() }
        LaunchedEffect(prompt) { firstRequester.requestFocusWhenReady() }
        Box(Modifier.fillMaxSize().background(AppColors.Scrim), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .widthIn(max = 560.dp)
                    .glass(AppShapes.Panel, fill = AppColors.Panel)
                    .padding(horizontal = if (isTouch) 24.dp else 40.dp, vertical = 28.dp)
                    .focusGroup(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(prompt.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    prompt.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(22.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    prompt.alternatives.forEachIndexed { index, alternative ->
                        val load = connections[alternative.playlistId]?.label?.let { " ($it)" }.orEmpty()
                        ActionButton(
                            "Über „${alternative.playlistName}“ schauen$load",
                            onClick = { onChoice(AccountChoice.Switch(alternative)) },
                            icon = AppIcons.User,
                            primary = index == 0,
                            modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(firstRequester) else Modifier),
                        )
                    }
                    ActionButton(
                        prompt.continueLabel,
                        onClick = { onChoice(AccountChoice.Continue) },
                        icon = AppIcons.Play,
                        primary = prompt.alternatives.isEmpty(),
                        modifier = Modifier.fillMaxWidth().then(if (prompt.alternatives.isEmpty()) Modifier.focusRequester(firstRequester) else Modifier),
                    )
                    ActionButton("Abbrechen", onClick = { onChoice(AccountChoice.Cancel) }, icon = AppIcons.Close, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
