// The client mod is its own Gradle build (ADR-M2-015): Loom forbids the configuration cache the
// engine build runs on, so this directory is deliberately absent from the root settings.gradle.kts.
pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "client-fabric"
