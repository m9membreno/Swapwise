pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "swapwise"

// Android client. The Spring Boot backend is a separate build in backend/.
include(":app")
include(":core:optimizer") // pure Kotlin/JVM
include(":core:domain") // pure Kotlin/JVM
include(":core:data")
include(":feature:scan")
include(":feature:swaps")
