plugins {
    id("synaxis.java-library")
    application
}

// The load test harness (docs/DESIGN-PROD.md REQ-PROD-029, API-PROD-025, ADR-PROD-013). A tool,
// not part of the engine or of anything published: nothing depends on this module.
dependencies {
    // The bots take their packet ids and codecs from the engine's registries
    implementation(project(":"))
    // C2S_HELLO for a gameplay mode server
    implementation(project(":protocol"))
}

application {
    mainClass.set("io.github.bearisbug.synaxis.loadtest.LoadTest")
    applicationDefaultJvmArgs = listOf("-Xmx2G")
}
