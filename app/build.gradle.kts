import java.text.SimpleDateFormat
import java.util.Date
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.ksp)
}

// Jede neue APK bekommt eine höhere Versionsnummer (Minuten seit 2026) und lässt sich als Update installieren.
val buildMinutes = ((System.currentTimeMillis() - 1_767_225_600_000L) / 60_000L).toInt().coerceAtLeast(2)
val buildStamp: String = SimpleDateFormat("yyMMdd.HHmm").format(Date())
val releaseKeystore: String? = System.getenv("SIGNING_KEYSTORE_PATH")
val releaseKeyFile = releaseKeystore?.let(rootProject::file)
val releaseSigningValues = listOf(
    releaseKeystore,
    System.getenv("SIGNING_STORE_PASSWORD"),
    System.getenv("SIGNING_KEY_ALIAS"),
    System.getenv("SIGNING_KEY_PASSWORD"),
)
val hasReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() } && releaseKeyFile?.let { it.isFile && it.canRead() } == true
val releaseArtifactTaskNames = setOf(
    "assembleRelease",
    "bundleRelease",
    "packageRelease",
    "packageReleaseBundle",
    "packageReleaseUniversalApk",
    "signReleaseBundle",
    "validateSigningRelease",
)
val includeEmulatorAbi = providers.gradleProperty("benchmarkEmulator").orNull == "true"

android {
    namespace = "com.mg.player"
    // Plan B1 (3.25): Startpfad-Klassen laut src/main/baselineProfiles/startup-prof.txt an den Anfang der ersten Dex-Datei.
    experimentalProperties["android.experimental.r8.dex-startup-optimization"] = true
    // 37: neue AndroidX-/Compose-Versionen verlangen das. targetSdk 37 (Android 17): Heimnetz-Erlaubnis siehe core/net/LocalNetwork.kt.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mg.player"
        minSdk = 24
        targetSdk = 37
        versionCode = buildMinutes
        versionName = "3.40.$buildStamp"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Labor (Plan A1): mit -PlabStrict=true beendet StrictMode die Debug-App bei jedem Plattenzugriff auf dem Hauptthread.
        buildConfigField("boolean", "LAB_STRICT", (providers.gradleProperty("labStrict").orNull == "true").toString())

        // Android-TV-Geräte nutzen ARM-Prozessoren. Das hält die APK trotz LibVLC kleiner.
        ndk {
            abiFilters += if (includeEmulatorAbi) {
                listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            } else {
                listOf("armeabi-v7a", "arm64-v8a")
            }
        }
    }

    signingConfigs {
        // Fester Schlüssel im Repository: Jede Debug-APK ist mit derselben Signatur versehen,
        // dadurch lassen sich neue Builds ohne Deinstallation (und ohne Datenverlust) installieren.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = requireNotNull(releaseKeyFile)
                storePassword = requireNotNull(System.getenv("SIGNING_STORE_PASSWORD"))
                keyAlias = requireNotNull(System.getenv("SIGNING_KEY_ALIAS"))
                keyPassword = requireNotNull(System.getenv("SIGNING_KEY_PASSWORD"))
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            isProfileable = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Die WireGuard-Bibliothek nutzt java.time und java.util.Optional – auf Fire OS 6 / Android 7 nicht vorhanden.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Für Robolectric-Tests der Oberfläche: Schriften, Farben und Texte aus res/ stehen zur Verfügung.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Kein automatischer Internetabgleich des Senderkatalogs aus Tests heraus.
            it.systemProperty("player.offlineTests", "true")
            it.systemProperty("catalogJson", providers.gradleProperty("catalogJson").orNull.orEmpty())
            it.systemProperty("realNames", providers.gradleProperty("realNames").orNull.orEmpty())
            it.systemProperty("realOut", providers.gradleProperty("realOut").orNull.orEmpty())
            it.systemProperty("benchDump", providers.gradleProperty("benchDump").orNull.orEmpty())
            // Labor: -PlabHeap=160m prüft, ob der Aufbau von „Alle Zugänge“ mit dem Speicher schwacher Geräte auskommt.
            it.maxHeapSize = providers.gradleProperty("labHeap").orNull ?: "1g"
            // Häufigere Neustarts begrenzen den Speicherbedarf großer Robolectric-/Compose-Testsuiten.
            it.forkEvery = 10
            // Robolectric mit Android 16 braucht Zugriff auf JDK-Interna (FileDescriptor).
            it.jvmArgs(
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
            )
            // Screenshots der Oberfläche (Roborazzi) landen in app/build/outputs/roborazzi.
            it.systemProperty("roborazzi.test.record", "true")
            // LibraryApiLevelTest: api-versions.xml aus dem Android SDK (welche API seit welcher Android-Version).
            it.systemProperty("guevenplayer.sdkDir", androidComponents.sdkComponents.sdkDirectory.get().asFile.absolutePath)
            it.systemProperty("roborazzi.record.filePathStrategy", "relativePathFromRoborazziContextOutputDirectory")
        }
    }

    lint {
        // Neue Warnungen lassen den Build scheitern; bekannte stehen in lint-baseline.xml (wird schrittweise abgebaut).
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
        abortOnError = true
        // Versionshinweise regelt Dependabot bzw. der Masterplan (bewusst gehaltene Versionen, minSdk 24).
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Native Bibliotheken (vor allem LibVLC, ~86 MB) komprimiert packen: Die Universal-APK für Handy und
        // Fernseher wird etwa halb so groß. Android entpackt bei der Installation nur die passende Architektur.
        // Plan B7 (3.24) geprüft und verworfen: Unkomprimiert läge die APK mit beiden Architekturen bei ≈ 105 MB statt
        // 51 MB, belegt auf dem Gerät aber nicht weniger (heute 51 MB APK + 43–57 MB entpackt ≈ 94–108 MB).
        jniLibs {
            useLegacyPackaging = true
            // wg-quick (Root-Werkzeug der WireGuard-Bibliothek) nutzt die App nicht – sie arbeitet über GoBackend.
            excludes += "**/libwg-quick.so"
        }
    }
}

// Release-Artefakte dürfen nie mit dem im Projekt liegenden Debug-Keystore signiert werden.
tasks.configureEach {
    if (name in releaseArtifactTaskNames) {
        doFirst {
            if (!hasReleaseSigning) {
                throw GradleException(
                    "Release-Signierung fehlt. Setze SIGNING_KEYSTORE_PATH, SIGNING_STORE_PASSWORD, " +
                        "SIGNING_KEY_ALIAS und SIGNING_KEY_PASSWORD auf einen vorhandenen, extern verwalteten Release-Keystore.",
                )
            }
        }
    }
}

gradle.taskGraph.whenReady {
    val releaseArtifactRequested = allTasks.any { it.project == project && it.name in releaseArtifactTaskNames }
    if (releaseArtifactRequested && !hasReleaseSigning) {
        throw GradleException(
            "Release-Signierung fehlt. Setze SIGNING_KEYSTORE_PATH, SIGNING_STORE_PASSWORD, " +
                "SIGNING_KEY_ALIAS und SIGNING_KEY_PASSWORD auf einen vorhandenen, extern verwalteten Release-Keystore.",
        )
    }
}

base {
    // APK-Dateien heißen GuevenPlayer-release.apk bzw. GuevenPlayer-debug.apk.
    archivesName.set("GuevenPlayer")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.addAll(
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
        )
    }
}

ksp {
    arg("room.generateKotlin", "true")
    // Ein Schema je Datenbank-Version (für Migrationstests und zum Nachvollziehen).
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Virtuelles Labor: Compose-Stabilitätsbericht (überspringbare/neu startbare Composables) nur auf Wunsch erzeugen.
if (providers.gradleProperty("composeReports").orNull == "true") {
    composeCompiler {
        reportsDestination = layout.buildDirectory.dir("compose_reports")
        metricsDestination = layout.buildDirectory.dir("compose_metrics")
    }
}

dependencies {
    constraints {
        implementation("com.google.guava:guava:33.7.2-android") {
            because("GHSA-xxph-c9ww-hj94; Media3 1.11.1 zieht 33.3.1-android ein")
        }
    }
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.androidx.compose.bom))
    // Labor (Plan B3): Composable-Namen in atrace/Perfetto, nur mit -PlabTrace=true – nie in der Alltags-APK.
    if (providers.gradleProperty("labTrace").orNull == "true") implementation("androidx.compose.runtime:runtime-tracing")
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.work.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.doh)
    implementation(libs.wireguard.tunnel)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ffmpeg.decoder)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.libvlc.all)

    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    testImplementation(libs.asm)
    testImplementation(libs.konsist)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.androidx.paging.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    // Leere Test-Activity für Compose-Tests – nur in Debug-Builds, nicht in der Release-APK.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Rauchtests auf Emulator/Gerät (CI: .github/workflows/device-tests.yml).
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}
