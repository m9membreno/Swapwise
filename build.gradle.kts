plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.spotless)
}

// Formatting is checked in CI (`./gradlew spotlessCheck`) and fixed locally with `./gradlew spotlessApply`.
spotless {
    kotlin {
        target("**/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}
