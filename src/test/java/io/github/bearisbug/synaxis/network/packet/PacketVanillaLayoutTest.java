package io.github.bearisbug.synaxis.network.packet;

import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChangeDifficultyPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientSetBeaconEffectPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientUpdateStructureBlockPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.DeleteChatPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.MoveMinecartPacket;
import io.github.bearisbug.synaxis.potion.PotionEffect;
import io.github.bearisbug.synaxis.world.Difficulty;
import org.junit.jupiter.api.Test;

import java.util.Objects;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reads packets from bytes laid out the way the 26.2 client writes or reads them (the stream codecs of
 * {@code minecraft-merged.jar}, disassembled with {@code javap -c}), then writes them back. Round trips through the
 * serializer alone ({@code PacketWriteReadRegistriesTest}) pass for any layout, and {@code PacketVanillaIdTest} only
 * checks the ids, so a field the client does not send, or sends in another width, goes unnoticed there.
 * <p>
 * Covers the packets whose layout was taken from upstream Minestom #3382 (89399c65ce), each checked against the
 * disassembly: every byte is consumed and the packet writes back the same bytes.
 */
class PacketVanillaLayoutTest {

    private static byte[] wire(Consumer<NetworkBuffer> writing) {
        return NetworkBuffer.makeArray(writing);
    }

    private static <T> T readWhole(NetworkBuffer.Type<T> type, byte[] wire) {
        final NetworkBuffer buffer = NetworkBuffer.wrap(wire, 0, wire.length);
        final T value = buffer.read(type);
        assertEquals(0, buffer.readableBytes(), () -> "bytes left unread after " + value);
        assertArrayEquals(wire, NetworkBuffer.makeArray(type, value), () -> "bytes written back from " + value);
        return value;
    }

    /**
     * {@code ServerboundChangeDifficultyPacket}: the difficulty id only. The lock is its own packet.
     */
    @Test
    void changeDifficultyIsTheDifficultyAlone() {
        final byte[] wire = wire(buffer -> buffer.write(NetworkBuffer.VAR_INT, 3));

        final ClientChangeDifficultyPacket packet = readWhole(ClientChangeDifficultyPacket.SERIALIZER, wire);
        assertEquals(Difficulty.HARD, packet.difficulty());
    }

    /**
     * {@code ServerboundSetBeaconPacket}: two optional {@code Holder<MobEffect>}, ids of the mob effect registry.
     */
    @Test
    void beaconEffectsAreMobEffects() {
        final byte[] wire = wire(buffer -> {
            buffer.write(NetworkBuffer.BOOLEAN, true);
            buffer.write(NetworkBuffer.VAR_INT, PotionEffect.SPEED.id());
            buffer.write(NetworkBuffer.BOOLEAN, true);
            buffer.write(NetworkBuffer.VAR_INT, PotionEffect.REGENERATION.id());
        });

        final ClientSetBeaconEffectPacket packet = readWhole(ClientSetBeaconEffectPacket.SERIALIZER, wire);
        assertEquals(PotionEffect.SPEED.key(), Objects.requireNonNull(packet.primaryEffect()).key());
        assertEquals(PotionEffect.REGENERATION.key(), Objects.requireNonNull(packet.secondaryEffect()).key());
    }

    /**
     * {@code ServerboundSetStructureBlockPacket}: the seed is a VarLong.
     */
    @Test
    void structureBlockSeedIsAVarLong() {
        final byte[] wire = wire(buffer -> {
            buffer.write(NetworkBuffer.BLOCK_POSITION, new Vec(1, 64, -2));
            buffer.write(NetworkBuffer.VAR_INT, 1); // update type SAVE_AREA
            buffer.write(NetworkBuffer.VAR_INT, 0); // mode SAVE
            buffer.write(NetworkBuffer.STRING, "synaxis:test");
            buffer.write(NetworkBuffer.BYTE, (byte) 0); // offset
            buffer.write(NetworkBuffer.BYTE, (byte) 1);
            buffer.write(NetworkBuffer.BYTE, (byte) 0);
            buffer.write(NetworkBuffer.BYTE, (byte) 5); // size
            buffer.write(NetworkBuffer.BYTE, (byte) 6);
            buffer.write(NetworkBuffer.BYTE, (byte) 7);
            buffer.write(NetworkBuffer.VAR_INT, 0); // mirror NONE
            buffer.write(NetworkBuffer.VAR_INT, 1); // rotation CLOCKWISE_90
            buffer.write(NetworkBuffer.STRING, "");
            buffer.write(NetworkBuffer.FLOAT, 1f); // integrity
            buffer.write(NetworkBuffer.VAR_LONG, 300L); // seed, two bytes
            buffer.write(NetworkBuffer.BYTE, ClientUpdateStructureBlockPacket.SHOW_AIR);
        });

        final ClientUpdateStructureBlockPacket packet = readWhole(ClientUpdateStructureBlockPacket.SERIALIZER, wire);
        assertEquals(300L, packet.seed());
        assertEquals(ClientUpdateStructureBlockPacket.SHOW_AIR, packet.flags());
    }

    /**
     * {@code ClientboundDeleteChatPacket}: a {@code MessageSignature.Packed}, the cache index plus one, then the full
     * signature only when that is zero.
     */
    @Test
    void deleteChatTakesACacheIndex() {
        final byte[] wire = wire(buffer -> buffer.write(NetworkBuffer.VAR_INT, 5 + 1));

        readWhole(DeleteChatPacket.SERIALIZER, wire);
    }

    @Test
    void deleteChatTakesAFullSignature() {
        final byte[] signature = new byte[256];
        for (int i = 0; i < signature.length; i++) signature[i] = (byte) i;
        final byte[] wire = wire(buffer -> {
            buffer.write(NetworkBuffer.VAR_INT, 0);
            buffer.write(NetworkBuffer.RAW_BYTES, signature);
        });

        readWhole(DeleteChatPacket.SERIALIZER, wire);
    }

    /**
     * {@code ClientboundMoveMinecartPacket}: each {@code NewMinecartBehavior$MinecartStep} writes its rotations with
     * {@code ByteBufCodecs.ROTATION_BYTE}, a 256th of a turn in a byte.
     */
    @Test
    void minecartStepRotationsAreAngleBytes() {
        final byte[] wire = wire(buffer -> {
            buffer.write(NetworkBuffer.VAR_INT, 7); // entity id
            buffer.write(NetworkBuffer.VAR_INT, 1); // one step
            buffer.write(NetworkBuffer.VECTOR3D, new Vec(1.5, 64, -3.25)); // position
            buffer.write(NetworkBuffer.VECTOR3D, new Vec(0.25, 0, -0.5)); // movement
            buffer.write(NetworkBuffer.BYTE, (byte) 64); // yaw, 90 degrees
            buffer.write(NetworkBuffer.BYTE, (byte) -32); // pitch, -45 degrees
            buffer.write(NetworkBuffer.FLOAT, 0.5f); // weight
        });

        final MoveMinecartPacket packet = readWhole(MoveMinecartPacket.SERIALIZER, wire);
        final MoveMinecartPacket.LerpStep step = packet.lerpSteps().getFirst();
        assertEquals(90f, step.yaw());
        assertEquals(-45f, step.pitch());
        assertEquals(0.5f, step.weight());
    }
}
