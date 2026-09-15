plugins {
    id("synaxis.java-library")
    id("synaxis.publishing")
}

dependencies {
    api(project(":"))

    implementation(libs.junit.api)
    implementation(libs.junit.params)
    implementation(libs.junit.suite.api)
    runtimeOnly(libs.junit.engine)
    runtimeOnly(libs.junit.suite.engine)
}
