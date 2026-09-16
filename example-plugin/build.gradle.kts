plugins {
    java
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

dependencies {
    // The stable surface and the engine below it, taken from the server distribution. Until
    // :plugin-api is published, `./gradlew :server-app:installDist` in the server repository is
    // what produces this directory; the same jars are on the classpath at runtime, so nothing here
    // is bundled into the plugin jar.
    compileOnly(fileTree("../server-app/build/install/server-app/lib") { include("*.jar") })
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
