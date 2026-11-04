package com.mg.player.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Welche Fläche liegt gerade über dem Videobild: ein Seitenpanel (Tonspur, Untertitel, Engine, Quelle, Aufnahme,
 * Sleep-Timer), das „Mehr“-Panel oder die Stream-Info. Bewusst ohne ViewModel und ohne Compose-Umgebung, damit
 * sich genau diese Logik prüfen lässt – hier lag der Fehler aus 3.19: Ein Tipp neben das Panel setzte `moreOpen`
 * nicht zurück, am Handy blieb „Mehr“ dadurch stehen und die Steuerung blendete sich nie wieder aus.
 */
@Stable
internal class SidePanels {
    var panel by mutableStateOf<OverlayPanel?>(null)
    var infoOpen by mutableStateOf(false)
    var moreOpen by mutableStateOf(false)

    /** Panel wurde aus „Mehr“ heraus geöffnet: Zurück führt dorthin zurück. */
    var panelFromMore by mutableStateOf(false)

    val anyOpen: Boolean get() = panel != null || infoOpen || moreOpen

    fun closeAll() {
        panel = null
        infoOpen = false
        moreOpen = false
        panelFromMore = false
    }

    fun backToMore() {
        if (!panelFromMore) return
        panelFromMore = false
        moreOpen = true
    }
}
