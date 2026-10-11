plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.patchscratch.novelnotifier"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.patchscratch.novelnotifier"
        minSdk = 26
        targetSdk = 37
        versionCode = 9
        versionName = "1.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
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