rootProject.name = "synaxis"

includeBuild("build-src")

include("testing")

include("jmh-benchmarks")
include("jcstress-tests")

include("protocol")
include("plugin-api")
include("server-app")
include("game-rts")

enableFeaturePreview("ENHANCED_GRAPH_ORDERING")
