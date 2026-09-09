rootProject.name = "minestom"

includeBuild("build-src")

include("code-generators")
include("testing")

include("jmh-benchmarks")
include("jcstress-tests")

include("smoke")

enableFeaturePreview("ENHANCED_GRAPH_ORDERING")
