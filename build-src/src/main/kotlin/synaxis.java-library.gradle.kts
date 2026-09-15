plugins {
    `java-library`
    jacoco
    id("synaxis.style")
}

val javaVersion = System.getenv("JAVA_VERSION") ?: "25"

group = "io.github.bearisbug.synaxis"
version = System.getenv("GAME_ENGINE_VERSION") ?: "dev"

configurations.all {
    // We only use Jetbrains Annotations
    exclude("org.checkerframework", "checker-qual")
}

val adventureVersion = libs.adventure.api.get().version ?: ""

repositories {
    if (adventureVersion.endsWith("-SNAPSHOT"))
        maven(url = "https://central.sonatype.com/repository/maven-snapshots/")

    mavenCentral()
}

dependencies {
    // Core dependencies
    api(libs.jetbrainsAnnotations)

    // Testing
    testImplementation(libs.bundles.junit)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
    modularity.inferModulePath = true

    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc> {
    (options as? StandardJavadocDocletOptions)?.apply {
        encoding = "UTF-8"

        // Custom options
        addBooleanOption("html5", true)
        addStringOption("-release", javaVersion)
        // Links to external javadocs
        links("https://docs.oracle.com/en/java/javase/$javaVersion/docs/api/")
        if (!adventureVersion.endsWith("-SNAPSHOT")) {
            links("https://jd.papermc.io/adventure/${libs.versions.adventure.get()}/")
        }
        links("https://javadoc.io/doc/com.google.code.gson/gson/${libs.versions.gson.get()}/")
        links("https://javadoc.io/doc/org.jetbrains/annotations/${libs.versions.jetbrainsAnnotations.get()}/")

        tags("apiNote:a:API Note:", "implSpec:a:Implementation Requirements:", "implNote:a:Implementation Note:")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Engine settings do not belong in the test JVM: a test that needs a different one builds
    // its own ServerConfig, which keeps the test defaults equal to the production ones (REQ-014).
    minHeapSize = "512m"
    maxHeapSize = "1024m"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)

    // The XML report is what the CI coverage gate reads.
    reports {
        xml.required = true
        html.required = true
        csv.required = false
    }
}

tasks.withType<Zip> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
