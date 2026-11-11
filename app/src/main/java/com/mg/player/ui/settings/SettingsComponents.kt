package com.mg.player.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.ui.common.ChoiceChip
import com.mg.player.ui.common.ListSurface
import com.mg.player.ui.common.LocalSurfaceFocused
import com.mg.player.ui.common.RowColors
import com.mg.player.ui.common.SectionLabel
import com.mg.player.ui.common.glass
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.theme.AppShapes
import com.mg.player.ui.theme.AnimatedIcon
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcon
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.Dimens

@Composable
fun SettingsSection(title: String, modifier: Modifier = Modifier) {
    SectionLabel(title, modifier.padding(top = 22.dp, bottom = 8.dp, start = 12.dp))
}

/** Gemeinsamer Aufbau aller Zeilen: Symbol-Kästchen, Titel, Beschreibung, rechts der Wert. */
@Composable
private fun RowLayout(
    label: String,
    description: String?,
    icon: AppIcon?,
    danger: Boolean = false,
    altAccent: Boolean = false,
    trailing: @Composable () -> Unit,
) {
    val inGroup = LocalInGroup.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (inGroup) 12.dp else 16.dp, vertical = if (inGroup) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            RowIcon(icon, danger, altAccent)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            val labelColor = if (danger && !LocalSurfaceFocused.current) AppColors.Danger else RowColors.primary
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = labelColor)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = RowColors.muted)
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** Zeile, die sich mit dem Fokus hell füllt; einzeln als Glasfläche, in einer Gruppe ohne eigenen Hintergrund. */
@Composable
private fun SettingsSurface(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val inGroup = LocalInGroup.current
    ListSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = if (inGroup) 0.dp else 3.dp).then(modifier),
        containerColor = if (inGroup) Color.Transparent else AppColors.Glass,
        focusedScale = if (inGroup) 1.01f else 1.02f,
    ) { content() }
}

/** Auswahl-Zeile: OK schaltet zur nächsten Option weiter. */
@Composable
fun ChoiceRow(label: String, value: String, onClick: () -> Unit, description: String? = null, icon: AppIcon? = null) {
    SettingsSurface(onClick) {
        RowLayout(label, description, icon) {
            Text(value, style = MaterialTheme.typography.titleSmall, color = RowColors.accent, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            AnimatedIcon(AppIcons.ChevronRight, tint = RowColors.accent, active = LocalSurfaceFocused.current, size = 18.dp)
        }
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit, description: String? = null, icon: AppIcon? = null) {
    SettingsSurface(onToggle, Modifier.semantics { stateDescription = if (checked) "Ein" else "Aus" }) {
        RowLayout(label, description, icon) { SwitchIndicator(checked) }
    }
}

/** Zeile, die etwas öffnet oder auslöst; [danger] = rot (Löschen). Ohne Pfeil, wenn sie nichts öffnet ([chevron] = false). */
@Composable
fun ActionRow(
    label: String,
    onClick: () -> Unit,
    description: String? = null,
    trailing: String? = null,
    icon: AppIcon? = null,
    danger: Boolean = false,
    chevron: Boolean = true,
    /** Partnerfarbe statt Hauptakzent fürs Symbol – siehe [RowIcon]. */
    altAccent: Boolean = false,
) {
    SettingsSurface(onClick) {
        RowLayout(label, description, icon, danger, altAccent) {
            if (trailing != null) {
                Text(trailing, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = RowColors.accent, maxLines = 1)
                Spacer(Modifier.width(6.dp))
            }
            if (chevron) AnimatedIcon(AppIcons.ChevronRight, tint = RowColors.muted, active = LocalSurfaceFocused.current, size = 18.dp)
        }
    }
}

/** Nicht fokussierbare Glas-Zeile mit eigenen Bedienelementen rechts (Stepper, Segmente, Farbfelder). */
@Composable
internal fun ControlRow(label: String, description: String?, icon: AppIcon?, controls: @Composable () -> Unit) {
    val inGroup = LocalInGroup.current
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (inGroup) Modifier else Modifier.padding(vertical = 3.dp).glass(AppShapes.Row)),
    ) {
        // Am Handy ist es zu schmal für Beschriftung und Knöpfe nebeneinander: dann untereinander.
        val stacked = isTouch
        Column(Modifier.padding(horizontal = if (inGroup) 12.dp else 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    RowIcon(icon)
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
                }
                if (!stacked) controls()
            }
            if (stacked) {
                Box(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState())) { controls() }
            }
        }
    }
}

/** Zahlenwert mit −/+ Buttons (z. B. EPG-Zeitverschiebung). */
@Composable
fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, description: String? = null, icon: AppIcon? = null) {
    ControlRow(label, description, icon) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            StepButton("−", onMinus)
            Text(
                value,
                modifier = Modifier.width(64.dp),
                style = MaterialTheme.typography.titleSmall,
                color = AppColors.AccentText,
                fontWeight = FontWeight.SemiBold,
            )
            StepButton("+", onPlus)
        }
    }
}

/** Mehrere Optionen nebeneinander (z. B. Automatisch / Dunkel / Hell). */
@Composable
fun <T> SegmentedRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    optionIcon: (T) -> AppIcon,
    onSelect: (T) -> Unit,
    description: String? = null,
    icon: AppIcon? = null,
) {
    ControlRow(label, description, icon) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                ChoiceChip(
                    text = optionLabel(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    icon = optionIcon(option),
                )
            }
        }
    }
}
