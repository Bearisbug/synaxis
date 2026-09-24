rootProject.name = "synaxis"

includeBuild("build-src")

include("testing")

include("jmh-benchmarks")
include("jcstress-tests")

include("protocol")
include("plugin-api")
include("server-app")
include("game-rts")

// The offline map baker (docs/DESIGN-M3.md §6, API-M3-003): an engine side tool, the engine never depends on it
include("baker")

// The load test harness (docs/DESIGN-PROD.md ADR-PROD-013): a tool, never published
include("loadtest")

// Pure test module: no main sources, depends on every module above as testImplementation and
// holds the ArchUnit rules no single module can express about itself (docs/DESIGN-M2.md §24).
include("architecture-tests")

enableFeaturePreview("ENHANCED_GRAPH_ORDERING")
