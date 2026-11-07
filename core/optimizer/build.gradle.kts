// Pure Kotlin/JVM: no Android imports, so tests run in seconds on any machine and the module could be
// shared with an iOS app through Kotlin Multiplatform later.
plugins {
    alias(libs.plugins.kotlin.jvm)
    jacoco
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStandardStreams = true // prints the "% of best possible" report from OptimizerQualityTest
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports { xml.required.set(true) }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule { limit { minimum = "0.85".toBigDecimal() } }
    }
}

tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }
