package io.github.bearisbug.synaxis.adventure;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntitySoundEffectPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetTitleSubTitlePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetTitleTextPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetTitleTimePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SoundEffectPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.StopSoundPacket;
import io.github.bearisbug.synaxis.sound.SoundEvent;
import io.github.bearisbug.synaxis.utils.TickUtils;

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Utility methods to convert adventure enums to their packet values.
 *
 * <p>Adventure enums are written by their ordinal: the order of {@link Sound.Source}, {@link BossBar.Color},
 * {@link BossBar.Overlay} and {@link BossBar.Flag} matches the ids of the 26.2 protocol. Check it again when upgrading
 * Adventure; {@code AdventurePacketConvertorTest} pins the ids.</p>
 */
public final class AdventurePacketConvertor {
    // The client adds the three title times up as ints and shows nothing once the sum overflows
    private static final int MAX_TITLE_TICKS = Integer.MAX_VALUE / 3;

    /**
     * Gets the byte value of a collection of boss bar flags.
     *
     * @param flags the flags
     * @return the value
     */
    public static byte getBossBarFlagValue(Collection<BossBar.Flag> flags) {
        byte val = 0x0;
        for (BossBar.Flag flag : flags) {
            val |= (byte) (1 << flag.ordinal());
        }
        return val;
    }

    /**
     * Creates a sound packet from a sound and a location.<br>
     * Random variation by default unless a seed is provided in the {@link Sound}.
     *
     * @param sound the sound
     * @param x     the x coordinate
     * @param y     the y coordinate
     * @param z     the z coordinate
     * @return the sound packet
     */
    public static ServerPacket createSoundPacket(Sound sound, double x, double y, double z) {
        return new SoundEffectPacket(soundEventOf(sound), sound.source(), new Vec(x, y, z), sound.volume(), sound.pitch(), seed(sound));
    }

    /**
     * Creates a sound effect packet from a sound and an emitter.<br>
     * Random variation by default unless a seed is provided in the {@link Sound}.
     *
     * @param sound   the sound
     * @param emitter the emitter, must be an {@link Entity}
     * @return the sound packet
     */
    public static ServerPacket createSoundPacket(Sound sound, Sound.Emitter emitter) {
        if (emitter == Sound.Emitter.self())
            throw new IllegalArgumentException("you must replace instances of Emitter.self() before calling this method");
        if (!(emitter instanceof Entity entity))
            throw new IllegalArgumentException("you can only call this method with entities");

        return new EntitySoundEffectPacket(soundEventOf(sound), sound.source(), entity.getEntityId(), sound.volume(), sound.pitch(), seed(sound));
    }

    /**
     * Gets the sound event of a sound. A name that is not a builtin sound event is sent as a custom sound without any
     * check, with a null range (derived from the volume by the client): a misspelt vanilla name raises no error, the
     * client plays nothing.
     *
     * @param sound the sound
     * @return the sound event
     */
    private static SoundEvent soundEventOf(Sound sound) {
        final SoundEvent soundEvent = SoundEvent.fromKey(sound.name());
        return soundEvent != null ? soundEvent : SoundEvent.of(sound.name(), null);
    }

    private static long seed(Sound sound) {
        return sound.seed().orElse(ThreadLocalRandom.current().nextLong());
    }

    /**
     * Creates a sound stop packet from a sound stop.
     *
     * @param stop the sound stop
     * @return the sound stop packet
     */
    public static ServerPacket createSoundStopPacket(SoundStop stop) {
        byte flags = 0x0;
        Sound.Source source = stop.source();
        String sound = null;

        if (source != null) flags |= 0x1;

        final Key soundKey = stop.sound();
        if (soundKey != null) {
            flags |= 0x2;
            sound = soundKey.asString();
        }

        return new StopSoundPacket(flags, source, sound);
    }

    /**
     * Creates one of the three title packets from a title part and a value.
     *
     * @param part  the part
     * @param value the value
     * @param <T>   the type of the part
     * @return the title packet
     */
    public static <T> ServerPacket createTitlePartPacket(TitlePart<T> part, T value) {
        if (part == TitlePart.TITLE) {
            return new SetTitleTextPacket((Component) value);
        } else if (part == TitlePart.SUBTITLE) {
            return new SetTitleSubTitlePacket((Component) value);
        } else if (part == TitlePart.TIMES) {
            Title.Times times = (Title.Times) value;
            return new SetTitleTimePacket(
                    Math.min(TickUtils.fromDuration(times.fadeIn(), TickUtils.CLIENT_TICK_MS), MAX_TITLE_TICKS),
                    Math.min(TickUtils.fromDuration(times.stay(), TickUtils.CLIENT_TICK_MS), MAX_TITLE_TICKS),
                    Math.min(TickUtils.fromDuration(times.fadeOut(), TickUtils.CLIENT_TICK_MS), MAX_TITLE_TICKS));
        } else {
            throw new IllegalArgumentException("Unknown TitlePart " + part);
        }
    }
}
