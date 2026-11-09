// Pure Kotlin/JVM: receipt rules and the bridge from receipts to the optimizer. No Android imports, so every
// rule here is tested in milliseconds on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    jacoco
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // Compiled by the JDK Gradle runs on (21), emitting Java 17 bytecode so Android can use these modules.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":core:optimizer"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}
