plugins {
    id("synaxis.java-library")
    id("synaxis.publishing")
}

dependencies {
    // The stable surface is a controlled re-export of engine types (ADR-M2-005), so the engine and
    // the protocol are part of it rather than hidden behind it.
    api(project(":"))
    api(project(":protocol"))
}
