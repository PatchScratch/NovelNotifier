import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// NovelNotifier desktop companion — pre-release (Linux-first; same build
// cross-packages for Windows). A STANDALONE Gradle build on purpose: the
// shipping Android app's build never configures this project and vice
// versa, so neither can destabilize the other.
plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.9.3"
    // No explicit `application` plugin: the Compose plugin applies it and
    // owns the run/package tasks.
}

kotlin {
    // Toolchain 21 resolves to the foojay-provisioned Temurin JDK (full —
    // Ubuntu's system java-21 is headless: no jlink/jpackage, and the
    // packaging tasks refuse without them). Run packaging as:
    //   ./gradlew packageAppImage -Dorg.gradle.java.home=~/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    // currentOs carries the window/runtime layer; the UI toolkit pieces are
    // explicit (CMP 1.9 desktop bundle no longer pulls them transitively).
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    // Same JSON library the Android app speaks — one contract, zero drift.
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
}

compose.desktop {
    application {
        mainClass = "com.patchscratch.novelnotifier.companion.MainKt"
        nativeDistributions {
            // Linux is the priority target (Wayne's desktop). Msi/Exe are
            // built from a Windows machine later — jpackage cannot produce
            // them on Linux, so they stay out of targetFormats.
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "NovelNotifierCompanion"
            packageVersion = "0.1.2"
            // The companion is a remote control; it ships no browser engine
            // and stores only the pairing config (host + token) locally.
            vendor = "PatchScratch"
            licenseFile = rootProject.file("../LICENSE")
            // java.net.http.HttpClient (the API client) lives in java.net.http —
            // jlink trimming dropped it silently (NoClassDefFoundError at
            // connect time in the 0.1.1 AppImage); pin it explicitly (the MODULE is java.net.http; jdk.httpclient does not exist).
            modules("java.net.http")
            linux {
                // Rendered from art/shiba-app-icon.svg (ImageMagick) — keep
                // the PNG in-tree so packaging never needs the SVG toolchain.
                iconFile = rootProject.file("icon-512.png")
            }
        }
    }
}
