package io.github.bearisbug.synaxis.game.rts;

/**
 * Test only stand-in for a class of the {@code :game-rts} module, used by {@code ArchitectureTest}
 * to prove that the "engine must not depend on the game side" rule really fires (REQ-M2-002).
 * <p>
 * The engine never ships a class in this package: {@code :game-rts} owns it, and the engine cannot
 * depend on that module without creating a Gradle cycle. Only the engine test source set may
 * declare it, and only for this purpose.
 */
public interface GameSideFixture {
}
