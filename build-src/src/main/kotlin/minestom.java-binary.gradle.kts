plugins {
    java
    application
    id("minestom.style")
}

val javaVersion = System.getenv("JAVA_VERSION") ?: "25"

group = "dev.gameengine"

repositories {
    val adventureVersion = libs.adventure.api.get().version ?: ""
    if (adventureVersion.endsWith("-SNAPSHOT"))
        maven(url = "https://central.sonatype.com/repository/maven-snapshots/")

    mavenCentral()
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
    modularity.inferModulePath = true
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
