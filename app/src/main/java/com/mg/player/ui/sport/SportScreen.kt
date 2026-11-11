package com.mg.player.ui.sport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.data.reminder.LaunchRequest
import com.mg.player.domain.model.MatchedEvent
import com.mg.player.domain.model.SportEvent
import com.mg.player.domain.model.SportGroups
import com.mg.player.domain.model.playlistIdOf
import com.mg.player.domain.model.stationLabel
import com.mg.player.domain.sport.EventCountdownTicker
import com.mg.player.domain.sport.SportBoard
import com.mg.player.domain.sport.SportEventFilter
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.ListSurface
import com.mg.player.ui.common.LiveBadge
import com.mg.player.ui.common.LocalAppContainer
import com.mg.player.ui.common.MarqueeText
import com.mg.player.ui.common.RowColors
import com.mg.player.ui.common.ScreenTitle
import com.mg.player.ui.common.edgePadding
import com.mg.player.ui.common.isTouch
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.live.SPORT_MOSAIC_PREFIX
import com.mg.player.ui.navigation.Screen
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.StaticIcon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Sport-Sektion: „Jetzt live“ (OK startet den Sender) und „Demnächst“ für die nächsten 7 Tage (OK: Erinnerung setzen
 * oder Sender ansehen), bei mehreren Sportarten darin je Sportart unterteilt. Darunter „Ohne Sender“: Spiele der gewählten Ligen, für die im EPG kein Sender gefunden wurde –
 * OK öffnet die Zuordnung für die Liga. So bleibt sichtbar, warum ein Spiel fehlt.
 */
@Composable
fun SportScreen(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val board by container.sport.repository.board.collectAsStateWithLifecycle()
    val unmatchedFlow = remember(container) { container.sport.repository.unmatched }
    val unmatched by unmatchedFlow.collectAsStateWithLifecycle(emptyList())
    val problem by container.sport.repository.problem.collectAsStateWithLifecycle()
    val hasEpg by produceState(true, board) { value = container.sport.repository.hasEpg() }
    var selected by remember { mutableStateOf<MatchedEvent?>(null) }
    val firstRequester = remember { FocusRequester() }
    val ticker = container.sport.countdownTicker
    // Sender aus einem anderen Zugang: Name des Zugangs dazuschreiben (gewechselt wird nie von selbst).
    val playlistsFlow = remember(container) { container.playlistRepository.observePlaylists() }
    val playlists by playlistsFlow.collectAsStateWithLifecycle(emptyList())
    val activeFlow = remember(container) { container.settingsRepository.settings.map { it.activePlaylistId }.distinctUntilChanged() }
    val activeId by activeFlow.collectAsStateWithLifecycle(null)
    val otherAccounts = remember(playlists, activeId) { playlists.filter { it.id != activeId }.associate { it.id to it.name } }
    val showHints by remember { container.settingsRepository.settings.map { it.showHints } }.collectAsStateWithLifecycle(true)

    Column(modifier.fillMaxSize().padding(start = edgePadding, end = edgePadding, top = 4.dp)) {
        ScreenTitle("Sport")
        // Einfuehrungstext nur, solange die App neu ist (AppSettings.showHints).
        if (showHints) Text(
            if (isTouch) {
                "Laufendes Spiel antippen: Der Sender startet. Bei kommenden Spielen kannst du eine Erinnerung setzen."
            } else {
                "OK auf einem laufenden Spiel startet den Sender – bei kommenden Spielen kannst du eine Erinnerung setzen."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
        val current = board
        if (current == null) {
            // Erst die Liste zeigen, wenn die Übersicht steht: Sonst landete der Fokus auf den zuerst sichtbaren Spielen
            // „Ohne Sender“, und beim Nachladen rutschte die Liste ans Ende (Fokus auf dem letzten Spiel). Nicht fokussierbar,
            // damit OK auf „Sport“ nicht auf einem Knopf landet, der gleich wieder verschwindet.
            Text("Wird geladen …", style = MaterialTheme.typography.titleMedium, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 48.dp))
            return@Column
        }
        val nothingMatched = current.isEmpty
        if (nothingMatched && unmatched.isEmpty()) {
            EmptySport(loading = false, problem = problem, onLeagues = { navigator.push(Screen.SportLeagues) })
            return@Column
        }
        if (nothingMatched) {
            // Spiele da, aber keins im EPG gefunden: Grund nennen statt den Bereich verschwinden zu lassen.
            Text(
                problem ?: if (hasEpg) {
                    "Für diese Spiele wurde in deinen Zugängen noch kein Sender gefunden. Bei jedem Spiel steht, wo es läuft – " +
                        "das Sport-Programm reicht etwa 4 bis 5 Tage voraus, spätere Spiele bekommen ihren Sender, sobald sie näher rücken."
                } else {
                    "Es ist noch kein EPG geladen – die App sucht die Sender über das öffentliche Sport-Programm und die Sendernamen."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.Rating,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        LazyColumn(
            // Beim ersten Betreten fest auf das oberste Spiel (sonst entscheidet die Fokussuche nach Lage).
            modifier = Modifier.fillMaxSize().focusRestorer(if (current.firstFocus != null) firstRequester else FocusRequester.Default).testTag(LIST_TAG),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp, start = 4.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            run {
                eventItems(current, ticker, otherAccounts, firstRequester) { item ->
                    // Läuft: direkt abspielen (gesperrte Gruppen fragen die PIN ab). Demnächst: Erinnerung oder Sender.
                    if (SportEventFilter.isLive(item, System.currentTimeMillis())) LaunchRequest.play(item.channelId) else selected = item
                }
            }
            if (unmatched.isNotEmpty()) {
                // Plan „Spiel-Finder“ I: „Sender suchen“ zeigt die wahrscheinlichsten Sender als Live-Mosaik.
                unmatchedItems(unmatched) { event -> navigator.push(Screen.Mosaic(SPORT_MOSAIC_PREFIX + event.id, null)) }
            }
            // Am Ende (nicht oben): Beim Öffnen liegt der Fokus so auf dem ersten Spiel.
            item(key = "leagues", contentType = "action") {
                ActionButton(
                    "Ligen wählen",
                    onClick = { navigator.push(Screen.SportLeagues) },
                    icon = AppIcons.Settings,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            item(key = "assign", contentType = "action") {
                ActionButton(
                    "Sender korrigieren",
                    onClick = { navigator.push(Screen.SportAssign()) },
                    icon = AppIcons.LiveTv,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    selected?.let { item -> SportEventDialog(item, onDismiss = { selected = null }) }
}

/**
 * Abschnitte mit Überschrift: „Jetzt live“, dann „Heute“, „Morgen“, „Diese Woche“. Sind mehrere Sportarten dabei, steht
 * in jedem Abschnitt darunter je Sportart eine kleine Überschrift („Fußball“, „Motorsport“ …).
 */
private fun LazyListScope.eventItems(
    board: SportBoard,
    ticker: EventCountdownTicker,
    otherAccounts: Map<Long, String>,
    firstRequester: FocusRequester,
    onOpen: (MatchedEvent) -> Unit,
) {
    val firstId = board.firstFocus?.event?.id
    board.sections().forEach { section ->
        item(key = "h-${section.key}", contentType = "header") { SectionHeader(section.title) }
        section.groups.forEach { group ->
            if (group.sport.isNotEmpty()) {
                item(key = "g-${section.key}-${group.sport}", contentType = "subheader") { SportHeader(group.sport) }
            }
            val prefix = if (section.live) "live" else "next"
            items(group.events, key = { "$prefix-${it.event.id}" }, contentType = { "event" }) {
                val modifier = if (it.event.id == firstId) Modifier.focusRequester(firstRequester) else Modifier
                EventRow(it, live = section.live, ticker, otherAccounts, onOpen, modifier)
            }
        }
    }
}

/** Spiele ohne Sender – bei mehreren Sportarten ebenfalls nach Sportart unterteilt. */
private fun LazyListScope.unmatchedItems(unmatched: List<SportEvent>, onOpen: (SportEvent) -> Unit) {
    item(key = "h-unmatched", contentType = "header") { SectionHeader("Ohne Sender in deinem Zugang") }
    val groups = SportGroups.split(unmatched) { it.sport }
    groups.forEach { (sport, events) ->
        if (groups.size > 1) item(key = "g-unmatched-$sport", contentType = "subheader") { SportHeader(sport) }
        items(events, key = { "none-${it.id}" }, contentType = { "event" }) { event -> UnmatchedRow(event) { onOpen(event) } }
    }
}

/** Unterüberschrift einer Sportart innerhalb eines Abschnitts (nicht fokussierbar). */
@Composable
private fun SportHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = AppColors.TextMuted,
        modifier = Modifier.padding(start = 10.dp, top = 4.dp),
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = AppColors.TextSecondary,
        modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun EventRow(
    item: MatchedEvent,
    live: Boolean,
    ticker: EventCountdownTicker,
    otherAccounts: Map<Long, String>,
    onOpen: (MatchedEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val event = item.event
    val touch = isTouch
    ListSurface(onClick = { onOpen(item) }, modifier = modifier.fillMaxWidth(), containerColor = AppColors.Glass) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StaticIcon(AppIcons.Sport, tint = RowColors.accent, size = 24.dp, contentDescription = null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = RowColors.primary,
                    maxLines = if (touch) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Der Sender ist die Antwort, auf die es ankommt - er steht vorn und traegt Gewicht. Wettbewerb,
                // Anstoss und Herkunft bleiben daneben, aber gedaempft: vorher hatten alle vier dasselbe Gewicht.
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = RowColors.primary, fontWeight = FontWeight.Medium)) { append(item.channelName) }
                        append(" · ${event.league} · ${kickoff(event.startMs)} · ${item.source.label}")
                        playlistIdOf(item.channelId)?.let(otherAccounts::get)?.let { append(" (Zugang $it)") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = RowColors.secondary,
                    maxLines = if (touch) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Am Handy ist die Zeile schmal: Status unter den Text statt daneben.
                if (touch) {
                    Spacer(Modifier.height(6.dp))
                    EventState(event.startMs, live, ticker)
                }
            }
            if (!touch) {
                Spacer(Modifier.width(12.dp))
                EventState(event.startMs, live, ticker)
            }
        }
    }
}

/**
 * Spiel ohne Sender im eigenen Zugang: zeigt, wo es laut Programm bzw. Quelle läuft („Läuft auf beIN SPORTS 1 – nicht in
 * deinem Zugang“), sonst „Sender unbekannt“. OK öffnet die Korrektur für die Liga.
 */
@Composable
private fun UnmatchedRow(event: SportEvent, onOpen: () -> Unit) {
    ListSurface(onClick = onOpen, modifier = Modifier.fillMaxWidth(), containerColor = AppColors.Glass) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StaticIcon(AppIcons.Sport, tint = RowColors.muted, size = 24.dp, contentDescription = null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                MarqueeText(event.title, style = MaterialTheme.typography.titleSmall, color = RowColors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${event.league} · ${kickoff(event.startMs)} · ${unmatchedHint(event.stations)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = RowColors.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Keine Spiele der gewählten Ligen: Grund nennen und den Weg zu den Ligen anbieten (fokussierbar am TV). */
@Composable
private fun EmptySport(loading: Boolean, problem: String?, onLeagues: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        StaticIcon(AppIcons.Sport, tint = AppColors.TextMuted, size = 40.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                loading -> "Wird geladen …"
                problem != null -> problem
                else -> "In den nächsten 7 Tagen keine Spiele der gewählten Ligen"
            },
            style = MaterialTheme.typography.titleMedium,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.height(16.dp))
        ActionButton("Ligen wählen", onClick = onLeagues, icon = AppIcons.Settings)
    }
}

@Composable
private fun EventState(startMs: Long, live: Boolean, ticker: EventCountdownTicker) {
    if (live) LiveBadge() else CountdownText(startMs, ticker, MaterialTheme.typography.labelLarge, RowColors.accent)
}

/** Hinweis für ein Spiel ohne Sender im Zugang. */
internal fun unmatchedHint(stations: List<String>): String = when {
    stations.isEmpty() -> "OK: Sender suchen"
    else -> "läuft auf ${stations.take(2).joinToString(", ", transform = ::stationLabel)} – nicht in deinem Zugang"
}

/** Je Thread ein Formatierer: Die Ereignisliste ruft das pro Zeile auf, ein neuer SimpleDateFormat kostet jedes Mal. */
private val kickoffFormat = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("EE dd.MM. HH:mm", Locale.GERMANY)
}

/** „Sa 31.10. 18:30“ (Ortszeit). */
internal fun kickoff(ms: Long): String =
    // get() ist ein Java-Plattformtyp (T!): Kotlin kennt ThreadLocals Nicht-Null-Garantie durch initialValue()
    // nicht, kann hier also nicht null sein (wie TimeUtils.formatClock).
    kickoffFormat.get()!!.apply { timeZone = TimeZone.getDefault() }.format(Date(ms))

const val LIST_TAG = "sport-list"
