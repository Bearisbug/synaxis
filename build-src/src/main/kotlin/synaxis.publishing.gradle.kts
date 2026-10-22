plugins {
    `maven-publish`
    signing
    alias(libs.plugins.nmcp)
}

val mcVersion = libs.versions.minecraft.get()

publishing.publications.create<MavenPublication>("maven") {
    groupId = project.group.toString()
    artifactId = project.name // eg "synaxis" or "testing"
    version = project.version.toString()

    from(project.components["java"])

    pom {
        name.set(this@create.artifactId)
        description.set("Extensible Minecraft $mcVersion server core, plugin SDK and companion client mod, derived from Minestom")
        url.set("https://github.com/Bearisbug/synaxis")

        licenses {
            license {
                name.set("Apache 2.0")
                url.set("https://github.com/Bearisbug/synaxis/blob/main/LICENSE")
            }
        }

        developers {
            developer {
                id.set("Bearisbug")
            }
        }

        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Bearisbug/synaxis/issues")
        }

        scm {
            connection.set("scm:git:git://github.com/Bearisbug/synaxis.git")
            developerConnection.set("scm:git:git@github.com:Bearisbug/synaxis.git")
            url.set("https://github.com/Bearisbug/synaxis")
            tag.set("HEAD")
        }

        ciManagement {
            system.set("Github Actions")
            url.set("https://github.com/Bearisbug/synaxis/actions")
        }
    }
}

signing {
    isRequired = System.getenv("CI") != null

    val privateKey = System.getenv("GPG_PRIVATE_KEY")
    val keyPassphrase = System.getenv()["GPG_PASSPHRASE"]
    useInMemoryPgpKeys(privateKey, keyPassphrase)

    sign(publishing.publications)
}

// The bootstrap rule of docs/DESIGN-M2.md §24: -PbaselineJarDir points at the jars of our own
// previous tag, and :plugin-api / :protocol have no tag at all before the first release. Without the
// property the task ends as an explicit SKIPPED saying so, so that a build stays green without
// the gate ever passing silently; with the property a missing baseline jar is a failure.
tasks.register<CheckAbiTask>("checkBinaryCompatibility") {
    group = "verification"
    description = "Checks binary compatibility against the baseline released version."

    // Local, not a script property: an onlyIf spec that reads a script field cannot be stored in
    // the configuration cache.
    val noBaselineMessage = "无 baseline，ABI 门未生效 — pass -PbaselineJarDir=<dir> once the first release exists, see docs/DESIGN-M2.md §24"
    val projectName = project.name
    val baselineJarDir = providers.gradleProperty("baselineJarDir")
    val baselineJar = baselineJarDir.map { dir -> layout.settingsDirectory.file("$dir/$projectName.jar").asFile }
    expectedBaselineJar = baselineJar.map { it.absolutePath }
    oldJar = layout.file(baselineJar.flatMap { file ->
        providers.provider { if (file.exists()) file else null }
    })

    onlyIf(noBaselineMessage) { task ->
        val declared = baselineJarDir.isPresent
        if (!declared) task.logger.lifecycle(noBaselineMessage)
        declared
    }

    newJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }

    rootProjectDir.set(layout.settingsDirectory.asFile)
    val javaExtension = project.extensions.findByType<JavaPluginExtension>() // No java plugin applied
    if (javaExtension != null) {
        sourceDirectories.from(javaExtension.sourceSets["main"].java.srcDirs)
        project.configurations.findByName("compileClasspath")?.let { classpath.from(it) }

        val javaToolchainService = project.extensions.findByType<JavaToolchainService>()
        if (javaToolchainService != null) {
            classpath.from(javaToolchainService.launcherFor(javaExtension.toolchain).map { launcher ->
                project.fileTree(launcher.metadata.installationPath.dir("jmods")) {
                    include("**/*.jmod")
                }
            })
        }
    }
}
