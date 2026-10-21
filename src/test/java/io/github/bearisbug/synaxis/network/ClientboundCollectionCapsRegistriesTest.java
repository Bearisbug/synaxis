package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.GameMode;
import io.github.bearisbug.synaxis.entity.Metadata;
import io.github.bearisbug.synaxis.entity.attribute.Attribute;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.packet.server.common.ServerLinksPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.SelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginSuccessPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.CustomChatCompletionPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.DeclareCommandsPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.DestroyEntitiesPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityAttributesPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityMetaDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.MoveMinecartPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerInfoRemovePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerInfoUpdatePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetPassengersPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.TabCompletePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.TradeListPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.ResolvableProfile;
import io.github.bearisbug.synaxis.particle.Particle;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.testing.RegistriesTest;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The collections of clientbound packets follow the counts a 26.2 client accepts. Where the client
 * reads any count, a list past the cap this engine used to read with writes and reads back; where
 * the client has a cap, writing one element more throws here instead of reaching a client that
 * drops the connection over it.
 */
@RegistriesTest
public class ClientboundCollectionCapsRegistriesTest {

    private static <T> T roundTrip(NetworkBuffer.Type<T> type, T value, Registries registries) {
        final byte[] bytes = NetworkBuffer.makeArray(type, value, registries);
        return NetworkBuffer.wrap(bytes, 0, bytes.length, registries).read(type);
    }

    private static <T> void assertRoundTrips(NetworkBuffer.Type<T> type, T value, Registries registries) {
        assertEquals(value, roundTrip(type, value, registries));
    }

    private static <T> List<T> listOf(int size, IntFunction<T> element) {
        return IntStream.range(0, size).mapToObj(element).toList();
    }

    @Test
    void serverLinksTakeMoreThan100Entries(Registries registries) {
        final var entry = new ServerLinksPacket.Entry(ServerLinksPacket.KnownLinkType.WEBSITE, "https://example.com");
        assertRoundTrips(ServerLinksPacket.SERIALIZER, new ServerLinksPacket(Collections.nCopies(101, entry)), registries);
    }

    @Test
    void knownPacksRequestsTakeMoreThan64Packs(Registries registries) {
        final List<SelectKnownPacksPacket.Entry> packs = listOf(65, i -> new SelectKnownPacksPacket.Entry("synaxis", "pack" + i, "1"));
        assertRoundTrips(SelectKnownPacksPacket.SERIALIZER, new SelectKnownPacksPacket(packs), registries);
    }

    @Test
    void playerInfoUpdatesTakeMoreThan1024Entries(Registries registries) {
        final List<PlayerInfoUpdatePacket.Entry> entries = listOf(1025, _ -> new PlayerInfoUpdatePacket.Entry(
                UUID.randomUUID(), "", List.of(), true, 0, GameMode.SURVIVAL, null, null, 0, true));
        assertRoundTrips(PlayerInfoUpdatePacket.SERIALIZER,
                new PlayerInfoUpdatePacket(PlayerInfoUpdatePacket.Action.UPDATE_LISTED, entries), registries);
    }

    @Test
    void playerInfoRemovalsTakeMoreThan1024Uuids(Registries registries) {
        assertRoundTrips(PlayerInfoRemovePacket.SERIALIZER,
                new PlayerInfoRemovePacket(listOf(1025, _ -> UUID.randomUUID())), registries);
    }

    @Test
    void entityRemovalsTakeMoreThan32767Ids(Registries registries) {
        assertRoundTrips(DestroyEntitiesPacket.SERIALIZER, new DestroyEntitiesPacket(listOf(32768, i -> i)), registries);
    }

    @Test
    void passengerListsTakeMoreThan16384Ids(Registries registries) {
        assertRoundTrips(SetPassengersPacket.SERIALIZER, new SetPassengersPacket(1, listOf(16385, i -> i + 2)), registries);
    }

    @Test
    void chatCompletionsTakeMoreThan32767Entries(Registries registries) {
        assertRoundTrips(CustomChatCompletionPacket.SERIALIZER,
                new CustomChatCompletionPacket(CustomChatCompletionPacket.Action.ADD, listOf(32768, i -> "entry" + i)), registries);
    }

    @Test
    void minecartMovesTakeMoreThan32767Steps(Registries registries) {
        final var step = new MoveMinecartPacket.LerpStep(Vec.ONE, Vec.ZERO, 0f, 0f, 1f);
        assertRoundTrips(MoveMinecartPacket.SERIALIZER, new MoveMinecartPacket(1, Collections.nCopies(32768, step)), registries);
    }

    @Test
    void tradeListsTakeMoreThan32767Trades(Registries registries) {
        final var trade = new TradeListPacket.Trade(ItemStack.of(Material.EMERALD), ItemStack.of(Material.STONE), null,
                false, 0, 1, 0, 0, 0f, 0);
        assertRoundTrips(TradeListPacket.SERIALIZER,
                new TradeListPacket(1, Collections.nCopies(32768, trade), 1, 0, true, true), registries);
    }

    @Test
    void tabCompletionsTakeMoreThan32767Matches(Registries registries) {
        assertRoundTrips(TabCompletePacket.SERIALIZER,
                new TabCompletePacket(1, 0, 0, listOf(32768, i -> new TabCompletePacket.Match("match" + i, null))), registries);
    }

    @Test
    void commandTreesTakeMoreThan32767Nodes(Registries registries) {
        final List<DeclareCommandsPacket.Node> nodes = listOf(32768, _ -> new DeclareCommandsPacket.Node());
        assertEquals(32768, roundTrip(DeclareCommandsPacket.SERIALIZER, new DeclareCommandsPacket(nodes, 0), registries).nodes().size());
    }

    @Test
    void particleListMetadataTakesMoreThan32767Particles(Registries registries) {
        final var metadata = Metadata.ParticleList(Collections.nCopies(32768, Particle.FLAME));
        assertRoundTrips(EntityMetaDataPacket.SERIALIZER, new EntityMetaDataPacket(1, Map.of(0, metadata)), registries);
    }

    @Test
    void entityAttributesHoldAtMost128Properties(Registries registries) {
        final var property = new EntityAttributesPacket.Property(Attribute.ARMOR, 1, List.of());
        assertRoundTrips(EntityAttributesPacket.SERIALIZER,
                new EntityAttributesPacket(1, Collections.nCopies(128, property)), registries);

        final var tooMany = new EntityAttributesPacket(1, Collections.nCopies(129, property));
        assertThrows(IllegalArgumentException.class, () -> NetworkBuffer.makeArray(EntityAttributesPacket.SERIALIZER, tooMany, registries));
    }

    @Test
    void gameProfilesHoldAtMost16Properties(Registries registries) {
        final List<GameProfile.Property> sixteen = listOf(16, i -> new GameProfile.Property("name" + i, "value"));
        final List<GameProfile.Property> seventeen = listOf(17, i -> new GameProfile.Property("name" + i, "value"));
        final UUID uuid = UUID.randomUUID();

        assertRoundTrips(LoginSuccessPacket.SERIALIZER, new LoginSuccessPacket(new GameProfile(uuid, "Steve", sixteen), uuid), registries);
        assertRoundTrips(ResolvableProfile.NETWORK_TYPE, new ResolvableProfile(new GameProfile(uuid, "Steve", sixteen)), registries);
        assertRoundTrips(ResolvableProfile.NETWORK_TYPE,
                new ResolvableProfile(new ResolvableProfile.Partial("Steve", null, sixteen)), registries);

        final var login = new LoginSuccessPacket(new GameProfile(uuid, "Steve", seventeen), uuid);
        assertThrows(IllegalArgumentException.class, () -> NetworkBuffer.makeArray(LoginSuccessPacket.SERIALIZER, login, registries));
        final var complete = new ResolvableProfile(new GameProfile(uuid, "Steve", seventeen));
        assertThrows(IllegalArgumentException.class, () -> NetworkBuffer.makeArray(ResolvableProfile.NETWORK_TYPE, complete, registries));
        final var partial = new ResolvableProfile(new ResolvableProfile.Partial("Steve", null, seventeen));
        assertThrows(IllegalArgumentException.class, () -> NetworkBuffer.makeArray(ResolvableProfile.NETWORK_TYPE, partial, registries));
    }

    @Test
    void addedPlayersHoldAtMost16Properties(Registries registries) {
        final List<PlayerInfoUpdatePacket.Property> sixteen = listOf(16, i -> new PlayerInfoUpdatePacket.Property("name" + i, "value"));
        final List<PlayerInfoUpdatePacket.Property> seventeen = listOf(17, i -> new PlayerInfoUpdatePacket.Property("name" + i, "value"));
        assertRoundTrips(PlayerInfoUpdatePacket.SERIALIZER, addPlayer(sixteen), registries);
        assertThrows(IllegalArgumentException.class,
                () -> NetworkBuffer.makeArray(PlayerInfoUpdatePacket.SERIALIZER, addPlayer(seventeen), registries));

        // 17 properties as a peer could send them: the actions, one entry, its uuid, name and properties
        final byte[] bytes = NetworkBuffer.makeArray(buffer -> {
            buffer.write(NetworkBuffer.EnumSet(PlayerInfoUpdatePacket.Action.class), EnumSet.of(PlayerInfoUpdatePacket.Action.ADD_PLAYER));
            buffer.write(VAR_INT, 1);
            buffer.write(NetworkBuffer.UUID, UUID.randomUUID());
            buffer.write(STRING, "Steve");
            buffer.write(PlayerInfoUpdatePacket.Property.SERIALIZER.list(), seventeen);
        }, registries);
        assertThrows(IllegalArgumentException.class,
                () -> NetworkBuffer.wrap(bytes, 0, bytes.length, registries).read(PlayerInfoUpdatePacket.SERIALIZER));
    }

    private static PlayerInfoUpdatePacket addPlayer(List<PlayerInfoUpdatePacket.Property> properties) {
        return new PlayerInfoUpdatePacket(PlayerInfoUpdatePacket.Action.ADD_PLAYER, new PlayerInfoUpdatePacket.Entry(
                UUID.randomUUID(), "Steve", properties, false, 0, GameMode.SURVIVAL, null, null, 0, true));
    }
}
