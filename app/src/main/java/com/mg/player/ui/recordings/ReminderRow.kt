package com.mg.player.ui.recordings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.util.TimeUtils
import com.mg.player.domain.model.Reminder
import com.mg.player.ui.common.IconActionButton
import com.mg.player.ui.common.ListSurface
import com.mg.player.ui.common.RowColors
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.StaticIcon

/** Eine Erinnerung in der Liste unter „Aufnahmen“: OK schaltet jetzt schon um, der Knopf rechts löscht sie. */
@Composable
internal fun ReminderRow(reminder: Reminder, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ListSurface(onClick = onOpen, modifier = Modifier.weight(1f), containerColor = AppColors.Glass) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                StaticIcon(AppIcons.Clock, tint = RowColors.accent, size = 22.dp, contentDescription = "Erinnerung")
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        reminder.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Erinnerung · ${reminder.channelName} · ${TimeUtils.formatDate(reminder.startMs)} Uhr",
                        style = MaterialTheme.typography.bodySmall,
                        color = RowColors.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        IconActionButton(icon = AppIcons.Trash, label = "Erinnerung löschen", onClick = onDelete, modifier = Modifier.padding(start = 12.dp))
    }
}
