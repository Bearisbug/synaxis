plugins {
    id("synaxis.java-library")
    application
}

dependencies {
    implementation(project(":plugin-api"))
    // The map mounting mode of the shard host (docs/DESIGN-M3.md API-M3-006): the region files, the
    // chunk loader and the overlay store
    implementation(project(":synx"))
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
    // The map releases of the shard tests are baked, as a shard mounts them
    testImplementation(project(":baker"))
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

// The component bytes PlayComponentVectorTest holds ComponentBytes to (docs/DESIGN-PLAY.md §26 S1), kept in
// :protocol's play-vectors/component/ where the client mod's decoder test reads them too. The directory is an
// input by its relative paths, so a changed file runs the test again; the absolute path of play-vectors/
// reaches the test JVM only as the argument -Dsynaxis.play-vectors.dir, outside the build cache key, the way
// architecture-tests hands over the language files.
class PlayComponentVectors(
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) val components: File,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dsynaxis.play-vectors.dir=${components.parentFile.absolutePath}")
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
    jvmArgumentProviders.add(PlayComponentVectors(
        layout.settingsDirectory.dir("protocol/src/test/resources/play-vectors/component").asFile))
}

// The light fixture of M3-D (docs/DESIGN-M3.md §26 step 4) runs on the test runtime classpath, which
// this writes as a java argument file: `java @server-app/build/light-fixture.args … LightShardFixture`
// starts it without a Gradle process kept alive beside it.
tasks.register("lightFixtureArgs") {
    val classpath = sourceSets.test.get().runtimeClasspath
    val args = layout.buildDirectory.file("light-fixture.args")
    inputs.files(classpath)
    outputs.file(args)
    doLast {
        args.get().asFile.writeText("-cp " + classpath.asPath + "\n")
    }
}

// The sample game of docs/DESIGN-PLAY.md §6.7 (ADR-PLAY-011): :game-rush rides on a configuration of its
// own, so that installDist, distZip and run keep the classpath they have, where :game-rts is the one
// plugin. It is resolved the way the runtime classpath is, which brings :play-kit and the stable surface
// along with it.
val rush: Configuration by configurations.creating {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}
dependencies {
    rush(project(":game-rush"))
}

// `./gradlew :server-app:runRush` is `run` with that configuration added: the same main class, JVM
// defaults, console and working directory, every synaxis.* property handed over, and only rush enabled
// unless synaxis.app.plugins says otherwise (both plugins register an admission provider, and a
// process has one slot).
tasks.register<JavaExec>("runRush") {
    group = "application"
    description = "Runs the host with the sample game rush (docs/DESIGN-PLAY.md §6.7.6)."
    mainClass = application.mainClass
    classpath = sourceSets.main.get().runtimeClasspath + rush
    jvmArgs(application.applicationDefaultJvmArgs)
    standardInput = System.`in`
    systemProperty("synaxis.app.plugins", "rush")
    systemProperties(synaxisProperties.get())
    workingDir = layout.settingsDirectory.asFile
}
