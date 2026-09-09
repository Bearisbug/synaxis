plugins {
    id("gameengine.java-library")
    id("gameengine.publishing")
    alias(libs.plugins.blossom)

    alias(libs.plugins.nmcp.aggregation)
    alias(libs.plugins.graalvm.native)
}

sourceSets {
    main {
        blossom {
            javaSources {
                property("COMMIT", System.getenv("GITHUB_SHA") ?: "LOCAL")
                property("BRANCH", System.getenv("GITHUB_REF") ?: "LOCAL")
                property("GROUP", project.group.toString())
                property("ARTIFACT", project.name)
                property("VERSION", project.version.toString())
            }
        }
    }
}

tasks.register<Task>("determineMinecraftVersion") {
    description = "Prints the pinned Minecraft version"

    val minecraftVersion = libs.versions.minecraft.get()
    doLast {
        println(minecraftVersion)
    }
}

dependencies {
    // Core dependencies
    api(libs.bundles.adventure)
    implementation(libs.slf4j)

    // Performance/data structures
    implementation(libs.fastutil)
    implementation(libs.bundles.flare)
    api(libs.gson)
    implementation(libs.jcTools)

    testImplementation(project(":testing"))
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:-requires-transitive-automatic") // Adventure dependencies are automatic until 5.0.0, see https://github.com/KyoriPowered/adventure/issues/1287
}

graalvmNative {
    agent {
        defaultMode = "standard"

        modes {
            standard {
                val file = layout.projectDirectory.file("src/test/resources/agent-filter.json").asFile.absolutePath
                callerFilterFiles.from(file)
                accessFilterFiles.from(file)
            }
        }

        metadataCopy {
            inputTaskNames.add("test")
            outputDirectories.add("src/main/resources/META-INF/native-image/dev.gameengine/game-engine")
        }
    }
    binaries {
        named("test") {
            buildArgs.add("-Ob")
        }
    }
}

// Publishing configuration below

nmcpAggregation {
    centralPortal {
        username = System.getenv("SONATYPE_USERNAME")
        password = System.getenv("SONATYPE_PASSWORD")
        publishingType = "AUTOMATIC"
    }
}

dependencies {
    nmcpAggregation(project(":"))
    nmcpAggregation(project(":testing"))
}
