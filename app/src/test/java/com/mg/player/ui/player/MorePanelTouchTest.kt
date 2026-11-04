package com.mg.player.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.domain.model.PlayerEngineType
import com.mg.player.screenshots.PHONE_LANDSCAPE
import com.mg.player.ui.theme.AppIcons
import com.mg.player.screenshots.PhoneHostPlain
import com.mg.player.screenshots.settle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Am Handy gemeldet (3.19): Das „Mehr“-Panel ließ sich nicht mehr schließen – ein Tipp daneben bewirkte nichts und
 * die Steuerung blendete sich wegen des offenen Panels auch nicht mehr aus.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = PHONE_LANDSCAPE)
class MorePanelTouchTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var panels: SidePanels

    private val engineEntry =
        MoreEntry("ENGINE", "Player-Engine: ${PlayerEngineType.MEDIA3.label}", AppIcons.Engine, OverlayAction.ENGINE)

    private fun showMorePanel() {
        compose.setContent {
            PhoneHostPlain {
                panels = remember { SidePanels().apply { moreOpen = true } }
                val feedback = remember { TouchFeedback() }
                var taps by remember { mutableStateOf(0) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("video")
                        .playerTouchControls(
                            enabled = true,
                            isLive = true,
                            feedback = feedback,
                            // Genau die Verdrahtung aus PlayerScreenState.tap().
                            onTap = {
                                taps++
                                if (panels.anyOpen) panels.closeAll()
                            },
                            onAction = {},
                        ),
                ) {
                    if (panels.moreOpen) {
                        MorePanel(
                            sections = listOf(
                                MoreSection("Bild & Ton", listOf(engineEntry)),
                            ),
                            focusKey = null,
                            onFocusKey = {},
                            onAction = {},
                            onClose = { panels.moreOpen = false },
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
            }
        }
        compose.settle(300)
    }

    @Test
    fun tapNextToThePanelClosesIt() {
        showMorePanel()
        compose.onNodeWithText("Mehr").assertExists()
        // Links neben dem Panel (das Panel liegt rechts).
        compose.onNodeWithTag("video").performTouchInput { click(Offset(width * 0.1f, centerY)) }
        compose.settle(400)
        assertFalse("Tipp daneben muss schließen", panels.moreOpen)
        compose.onNodeWithText("Mehr").assertDoesNotExist()
    }

    @Test
    fun closeButtonIsOfferedOnPhonesAndWorks() {
        showMorePanel()
        compose.onNodeWithContentDescription("Schließen").performClick()
        compose.settle(400)
        assertFalse(panels.moreOpen)
    }

    /** Ein Tipp im Panel (zwischen den Einträgen) darf es nicht schließen – sonst trifft man beim Scrollen daneben. */
    @Test
    fun tapInsideThePanelKeepsItOpen() {
        showMorePanel()
        compose.onNodeWithTag("video").performTouchInput { click(Offset(width * 0.85f, height * 0.75f)) }
        compose.settle(400)
        assertTrue("Tipp im Panel darf nichts schließen", panels.moreOpen)
        compose.onNodeWithText("Mehr").assertExists()
    }
}
