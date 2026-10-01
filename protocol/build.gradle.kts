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

// CI runs `./gradlew check -Dsynaxis.synx.require-frozen-zlib=true`: there FarViewVectorTest fails instead
// of skipping the byte for byte comparison of the far view packs when the JDK's zlib is not the frozen one.
tasks.test {
    val requireFrozenZlib = "synaxis.synx.require-frozen-zlib"
    providers.systemProperty(requireFrozenZlib).orNull?.let { systemProperty(requireFrozenZlib, it) }
}

// Regenerates the frozen far view vectors of docs/DESIGN-M3.md §24. Only run it together with a
// format_version bump of FarViewPack or FarViewAnnouncement, or a deliberate change of the inputs in
// FarViewVectors, and commit the result; src/test/resources/farview-vectors/README.md says what each
// file is.
tasks.register<JavaExec>("generateFarViewVectors") {
    group = "verification"
    description = "Rewrites src/test/resources/farview-vectors from the inputs in FarViewVectors"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "io.github.bearisbug.synaxis.game.protocol.FarViewVectorGenerator"
    args(layout.projectDirectory.dir("src/test/resources/farview-vectors").asFile.absolutePath)
}
