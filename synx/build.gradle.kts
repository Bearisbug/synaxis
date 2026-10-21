import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    id("synaxis.java-library")
}

// Engine side module (docs/DESIGN-M3.md §6): it may use the engine members listed in the §6
// internal surface table, and :architecture-tests holds it to that list. No module-info, like the
// other modules above the engine: it runs on the classpath of the shard host.
dependencies {
    api(project(":"))
    // SynxChunk works on the fastutil maps DynamicChunk keeps its block entities in, and logs through slf4j as the engine does
    implementation(libs.fastutil)
    implementation(libs.slf4j)

    testImplementation(project(":testing"))
    // The integration tests of SynxChunkLoader run on a map the real baker bakes, and read the log
    // lines the loader writes for broken records and skipped entities. Without the baker's runtime
    // :game-rts: its data marker services would take the keys SynxHandlersTest registers its own under
    testImplementation(project(":baker")) {
        exclude(group = "io.github.bearisbug.synaxis", module = "game-rts")
    }
    testImplementation(libs.bundles.logback)
}

// CI runs `./gradlew check -Dsynaxis.synx.require-frozen-zlib=true`: there SynxVectorTest fails instead
// of skipping the byte for byte comparison when the JDK's zlib is not the one the vectors were frozen with.
// The measurement mode of SynxRelightBacklogTest (docs/DESIGN-M3.md §15 光照重算吞吐) only runs with
// `-Dsynaxis.relight.measure=true`: a key of the tests alone, which the config key gate does not see.
// It runs without the coverage agent, whose probes on every block the relight reads would be most of
// what it measures.
tasks.test {
    val requireFrozenZlib = "synaxis.synx.require-frozen-zlib"
    providers.systemProperty(requireFrozenZlib).orNull?.let { systemProperty(requireFrozenZlib, it) }
    val relightMeasure = "synaxis.relight.measure"
    providers.systemProperty(relightMeasure).orNull?.let {
        systemProperty(relightMeasure, it)
        if (it.toBoolean()) extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
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
