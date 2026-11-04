package com.mg.player.core.network

import com.mg.player.core.log.AppLog
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

/**
 * Verschlüsseltes DNS (DNS über HTTPS) als Rückfall, solange das VPN nicht verbunden ist: Der Internetanbieter
 * sieht dann nicht, welche Server die App aufruft. Steht das VPN, läuft DNS ohnehin durch den Tunnel.
 *
 * Nie schlechter als vorher: Namen im Heimnetz (ohne Punkt, .local, .lan, .fritz.box …) und IP-Adressen gehen ans
 * normale DNS; scheitert DoH an der Verbindung (Firewall, Störung), wird 10 Minuten lang das normale DNS genutzt.
 */
class PrivateDns(
    private val useDoh: () -> Boolean,
    private val system: Dns = Dns.SYSTEM,
    private val clock: () -> Long = System::currentTimeMillis,
    resolvers: List<Dns>? = null,
) : Dns {

    private val resolvers: List<Dns> by lazy { resolvers ?: defaultResolvers() }
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    @Volatile
    private var brokenUntil = 0L

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.lowercase(Locale.ROOT)
        if (!useDoh() || isLocal(host) || clock() < brokenUntil) return system.lookup(hostname)
        cache[host]?.let { (at, addresses) -> if (clock() - at < CACHE_MS) return addresses }
        var networkFailure = false
        for (resolver in resolvers) {
            try {
                val addresses = resolver.lookup(host)
                if (addresses.isNotEmpty()) {
                    if (cache.size > MAX_CACHE) cache.clear()
                    cache[host] = clock() to addresses
                    return addresses
                }
            } catch (e: UnknownHostException) {
                // Ohne Ursache = Name existiert nicht; mit IOException als Ursache = DoH-Server nicht erreichbar.
                if (e.cause is IOException) networkFailure = true else break
            } catch (e: IOException) {
                networkFailure = true
            }
        }
        if (networkFailure) {
            brokenUntil = clock() + BROKEN_PAUSE_MS
            AppLog.log("DNS", "Verschlüsseltes DNS nicht erreichbar – 10 Minuten normales DNS")
        }
        return system.lookup(hostname)
    }

    private fun defaultResolvers(): List<Dns> {
        val base = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
        return listOf(
            doh(base, "https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
            doh(base, "https://dns.quad9.net/dns-query", "9.9.9.9", "149.112.112.112"),
        )
    }

    private fun doh(base: OkHttpClient, url: String, vararg bootstrap: String): Dns = DnsOverHttps.Builder()
        .client(base)
        .url(url.toHttpUrl())
        // Feste Adressen: Der DoH-Server selbst wird nicht über das normale DNS gesucht.
        .bootstrapDnsHosts(bootstrap.map { InetAddress.getByName(it) })
        .build()

    companion object {
        private const val CACHE_MS = 120_000L
        private const val BROKEN_PAUSE_MS = 600_000L
        private const val MAX_CACHE = 512
        private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".fritz.box", ".localdomain", ".home.arpa")
        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        /** Heimnetz-Namen und IP-Adressen gehören nie zu einem öffentlichen DNS-Dienst. */
        fun isLocal(host: String): Boolean = '.' !in host || ':' in host || IPV4.matches(host) || LOCAL_SUFFIXES.any { host.endsWith(it) }
    }
}
