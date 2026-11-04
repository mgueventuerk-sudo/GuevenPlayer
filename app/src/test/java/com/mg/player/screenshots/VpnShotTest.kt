package com.mg.player.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mg.player.core.vpn.VpnDropReason
import com.mg.player.core.vpn.VpnState
import com.mg.player.ui.common.AmbientBackground
import com.mg.player.ui.vpn.VpnBadge
import com.mg.player.ui.vpn.VpnBanner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** VPN-Anzeige: alle Schilder und das rote Band (Fernseher und Handy). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VpnShotTest {

    @get:Rule
    val compose = createComposeRule()

    private val states = listOf(
        VpnState.Connected("Deutschland · Frankfurt", "DE", 0L),
        VpnState.Connecting("Deutschland · Frankfurt", "DE"),
        VpnState.Disconnected("Deutschland · Frankfurt", "DE", VpnDropReason.SERVER_UNREACHABLE, 0L),
        VpnState.Off,
    )

    @Test
    @Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun tvBannerAndBadges() {
        compose.setContent {
            TvHostPlain {
                AmbientBackground(artworkUrl = null) {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { states.forEach { VpnBadge(it) } }
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { states.forEach { VpnBadge(it, short = true) } }
                        }
                        VpnBanner(states[2], touch = false, Modifier.align(Alignment.TopCenter))
                    }
                }
            }
        }
        compose.waitForText("Verbindung ungeschützt")
        compose.shootBothThemes("30_vpn_tv")
    }

    @Test
    @Config(sdk = [34], qualifiers = PHONE_PORTRAIT)
    fun phoneBanner() {
        compose.setContent {
            PhoneHostPlain {
                AmbientBackground(artworkUrl = null) {
                    Box(Modifier.fillMaxSize()) {
                        Row(Modifier.align(Alignment.Center).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            states.forEach { VpnBadge(it, iconOnly = true) }
                        }
                        VpnBanner(VpnState.Disconnected("Niederlande", "NL", VpnDropReason.PERMISSION, 0L), touch = true, Modifier.align(Alignment.TopCenter))
                    }
                }
            }
        }
        compose.waitForText("VPN-Erlaubnis fehlt")
        compose.shootBothThemes("31_vpn_handy")
    }
}
