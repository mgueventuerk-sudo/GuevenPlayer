package com.mg.player.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.LocalDate
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Zertifikats-Pinning (ab 3.22): Jeder Dienst, der Schlüssel, Zugangsdaten oder DNS-Anfragen bekommt, ist gepinnt,
 * mit Reserve-Pins und einem Ablaufdatum in der Zukunft. Läuft das Datum in weniger als 60 Tagen ab, schlägt dieser
 * Test absichtlich fehl: Pins mit tools/check_pins.py gegen die echten Ketten prüfen und das Datum verlängern.
 */
class NetworkSecurityConfigTest {

    private val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res/xml/network_security_config.xml"))

    private val pinnedConfigs: Map<String, Element> = buildMap {
        val configs = doc.getElementsByTagName("domain-config")
        for (i in 0 until configs.length) {
            val config = configs.item(i) as Element
            if (config.getElementsByTagName("pin-set").length == 0) continue
            val domains = config.getElementsByTagName("domain")
            for (d in 0 until domains.length) put(domains.item(d).textContent.trim(), config)
        }
    }

    @Test
    fun allSecretReceivingHostsArePinned() {
        val expected = setOf(
            "api.themoviedb.org", // TMDB-API-Schlüssel
            "sports.bzzoiro.com", // Bzzoiro-Token
            "api.sportmonks.com", // Sportmonks-Token
            "api.surfshark.com", // Surfshark-Anmeldung
            "cloudflare-dns.com", // DNS über HTTPS
            "dns.quad9.net", // DNS über HTTPS
        )
        assertEquals(expected, pinnedConfigs.keys)
    }

    @Test
    fun everyPinSetHasBackupPinsAndValidDigests() {
        pinnedConfigs.forEach { (domain, config) ->
            val pins = config.getElementsByTagName("pin")
            assertTrue("$domain braucht Reserve-Pins (mindestens 2 Wurzeln)", pins.length >= 2)
            for (i in 0 until pins.length) {
                val pin = pins.item(i) as Element
                assertEquals("SHA-256", pin.getAttribute("digest"))
                assertEquals("$domain: Pin ist kein SHA-256-Wert", 32, Base64.getDecoder().decode(pin.textContent.trim()).size)
            }
        }
    }

    @Test
    fun pinnedHostsNeverAllowCleartext() {
        pinnedConfigs.forEach { (domain, config) -> assertEquals(domain, "false", config.getAttribute("cleartextTrafficPermitted")) }
    }

    /** Absichtliche Erinnerung: Abgelaufene Pins schützen nicht mehr (Android prüft dann wieder ohne Pinning). */
    @Test
    fun pinsDoNotExpireSoon() {
        pinnedConfigs.forEach { (domain, config) ->
            val pinSet = config.getElementsByTagName("pin-set").item(0) as Element
            val expiration = LocalDate.parse(pinSet.getAttribute("expiration"))
            assertTrue("$domain: Pins laufen am $expiration ab – erneuern (docs/Pinning.md)", expiration.isAfter(LocalDate.now().plusDays(60)))
        }
    }

    /**
     * Android bricht beim Lesen ab, wenn eine Domain zweimal vorkommt (auch mit unterschiedlichem includeSubdomains):
     * „cloudflare-dns.com has already been specified“ – dann scheitert jede HTTPS-Verbindung der App. Im Emulator-Labor
     * von 3.23 so aufgefallen; Robolectric liest die Datei nicht, darum diese Prüfung.
     */
    @Test
    fun noDomainIsSpecifiedTwice() {
        val domains = doc.getElementsByTagName("domain")
        val names = (0 until domains.length).map { domains.item(it).textContent.trim() }
        assertEquals(emptySet<String>(), names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys)
    }

    /** Pins ohne Domain-Freigabe für Subdomains: ein *.themoviedb.org-Bilder-CDN mit anderer CA bleibt erreichbar. */
    @Test
    fun pinsApplyOnlyToTheNamedHost() {
        val domains = doc.getElementsByTagName("domain")
        for (i in 0 until domains.length) {
            val domain = domains.item(i) as Element
            if (domain.textContent.trim() in pinnedConfigs) assertEquals("false", domain.getAttribute("includeSubdomains"))
        }
    }
}
