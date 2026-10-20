package io.github.bearisbug.synaxis.instance;

import net.kyori.adventure.nbt.BinaryTag;
import io.github.bearisbug.synaxis.registry.RegistryKey;

/// A clock of an instance, one for each key of `BuiltinRegistries.WORLD_CLOCK`.
///
/// [#time(long)], [#rate(float)], [#pause()] and [#resume()] send a `SetTimePacket` holding every clock of
/// the instance to all of its players at once, on every call ([#pause()] and [#resume()] only when they change
/// the state): changing clocks in a loop sends one packet per change. While the clock is paused, the tick does
/// not advance [#time()], [#time(long)] still sets it.
public sealed interface Clock permits Instance.ClockInstance {

    /// Returns the key of this clock in `BuiltinRegistries.WORLD_CLOCK`.
    RegistryKey<BinaryTag> clock();

    /// Gets the rate at which the clock advances per tick, in partial ticks.
    ///
    /// The default is 1 (advance one tick per tick).
    float rate();

    /// Sets the rate at which the clock advances per tick, in partial ticks.
    ///
    /// The default is 1 (advance one tick per tick).
    ///
    /// @throws IllegalArgumentException if `rate` is negative or not finite
    void rate(float rate);

    /// Returns the current time (in ticks).
    long time();

    /// Sets the current time (in ticks).
    void time(long time);

    boolean paused();

    void pause();

    void resume();

}
