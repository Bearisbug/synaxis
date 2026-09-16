plugins {
    id("synaxis.java-library")
}

dependencies {
    implementation(project(":plugin-api"))

    // The one compile time plugin of M2, found through ServiceLoader (ADR-M2-006). The host never
    // names a game class, so the dependency is runtime only.
    runtimeOnly(project(":game-rts"))
}

// No module-info here: the host runs on the classpath (ADR-M2-005). The `application` plugin that
// ADR-M2-005 also asks for lands with the Main, start sequence and ready log of REQ-M2-021
// (API-M2-010), which is what gives it a mainClass to point at; docs/TEST.md runs the host with
// `./gradlew :server-app:run` from then on.
