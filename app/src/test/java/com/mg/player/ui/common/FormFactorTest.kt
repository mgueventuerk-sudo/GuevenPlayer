package com.mg.player.ui.common

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Fernseher oder Handy? Davon hängt ab, welche Oberfläche die App zeigt. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FormFactorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun features(touch: Boolean, leanback: Boolean) {
        val pm = shadowOf(context.packageManager)
        pm.setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, touch)
        pm.setSystemFeature(PackageManager.FEATURE_LEANBACK, leanback)
    }

    @Test
    fun phoneWithTouchscreenGetsTouchUi() {
        features(touch = true, leanback = false)
        assertEquals(FormFactor.TOUCH, detectFormFactor(context))
    }

    @Test
    fun leanbackDeviceIsTv() {
        features(touch = true, leanback = true)
        assertEquals(FormFactor.TV, detectFormFactor(context))
    }

    @Test
    fun boxWithoutTouchscreenIsTv() {
        features(touch = false, leanback = false)
        assertEquals(FormFactor.TV, detectFormFactor(context))
    }

    @Test
    @Config(qualifiers = "television")
    fun televisionUiModeIsTv() {
        features(touch = true, leanback = false)
        assertEquals(FormFactor.TV, detectFormFactor(context))
    }
}
