rootProject.name = "game-engine"

includeBuild("build-src")

include("testing")

include("jmh-benchmarks")
include("jcstress-tests")

include("smoke")

enableFeaturePreview("ENHANCED_GRAPH_ORDERING")
