plugins {
    id("synaxis.java-library")
}

dependencies {
    implementation(project(":plugin-api"))

    testImplementation(project(":testing"))
}

// No module-info here: plugins run on the classpath (ADR-M2-005). The unit, match and pathfinding
// code arrives with REQ-M2-016 and REQ-M2-017, and takes the direct dependency on :protocol that
// the §6 module graph draws as `rts -> proto_s`.
