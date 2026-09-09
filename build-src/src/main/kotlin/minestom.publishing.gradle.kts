plugins {
    `maven-publish`
    signing
    alias(libs.plugins.nmcp)
}

val mcVersion = libs.versions.minecraft.get()

publishing.publications.create<MavenPublication>("maven") {
    groupId = project.group.toString()
    artifactId = project.name // eg "game-engine" or "testing"
    version = project.version.toString()

    from(project.components["java"])

    pom {
        name.set(this@create.artifactId)
        description.set("$mcVersion Lightweight Minecraft server")
        url.set("https://github.com/Bearisbug/game-engine")

        licenses {
            license {
                name.set("Apache 2.0")
                url.set("https://github.com/Bearisbug/game-engine/blob/main/LICENSE")
            }
        }

        developers {
            developer {
                id.set("Bearisbug")
            }
        }

        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Bearisbug/game-engine/issues")
        }

        scm {
            connection.set("scm:git:git://github.com/Bearisbug/game-engine.git")
            developerConnection.set("scm:git:git@github.com:Bearisbug/game-engine.git")
            url.set("https://github.com/Bearisbug/game-engine")
            tag.set("HEAD")
        }

        ciManagement {
            system.set("Github Actions")
            url.set("https://github.com/Bearisbug/game-engine/actions")
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

// Not part of CI yet: -PbaselineJarDir must point at the jars of our own previous tag, and no
// such tag exists before M1 ships. Wire it into the CI workflow once the first one does.
tasks.register<CheckAbiTask>("checkBinaryCompatibility") {
    group = "verification"
    description = "Checks binary compatibility against the baseline released version."

    val baselineJarDir = providers.gradleProperty("baselineJarDir")
    oldJar = layout.file(baselineJarDir.flatMap { dir ->
        val file = layout.settingsDirectory.file("$dir/${project.name}.jar")
        providers.provider { if (file.asFile.exists()) file.asFile else null }
    })

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
