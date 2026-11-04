plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose) apply false
    // Legt das Kotlin-Gradle-Plugin in der passenden Version auf den Classpath (AGP 9 nutzt Built-in Kotlin).
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

// Formatierung: ./gradlew spotlessApply (ändert) bzw. spotlessCheck (prüft, läuft in der CI).
spotless {
    kotlin {
        target("app/src/**/*.kt")
        target("benchmark/src/**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.kts", "app/*.kts", "benchmark/*.kts")
        ktlint(libs.versions.ktlint.get())
    }
}

// Statische Prüfung: ./gradlew detekt. Alte Funde stehen in config/detekt/baseline.xml (wird je Phase kleiner),
// neue Funde lassen den Build scheitern.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
    baseline = file("config/detekt/baseline.xml")
    source.setFrom(files("app/src/main/java", "app/src/test/java"))
    parallel = true
}
