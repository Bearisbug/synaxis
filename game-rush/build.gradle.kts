plugins {
    id("synaxis.java-library")
}

// The sample game "Beacon Rush" of docs/DESIGN-PLAY.md §6.7, found by the host through ServiceLoader.
// :server-app does not depend on it; the runRush task of :server-app puts it on the classpath
// (ADR-PLAY-011).
dependencies {
    implementation(project(":plugin-api"))
    implementation(project(":play-kit"))

    testImplementation(project(":testing"))
    testImplementation(libs.archunit.junit5)
}

// No module-info here: plugins run on the classpath (ADR-M2-005).
