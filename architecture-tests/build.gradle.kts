plugins {
    id("synaxis.java-library")
}

// The aggregate project of docs/DESIGN-M2.md §24 and §28.2: the one place on this build where the
// engine, the stable surface, the protocol, the host and the game module sit on a single classpath.
// It ships no production code and never will — a module that depends on every other module cannot
// be depended on by any of them without a cycle, so the only thing it may hold is rules that no
// single module can express about itself. A rule a module can enforce about its own classes stays
// with that module (see :game-rts and :server-app), because that test source set is also the
// template a third party plugin copies.
//
// Having no main source set changes two things about the coverage gate (REQ-M2-022): the
// convention plugin's jacocoTestReport still runs here (setOnlyIf { true }) and reports nothing
// rather than failing, and CI's diff-cover --src-roots does not list this module, since there are
// no main lines here for it to cover.
dependencies {
    testImplementation(project(":"))
    testImplementation(project(":plugin-api"))
    testImplementation(project(":protocol"))
    testImplementation(project(":game-rts"))
    testImplementation(project(":server-app"))
    testImplementation(project(":baker"))
    testImplementation(project(":synx"))
    testImplementation(libs.archunit.junit5)
}

// The translation key gate (docs/DESIGN-PROD.md §24, API-PROD-029) reads the language files of the
// client mod, a separate build (ADR-M2-015), straight from its source tree, in the directory named by
// the system property synaxis.lang.dir. The directory is an input of the test task, so editing a
// language file runs the gate again instead of finding the task up to date. The input takes the files
// by their relative path, and the absolute path reaches the test JVM only as an argument, which is
// not part of the build cache key: checkouts of the same commit at different paths share the entry.
class ModLanguageFiles(
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) val directory: File,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dsynaxis.lang.dir=${directory.absolutePath}")
}

// :server-app puts its logback.xml on this classpath, and that one writes logs/latest.log in the
// working directory, which is this module's. The console mode builds no file appender.
tasks.named<Test>("test") {
    systemProperty("synaxis.app.log", "stdout")
    jvmArgumentProviders.add(ModLanguageFiles(
        layout.settingsDirectory.dir("client-fabric/src/main/resources/assets/synaxis/lang").asFile))
}
