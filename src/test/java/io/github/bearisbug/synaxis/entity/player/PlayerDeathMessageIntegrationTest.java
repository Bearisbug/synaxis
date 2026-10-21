package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.entity.damage.Damage;
import io.github.bearisbug.synaxis.entity.damage.DamageType;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.play.DeathCombatEventPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The death message of a player ({@code docs/DESIGN-PROD.md} {@code API-PROD-008}): it reaches the players of the
 * instance the player died in, the dead one included, and nobody elsewhere on the server.
 */
@EnvTest
public class PlayerDeathMessageIntegrationTest {

    private static final Pos SPAWN = new Pos(0.5, 40, 0.5);

    @Test
    public void theDeathMessageReachesThePlayersOfTheInstanceOnly(Env env) {
        final Instance arena = env.createFlatInstance();
        final Instance elsewhere = env.createFlatInstance();
        final TestConnection victimConnection = env.createConnection(new GameProfile(UUID.randomUUID(), "Victim"));
        final Player victim = victimConnection.connect(arena, SPAWN);
        final TestConnection witnessConnection = env.createConnection();
        witnessConnection.connect(arena, SPAWN);
        final TestConnection farConnection = env.createConnection();
        farConnection.connect(elsewhere, SPAWN);
        final Collector<SystemChatPacket> victimChat = victimConnection.trackIncoming(SystemChatPacket.class);
        final Collector<SystemChatPacket> witnessChat = witnessConnection.trackIncoming(SystemChatPacket.class);
        final Collector<SystemChatPacket> farChat = farConnection.trackIncoming(SystemChatPacket.class);

        final Damage hit = new Damage(DamageType.PLAYER_ATTACK, null, null, null, 1000);
        victim.damage(hit);

        assertTrue(victim.isDead());
        final Component message = hit.buildDeathMessage(victim);
        victimChat.assertSingle(packet -> assertEquals(message, packet.message()));
        witnessChat.assertSingle(packet -> assertEquals(message, packet.message()));
        farChat.assertEmpty();
    }

    /** Killed without any damage, the death screen and the chat show the vanilla generic death message. */
    @Test
    public void aKillWithoutDamageShowsTheGenericDeathMessage(Env env) {
        final TestConnection connection = env.createConnection(new GameProfile(UUID.randomUUID(), "Victim"));
        final Player victim = connection.connect(env.createFlatInstance(), SPAWN);
        final Collector<DeathCombatEventPacket> screens = connection.trackIncoming(DeathCombatEventPacket.class);
        final Collector<SystemChatPacket> chat = connection.trackIncoming(SystemChatPacket.class);

        victim.kill();

        final Component generic = Component.translatable("death.attack.generic", Component.text("Victim"));
        screens.assertSingle(packet -> assertEquals(generic, packet.message()));
        chat.assertSingle(packet -> assertEquals(generic, packet.message()));
    }
}
