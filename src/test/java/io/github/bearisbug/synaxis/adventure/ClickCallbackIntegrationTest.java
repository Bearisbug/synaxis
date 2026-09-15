package io.github.bearisbug.synaxis.adventure;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientCustomClickActionPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@EnvTest
public class ClickCallbackIntegrationTest {

    @Test
    public void unlimitedUsesWithFiniteLifetime(Env env) {
        final ClickCallbackManager manager = env.process().clickCallbackManager();
        final Player player = env.createPlayer(env.createEmptyInstance(), new Pos(0, 40, 0));

        final AtomicInteger calls = new AtomicInteger();
        final ClickEvent<ClickEvent.Payload.Custom> event = manager.createClickEvent(
                _ -> calls.incrementAndGet(),
                ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).build());
        final ClientCustomClickActionPacket packet = clickPacket(event);

        manager.consumeCustomClick(player, packet);
        env.tick();
        manager.consumeCustomClick(player, packet);

        assertEquals(2, calls.get());
    }

    @Test
    public void limitedUsesAreExhausted(Env env) {
        final ClickCallbackManager manager = env.process().clickCallbackManager();
        final Player player = env.createPlayer(env.createEmptyInstance(), new Pos(0, 40, 0));

        final AtomicInteger calls = new AtomicInteger();
        final ClickEvent<ClickEvent.Payload.Custom> event = manager.createClickEvent(
                _ -> calls.incrementAndGet(),
                ClickCallback.Options.builder().uses(1).build());
        final ClientCustomClickActionPacket packet = clickPacket(event);

        manager.consumeCustomClick(player, packet);
        manager.consumeCustomClick(player, packet);

        assertEquals(1, calls.get());
    }

    private static ClientCustomClickActionPacket clickPacket(ClickEvent<ClickEvent.Payload.Custom> event) {
        final ClickEvent.Payload.Custom payload = assertInstanceOf(ClickEvent.Payload.Custom.class, event.payload());
        final BinaryTag nbt = EngineAdventure.unwrapNbt(payload.nbt());
        return new ClientCustomClickActionPacket(payload.key(), nbt);
    }
}
