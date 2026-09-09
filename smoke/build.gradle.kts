plugins {
    id("minestom.java-binary")
}

dependencies {
    implementation(project(":"))
    implementation(libs.slf4j)

    runtimeOnly(libs.bundles.logback)
}

application {
    mainClass.set("net.minestom.smoke.Main")
    mainModule.set("net.minestom.smoke")
}

tasks.named<JavaExec>("run") {
    // Without this the console reader gets an empty stream and `stop` never arrives
    standardInput = System.`in`
}
