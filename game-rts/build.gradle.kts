plugins {
    id("synaxis.java-library")
}

dependencies {
    implementation(project(":plugin-api"))
    implementation(project(":protocol"))
}

// No module-info here: plugins run on the classpath (ADR-M2-005). The unit, match and pathfinding
// code arrives with REQ-M2-016 and REQ-M2-017.
