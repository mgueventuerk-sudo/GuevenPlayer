package com.mg.player.player.engine

import java.lang.reflect.Modifier
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.Type

/**
 * Einige Bibliotheken sind gegen ältere Versionen ihrer Abhängigkeiten gebaut, als die App einbindet:
 * - FFmpeg-Tondecoder (Jellyfin) gibt es nur bis Media3 1.9.0, die App nutzt eine neuere Media3-Version.
 * - Media3-OkHttp und Coil-OkHttp sind gegen OkHttp 4 gebaut, die App nutzt OkHttp 5.
 * Fehlt eine aufgerufene Methode, stürzt es erst auf dem Gerät ab (NoSuchMethodError/AbstractMethodError,
 * z. B. beim ersten AC3-/DTS-Ton). Dieser Test findet das im Build: Jede Klasse, Methode und jedes Feld der
 * Zielbibliothek, das der Bytecode benutzt, muss in der eingebundenen Version existieren und erreichbar sein,
 * und jede konkrete Klasse muss alle geerbten abstrakten Methoden umsetzen.
 */
class BinaryCompatTest {

    private val loader = BinaryCompatTest::class.java.classLoader!!

    @Test
    fun ffmpegDecoderMatchesBundledMedia3() = check(
        anchor = "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer",
        packagePrefix = "androidx/media3/decoder/ffmpeg/",
        targets = listOf("androidx/media3/"),
        minRefs = 30,
    )

    @Test
    fun media3OkHttpMatchesBundledOkHttp() = check(
        anchor = "androidx.media3.datasource.okhttp.OkHttpDataSource",
        packagePrefix = "androidx/media3/datasource/okhttp/",
        targets = listOf("okhttp3/", "okio/"),
        minRefs = 20,
    )

    @Test
    fun coilOkHttpMatchesBundledOkHttp() = check(
        anchor = "coil3.network.okhttp.OkHttpNetworkFetcher",
        packagePrefix = "coil3/network/okhttp/",
        targets = listOf("okhttp3/", "okio/"),
        minRefs = 10,
    )

    private fun check(anchor: String, packagePrefix: String, targets: List<String>, minRefs: Int) {
        val classes = BytecodeRefs.classBytes(Class.forName(anchor, false, loader), packagePrefix)
        assertTrue("Klassen aus $packagePrefix nicht gefunden", classes.isNotEmpty())

        val (refs, owners) = BytecodeRefs.collect(classes)
        fun isTarget(name: String) = targets.any { name.startsWith(it) } && !name.startsWith(packagePrefix)

        val targetRefs = refs.filter { isTarget(it.owner) }
        // Schutz gegen einen Test, der still nichts prüft (z. B. falsches Paket nach einem Update).
        assertTrue("Nur ${targetRefs.size} Verweise auf $targets gefunden", targetRefs.size >= minRefs)

        val problems = mutableListOf<String>()
        owners.filter(::isTarget).forEach { if (load(it) == null) problems += "Klasse fehlt: $it" }
        targetRefs.forEach { ref ->
            val owner = load(ref.owner) ?: return@forEach
            val problem = if (ref.field) checkField(owner, ref) else checkMethod(owner, ref)
            if (problem != null) problems += "${ref.from}: $problem"
        }
        classes.keys.forEach { name ->
            val type = load(name) ?: run {
                problems += "Klasse lässt sich nicht laden: $name"
                return@forEach
            }
            problems += missingImplementations(type)
        }
        assertTrue("$packagePrefix passt nicht zu $targets:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private fun load(internalName: String): Class<*>? = try {
        Class.forName(internalName.replace('/', '.'), false, loader)
    } catch (e: ClassNotFoundException) {
        null
    } catch (e: LinkageError) {
        null
    }

    private fun hierarchy(type: Class<*>): Sequence<Class<*>> = sequence {
        val seen = mutableSetOf<Class<*>>()
        val queue = ArrayDeque(listOf(type))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            yield(next)
            next.superclass?.let { queue += it }
            queue += next.interfaces
        }
    }

    private fun checkMethod(owner: Class<*>, ref: BytecodeRefs.Ref): String? {
        val member = "${ref.owner}.${ref.name}${ref.desc}"
        if (ref.name == "<init>") {
            val ctor = owner.declaredConstructors.firstOrNull { Type.getConstructorDescriptor(it) == ref.desc }
                ?: return "Konstruktor fehlt: $member"
            return accessProblem(ctor.modifiers, owner, ref, member)
        }
        val method = hierarchy(owner).flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == ref.name && Type.getMethodDescriptor(it) == ref.desc }
            ?: return "Methode fehlt: $member"
        return accessProblem(method.modifiers, method.declaringClass, ref, member)
    }

    private fun checkField(owner: Class<*>, ref: BytecodeRefs.Ref): String? {
        val member = "${ref.owner}.${ref.name}:${ref.desc}"
        val field = hierarchy(owner).flatMap { it.declaredFields.asSequence() }
            .firstOrNull { it.name == ref.name && Type.getDescriptor(it.type) == ref.desc }
            ?: return "Feld fehlt: $member"
        return accessProblem(field.modifiers, field.declaringClass, ref, member)
    }

    /** Private oder paketinterne Mitglieder sind aus einem anderen Paket nicht erreichbar (IllegalAccessError). */
    private fun accessProblem(modifiers: Int, declaring: Class<*>, ref: BytecodeRefs.Ref, member: String): String? {
        val callerPackage = ref.from.substringBeforeLast('.').substringBeforeLast('/').replace('/', '.')
        return when {
            Modifier.isPrivate(modifiers) -> "nicht erreichbar (private): $member"

            !Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers) &&
                declaring.name.substringBeforeLast('.') != callerPackage -> "nicht erreichbar (paketintern): $member"

            else -> null
        }
    }

    /** Neue abstrakte Methoden in Oberklassen, die die Bibliothek nicht kennt → AbstractMethodError zur Laufzeit. */
    private fun missingImplementations(type: Class<*>): List<String> {
        if (type.isInterface || Modifier.isAbstract(type.modifiers)) return emptyList()
        // Brückenmethoden (synthetisch) zählen mit: Über sie setzt die JVM generische abstrakte Methoden um.
        val all = hierarchy(type).flatMap { it.declaredMethods.asSequence() }.toList()
        return all.filter { Modifier.isAbstract(it.modifiers) }
            .filter { abstract ->
                val desc = Type.getMethodDescriptor(abstract)
                all.none { !Modifier.isAbstract(it.modifiers) && it.name == abstract.name && Type.getMethodDescriptor(it) == desc }
            }
            .map { "${type.name} setzt nicht um: ${it.declaringClass.name}.${it.name}${Type.getMethodDescriptor(it)}" }
            .distinct()
    }
}
