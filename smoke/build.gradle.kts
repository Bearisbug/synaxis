plugins {
    id("gameengine.java-binary")
}

dependencies {
    implementation(project(":"))
    implementation(libs.slf4j)

    runtimeOnly(libs.bundles.logback)
}

application {
    mainClass.set("dev.gameengine.smoke.Main")
    mainModule.set("dev.gameengine.smoke")
}

tasks.named<JavaExec>("run") {
    // Without this the console reader gets an empty stream and `stop` never arrives
    standardInput = System.`in`
}
