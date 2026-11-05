package com.mg.player.ui.vpn

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.core.vpn.VpnController
import com.mg.player.core.vpn.VpnDropReason
import com.mg.player.core.vpn.VpnState
import com.mg.player.ui.common.MarqueeText
import com.mg.player.ui.common.glass
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.StaticIcon
import kotlinx.coroutines.delay

/** Aktueller VPN-Zustand für Schild und Band – in AppRoot bereitgestellt. */
val LocalVpnState = compositionLocalOf<VpnState> { VpnState.Off }

/** Kräftiges Rot fürs Band: auf hellem wie dunklem Hintergrund und über Video gut sichtbar. */
private val BandRed = Color(0xFFD7141E)

private fun VpnState.color(): Color = when (this) {
    // Im hellen Modus dunklere Töne – Gelb und Hellgrün sind auf hellem Grund sonst kaum lesbar.
    is VpnState.Connected -> if (AppColors.isDark) AppColors.Success else Color(0xFF15803D)

    is VpnState.Connecting -> if (AppColors.isDark) AppColors.Rating else Color(0xFFB45309)

    is VpnState.Disconnected -> BandRed

    VpnState.Off, VpnState.Unsupported -> AppColors.TextMuted
}

/** Kurztext fürs Schild: „VPN · DE“, „VPN verbindet …“, „VPN getrennt“, „VPN aus“. */
fun VpnState.badgeLabel(): String = when (this) {
    is VpnState.Connected -> country?.let { "VPN · $it" } ?: "VPN"
    is VpnState.Connecting -> "VPN verbindet …"
    is VpnState.Disconnected -> "VPN getrennt"
    VpnState.Off, VpnState.Unsupported -> "VPN aus"
}

/**
 * Schild mit Zustand (oben neben dem Zugang und im Player). [short]: nur „DE“ bzw. „VPN“ – die Farbe zeigt den
 * Zustand (TV-Kopfzeile). [iconOnly] für die schmale Handy-Kopfzeile.
 */
@Composable
fun VpnBadge(state: VpnState, modifier: Modifier = Modifier, short: Boolean = false, iconOnly: Boolean = false, onClick: (() -> Unit)? = null) {
    val tint = state.color()
    val off = state is VpnState.Off || state is VpnState.Unsupported
    val label = state.badgeLabel()
    val shownLabel = if (short) (state as? VpnState.Connected)?.country ?: "VPN" else label
    val click = onClick?.let { Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = it) } ?: Modifier
    val shield: @Composable () -> Unit = {
        StaticIcon(
            AppIcons.Shield,
            tint = tint,
            size = 20.dp,
            modifier = if (off) Modifier.strikeThrough(tint) else Modifier,
        )
    }
    if (iconOnly) {
        Box(
            modifier.size(42.dp).glass(CircleShape, raised = true).then(click).semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { shield() }
        return
    }
    Row(
        modifier
            .glass(RoundedCornerShape(50), fill = tint.copy(alpha = 0.16f))
            .then(click)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shield()
        Spacer(Modifier.width(6.dp))
        Text(
            shownLabel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/** Durchgestrichen (VPN aus). */
private fun Modifier.strikeThrough(color: Color): Modifier = drawWithContent {
    drawContent()
    drawLine(color, Offset(size.width * 0.12f, size.height * 0.88f), Offset(size.width * 0.88f, size.height * 0.12f), 2.dp.toPx(), StrokeCap.Round)
}

/** Text des roten Bands je nach Grund. */
internal fun bannerText(reason: VpnDropReason): String = when (reason) {
    VpnDropReason.PERMISSION -> "VPN-Erlaubnis fehlt – unter Einstellungen › Sicherheit & VPN „Neu verbinden“ wählen."
    VpnDropReason.BAD_CONFIG -> "Konfiguration ungültig – unter Einstellungen › Sicherheit & VPN neu einrichten."
    VpnDropReason.NO_NETWORK -> "Kein Netzwerk – verbinde neu, sobald es wieder da ist …"
    else -> "Verbinde neu …"
}

/**
 * Rotes Band oben über allem (auch Vollbild und Vorschau), solange das VPN getrennt ist.
 * Wegklicken geht nicht – nur verkleinern zu einem blinkenden Hinweis (am Fernseher nach 20 s von selbst,
 * damit es keinen Fokus braucht; am Handy auch per Tippen).
 */
@Composable
fun VpnBanner(state: VpnState, touch: Boolean, modifier: Modifier = Modifier) {
    val down = state as? VpnState.Disconnected
    var last by remember { mutableStateOf(down) }
    if (down != null) last = down
    AnimatedVisibility(
        visible = down != null,
        modifier = modifier,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val shown = last ?: return@AnimatedVisibility
        var collapsed by rememberSaveable(shown.sinceMs) { mutableStateOf(false) }
        LaunchedEffect(shown.sinceMs, collapsed) {
            if (!collapsed) {
                delay(if (touch) 30_000 else 20_000)
                collapsed = true
            }
        }
        val toggle = if (touch) {
            Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { collapsed = !collapsed }
        } else {
            Modifier
        }
        if (collapsed) CollapsedHint(toggle) else FullBand(shown, toggle)
    }
}

@Composable
private fun FullBand(state: VpnState.Disconnected, modifier: Modifier) {
    Row(
        Modifier.fillMaxWidth().background(BandRed).then(modifier).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StaticIcon(AppIcons.Shield, tint = Color.White, size = 22.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "⚠ VPN getrennt – Verbindung ungeschützt. ${bannerText(state.reason)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MarqueeText(
                state.reason.text,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CollapsedHint(modifier: Modifier) {
    val pulse by rememberInfiniteTransition(label = "vpn-blink").animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "vpn-blink-alpha",
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier
                .padding(top = 4.dp)
                .alpha(pulse)
                .background(BandRed, RoundedCornerShape(50))
                .then(modifier)
                .padding(horizontal = 12.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StaticIcon(AppIcons.Shield, tint = Color.White, size = 16.dp)
            Spacer(Modifier.width(6.dp))
            Text("⚠ VPN getrennt", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

/** Androids Dialog „VPN-Verbindung zulassen?“ – einmalig bzw. wenn ein anderes VPN die Erlaubnis übernommen hat. */
@Composable
fun VpnPermissionHost(vpn: VpnController) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vpn.onPermissionResult(result.resultCode == Activity.RESULT_OK)
    }
    val request by vpn.permissionRequest.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        val intent = request ?: return@LaunchedEffect
        vpn.consumePermissionRequest()
        // Manche TV-Firmware hat den Dialog nicht – dann bleibt das rote Band mit Hinweis stehen.
        runCatching { launcher.launch(intent) }.onFailure { vpn.onPermissionResult(false) }
    }
}
