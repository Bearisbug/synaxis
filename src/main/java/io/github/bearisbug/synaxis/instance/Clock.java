package io.github.bearisbug.synaxis.instance;

import net.kyori.adventure.nbt.BinaryTag;
import io.github.bearisbug.synaxis.registry.RegistryKey;

public sealed interface Clock permits Instance.ClockInstance {

    RegistryKey<BinaryTag> clock();

    /// Gets the rate at which the clock advances per tick, in partial ticks.
    ///
    /// The default is 1 (advance one tick per tick).
    float rate();

    /// Sets the rate at which the clock advances per tick, in partial ticks.
    ///
    /// The default is 1 (advance one tick per tick).
    void rate(float rate);

    /// Returns the current time (in ticks).
    long time();

    /// Sets the current time (in ticks).
    void time(long time);

    boolean paused();

    void pause();

    void resume();

}
