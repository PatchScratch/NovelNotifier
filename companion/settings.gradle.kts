pluginManagement {
    repositories {
        // google(): Compose Desktop 1.9+ artifacts may ask for androidx
        // relocated deps that only exist on Google Maven.
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Compose Desktop 1.9+ pulls androidx-relocated artifacts that are
        // published ONLY to Google Maven (not Maven Central).
        google()
        mavenCentral()
    }
}

rootProject.name = "NovelNotifierCompanion"
