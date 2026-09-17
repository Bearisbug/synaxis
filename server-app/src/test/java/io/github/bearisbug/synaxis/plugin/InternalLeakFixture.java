package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;

/**
 * Stands in for a stable surface type that hands a plugin an engine internal. It lives in the
 * package the rule watches so that the rule really matches it, and is only ever imported as a
 * synthetic class, never scanned as part of the surface.
 *
 * @see io.github.bearisbug.synaxis.app.StableSurfaceArchitectureTest
 */
public interface InternalLeakFixture {
    CachedPacket leakedByReturnType();

    void leakedByParameter(CachedPacket packet);
}
