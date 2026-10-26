pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "ChaosBank-Server"

// `chaos`: the chaos engine (sessions, defect catalog, profiles), with no HTTP in it,
// so it can move into ChaosKit once a second practice app needs it.
// `server`: the Ktor app — routes, the bank domain and its in-memory store.
include(":chaos", ":server")
