package com.mg.player.ui.player

import android.content.pm.PackageManager
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Text
import com.mg.player.screenshots.PhoneHostPlain
import com.mg.player.screenshots.TvHostPlain
import com.mg.player.screenshots.settle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Bild im Bild: nur am Handy, nur wenn das Gerät es kann – und nie ein Absturz, wenn nicht. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PictureInPictureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun pipFeature(available: Boolean) {
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, available)
    }

    @Test
    fun supportDependsOnDeviceFeature() {
        pipFeature(true)
        assertTrue(pipSupported(compose.activity))
        pipFeature(false)
        assertFalse(pipSupported(compose.activity))
        assertFalse(pipSupported(null))
        assertFalse(compose.activity.inPictureInPicture())
    }

    @Test
    fun phonePlayerStartsInFullWindowAndSurvivesUpdates() {
        pipFeature(true)
        var playing by mutableStateOf(true)
        var bounds by mutableStateOf<Rect?>(null)
        compose.setContent {
            PhoneHostPlain {
                val pip by rememberPictureInPicture(playing, bounds)
                Text(if (pip) "klein" else "Vollbild")
            }
        }
        compose.settle(200)
        // Parameter ändern sich (Pause, Videogröße) – die App setzt sie neu, ohne abzustürzen.
        bounds = Rect(0, 0, 1920, 1080)
        compose.settle(200)
        playing = false
        compose.settle(200)
        compose.onNodeWithText("Vollbild").assertExists()
    }

    @Test
    fun tvAndDevicesWithoutPipAreLeftAlone() {
        pipFeature(false)
        compose.setContent {
            TvHostPlain {
                val pip by rememberPictureInPicture(true)
                Text(if (pip) "klein" else "Vollbild")
            }
        }
        compose.settle(200)
        compose.onNodeWithText("Vollbild").assertExists()
    }
}
