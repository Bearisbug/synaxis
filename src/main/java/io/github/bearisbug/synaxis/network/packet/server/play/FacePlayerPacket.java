package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.Nullable;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.Enum;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VECTOR3D;

/**
 * Turns the player to face a point, or an entity.
 *
 * @param facePosition       the part of the player that faces the target
 * @param target             the point to face
 * @param entityId           the entity to face, or 0 to face {@code target} only
 * @param entityFacePosition the part of the entity to face, required when {@code entityId} is over 0
 */
public record FacePlayerPacket(FacePosition facePosition,
                               Point target, int entityId,
                               @Nullable FacePosition entityFacePosition) implements ServerPacket.Play {
    private static final NetworkBuffer.Type<FacePosition> FACE = Enum(FacePosition.class);

    public static final NetworkBuffer.Type<FacePlayerPacket> SERIALIZER = new NetworkBuffer.Type<>() {
        @Override
        public void write(NetworkBuffer buffer, FacePlayerPacket value) {
            buffer.write(FACE, value.facePosition);
            buffer.write(VECTOR3D, value.target);
            final boolean isEntity = value.entityId > 0;
            buffer.write(BOOLEAN, isEntity);
            if (isEntity) {
                buffer.write(VAR_INT, value.entityId);
                buffer.write(FACE, value.entityFacePosition);
            }
        }

        @Override
        public FacePlayerPacket read(NetworkBuffer buffer) {
            final FacePosition face = buffer.read(FACE);
            final Point target = buffer.read(VECTOR3D);
            if (!buffer.read(BOOLEAN)) return new FacePlayerPacket(face, target, 0, null);
            return new FacePlayerPacket(face, target, buffer.read(VAR_INT), buffer.read(FACE));
        }
    };

    public FacePlayerPacket {
        Check.argCondition(entityId > 0 && entityFacePosition == null,
                "entityFacePosition is required when entityId is {0}", entityId);
    }

    public enum FacePosition {
        FEET, EYES
    }
}
