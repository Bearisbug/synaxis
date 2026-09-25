plugins {
    id("synaxis.java-library")
    alias(libs.plugins.jmh.plugin)
}

// Whole-world measurements (packet size, retained heap, relight) that JMH's per-invocation model
// does not fit: one plain main that forks a JVM per job. Compiled by check, run only on demand.
val worldbench: SourceSet by sourceSets.creating

dependencies {
    implementation(libs.fastutil)
    jmhImplementation(project(":"))
    // EntityDormancyBenchmark ticks a whole process with players in it, which :testing sets up
    jmhImplementation(project(":testing"))
    jmh(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.annotationprocessor)

    "worldbenchImplementation"(project(":"))
}

tasks.register<JavaExec>("worldBench") {
    group = "benchmark"
    description = "Runs LargeWorldBench; pass driver arguments with -PworldBenchArgs=\"--quick\" (see README.md)."
    classpath = worldbench.runtimeClasspath
    mainClass = "io.github.bearisbug.synaxis.instance.LargeWorldBench"
    workingDir = layout.projectDirectory.asFile
    val extra = providers.gradleProperty("worldBenchArgs").orNull
    if (extra != null) args(extra.split(" ").filter { it.isNotBlank() })
}
