plugins {
    id("synaxis.java-library")
    id("synaxis.publishing")
}

dependencies {
    // The stable surface is a controlled re-export of engine types (ADR-M2-005), so the engine and
    // the protocol are part of it rather than hidden behind it.
    api(project(":"))
    api(project(":protocol"))

    // PluginContext#logger() hands a Logger to the plugin, and the engine keeps slf4j to itself.
    api(libs.slf4j)

    // LogBudgetTest asserts on what a budget writes, which needs a real logging backend.
    testImplementation(libs.bundles.logback)
}
