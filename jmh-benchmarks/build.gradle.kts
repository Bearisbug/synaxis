plugins {
    id("synaxis.java-library")
    alias(libs.plugins.jmh.plugin)
}

dependencies {
    implementation(libs.fastutil)
    jmhImplementation(project(":"))
    jmh(libs.jmh.core)
    jmhAnnotationProcessor(libs.jmh.annotationprocessor)
}
