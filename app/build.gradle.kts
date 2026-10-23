plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// In-app updater: bundle the fastlane changelogs as assets so the
// post-update popup (Updater.kt ChangelogDialog) reads
// assets/changelogs/<versionCode>.txt. Copies before every build — a new
// release changelog ships automatically with the release build.
val copyChangelogs by tasks.registering(Copy::class) {
    from("${rootProject.projectDir}/fastlane/metadata/android/en-US/changelogs")
    into("src/main/assets/changelogs")
    include("*.txt")
}
tasks.named("preBuild") { dependsOn(copyChangelogs) }

android {
    packaging {
        resources {
            // kuromoji-ipadic and kuromoji-core ship duplicate META-INF docs
            excludes += "META-INF/CONTRIBUTORS.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/NOTICE.md"
        }
    }
    namespace = "com.patchscratch.novelnotifier"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.patchscratch.novelnotifier"
        minSdk = 26
        targetSdk = 37
        versionCode = 19
        versionName = "3.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // JVM unit tests: parser catch-blocks log via DebugLog → android.util.Log;
    // default-values keep those paths testable instead of throwing "not mocked".
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // The APK is distributed directly (GitHub Releases), not via a store.
    // Signing with the standard debug keystore keeps the signature stable so
    // devices running earlier builds update in place instead of reinstalling.
    // Conditional so source-only builds (e.g. F-Droid's servers, where the
    // keystore does not exist) still compile — they re-sign with their own key.
    // Release signing: prefer the real release keystore; fall back to the
    // debug keystore only on machines that don't have it (CI, fresh clones).
    // Keystore lives OUTSIDE the repo at ~/.keystores/novelnotifier-release.jks
    // with its password in the sibling .password file — never commit either.
    // v1.2 and earlier shipped debug-signed; v1.2.1+ ships release-signed.
    val releaseKeystore = File(System.getProperty("user.home") + "/.keystores/novelnotifier-release.jks")
    val releaseKeystorePassword = runCatching {
        File(System.getProperty("user.home") + "/.keystores/novelnotifier-release.password")
            .readText().trim()
    }.getOrDefault("")
    val legacyDebugKeystore = File(System.getProperty("user.home") + "/.android/debug.keystore")
    if (releaseKeystore.exists()) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = "novelnotifier"
                keyPassword = releaseKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // Readium Kotlin toolkit requires core library desugaring.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Japanese tokenizer for the long-press dictionary (embeds IPADIC)
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    // SAF tree file management for the Local Library storage locations.
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Readium Kotlin toolkit (Wayne 2026-10-21): epub parsing engine —
    // replaces hand-rolled OPF/spine/content parsing with the tested library.
    implementation("org.readium.kotlin-toolkit:readium-streamer:3.4.0")
    implementation("org.readium.kotlin-toolkit:readium-navigator:3.4.0")
    // Sherpa-ONNX neural TTS (arm64-v8a only, ~12MB). Download with
    // scripts/download-sherpa-onnx.sh — never commit the binary.
    implementation(files("libs/sherpa-onnx.aar"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.media)
    implementation(libs.mlkit.translate)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation(libs.junit)
    // Real implementations of Android's org.json + XML pull parser so the pure
    // parsing helpers can run in plain JVM unit tests (framework stubs throw).
    testImplementation("org.json:json:20240303")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.coil.compose)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}