package com.mg.player.player.afr

import android.app.Activity
import android.os.Build
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Auto Frame Rate: schaltet den Fernseher auf eine Bildwiederholrate, die ein ganzzahliges
 * Vielfaches der Video-Bildrate ist (24 fps -> 24/48 Hz, 25 fps -> 50 Hz). Das verhindert Mikroruckler.
 *
 * Funktioniert über einen Display-Mode-Wechsel (ab Android 6). Media3 setzt zusätzlich
 * ab Android 11 selbst Surface.setFrameRate.
 */
object AutoFrameRate {

    private const val MAX_ERROR = 0.01f

    fun apply(activity: Activity, frameRate: Float): Boolean {
        if (frameRate <= 0f) return false
        val display = currentDisplay(activity) ?: return false
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .minByOrNull { score(it.refreshRate, frameRate) }
            ?: return false
        if (score(best.refreshRate, frameRate) > MAX_ERROR) return false
        val window = activity.window
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId != best.modeId) {
            attrs.preferredDisplayModeId = best.modeId
            window.attributes = attrs
        }
        return true
    }

    fun reset(activity: Activity) {
        val window = activity.window
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId != 0) {
            attrs.preferredDisplayModeId = 0
            window.attributes = attrs
        }
    }

    /** Kleiner ist besser: Abweichung vom nächsten ganzzahligen Vielfachen, niedrige Vielfache bevorzugt. */
    private fun score(refreshRate: Float, frameRate: Float): Float {
        val ratio = refreshRate / frameRate
        val nearest = ratio.roundToInt()
        if (nearest < 1) return Float.MAX_VALUE
        return abs(ratio - nearest) / nearest + (nearest - 1) * 0.0001f
    }

    private fun currentDisplay(activity: Activity): Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity.display
    } else {
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay
    }
}
