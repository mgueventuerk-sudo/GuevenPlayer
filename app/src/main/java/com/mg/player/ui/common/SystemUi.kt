package com.mg.player.ui.common

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mg.player.ui.theme.AppColors

/**
 * Handy: Status- und Navigationsleiste. Außerhalb des Players sichtbar (randlos, Symbole passend zu hell/dunkel),
 * im Player ausgeblendet (Wischen vom Rand holt sie kurz zurück). Der Player dreht zudem ins Querformat.
 * Auf dem Fernseher passiert hier nichts.
 */
@Composable
fun TouchWindowMode(fullscreenVideo: Boolean) {
    if (!isTouch) return
    val activity = LocalContext.current.findActivity() ?: return
    val dark = AppColors.isDark
    DisposableEffect(fullscreenVideo, dark) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreenVideo) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { }
    }
}

/** Zählt offene geschützte Bildschirme – FLAG_SECURE bleibt gesetzt, solange mindestens einer sichtbar ist. */
private var secureScreens = 0

/**
 * Kein Bildschirmfoto und keine Vorschau in der App-Übersicht, solange dieser Inhalt sichtbar ist
 * (Einrichtung mit Zugangsdaten, PIN, Sicherung mit Kennwort).
 */
@Composable
fun SecureContent() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        secureScreens++
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            secureScreens--
            if (secureScreens <= 0) {
                secureScreens = 0
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}
