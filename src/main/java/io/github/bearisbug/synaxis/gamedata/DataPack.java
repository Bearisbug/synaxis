package io.github.bearisbug.synaxis.gamedata;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Experimental
public sealed interface DataPack permits DataPackImpl {

    DataPack MINECRAFT_CORE = new DataPackImpl(Key.key("core"), true);

    DataPack ENGINE_UNNAMED = new DataPackImpl(Key.key("synaxis", "unnamed"), false);

    /**
     * Returns whether the client already has this data pack, so that its entries need not be sent when the client
     * lists it among its known packs. Only {@link #MINECRAFT_CORE} is.
     *
     * @return true if the client has this data pack, false otherwise.
     */
    boolean isSynced();

}
