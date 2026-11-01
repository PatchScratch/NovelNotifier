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
            // Linux is the priority target (Wayne's desktop); keep the
            // other formats declared so the same build packages anywhere.
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage, TargetFormat.Msi)
            packageName = "NovelNotifierCompanion"
            packageVersion = "0.1.0"
            // The companion is a remote control; it ships no browser engine
            // and stores only the pairing config (host + token) locally.
            vendor = "PatchScratch"
            licenseFile = rootProject.file("../LICENSE")
        }
    }
}
