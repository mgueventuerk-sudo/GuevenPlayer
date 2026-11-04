package com.mg.player.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fall aus der Prüfung: VPNs ohne Android-VPN-Netz (Kernel-WireGuard, OpenVPN auf Root-Boxen) wurden übersehen. */
class VpnInterfaceTest {

    @Test
    fun recognisesTypicalVpnInterfaces() {
        listOf("tun0", "wg0", "ppp0", "ipsec0", "tap1", "WG1").forEach { assertTrue(it, NetworkMonitor.isVpnInterfaceName(it)) }
    }

    @Test
    fun ignoresRegularInterfaces() {
        listOf("wlan0", "eth0", "lo", "rmnet0", "dummy0").forEach { assertFalse(it, NetworkMonitor.isVpnInterfaceName(it)) }
    }
}
