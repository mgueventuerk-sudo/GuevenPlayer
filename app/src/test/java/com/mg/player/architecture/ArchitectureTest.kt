package com.mg.player.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.Test

/**
 * Schichtregeln (Plan „Sauberer Code“ §3.1), geprüft am Quelltext. Neue Verstöße lassen den Build scheitern.
 * - Oberfläche greift nicht direkt auf Datenbank oder HTTP zu (immer über Repositories/Dienste).
 * - Der Wiedergabe-Wächter ist reines Kotlin (testbar ohne Android) und kennt die Oberfläche nicht.
 * - Engines, Daten- und Kern-Schicht kennen die Oberfläche nicht (Ausnahme: MainActivity als Ziel von Benachrichtigungen).
 * - Fachmodell ohne Android (nur die Compose-Markierung @Immutable und PagingData sind erlaubt).
 * - Protokoll nur über AppLog (Schwärzen von Zugangsdaten an einer Stelle).
 */
class ArchitectureTest {

    private fun production(pkg: String) = Konsist.scopeFromPackage(pkg, sourceSetName = "main").files

    private fun imports(file: com.lemonappdev.konsist.api.declaration.KoFileDeclaration) = file.imports.map { it.name }

    @Test
    fun uiDoesNotTouchDatabaseOrHttp() {
        production("com.mg.player.ui..").assertFalse { file ->
            imports(file).any { it.startsWith("com.mg.player.data.db") || it.startsWith("okhttp3") }
        }
    }

    @Test
    fun supervisorIsPureKotlin() {
        production("com.mg.player.player.supervisor..").assertFalse { file ->
            imports(file).any { it.startsWith("android.") || it.startsWith("androidx.") || it.startsWith("com.mg.player.ui") }
        }
    }

    @Test
    fun lowerLayersDoNotDependOnUi() {
        listOf("com.mg.player.player..", "com.mg.player.data..", "com.mg.player.core..", "com.mg.player.domain..").forEach { pkg ->
            production(pkg).assertFalse { file ->
                imports(file).any { it.startsWith("com.mg.player.ui") && it != "com.mg.player.ui.MainActivity" }
            }
        }
    }

    @Test
    fun domainModelHasNoAndroidDependencies() {
        val allowed = setOf("androidx.compose.runtime.Immutable", "androidx.paging.PagingData")
        production("com.mg.player.domain..").assertFalse { file ->
            imports(file).any { (it.startsWith("android.") || it.startsWith("androidx.")) && it !in allowed }
        }
    }

    @Test
    fun loggingOnlyThroughAppLog() {
        val allowedPackages = setOf("com.mg.player.core.log", "com.mg.player.core.security")
        Konsist.scopeFromProduction().files
            .filter { it.packagee?.name !in allowedPackages }
            .assertFalse { file -> imports(file).contains("android.util.Log") }
    }
}
