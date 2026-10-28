rootProject.name = "anvil-android"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// This repository vendors the two Android artifacts consumed by the
// whiteboard. The Compose Multiplatform artifact and catalog applications are
// intentionally omitted; the whiteboard uses androidx.compose.material3.
include(":anvil-tokens")
include(":anvil-material3")
