rootProject.name = "synaxis"

includeBuild("build-src")

include("testing")

include("jmh-benchmarks")
include("jcstress-tests")

include("protocol")
include("plugin-api")
include("server-app")
include("game-rts")

// Pure test module: no main sources, depends on every module above as testImplementation and
// holds the ArchUnit rules no single module can express about itself (docs/DESIGN-M2.md §24).
include("architecture-tests")

enableFeaturePreview("ENHANCED_GRAPH_ORDERING")
