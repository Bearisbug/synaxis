plugins {
    id("synaxis.java-library")
    application
}

dependencies {
    implementation(project(":plugin-api"))
    implementation(libs.slf4j)

    // The player data store (docs/DESIGN-PROD.md API-PROD-004, API-PROD-006). Host only: the engine,
    // the plugin API and the plugins never see a driver, a pool or a Redis client (ADR-PROD-012).
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    implementation(libs.jedis)

    // The metrics endpoint (docs/DESIGN-PROD.md API-PROD-015), host only like the store
    implementation(libs.micrometer.registry.prometheus)

    // The management interface (docs/DESIGN-PROD.md API-PROD-022, ADR-PROD-014), host only as well
    implementation(libs.java.websocket)

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
    // Production defaults (docs/DESIGN-PROD.md REQ-PROD-028 and REQ-PROD-015; rationale in
    // docs/ENGINE.md). They reach both `:server-app:run` and the start scripts of installDist and
    // distZip. The start scripts put JAVA_OPTS and SERVER_APP_OPTS after these, so an operator
    // overrides any of them there without a rebuild.
    applicationDefaultJvmArgs = listOf(
        "-XX:+UseG1GC",
        "-Xms4G",
        "-Xmx4G",
        "-XX:+AlwaysPreTouch",
        "-XX:MaxGCPauseMillis=50",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:+ExitOnOutOfMemoryError",
    )
}

// The launcher of an MMO shard (docs/DESIGN-PROD.md REQ-PROD-020): bin/server-shard next to
// bin/server-app in the same distribution, with the same JVM defaults and the same SERVER_APP_OPTS.
val shardStartScripts = tasks.register<CreateStartScripts>("shardStartScripts") {
    applicationName = "server-shard"
    optsEnvironmentVar = "SERVER_APP_OPTS"
    mainClass = "io.github.bearisbug.synaxis.app.ShardMain"
    classpath = files(tasks.jar, configurations.runtimeClasspath)
    defaultJvmOpts = application.applicationDefaultJvmArgs
    outputDir = layout.buildDirectory.dir("shard-scripts").get().asFile
}
distributions.main {
    contents {
        from(shardStartScripts) { into("bin") }
    }
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

// The store tests find synaxis-pg and synaxis-valkey through these (docs/TEST.md): a run without
// them skips what a run with them executes, so they are inputs of the test results. Only whether
// the password is set goes in, an input's value is kept in Gradle's task history.
tasks.named<Test>("test") {
    for (name in listOf("SYNAXIS_TEST_PG_URL", "SYNAXIS_TEST_PG_USER", "SYNAXIS_TEST_REDIS_URL")) {
        inputs.property(name, providers.environmentVariable(name).orElse(""))
    }
    inputs.property("SYNAXIS_TEST_PG_PASSWORD set",
        providers.environmentVariable("SYNAXIS_TEST_PG_PASSWORD").map { true }.orElse(false))
}
