plugins {
    id("synaxis.java-library")
}

// The offline map baker (docs/DESIGN-M3.md §6, API-M3-003). An engine side module: its package sits
// under the engine's root, so it may use the engine internals listed in §6, and the engine never
// depends on it (the Gradle graph makes that a cycle, :architecture-tests checks the bytecode).
// No module-info: a command line tool on the class path, like :loadtest.
dependencies {
    implementation(project(":"))
}
