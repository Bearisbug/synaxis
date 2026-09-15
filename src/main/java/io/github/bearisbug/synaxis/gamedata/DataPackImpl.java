package io.github.bearisbug.synaxis.gamedata;

import net.kyori.adventure.key.Key;

record DataPackImpl(Key key, boolean isSynced) implements DataPack {

    @Override
    public boolean isSynced() {
        return false;
    }
}
