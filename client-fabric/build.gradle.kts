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
    // `mod*`, not plain `implementation`: with a `mappings` entry Loom only reads the loader's
    // fabric-installer.json from the mod configurations, and that file is what puts ASM and Mixin on
    // the runtime classpath. Without it a launched client dies with "ASM not detected on the classpath".
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
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

// The real client, driven through Fabric's client gametest API (docs/research/client-spikes-2026-09).
// It needs a display, so it runs in the headless container of gametest-env/, never on a desktop:
// Minecraft grabs the mouse once a world is open. `./gradlew runClientGameTest` inside that
// container; SYNAXIS_GAMETEST_SERVER=<host:port> points it at a running server-app.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "synaxis-gametest"
        enableGameTests = false
        enableClientGameTests = true
        eula = false
    }
}

// The full online login of REQ-PROD-030 without a Mojang account (docs/DESIGN-PROD.md API-PROD-026):
// SYNAXIS_GAMETEST_SESSION_HOST=http://<host>:<port> points authlib's three hosts at
// tools/fake-sessionserver, so the client joins a session there that a server-app in online mode,
// its session server overridden to the same place, can verify. authlib ignores the hosts unless all
// three are set. Unset, the client talks to Mojang as usual, which only an offline server accepts.
val authlibHosts = providers.environmentVariable("SYNAXIS_GAMETEST_SESSION_HOST").map { host ->
    listOf("session", "services", "profiles").map { "-Dminecraft.api.$it.host=$host" }
}.orElse(emptyList())

// The gametests load the mod jar players get, from the run directory's mods/, instead of the dev
// classes: only that jar nests :protocol, and whether Fabric Loader accepts the nested jar is what
// spike S0 has to see.
tasks.named<JavaExec>("runClientGameTest") {
    val modJar = tasks.named<AbstractArchiveTask>("remapJar").flatMap { it.archiveFile }
    val devMod = sourceSets.main.get().output
    val protocolJar = "protocol-$protocolVersion.jar"
    inputs.file(modJar)
    // A new collection over the same sources: filtering `classpath` itself would feed it into itself.
    val loomClasspath = files((classpath as ConfigurableFileCollection).from.toList())
    classpath = loomClasspath.filter { it !in devMod.files && it.name != protocolJar }
    jvmArgumentProviders.add(CommandLineArgumentProvider { authlibHosts.get() })
    // Loom keeps the run directory to itself; `workingDir` here would be the project directory.
    val mods = layout.buildDirectory.dir("run/clientGameTest/mods")
    doFirst {
        val jar = modJar.get().asFile
        jar.copyTo(mods.get().asFile.resolve(jar.name), overwrite = true)
    }
}

// The same gametests through Loom's production launcher, the way players start the game: remapJar
// plus Fabric API from mods/, no dev classpath at all.
val gametestJar = tasks.register<Jar>("gametestJar") {
    archiveClassifier = "gametest"
    from(sourceSets["gametest"].output)
}
dependencies {
    // Loom's production run takes remapJar plus whatever is in this configuration
    "productionRuntimeMods"("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
}
tasks.register<net.fabricmc.loom.task.prod.ClientProductionRunTask>("runProductionClientGameTest") {
    mods.from(gametestJar)
    jvmArgs.add("-Dfabric.client.gametest")
    jvmArgs.addAll(authlibHosts)
    runDir = layout.buildDirectory.dir("run/productionClientGameTest")
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
