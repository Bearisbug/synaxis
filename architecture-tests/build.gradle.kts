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

// :server-app puts its logback.xml on this classpath, and that one writes logs/latest.log in the
// working directory, which is this module's. The console mode builds no file appender.
tasks.named<Test>("test") {
    systemProperty("synaxis.app.log", "stdout")
}
