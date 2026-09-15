plugins {
    id("synaxis.java-binary")
}

dependencies {
    implementation(project(":"))
    implementation(libs.slf4j)

    runtimeOnly(libs.bundles.logback)
}

application {
    mainClass.set("io.github.bearisbug.synaxis.smoke.Main")
    mainModule.set("io.github.bearisbug.synaxis.smoke")
}

tasks.named<JavaExec>("run") {
    // Without this the console reader gets an empty stream and `stop` never arrives
    standardInput = System.`in`
}
