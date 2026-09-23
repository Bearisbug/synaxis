plugins {
    id("synaxis.java-library")
    application
}

dependencies {
    implementation(project(":plugin-api"))
    implementation(libs.slf4j)

    // The one compile time plugin of M2, found through ServiceLoader (ADR-M2-006). The host never
    // names a game class, so the dependency is runtime only.
    runtimeOnly(project(":game-rts"))
    runtimeOnly(libs.bundles.logback)

    testImplementation(project(":testing"))
    testImplementation(libs.bundles.logback)
    testImplementation(libs.archunit.junit5)
}

// No module-info here: the host runs on the classpath (ADR-M2-005).
application {
    mainClass.set("io.github.bearisbug.synaxis.app.Main")
    // An exhausted heap leaves a heap dump and ends the JVM on the spot, without the stop sequence
    // (docs/DESIGN-PROD.md API-PROD-013). The run task and the start scripts both take these.
    applicationDefaultJvmArgs = listOf("-XX:+HeapDumpOnOutOfMemoryError", "-XX:+ExitOnOutOfMemoryError")
}

// The git head a crash report names (API-PROD-013). A build outside a git checkout writes "unknown".
val gitHead = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

val buildInfo = tasks.register("buildInfo") {
    val head = gitHead
    val output = layout.buildDirectory.dir("generated/build-info")
    inputs.property("gitHead", head)
    outputs.dir(output)
    doLast {
        output.get().file("synaxis-build.properties").asFile.writeText("git.head=${head.get()}\n")
    }
}
sourceSets.main { resources.srcDir(buildInfo) }

// `./gradlew -Dsynaxis.app.port=...` sets the property on the Gradle JVM, while the app runs in a
// forked one, so the whole synaxis.* namespace docs/TEST.md §3 relies on is handed over explicitly.
val synaxisProperties = providers.systemPropertiesPrefixedBy("synaxis.")

tasks.named<JavaExec>("run") {
    // Without this the console reader gets an empty stream and `stop` never arrives
    standardInput = System.`in`
    systemProperties(synaxisProperties.get())

    // The host writes plugins/<id>/ and logs/events-*.jsonl next to the working directory; keeping
    // it at the repository root is what makes the paths in docs/TEST.md work from where the
    // command is run.
    workingDir = layout.settingsDirectory.asFile
}
