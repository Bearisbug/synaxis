plugins {
    id("synaxis.java-library")
    id("synaxis.publishing")
}

// Zero third party dependencies (ADR-M2-008): this jar is shared with the Fabric client, so it must
// stay pure JDK, and the coordinates we publish as the ABI baseline have to say the same thing. The
// convention plugin gives every module `api(libs.jetbrainsAnnotations)`, which lands in this pom
// with compile scope, so this module drops it again.
configurations.named("api") {
    dependencies.removeIf { it.group == "org.jetbrains" && it.name == "annotations" }
}

// The message records and their hand written codec land with REQ-M2-007.
