plugins {
    // AGP wird bereits über :app auf den Classpath gelegt; Version hier nicht erneut angeben.
    id("com.android.test")
}

android {
    namespace = "com.mg.player.benchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        // Name muss zum "benchmark"-Build-Typ in app/build.gradle.kts passen (targetProjectPath matcht per Name).
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro)
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    // Fixture-Server (BenchmarkFixture.kt): macht die Benchmarks lauffähig, ohne eine Test-Playlist von Hand
    // einzurichten - lief dieser Server auf 127.0.0.1 im Benchmark-Prozess, erreicht ihn die separate App per
    // echtem HTTP (Loopback ist geräteweit, nicht prozessgebunden), genau wie TrailerLocalServer das nutzt.
    implementation(libs.okhttp.mockwebserver)
}
