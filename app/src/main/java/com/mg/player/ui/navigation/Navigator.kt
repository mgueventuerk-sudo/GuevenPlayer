package com.mg.player.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.mg.player.domain.model.PlayRequest
import com.mg.player.domain.model.TmdbRecommendation
import com.mg.player.ui.settings.ShareMode
import java.util.concurrent.atomic.AtomicLong

sealed interface Screen {
    data object Setup : Screen
    data object AddPlaylist : Screen
    data class EditPlaylist(val playlistId: Long) : Screen
    data object Home : Screen
    data class MovieDetail(val movieId: String) : Screen
    data class SeriesDetail(val seriesId: String) : Screen
    data class TmdbCatalogTitle(val recommendation: TmdbRecommendation) : Screen
    data class Player(val request: PlayRequest) : Screen
    data object ManageCategories : Screen
    /** [playlistId] = dieser Zugang (aus seinen Einstellungen); null = der aktive. */
    data class CountryFilter(val firstRun: Boolean, val playlistId: Long? = null) : Screen
    data class Share(val mode: ShareMode) : Screen
    data object SportLeagues : Screen

    data object FavoritesOrder : Screen
    data object Surfshark : Screen

    /** Live-Mosaik: Sender der Gruppe [categoryId] ab [startChannelId] als Standbilder. */
    data class Mosaic(val categoryId: String, val startChannelId: String?) : Screen

    /** [leagueKey]: gleich die Senderauswahl dieser Liga öffnen. */
    data class SportAssign(val leagueKey: String? = null) : Screen
}

/** Ein Eintrag im Back-Stack mit eigenem ViewModel-Speicher (ViewModels leben nur so lange wie der Screen). */
class BackStackEntry(val screen: Screen) : ViewModelStoreOwner {
    val id: Long = nextId.incrementAndGet()
    override val viewModelStore: ViewModelStore = ViewModelStore()

    private companion object {
        val nextId = AtomicLong()
    }
}

/** Schlanker Navigations-Stack ohne navigation-compose. */
@Stable
class Navigator(initial: Screen) {

    val entries = mutableStateListOf(BackStackEntry(initial))

    /** Wird für entfernte Einträge aufgerufen (z. B. um gespeicherten UI-Zustand freizugeben). */
    var onRemoved: ((BackStackEntry) -> Unit)? = null

    val current: BackStackEntry get() = entries.last()
    val canPop: Boolean get() = entries.size > 1

    fun push(screen: Screen) {
        entries.add(BackStackEntry(screen))
    }

    fun pop(): Boolean {
        if (!canPop) return false
        dispose(entries.removeAt(entries.lastIndex))
        return true
    }

    /** Ersetzt den obersten Eintrag (dessen ViewModels werden freigegeben, z. B. der Player samt Stream). */
    fun replace(screen: Screen) {
        val old = entries.removeAt(entries.lastIndex)
        entries.add(BackStackEntry(screen))
        dispose(old)
    }

    /** Ersetzt den kompletten Stack (z. B. nach der Ersteinrichtung). */
    fun resetTo(screen: Screen) {
        val old = entries.toList()
        entries.clear()
        entries.add(BackStackEntry(screen))
        old.forEach(::dispose)
    }

    private fun dispose(entry: BackStackEntry) {
        entry.viewModelStore.clear()
        onRemoved?.invoke(entry)
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator fehlt") }
