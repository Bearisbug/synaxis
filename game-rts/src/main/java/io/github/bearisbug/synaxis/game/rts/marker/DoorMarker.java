package io.github.bearisbug.synaxis.game.rts.marker;

import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import net.kyori.adventure.key.Key;

/** The {@code synaxis:door} marker: the client gets no tag of its block entity. */
public final class DoorMarker implements BlockHandler {
    private static final Key KEY = Key.key("synaxis:door");

    @Override
    public Key getKey() {
        return KEY;
    }
}
