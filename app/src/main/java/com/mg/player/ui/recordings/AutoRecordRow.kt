package com.mg.player.ui.recordings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.common.LocalToast
import com.mg.player.ui.common.TvTextField
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import kotlinx.coroutines.launch

/** „Automatisch aufnehmen“: Stichwörter (Mannschaft, Sendung) als Knöpfe – OK entfernt eins, rechts kommt ein neues dazu. */
@Composable
internal fun AutoRecordRow(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val auto = container.autoRecorder
    val rules by auto.rules.collectAsStateWithLifecycle()
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val submit: () -> Unit = submit@{
        val keyword = text.trim()
        auto.add(keyword)?.let {
            toast.show(it)
            return@submit
        }
        text = ""
        scope.launch {
            auto.plan()
            // Gezählt wird, was jetzt eingeplant ist – auch wenn die Automatik im Hintergrund schneller war.
            val n = container.recordingManager.recordings.value.count { it.isScheduled && it.title.contains(keyword, ignoreCase = true) }
            toast.show(
                if (n > 0) "„$keyword“: $n Sendung(en) eingeplant" else "„$keyword“ gemerkt – passende Sendungen werden eingeplant, sobald sie im EPG stehen",
                success = n > 0,
            )
        }
    }
    Column(modifier) {
        Text("Automatisch aufnehmen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Sendungen der nächsten 24 Stunden, deren Titel ein Stichwort enthält (z. B. eine Mannschaft oder Serie), werden eingeplant.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextMuted,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            rules.forEach { keyword ->
                ActionButton(keyword, onClick = {
                    auto.remove(keyword)
                    toast.show("„$keyword“ entfernt – bereits eingeplante Aufnahmen bleiben")
                }, icon = AppIcons.Close, compact = true)
            }
            TvTextField(text, { text = it }, "", Modifier.width(260.dp), placeholder = "Stichwort, z. B. Galatasaray", onSubmit = submit)
            ActionButton("Hinzufügen", onClick = submit, icon = AppIcons.Plus, compact = true)
        }
    }
}
