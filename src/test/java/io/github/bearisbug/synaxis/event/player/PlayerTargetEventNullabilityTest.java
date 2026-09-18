package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSetGameRulesPacket;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class PlayerTargetEventNullabilityTest {

    // These events are built by the packet listeners, a missing actor is a bug on our side and
    // has to fail at construction instead of becoming a null somewhere in a plugin
    private static final @Nullable Player NO_PLAYER = null;
    private static final @Nullable Entity NO_ENTITY = null;
    private static final @Nullable List<ClientSetGameRulesPacket.Entry> NO_RULES = null;

    @Test
    public void spectatingRefusesAMissingPlayerOrTarget() {
        assertThrows(NullPointerException.class,
                () -> new PlayerSpectateEntityEvent(NO_PLAYER, NO_ENTITY));
    }

    @Test
    public void teleportingToAnEntityRefusesAMissingPlayerOrTarget() {
        assertThrows(NullPointerException.class,
                () -> new PlayerTeleportToEntityEvent(NO_PLAYER, NO_ENTITY));
    }

    @Test
    public void theGameRuleRequestRefusesAMissingPlayer() {
        assertThrows(NullPointerException.class, () -> new PlayerGameRulesRequestEvent(NO_PLAYER));
    }

    @Test
    public void settingGameRulesRefusesAMissingPlayerOrRuleList() {
        assertThrows(NullPointerException.class,
                () -> new PlayerSetGameRulesEvent(NO_PLAYER, List.of()));
        assertThrows(NullPointerException.class,
                () -> new PlayerSetGameRulesEvent(NO_PLAYER, NO_RULES));
    }

    @Test
    public void aTransferRefusesAMissingPlayerOrHost() {
        assertThrows(NullPointerException.class,
                () -> new OutgoingTransferEvent(NO_PLAYER, "example.com", 25565));
    }
}
