plugins {
    id("synaxis.java-library")
}

// Engine side module (docs/DESIGN-M3.md §6): it may use the engine members listed in the §6
// internal surface table, and :architecture-tests holds it to that list. No module-info, like the
// other modules above the engine: it runs on the classpath of the shard host.
dependencies {
    api(project(":"))

    testImplementation(project(":testing"))
}

// CI runs `./gradlew check -Dsynaxis.synx.require-frozen-zlib=true`: there SynxVectorTest fails instead
// of skipping the byte for byte comparison when the JDK's zlib is not the one the vectors were frozen with.
tasks.test {
    val requireFrozenZlib = "synaxis.synx.require-frozen-zlib"
    providers.systemProperty(requireFrozenZlib).orNull?.let { systemProperty(requireFrozenZlib, it) }
}

// Regenerates the frozen test vectors of docs/DESIGN-M3.md §24. Only run it together with a
// format_version bump or a deliberate change of the vector inputs, and commit the result;
// src/test/resources/vectors/README.md says what each file is.
tasks.register<JavaExec>("generateVectors") {
    group = "verification"
    description = "Rewrites src/test/resources/vectors from the inputs in SynxVectors"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "io.github.bearisbug.synaxis.synx.SynxVectorGenerator"
    args(layout.projectDirectory.dir("src/test/resources/vectors").asFile.absolutePath)
}
