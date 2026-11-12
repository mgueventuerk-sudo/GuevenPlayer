package com.mg.player.ui.detail

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mg.player.ui.common.ActionButton
import com.mg.player.ui.common.glass
import com.mg.player.ui.navigation.LocalNavigator
import com.mg.player.ui.theme.AppColors
import com.mg.player.ui.theme.AppIcons
import com.mg.player.ui.theme.StaticIcon

/**
 * Trailer-Vorschlag bei Film/Serie: YouTubes eigene, schlanke Embed-Seite läuft eingebettet in GuevenPlayer weiter -
 * kein Website-Drumherum (keine Kommentare, keine „Nächstes Video“-Vorschläge), kein Wechsel zur YouTube-App und
 * kein externer Browser. Bleibt technisch YouTubes Player (eine eigene Stream-Wiedergabe verstieße gegen YouTubes
 * Nutzungsbedingungen und bräche bei jeder YouTube-Änderung neu). „Zurück“ (Fernbedienung oder Handy-Geste, über
 * den globalen BackHandler in AppRoot) kehrt sofort zum Titel zurück.
 *
 * JavaScript ist für YouTubes Embed-Player zwingend - unbedenklich, weil dieses WebView nie eine fremde oder
 * nutzergesteuerte Adresse lädt, nur das feste "youtube.com/embed/<key>" unten.
 *
 * Nicht direkt per loadUrl() auf die Embed-Adresse: Ohne einen gültigen YouTube-Origin/Referrer lehnt YouTubes
 * Einbettungs-Prüfung jedes Video ab (Fehler 153 "Fehler bei der Konfiguration des Videoplayers", unabhängig vom
 * Video selbst). loadDataWithBaseURL() mit https://www.youtube.com als Basisadresse gibt dem WebView genau diesen
 * Origin, als läge die Seite tatsächlich auf youtube.com.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TrailerScreen(youtubeKey: String) {
    val navigator = LocalNavigator.current
    var failed by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (failed) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Trailer konnte nicht geladen werden", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Box(Modifier.height(16.dp))
                ActionButton("Zurück", onClick = navigator::pop, primary = true)
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Ohne Tipp im eingebetteten Player starten - "nahtlos", kein zweiter Schritt nach dem Laden.
                        settings.mediaPlaybackRequiresUserGesture = false
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) failed = true
                            }
                        }
                        // youtube.com/embed statt /watch: nur der Player, kein Website-Drumherum (Kommentare, Vorschläge).
                        // Als Iframe mit youtube.com als Basisadresse laden (siehe Klassenkommentar) - sonst Fehler 153.
                        val html =
                            """
                            <html><body style="margin:0;padding:0;background:#000;">
                            <iframe width="100%" height="100%" style="position:fixed;top:0;left:0;border:0;"
                                src="https://www.youtube.com/embed/$youtubeKey?autoplay=1&playsinline=1"
                                allow="autoplay; encrypted-media" allowfullscreen></iframe>
                            </body></html>
                            """.trimIndent()
                        loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() },
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp)
                .size(40.dp)
                .glass(CircleShape, raised = true)
                .clickable(onClickLabel = "Zurück", onClick = navigator::pop),
            contentAlignment = Alignment.Center,
        ) {
            StaticIcon(AppIcons.Close, tint = AppColors.TextPrimary, size = 20.dp, contentDescription = "Zurück")
        }
    }
}
