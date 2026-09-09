package net.minestom.server.network.packet.server.common;

import net.kyori.adventure.nbt.BinaryTag;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.NetworkBufferTemplate;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.registry.BuiltinRegistries;
import net.minestom.server.registry.Registries;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.utils.Either;
import net.minestom.server.utils.validate.Check;

import java.util.Objects;

/**
 * Shows a dialog to the client.
 *
 * @param dialog either a reference into the {@code minecraft:dialog} registry or an inline dialog
 */
public record ShowDialogPacket(
        Either<RegistryKey<BinaryTag>, BinaryTag> dialog
) implements ServerPacket.Configuration, ServerPacket.Play {
    private static final Registries.Selector<BinaryTag> REGISTRY = registries -> registries.opaque(BuiltinRegistries.DIALOG);

    /**
     * The dialog framed as a vanilla holder: the registry id offset by one, or zero followed by the inline dialog.
     */
    private static final NetworkBuffer.Type<Either<RegistryKey<BinaryTag>, BinaryTag>> HOLDER_TYPE = new NetworkBuffer.Type<>() {
        @Override
        public void write(NetworkBuffer buffer, Either<RegistryKey<BinaryTag>, BinaryTag> value) {
            switch (value) {
                case Either.Left(RegistryKey<BinaryTag> key) -> {
                    final var registries = Objects.requireNonNull(buffer.registries(), "Buffer is missing registries");
                    final int id = REGISTRY.select(registries).getId(key);
                    Check.stateCondition(id == -1, "Key {0} is not registered in the dialog registry", key);
                    buffer.write(NetworkBuffer.VAR_INT, id + 1);
                }
                case Either.Right(BinaryTag inline) -> {
                    buffer.write(NetworkBuffer.VAR_INT, 0);
                    buffer.write(NetworkBuffer.NBT, inline);
                }
            }
        }

        @Override
        public Either<RegistryKey<BinaryTag>, BinaryTag> read(NetworkBuffer buffer) {
            final int id = buffer.read(NetworkBuffer.VAR_INT);
            if (id == 0) return Either.right(buffer.read(NetworkBuffer.NBT));
            final var registries = Objects.requireNonNull(buffer.registries(), "Buffer is missing registries");
            final var key = REGISTRY.select(registries).getKey(id - 1);
            Check.stateCondition(key == null, "Unknown id {0} for the dialog registry", id - 1);
            return Either.left(key);
        }
    };

    public static final NetworkBuffer.Type<ShowDialogPacket> SERIALIZER = NetworkBufferTemplate.template(
            HOLDER_TYPE, ShowDialogPacket::dialog,
            ShowDialogPacket::new);

    public static final NetworkBuffer.Type<ShowDialogPacket> INLINE_SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.NBT, packet -> ((Either.Right<RegistryKey<BinaryTag>, BinaryTag>) packet.dialog()).value(),
            inline -> new ShowDialogPacket(Either.right(inline)));
}
