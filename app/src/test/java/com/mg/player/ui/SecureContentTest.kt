package com.mg.player.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.ui.common.SecureContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Einrichtung, PIN und Sicherung: kein Bildschirmfoto, solange sie sichtbar sind – danach wieder erlaubt. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SecureContentTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun secure() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE

    @Test
    fun flagFollowsVisibility() {
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) SecureContent() }
        compose.waitForIdle()
        assertEquals(WindowManager.LayoutParams.FLAG_SECURE, secure())
        shown = false
        compose.waitForIdle()
        assertEquals(0, secure())
    }
}
