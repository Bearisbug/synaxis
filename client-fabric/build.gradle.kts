import java.util.zip.ZipFile

plugins {
    id("fabric-loom") version "1.17.21"
}

group = "io.github.bearisbug.synaxis.game"

// The mod version is what players see in the mod list, so it stays SemVer; the engine's own
// version scheme (`dev` unless GAME_ENGINE_VERSION says otherwise) is what :protocol is published
// under and is read separately below.
version = "0.1.0"

val minecraftVersion = "26.2"
val loaderVersion = "0.19.5"
val fabricApiVersion = "0.161.0+26.2"
val protocolVersion = System.getenv("GAME_ENGINE_VERSION") ?: "dev"

repositories {
    // :protocol comes from the engine build's publishToMavenLocal (ADR-M2-015). That is the only
    // coordinate this build takes from ~/.m2, so it is fenced off: an unrelated stale artifact
    // sitting in the local repository must not be able to change what this build resolves.
    mavenLocal {
        content { includeGroup("io.github.bearisbug.synaxis") }
    }
    mavenCentral()
}

dependencies {
    // 26.2 ships unobfuscated: Mojang publishes no mappings file for it and there is no yarn build,
    // so the mod is written against Minecraft's own names. Intermediary 0.0.0 is the empty tiny
    // file Fabric publishes for that era; Loom still wants something in the `mappings` configuration.
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:intermediary:0.0.0:v2")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // One codec for both ends (§24). `include` nests it in the mod jar, so the mod ships the very
    // artifact the server was built against.
    implementation("io.github.bearisbug.synaxis:protocol:$protocolVersion")
    include("io.github.bearisbug.synaxis:protocol:$protocolVersion")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

// The frozen wire vectors of §24, read where the engine build keeps them. Both ends run the same
// files rather than a copy each: a copy is a second thing to keep in step, which is the drift the
// vectors exist to catch.
val frozenVectors = layout.projectDirectory.asFile.parentFile.resolve("protocol/src/test/resources/vectors")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("synaxis.vectors.dir", frozenVectors.absolutePath)
    inputs.dir(frozenVectors).withPropertyName("frozenVectors").withPathSensitivity(PathSensitivity.RELATIVE)
    // Loading Minecraft classes brings its log4j2 configuration along, which writes `logs/` under
    // the working directory; keep that inside the build directory so `clean` takes it away.
    workingDir = layout.buildDirectory.dir("test-workdir").get().asFile
    doFirst { workingDir.mkdirs() }
}

tasks.processResources {
    inputs.property("version", version)
    inputs.property("minecraftVersion", minecraftVersion)
    inputs.property("loaderVersion", loaderVersion)

    filesMatching("fabric.mod.json") {
        expand(
            "version" to version,
            "minecraftVersion" to minecraftVersion,
            "loaderVersion" to loaderVersion,
        )
    }
}

// What the mod jar ships is itself a delivery criterion (REQ-M2-020b, docs/TEST.md TC-GAME-045),
// and every one of these four facts is silent when it breaks: a jar that lost its resources, or
// quietly loosened the version pin, still builds and still loads.
val verifyModJar = tasks.register("verifyModJar") {
    group = "verification"
    description = "Asserts what the mod jar ships: the pinned game version, the resources, the shared codec, no engine"

    val modJar = tasks.named<AbstractArchiveTask>("remapJar").flatMap { it.archiveFile }
    val pinnedVersion = minecraftVersion
    inputs.file(modJar)

    doLast {
        ZipFile(modJar.get().asFile).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toList()

            val descriptor = jar.getEntry("fabric.mod.json") ?: error("模组 jar 里没有 fabric.mod.json")
            val declared = Regex("\"minecraft\"\\s*:\\s*\"([^\"]*)\"")
                .find(jar.getInputStream(descriptor).use { it.reader(Charsets.UTF_8).readText() })
                ?.groupValues?.get(1)
            check(declared == pinnedVersion) {
                "fabric.mod.json 的 minecraft 依赖是 $declared，不是钉死的 $pinnedVersion（REQ-M2-020：不接受 >= 区间）"
            }

            check(entries.any { it.startsWith("assets/synaxis/lang/") && it.endsWith(".json") }) {
                "模组 jar 里没有 assets/ 下的资源——断线原因键会以 synaxis:… 原文显示给玩家（§14）"
            }

            val nested = entries.filter { it.startsWith("META-INF/jars/") && it.endsWith(".jar") }
            check(nested.size == 1 && nested.single().contains("protocol")) {
                "模组 jar 应当恰好 JiJ 一个 :protocol（ADR-M2-015），实际是 $nested"
            }

            val engine = entries.filter {
                it.endsWith(".class") &&
                    it.startsWith("io/github/bearisbug/synaxis/") &&
                    !it.startsWith("io/github/bearisbug/synaxis/game/client/")
            }
            check(engine.isEmpty()) { "模组 jar 里混进了客户端包之外的服务端类：${engine.take(5)}" }

            logger.lifecycle("mod jar: minecraft=$declared, ${entries.size} entries, ships ${nested.single()}")
        }
    }
}

tasks.named("check") { dependsOn(verifyModJar) }
