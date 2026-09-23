plugins {
    id("synaxis.java-library")
    id("synaxis.publishing")
}

dependencies {
    api(project(":"))
    // InMemoryPlayerDataStore stands in for the host's store in a plugin's own tests
    api(project(":plugin-api"))

    implementation(libs.junit.api)
    implementation(libs.junit.params)
    implementation(libs.junit.suite.api)
    runtimeOnly(libs.junit.engine)
    runtimeOnly(libs.junit.suite.engine)
}
