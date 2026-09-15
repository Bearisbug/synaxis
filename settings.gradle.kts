rootProject.name = "synaxis"

includeBuild("build-src")

include("testing")

include("jmh-benchmarks")
include("jcstress-tests")

include("smoke")

enableFeaturePreview("ENHANCED_GRAPH_ORDERING")
