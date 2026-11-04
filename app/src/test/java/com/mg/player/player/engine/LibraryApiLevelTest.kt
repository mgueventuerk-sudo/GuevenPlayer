package com.mg.player.player.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import javax.xml.parsers.SAXParserFactory

/**
 * Bibliotheken dürfen keine Android-/Java-Schnittstellen aufrufen, die es auf unseren ältesten Geräten nicht gibt.
 * Anlass (3.14): WireGuard 1.0.20251231 rief `CompletableFuture.newIncompleteFuture()` auf (erst ab Android 12) –
 * auf Fire TV mit Android 9 stürzte die App beim Beenden des VPN ab. Lint prüft fremde Bibliotheken nicht, die
 * Tests auf der JVM auch nicht (das JDK hat die Methode). Dieser Test vergleicht den Bytecode mit `api-versions.xml`
 * aus dem Android SDK.
 */
class LibraryApiLevelTest {

    private val loader = LibraryApiLevelTest::class.java.classLoader!!

    /** WireGuard läuft nur ab Android 7 (WireGuardBackend ist @RequiresApi(N)). */
    @Test
    fun wireGuardRunsOnAndroid7() = check(
        anchor = "com.wireguard.android.backend.GoBackend",
        packagePrefix = "com/wireguard/",
        minApi = 24,
        minRefs = 30,
        // Nur über Root (wg-quick) – nutzt die App nicht.
        skipClasses = listOf("com/wireguard/android/backend/WgQuickBackend"),
        // In WireGuard per Build.VERSION.SDK_INT >= Q abgesichert.
        guarded = setOf(
            "android/net/VpnService\$Builder.setMetered",
            "android/net/InetAddresses.parseNumericAddress",
            // Ab 1.0.20260102 in GoBackend.VpnService.onDestroy mit SDK_INT >= S abgesichert, sonst new CompletableFuture()
            // (Upstream-Commit 3f30378c „tunnel: newIncompleteFuture requires API 31“). 1.0.20251231 hatte die Abfrage nicht.
            "java/util/concurrent/CompletableFuture.newIncompleteFuture",
        ),
    )

    /**
     * Der als „abgesichert“ geführte Aufruf von newIncompleteFuture muss wirklich hinter einer SDK_INT-Abfrage in
     * derselben Methode stehen – sonst würde die Ausnahme oben einen echten Absturz (wie mit 1.0.20251231) verdecken.
     */
    @Test
    fun wireGuardGuardsNewIncompleteFuture() {
        val classes = BytecodeRefs.classBytes(Class.forName("com.wireguard.android.backend.GoBackend", false, loader), "com/wireguard/")
        val refs = BytecodeRefs.collect(classes).first
        val callers = refs.filter { it.owner == "java/util/concurrent/CompletableFuture" && it.name == "newIncompleteFuture" }.map { it.from }.toSet()
        // Fällt der Aufruf in einer neuen Version weg, diesen Test und die Ausnahme in wireGuardRunsOnAndroid7 entfernen.
        assertTrue("newIncompleteFuture wird nicht mehr aufgerufen", callers.isNotEmpty())
        // Genau hier stürzte 3.14 auf dem Fire TV (Android 9) zweimal ab (Protokoll 28.10.): VpnService.onDestroy
        // läuft bei jedem Abbau des Tunnels. Der Aufruf muss also dort stehen – und abgesichert sein.
        assertTrue(
            "Absturzpfad aus 3.14 (VpnService.onDestroy) nicht mehr vorhanden – Annahme neu prüfen: $callers",
            callers.any { it.endsWith("GoBackend\$VpnService.onDestroy") },
        )
        callers.forEach { method ->
            assertTrue(
                "$method ruft newIncompleteFuture ohne SDK_INT-Abfrage auf",
                refs.any { it.from == method && it.field && it.owner == "android/os/Build\$VERSION" && it.name == "SDK_INT" },
            )
        }
    }

    @Test
    fun ffmpegDecoderRunsOnMinSdk() = check(
        anchor = "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer",
        packagePrefix = "androidx/media3/decoder/ffmpeg/",
        minApi = 24,
        minRefs = 5,
    )

    /**
     * Was der Build (D8 + Core Library Desugaring, siehe `coreLibraryDesugaring`) für ältere Android-Versionen
     * nachrüstet: java.time, Streams, Records und statische Java-9-Fabriken wie List.of. Instanzmethoden neuer
     * JDK-Klassen (z. B. CompletableFuture.newIncompleteFuture) rüstet er NICHT nach – genau die fängt der Test.
     */
    private fun desugared(owner: String, name: String): Boolean =
        owner.startsWith("java/time/") || owner.startsWith("java/util/stream/") || owner.startsWith("java/util/function/") ||
            owner == "java/lang/Record" ||
            (owner in setOf("java/util/List", "java/util/Set", "java/util/Map") && name in setOf("of", "copyOf", "entry", "ofEntries"))

    private fun check(
        anchor: String,
        packagePrefix: String,
        minApi: Int,
        minRefs: Int,
        skipClasses: List<String> = emptyList(),
        guarded: Set<String> = emptySet(),
    ) {
        val api = ApiVersions.load()
        val classes = BytecodeRefs.classBytes(Class.forName(anchor, false, loader), packagePrefix)
            .filterKeys { name -> skipClasses.none { name == it || name.startsWith("$it\$") } }
        val (refs, owners) = BytecodeRefs.collect(classes)
        val platformRefs = refs.filter { api.knows(it.owner) }
        assertTrue("Nur ${platformRefs.size} Plattform-Verweise in $packagePrefix", platformRefs.size >= minRefs)

        val problems = mutableListOf<String>()
        owners.filter { api.knows(it) && !desugared(it, "") }.forEach { owner ->
            val since = api.classSince(owner)
            if (since > minApi) problems += "Klasse $owner erst ab API $since"
        }
        platformRefs.forEach { ref ->
            if (desugared(ref.owner, ref.name) || "${ref.owner}.${ref.name}" in guarded) return@forEach
            val since = api.memberSince(ref.owner, ref.name, ref.desc, ref.field) ?: return@forEach
            if (since > minApi) problems += "${ref.from}: ${ref.owner}.${ref.name}${if (ref.field) "" else ref.desc} erst ab API $since"
        }
        assertTrue(
            "$packagePrefix braucht neuere Android-Versionen als API $minApi:\n" + problems.distinct().joinToString("\n"),
            problems.isEmpty(),
        )
    }

    /** Auszug aus `platforms/android-XX/data/api-versions.xml`: seit welcher API es Klassen und Mitglieder gibt. */
    private class ApiVersions(private val classes: Map<String, ApiClass>) {
        class ApiClass(val since: Int, val parents: List<String>, val members: Map<String, Int>)

        fun knows(owner: String) = owner in classes

        fun classSince(owner: String) = classes.getValue(owner).since

        /** Sucht das Mitglied in der Klasse und ihren Oberklassen/Schnittstellen; null = nicht Teil des SDK. */
        fun memberSince(owner: String, name: String, desc: String, field: Boolean): Int? {
            val key = if (field) name else name + desc
            val seen = mutableSetOf<String>()
            val queue = ArrayDeque(listOf(owner))
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (!seen.add(next)) continue
                val cls = classes[next] ?: continue
                cls.members[key]?.let { return maxOf(it, classes.getValue(owner).since) }
                queue += cls.parents
            }
            return null
        }

        companion object {
            /** „31“ oder mit Nebenversion „36.1“ → Hauptversion. */
            private fun apiLevel(value: String): Int = value.substringBefore('.').toInt()

            fun load(): ApiVersions {
                val sdk = System.getProperty("guevenplayer.sdkDir")
                    ?: error("Systemeigenschaft guevenplayer.sdkDir fehlt (app/build.gradle.kts, testOptions)")
                val file = File(sdk, "platforms").listFiles().orEmpty()
                    .map { File(it, "data/api-versions.xml") }
                    .filter { it.isFile }
                    .maxByOrNull { it.parentFile.parentFile.name.removePrefix("android-").toDoubleOrNull() ?: 0.0 }
                    ?: error("api-versions.xml nicht gefunden unter $sdk/platforms")
                val classes = HashMap<String, ApiClass>(8192)
                SAXParserFactory.newInstance().newSAXParser().parse(
                    file,
                    object : DefaultHandler() {
                        private var name: String? = null
                        private var since = 1
                        private val parents = mutableListOf<String>()
                        private val members = HashMap<String, Int>()

                        override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes) {
                            when (qName) {
                                "class" -> {
                                    name = attrs.getValue("name")
                                    since = attrs.getValue("since")?.let(::apiLevel) ?: 1
                                    parents.clear()
                                    members.clear()
                                }

                                "extends", "implements" -> if (attrs.getValue("removed") == null) parents += attrs.getValue("name")

                                "method", "field" -> if (name != null) {
                                    members[attrs.getValue("name")] = attrs.getValue("since")?.let(::apiLevel) ?: since
                                }
                            }
                        }

                        override fun endElement(uri: String?, local: String?, qName: String) {
                            if (qName == "class") {
                                name?.let { classes[it] = ApiClass(since, parents.toList(), HashMap(members)) }
                                name = null
                            }
                        }
                    },
                )
                return ApiVersions(classes)
            }
        }
    }
}
