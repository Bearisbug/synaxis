// Container-only: Maven Central through Aliyun, reached directly (run.sh keeps it out of the proxy).
// Gradle fetches the ~20 linux natives of LWJGL in parallel, and that burst through the host proxy
// fails its TLS handshakes. Put first, so Loom's Mojang and Fabric repositories stay as they are.
beforeProject {
    repositories.maven("https://maven.aliyun.com/repository/central") { name = "AliyunCentral" }
}
