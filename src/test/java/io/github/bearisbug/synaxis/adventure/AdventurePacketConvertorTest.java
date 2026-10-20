package io.github.bearisbug.synaxis.adventure;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.play.BossBarPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetTitleTimePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SoundEffectPacket;
import io.github.bearisbug.synaxis.sound.CustomSoundEvent;
import io.github.bearisbug.synaxis.sound.SoundEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static net.kyori.adventure.key.Key.key;
import static net.kyori.adventure.text.Component.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Adventure enums are written by their ordinal. The values asserted here are the ids of the 26.2 protocol, so an
 * Adventure upgrade that inserts a constant fails here rather than on the client.
 */
class AdventurePacketConvertorTest {

    @Test
    void aSoundSourceIsWrittenAsItsProtocolId() {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer();
        buffer.write(SoundEffectPacket.SERIALIZER, new SoundEffectPacket(SoundEvent.BLOCK_STONE_BREAK, Sound.Source.UI,
                new Vec(0, 0, 0), 1f, 1f, 0L));
        buffer.read(SoundEvent.NETWORK_TYPE);
        assertEquals(10, buffer.read(NetworkBuffer.VAR_INT));
    }

    @Test
    void aBossBarIsWrittenWithTheProtocolIdsOfItsStyleAndFlags() {
        final BossBar bar = BossBar.bossBar(text("bar"), 1f, BossBar.Color.WHITE, BossBar.Overlay.NOTCHED_20,
                Set.of(BossBar.Flag.CREATE_WORLD_FOG));
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer();
        buffer.write(BossBarPacket.SERIALIZER, new BossBarPacket(UUID.randomUUID(), new BossBarPacket.AddAction(bar)));
        buffer.read(NetworkBuffer.UUID);
        assertEquals(0, buffer.read(NetworkBuffer.VAR_INT));
        buffer.read(NetworkBuffer.COMPONENT);
        buffer.read(NetworkBuffer.FLOAT);
        assertEquals(6, buffer.read(NetworkBuffer.VAR_INT));
        assertEquals(4, buffer.read(NetworkBuffer.VAR_INT));
        assertEquals((byte) 0x4, buffer.read(NetworkBuffer.BYTE));
        assertEquals((byte) 0x4, AdventurePacketConvertor.getBossBarFlagValue(Set.of(BossBar.Flag.CREATE_WORLD_FOG)));
    }

    @Test
    void aSoundOutsideTheBuiltinTableIsSentAsACustomSound() {
        final SoundEffectPacket packet = assertInstanceOf(SoundEffectPacket.class, AdventurePacketConvertor.createSoundPacket(
                Sound.sound(key("synaxis:nope"), Sound.Source.MASTER, 1f, 1f), 0, 0, 0));
        final CustomSoundEvent soundEvent = assertInstanceOf(CustomSoundEvent.class, packet.soundEvent());
        assertEquals(key("synaxis:nope"), soundEvent.key());
        assertNull(soundEvent.range());
    }

    /** The client adds the three durations up as ints and shows nothing once the sum overflows. */
    @Test
    void titleTimesAreCappedSoThatTheirSumFitsAnInt() {
        final int cap = Integer.MAX_VALUE / 3;
        assertEquals(new SetTitleTimePacket(20, cap, 20),
                AdventurePacketConvertor.createTitlePartPacket(TitlePart.TIMES,
                        Title.Times.times(Duration.ofSeconds(1), Duration.ofDays(2000), Duration.ofSeconds(1))));
        final Duration forever = ChronoUnit.FOREVER.getDuration();
        assertEquals(new SetTitleTimePacket(cap, cap, cap),
                AdventurePacketConvertor.createTitlePartPacket(TitlePart.TIMES, Title.Times.times(forever, forever, forever)));
        assertEquals(new SetTitleTimePacket(10, 60, 20),
                AdventurePacketConvertor.createTitlePartPacket(TitlePart.TIMES,
                        Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1))));
    }
}
