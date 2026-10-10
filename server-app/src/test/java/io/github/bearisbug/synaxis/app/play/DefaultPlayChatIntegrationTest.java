package io.github.bearisbug.synaxis.app.play;

import io.github.bearisbug.synaxis.app.play.DefaultPlay.Feature;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.Event;
import io.github.bearisbug.synaxis.event.EventNode;
import io.github.bearisbug.synaxis.event.player.PlayerChatEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChatMessagePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.EnumSet;
import java.util.Set;

/**
 * {@code CHAT} of the default play layer ({@code docs/DESIGN-PROD.md} {@code API-PROD-031}, {@code API-PROD-008}): the
 * empty recipients the engine starts a chat message with become the players of the sender's instance, in the core
 * world and under a gameplay plugin alike; a listener after the host can change them, and recipients a listener before
 * it chose are kept.
 */
@EnvTest
class DefaultPlayChatIntegrationTest {

    private static final Pos SPAWN = new Pos(0.5, 40, 0.5);

    @Test
    void inTheCoreWorldChatReachesTheSendersInstance(Env env) {
        chatReachesTheSendersInstance(env, EnumSet.allOf(Feature.class));
    }

    @Test
    void underAGameplayPluginChatReachesTheSendersInstance(Env env) {
        chatReachesTheSendersInstance(env, EnumSet.of(Feature.CHAT));
    }

    private static void chatReachesTheSendersInstance(Env env, Set<Feature> features) {
        final Instance instance = env.createFlatInstance();
        final Instance elsewhere = env.createFlatInstance();
        PlayFixture.attach(env, PlayFixture.hostNode(env), features, instance);
        final Party party = new Party(env, instance, elsewhere);

        party.say("hello");

        party.toA.assertSingle();
        party.toB.assertSingle();
        party.toC.assertEmpty();
    }

    /**
     * A listener where a global scope plugin listener at {@code NORMAL} runs empties the recipients the host filled in,
     * and the message goes to nobody.
     */
    @Test
    void aListenerAfterTheHostCanEmptyTheRecipients(Env env) {
        final Instance instance = env.createFlatInstance();
        final EventNode<Event> hostNode = PlayFixture.hostNode(env);
        PlayFixture.attach(env, hostNode, EnumSet.of(Feature.CHAT), null);
        PlayFixture.pluginTier(hostNode, 0).addListener(PlayerChatEvent.class, event -> event.getRecipients().clear());
        final Party party = new Party(env, instance, env.createFlatInstance());

        party.say("hello");

        party.toA.assertEmpty();
        party.toB.assertEmpty();
        party.toC.assertEmpty();
    }

    /**
     * An instance scoped listener runs before the host node and already named a recipient: the host leaves them as
     * they are.
     */
    @Test
    void recipientsChosenBeforeTheHostAreKept(Env env) {
        final Instance instance = env.createFlatInstance();
        PlayFixture.attach(env, PlayFixture.hostNode(env), EnumSet.of(Feature.CHAT), null);
        final Party party = new Party(env, instance, env.createFlatInstance());
        instance.eventNode().addListener(PlayerChatEvent.class, event -> event.getRecipients().add(party.b));

        party.say("psst");

        party.toA.assertEmpty();
        party.toB.assertSingle();
        party.toC.assertEmpty();
    }

    /**
     * A and B in one instance, C in another, and what each of them is sent.
     */
    private static final class Party {
        private final Player a;
        private final Player b;
        private final Collector<SystemChatPacket> toA;
        private final Collector<SystemChatPacket> toB;
        private final Collector<SystemChatPacket> toC;

        private Party(Env env, Instance instance, Instance elsewhere) {
            final TestConnection connectionA = env.createConnection();
            final TestConnection connectionB = env.createConnection();
            final TestConnection connectionC = env.createConnection();
            this.a = connectionA.connect(instance, SPAWN);
            this.b = connectionB.connect(instance, SPAWN);
            connectionC.connect(elsewhere, SPAWN);
            this.toA = connectionA.trackIncoming(SystemChatPacket.class);
            this.toB = connectionB.trackIncoming(SystemChatPacket.class);
            this.toC = connectionC.trackIncoming(SystemChatPacket.class);
        }

        /** A sends a chat message, as a client does. */
        private void say(String message) {
            a.addPacketToQueue(new ClientChatMessagePacket(message, System.currentTimeMillis(), 0L, null, 0,
                    new BitSet(), (byte) 0));
            a.interpretPacketQueue();
        }
    }
}
