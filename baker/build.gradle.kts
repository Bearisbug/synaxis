plugins {
    id("synaxis.java-library")
    application
}

// The offline map baker (docs/DESIGN-M3.md §6, API-M3-003). An engine side module: its package sits
// under the engine's root, so it may use the engine internals listed in §6, and the engine never
// depends on it (the Gradle graph makes that a cycle, :architecture-tests checks the bytecode).
// No module-info: a command line tool on the class path, like :loadtest.
dependencies {
    implementation(project(":"))
    implementation(project(":synx"))
    // The far view height field pack (docs/DESIGN-M3.md API-M3-014): FarViewPack writes it, the client reads it
    implementation(project(":protocol"))
    // GeneratorImpl.GenSection#specials is a fastutil map
    implementation(libs.fastutil)

    // The M2 generator presets (generator:rts-512:<seed>), found through ServiceLoader: the baker never
    // compiles against a game module (§6)
    runtimeOnly(project(":game-rts"))
}

application {
    mainClass.set("io.github.bearisbug.synaxis.baker.BakerMain")
}

// GeneratorBakeTest bakes a map of 3 × 3 regions twice; one region with its light ring is some 450 MB of
// palettes and light while it is being lit
tasks.test {
    maxHeapSize = "2g"
}
