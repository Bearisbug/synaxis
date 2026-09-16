plugins {
    id("synaxis.java-library")
}

dependencies {
    implementation(project(":plugin-api"))
    implementation(project(":protocol"))

    // The one compile time plugin of M2, found through ServiceLoader (ADR-M2-006). The host never
    // names a game class, so the dependency is runtime only.
    runtimeOnly(project(":game-rts"))
}

// No module-info and no application plugin here: the host runs on the classpath (ADR-M2-005) and
// its Main, start sequence and ready log arrive with REQ-M2-021 (API-M2-010).
