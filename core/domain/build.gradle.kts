// Pure Kotlin/JVM: receipt rules and the bridge from receipts to the optimizer. No Android imports, so every
// rule here is tested in milliseconds on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    jacoco
}

kotlin {
    jvmToolchain(17)
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    api(project(":core:optimizer"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}
