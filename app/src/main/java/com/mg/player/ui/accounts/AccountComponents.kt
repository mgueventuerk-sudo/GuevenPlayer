package com.mg.player.ui.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.mg.player.core.di.AppContainer
import com.mg.player.data.combined.Combined
import com.mg.player.domain.model.Playlist
import com.mg.player.domain.model.PlaylistType
import com.mg.player.ui.common.touchClick
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.InterDisplay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Liefert alle Zugänge und den aktiven – für Navigationsleiste und Umschalter. */
class AccountsViewModel(container: AppContainer) : ViewModel() {

    /** Ab zwei Zugängen steht „Alle Zugänge“ (kombiniert) ganz oben. */
    val playlists: StateFlow<List<Playlist>> = container.playlistRepository.observePlaylists()
        .map { list -> if (list.size >= 2) listOf(Combined.playlist(list.maxOf { it.lastUpdatedMs })) + list else list }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeId: StateFlow<Long?> = container.settingsRepository.settings
        .map { it.activePlaylistId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val active: StateFlow<Playlist?> = combine(playlists, activeId) { list, id -> list.firstOrNull { it.id == id } ?: list.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

/** Runder Avatar mit Anfangsbuchstaben; jede ID bekommt dauerhaft einen eigenen Farbverlauf. */
@Composable
fun AccountAvatar(playlist: Playlist, size: Dp = 40.dp) {
    val colors = if (Combined.isCombined(playlist.id)) combinedGradient else avatarGradients[Math.floorMod(playlist.id, avatarGradients.size.toLong()).toInt()]
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            playlist.initial(),
            fontFamily = InterDisplay,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp,
            color = Color.White,
        )
    }
}

/** Zugangs-Knopf oben rechts in der Navigationsleiste: Avatar mit Leuchtring im Fokus, öffnet den Umschalter. */
@Composable
fun AccountButton(playlist: Playlist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val connection = rememberConnections()[playlist.id]
    Box(modifier) {
        Surface(
            onClick = onClick,
            modifier = Modifier.touchClick(interaction, onClick),
            interactionSource = interaction,
            shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                pressedContainerColor = Color.Transparent,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(2.5.dp, AppColors.FocusFill), shape = CircleShape),
            ),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(elevationColor = AppColors.Shadow, elevation = 12.dp)),
        ) {
            Box(Modifier.padding(3.dp)) { AccountAvatar(playlist, size = 38.dp) }
        }
        // Grünes "1/1" unter dem Avatar, solange jemand über diesen Zugang schaut (außerhalb des runden Zuschnitts).
        ConnectionDot(connection, Modifier.align(Alignment.BottomCenter).offset(y = 6.dp))
    }
}

private val combinedGradient = listOf(Color(0xFF6366F1), Color(0xFF10B981))

private val avatarGradients = listOf(
    listOf(Color(0xFF5B5CF6), Color(0xFF22D3EE)),
    listOf(Color(0xFFF97316), Color(0xFFF43F5E)),
    listOf(Color(0xFF10B981), Color(0xFF22D3EE)),
    listOf(Color(0xFFA855F7), Color(0xFFEC4899)),
    listOf(Color(0xFFF59E0B), Color(0xFFEF4444)),
    listOf(Color(0xFF0EA5E9), Color(0xFF6366F1)),
)

internal fun Playlist.initial(): String = if (Combined.isCombined(id)) "∑" else name.trim().firstOrNull()?.uppercase() ?: "?"

internal fun Playlist.typeLabel(): String = if (type == PlaylistType.XTREAM) "Xtream" else "M3U"

internal fun Playlist.hostLabel(): String {
    val raw = (if (type == PlaylistType.XTREAM) serverUrl else m3uUrl).trim()
    val host = runCatching { URI(raw).host }.getOrNull()
    return host?.removePrefix("www.") ?: raw.substringAfter("://").substringBefore('/').substringBefore('?').ifBlank { "–" }
}

/**
 * Zugang ohne HTTPS: Benutzername und Passwort stehen bei Xtream in jeder Stream-Adresse und sind im WLAN bzw.
 * beim Internetanbieter mitlesbar. Die App kann das nicht verhindern – nur sichtbar machen.
 *
 * Deutlicher Hinweis bei unverschlüsselten Zugängen (Einstellungen, Einrichtung).
 */
internal const val PLAIN_HTTP_WARNING = "⚠ Unverschlüsselt (http): Zugangsdaten oder URL können im Netz mitgelesen werden – HTTPS nutzen oder VPN einschalten."

internal fun isPlainHttpUrl(url: String): Boolean = url.trim().let { it.startsWith("http://", ignoreCase = true) }

internal fun Playlist.isPlainHttp(): Boolean =
    listOf(serverUrl.takeIf { type == PlaylistType.XTREAM }, m3uUrl.takeIf { type == PlaylistType.M3U }, customEpgUrl, headerEpgUrl)
        .filterNotNull()
        .any(::isPlainHttpUrl)

/** Zweite Zeile eines Zugangs: Typ · Server · ggf. „unverschlüsselt (HTTP)“ · Stand. */
internal fun Playlist.detailLabel(): String = if (Combined.isCombined(id)) {
    "Live-TV, Filme und Serien aller Zugänge – beste Quelle zuerst"
} else {
    listOfNotNull(typeLabel(), hostLabel(), "unverschlüsselt (HTTP)".takeIf { isPlainHttp() }, updatedLabel()).joinToString("  ·  ")
}

/** Je Thread ein Formatierer: Die Zugangsliste ruft das pro Zeile auf, ein neuer SimpleDateFormat kostet jedes Mal. */
private val updatedFormat = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY)
}

internal fun Playlist.updatedLabel(): String = if (lastUpdatedMs == 0L) {
    "noch nicht geladen"
} else {
    // get() ist ein Java-Plattformtyp (T!): Kotlin kennt ThreadLocals Nicht-Null-Garantie durch initialValue()
    // nicht, kann hier also nicht null sein (wie TimeUtils.formatClock).
    "aktualisiert " + updatedFormat.get()!!.apply { timeZone = TimeZone.getDefault() }.format(Date(lastUpdatedMs))
}
