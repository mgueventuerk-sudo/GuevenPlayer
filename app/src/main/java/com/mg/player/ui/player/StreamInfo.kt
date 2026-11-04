package com.mg.player.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import com.mg.player.core.network.NetworkMonitor
import com.mg.player.core.network.NetworkStatus
import com.mg.player.core.network.Transport
import com.mg.player.player.engine.StreamStats
import com.mg.player.player.engine.VideoPlayerEngine
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Eine Zeile der Stream-Info (z. B. "Auflösung" – "1080p · 50 fps"). */
@Immutable
data class InfoRow(val label: String, val value: String, val highlight: Boolean = false)

/** Echtzeit-Daten fürs Player-Menü: VPN, tatsächliche Stream-Qualität, Datenrate, Verbindung. */
@Immutable
data class StreamInfo(val rows: List<InfoRow>)

/**
 * Misst einmal pro Sekunde, solange die Info-Anzeige offen ist (sonst kostet sie nichts):
 * - Downloadrate = geladene Bytes seit der letzten Messung (das, was gerade wirklich ankommt),
 * - Ø Datenrate über 10 s – bei Live-TS ohne Bitrate-Angabe die beste Schätzung der Stream-Bitrate.
 */
class StreamInfoSampler(
    private val scope: CoroutineScope,
    private val network: NetworkMonitor,
    private val engine: () -> VideoPlayerEngine?,
    private val engineLabel: () -> String,
) {
    private val _info = MutableStateFlow<StreamInfo?>(null)
    val info: StateFlow<StreamInfo?> = _info.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val samples = ArrayDeque<Pair<Long, Long>>()
            while (true) {
                network.refresh()
                val stats = engine()?.stats()
                val now = SystemClock.elapsedRealtime()
                val bytes = stats?.bytesLoaded ?: 0L
                // Neuer Stream (Zähler zurückgesetzt): Messreihe neu beginnen.
                if (samples.isNotEmpty() && bytes < samples.last().second) samples.clear()
                samples.addLast(now to bytes)
                while (samples.size > WINDOW_SAMPLES) samples.removeFirst()
                _info.value = build(stats, network.status.value, rate(samples, 2), rate(samples, WINDOW_SAMPLES))
                delay(1_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _info.value = null
    }

    /** bit/s über die letzten [count] Messungen. */
    private fun rate(samples: ArrayDeque<Pair<Long, Long>>, count: Int): Long? {
        if (samples.size < 2) return null
        val first = samples[(samples.size - count).coerceAtLeast(0)]
        val last = samples.last()
        val ms = last.first - first.first
        if (ms <= 0) return null
        return (last.second - first.second) * 8_000 / ms
    }

    private fun build(stats: StreamStats?, net: NetworkStatus, current: Long?, average: Long?): StreamInfo {
        val rows = ArrayList<InfoRow>()
        rows += InfoRow(
            "VPN",
            when {
                net.vpnForApp -> "Verbunden"
                net.vpnOnDevice -> "Aktiv, aber diese App läuft daran vorbei"
                else -> "Nicht verbunden"
            },
            highlight = net.vpnForApp,
        )
        rows += InfoRow("Auflösung", resolution(stats))
        listOfNotNull(stats?.videoCodec, stats?.audioCodec?.let { codec -> listOfNotNull(codec, channels(stats.audioChannels)).joinToString(" ") })
            .takeIf { it.isNotEmpty() }
            ?.let { rows += InfoRow("Codecs", it.joinToString(" · ")) }
        rows += InfoRow(
            "Bitrate",
            when {
                stats?.videoBitrate != null -> formatRate(stats.videoBitrate.toLong()) + " (laut Stream)"
                average != null && average > 0 -> "≈ ${formatRate(average)} (Ø 10 s gemessen)"
                else -> "wird gemessen …"
            },
        )
        rows += InfoRow("Downloadrate", current?.let(::formatRate) ?: "wird gemessen …")
        rows += InfoRow("Verbindung", connection(net))
        stats?.bufferedMs?.let { rows += InfoRow("Puffer", String.format(Locale.GERMAN, "%.1f s", it / 1000.0)) }
        stats?.droppedFrames?.takeIf { it > 0 }?.let { rows += InfoRow("Verlorene Bilder", it.toString()) }
        rows += InfoRow("Player", engineLabel())
        return StreamInfo(rows)
    }

    private fun resolution(stats: StreamStats?): String {
        val height = stats?.height ?: return "wird ermittelt …"
        val width = stats.width ?: 0
        val name = when {
            height >= 2000 || width >= 3800 -> "4K ($width×$height)"
            height >= 1400 -> "1440p"
            height >= 1000 -> "1080p"
            height >= 700 -> "720p"
            height >= 560 -> "576p (SD)"
            else -> "${height}p"
        }
        val fps = stats.frameRate?.let { String.format(Locale.GERMAN, " · %.0f fps", it) }.orEmpty()
        return name + fps
    }

    private fun channels(count: Int?): String? = when (count) {
        null -> null
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$count Kanäle"
    }

    private fun connection(net: NetworkStatus): String {
        if (!net.online) return "Keine Verbindung"
        val speed = when {
            net.transport == Transport.WIFI && net.wifiLinkMbps != null -> "${net.wifiLinkMbps} Mbit/s zum Router"
            net.linkDownKbps != null -> "bis ${formatRate(net.linkDownKbps * 1_000L)} (Schätzung)"
            else -> null
        }
        return listOfNotNull(net.transport.label, speed).joinToString(" · ")
    }

    companion object {
        private const val WINDOW_SAMPLES = 11

        fun formatRate(bitsPerSecond: Long): String = when {
            bitsPerSecond >= 1_000_000 -> String.format(Locale.GERMAN, "%.1f Mbit/s", bitsPerSecond / 1_000_000.0)
            bitsPerSecond >= 1_000 -> String.format(Locale.GERMAN, "%.0f kbit/s", bitsPerSecond / 1_000.0)
            else -> "$bitsPerSecond bit/s"
        }
    }
}
