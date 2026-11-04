package com.mg.player.data.combined

import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Einstellungen von „Alle Zugänge“ (Entscheidungen 03.11.2026):
 * - [autoSwitch]: automatisch zur nächsten Quelle, **standardmäßig an**. Aus = bisheriges Verhalten (nachfragen).
 * - [byAccount]: Kategorien nach Zugang getrennt statt zusammengelegt (Standard: zusammengelegt).
 * - [excluded]: Zugänge, die nicht in die Kombination einfließen.
 * - [order]: Rangfolge der Zugänge – gilt bei Gleichstand erst nach dem freiesten Zugang.
 */
@Immutable
data class CombinedConfig(
    val autoSwitch: Boolean = true,
    val byAccount: Boolean = false,
    val excluded: Set<Long> = emptySet(),
    val order: List<Long> = emptyList(),
) {
    /** Rang eines Zugangs (0 = zuerst); nicht eingeordnete Zugänge folgen nach ID. */
    fun rankOf(playlistId: Long): Int = order.indexOf(playlistId).let { if (it >= 0) it else order.size + playlistId.toInt().coerceAtLeast(0) }

    fun included(ids: List<Long>): List<Long> = ids.filter { it !in excluded }.sortedBy(::rankOf)
}

class CombinedSettings(private val prefs: SharedPreferences) {

    private val _config = MutableStateFlow(read())
    val config: StateFlow<CombinedConfig> = _config.asStateFlow()

    fun current(): CombinedConfig = _config.value

    fun update(transform: (CombinedConfig) -> CombinedConfig) {
        val next = transform(_config.value)
        prefs.edit {
            putBoolean(AUTO, next.autoSwitch)
            putBoolean(BY_ACCOUNT, next.byAccount)
            putString(EXCLUDED, next.excluded.joinToString(","))
            putString(ORDER, next.order.joinToString(","))
        }
        _config.value = next
    }

    /**
     * Einmalig mit 3.34: Korrekturen aus der früheren Prüfliste verwerfen. Sie entstanden von Hand und oft für Quellen,
     * die tatsächlich zusammengehörten (n-tv in UHD/FHD/HD); die automatische Zuordnung prüft jetzt selbst.
     * true = jetzt zum ersten Mal.
     */
    fun takeLegacyRuleReset(): Boolean {
        if (prefs.getBoolean(RULES_RESET, false)) return false
        prefs.edit { putBoolean(RULES_RESET, true) }
        return true
    }

    /**
     * Stand der Zuordnungsregeln, mit dem „Alle Zugänge“ zuletzt aufgebaut wurde. Ändern sich die Regeln mit einem
     * Update, muss neu aufgebaut werden – sonst bleibt bis zur nächsten Aktualisierung die alte Zuordnung stehen
     * (gemeldet nach 3.36: TRT 2 zeigte weiter TRT 1 als Quelle).
     */
    var builtWithMatcher: Int
        get() = prefs.getInt(MATCHER, 0)
        set(value) = prefs.edit { putInt(MATCHER, value) }

    private fun read() = CombinedConfig(
        autoSwitch = prefs.getBoolean(AUTO, true),
        byAccount = prefs.getBoolean(BY_ACCOUNT, false),
        excluded = ids(prefs.getString(EXCLUDED, null)).toSet(),
        order = ids(prefs.getString(ORDER, null)),
    )

    private fun ids(raw: String?): List<Long> = raw.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }

    private companion object {
        const val AUTO = "auto_switch"
        const val BY_ACCOUNT = "by_account"
        const val EXCLUDED = "excluded"
        const val ORDER = "order"
        const val RULES_RESET = "rules_reset_v2"
        const val MATCHER = "matcher_version"
    }
}
