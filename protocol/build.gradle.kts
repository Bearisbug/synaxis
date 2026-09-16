plugins {
    id("synaxis.java-library")
    id("synaxis.publishing")
}

// No dependencies on purpose (ADR-M2-008): this jar is shared with the Fabric client, so it must
// stay pure JDK. The message records and their hand written codec land with REQ-M2-007.
