package com.mg.player.ui.search

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mg.player.ui.common.IconActionButton
import com.mg.player.ui.common.LocalToast
import com.mg.player.ui.theme.AppIcons

/**
 * Mikrofon-Knopf neben dem Suchfeld: startet die Spracherkennung des Geräts (Google bzw. Fire-TV-Dienst). Die App
 * selbst nimmt nichts auf und braucht keine Mikrofon-Berechtigung. Ohne Spracherkennung wird der Knopf nicht gezeigt.
 */
@Composable
internal fun VoiceSearchButton(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val intent = remember { voiceIntent() }
    val available = remember { runCatching { intent.resolveActivity(context.packageManager) != null }.getOrDefault(false) }
    if (!available) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!text.isNullOrEmpty()) onResult(text)
    }
    IconActionButton(
        icon = AppIcons.Mic,
        label = "Sprachsuche",
        onClick = { runCatching { launcher.launch(intent) }.onFailure { toast.show("Spracherkennung nicht verfügbar") } },
        modifier = modifier,
    )
}

private fun voiceIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Sender, Film oder Serie sagen")
    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
