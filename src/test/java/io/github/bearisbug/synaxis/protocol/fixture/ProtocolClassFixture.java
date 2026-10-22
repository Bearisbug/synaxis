package io.github.bearisbug.synaxis.protocol.fixture;

/**
 * Test only stand-in for a class of the {@code :protocol} module, used by {@code ArchitectureTest}
 * to prove that the "engine must not depend on the other modules" rule really fires (REQ-M2-002).
 * <p>
 * The engine cannot depend on that module without creating a Gradle cycle. Only the engine test
 * source set may declare a class in this package, and only for this purpose.
 */
public interface ProtocolClassFixture {
}
