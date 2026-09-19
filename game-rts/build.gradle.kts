plugins {
    id("synaxis.java-library")
}

dependencies {
    implementation(project(":plugin-api"))

    // The `rts -> proto_s` edge of the §6 module graph: the plugin decodes and encodes the slice
    // messages itself, it does not go through the stable surface for them.
    implementation(project(":protocol"))

    testImplementation(project(":testing"))
    testImplementation(libs.archunit.junit5)
}

// No module-info here: plugins run on the classpath (ADR-M2-005). The unit and pathfinding code
// arrives with REQ-M2-016 and REQ-M2-017.
