package com.mg.player.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import com.mg.player.ui.common.findActivity
import com.mg.player.ui.common.isTouch

/** Bild-im-Bild ist möglich: Handy, Android 8+ und das Gerät bietet die Funktion an. */
fun pipSupported(activity: Activity?): Boolean = activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
    activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

/** true, wenn die Activity gerade als kleines Fenster läuft. */
fun Activity?.inPictureInPicture(): Boolean = this != null && isInPictureInPictureMode

/**
 * Bild-im-Bild am Handy: Wer während der Wiedergabe die App verlässt (Home-Geste), sieht das Video als kleines
 * Fenster weiter. Ab Android 12 macht das System den Übergang selbst (flüssig), davor wird beim Verlassen
 * umgeschaltet. Liefert, ob der Player gerade im kleinen Fenster läuft – dann ohne Bedienelemente.
 */
@Composable
fun rememberPictureInPicture(playing: Boolean, videoBounds: Rect? = null): State<Boolean> {
    val activity = LocalContext.current.findActivity()
    val inPip = remember { mutableStateOf(activity.inPictureInPicture()) }
    val touch = isTouch
    val wantPip by rememberUpdatedState(touch && playing)
    val bounds by rememberUpdatedState(videoBounds)
    val enabled = touch && pipSupported(activity)

    DisposableEffect(activity, enabled) {
        val component = activity as? ComponentActivity
        if (!enabled || component == null) return@DisposableEffect onDispose { }
        val modeListener = Consumer<PictureInPictureModeChangedInfo> { inPip.value = it.isInPictureInPictureMode }
        val leaveListener = Runnable {
            // Vor Android 12 gibt es kein automatisches Bild-im-Bild.
            if (wantPip && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                runCatching { component.enterPictureInPictureMode(pipParams(autoEnter = false, bounds)) }
            }
        }
        component.addOnPictureInPictureModeChangedListener(modeListener)
        component.addOnUserLeaveHintListener(leaveListener)
        onDispose {
            component.removeOnPictureInPictureModeChangedListener(modeListener)
            component.removeOnUserLeaveHintListener(leaveListener)
            // Außerhalb des Players kein Bild-im-Bild.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching { component.setPictureInPictureParams(pipParams(autoEnter = false)) }
        }
    }
    DisposableEffect(activity, enabled, playing, videoBounds) {
        if (enabled && activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { activity.setPictureInPictureParams(pipParams(autoEnter = playing, videoBounds)) }
        }
        onDispose { }
    }
    return inPip
}

/** Fensterparameter: 16:9, und – damit das Video beim Verkleinern nahtlos ins kleine Fenster gleitet – seine Lage. */
@RequiresApi(Build.VERSION_CODES.O)
private fun pipParams(autoEnter: Boolean, sourceRect: Rect? = null): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
    sourceRect?.takeIf { !it.isEmpty }?.let { builder.setSourceRectHint(it) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder.setAutoEnterEnabled(autoEnter)
        builder.setSeamlessResizeEnabled(true)
    }
    return builder.build()
}
