package io.github.bearisbug.synaxis.game.client.gametest.light;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

/**
 * The client end of the light test channel {@code synaxis_test:light} ({@code docs/DESIGN-M3.md} §26
 * M3-D step 4): a request is one line of UTF-8, the answer bytes the light fixture of the server
 * test sources sends back, queued here for {@link LightClientGameTest}. It also keeps what
 * {@code LightUpdateCounterMixin} counts on the client thread: the light packets per chunk, with the
 * sections each carried, and the block updates per position.
 */
public final class LightProbe implements ClientModInitializer {

    /** One message of the channel in either direction, its bytes as they are. */
    public record Payload(byte[] data) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("synaxis_test", "light"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = CustomPacketPayload.codec(
                (payload, buf) -> buf.writeBytes(payload.data()), buf -> {
                    final byte[] data = new byte[buf.readableBytes()];
                    buf.readBytes(data);
                    return new Payload(data);
                });

        @Override
        public Type<Payload> type() {
            return TYPE;
        }
    }

    /**
     * A light packet the client handled.
     *
     * @param sections the sections of the world it carried, sky or block, empty or not
     * @param full     whether it carried every section of the world
     */
    public record LightPacket(int sections, boolean full) {
    }

    static final BlockingQueue<byte[]> ANSWERS = new LinkedBlockingQueue<>();
    static final Map<Long, List<LightPacket>> LIGHT_PACKETS = new ConcurrentHashMap<>();
    static final Map<Long, AtomicInteger> BLOCK_UPDATES = new ConcurrentHashMap<>();

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.serverboundPlay().register(Payload.TYPE, Payload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(Payload.TYPE, (payload, _) -> ANSWERS.add(payload.data()));
    }

    /** From the mixin, on the client thread. */
    public static void lightPacket(int chunkX, int chunkZ, int sections, boolean full) {
        LIGHT_PACKETS.computeIfAbsent(ChunkPos.pack(chunkX, chunkZ), _ -> new CopyOnWriteArrayList<>())
                .add(new LightPacket(sections, full));
    }

    /** From the mixin, on the client thread. */
    public static void blockUpdate(BlockPos pos) {
        BLOCK_UPDATES.computeIfAbsent(pos.asLong(), _ -> new AtomicInteger()).incrementAndGet();
    }

    static List<LightPacket> lightPackets(int chunkX, int chunkZ) {
        return List.copyOf(LIGHT_PACKETS.getOrDefault(ChunkPos.pack(chunkX, chunkZ), List.of()));
    }

    static int blockUpdates(BlockPos pos) {
        final AtomicInteger count = BLOCK_UPDATES.get(pos.asLong());
        return count == null ? 0 : count.get();
    }

    static int lightPacketsInAll() {
        return LIGHT_PACKETS.values().stream().mapToInt(List::size).sum();
    }
}
