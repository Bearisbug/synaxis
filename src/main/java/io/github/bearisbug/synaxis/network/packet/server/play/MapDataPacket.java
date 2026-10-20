package io.github.bearisbug.synaxis.network.packet.server.play;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.BYTE_ARRAY;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.COMPONENT;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record MapDataPacket(int mapId, byte scale, boolean locked,
                            boolean trackingPosition, List<Icon> icons,
                            @Nullable MapDataPacket.ColorContent colorContent) implements ServerPacket.Play {
    public static final int MAX_ICONS = 1024;

    public MapDataPacket {
        icons = List.copyOf(icons);
    }

    public static final NetworkBuffer.Type<MapDataPacket> SERIALIZER = new NetworkBuffer.Type<>() {
        @Override
        public void write(NetworkBuffer buffer, MapDataPacket value) {
            buffer.write(VAR_INT, value.mapId);
            buffer.write(BYTE, value.scale);
            buffer.write(BOOLEAN, value.locked);
            buffer.write(BOOLEAN, value.trackingPosition);
            if (value.trackingPosition) buffer.write(Icon.SERIALIZER.list(), value.icons);
            if (value.colorContent != null) {
                buffer.write(ColorContent.SERIALIZER, value.colorContent);
            } else {
                buffer.write(BYTE, (byte) 0);
            }
        }

        @Override
        public MapDataPacket read(NetworkBuffer buffer) {
            var mapId = buffer.read(VAR_INT);
            var scale = buffer.read(BYTE);
            var locked = buffer.read(BOOLEAN);
            var trackingPosition = buffer.read(BOOLEAN);
            List<Icon> icons = trackingPosition ? buffer.read(Icon.SERIALIZER.list(MAX_ICONS)) : List.of();

            var columns = buffer.read(BYTE);
            if (columns == 0) return new MapDataPacket(mapId, scale, locked, trackingPosition, icons, null);
            byte rows = buffer.read(BYTE);
            byte x = buffer.read(BYTE);
            byte z = buffer.read(BYTE);
            byte[] data = buffer.read(BYTE_ARRAY);
            return new MapDataPacket(mapId, scale, locked,
                    trackingPosition, icons, new ColorContent(columns, rows, x, z,
                    data));
        }
    };

    public record Icon(int type, byte x, byte z, byte direction,
                       @Nullable Component displayName) {
        public static final NetworkBuffer.Type<Icon> SERIALIZER = NetworkBufferTemplate.template(
                VAR_INT, Icon::type,
                BYTE, Icon::x,
                BYTE, Icon::z,
                BYTE, Icon::direction,
                COMPONENT.optional(), Icon::displayName,
                Icon::new);
    }

    /**
     * A rectangle of map colors to update.
     *
     * @param columns the width of the rectangle, 1 to 128 read as an unsigned byte: 128 is written {@code (byte) 128}
     * @param rows    the height of the rectangle, 1 to 128 read as an unsigned byte
     * @param x       the column of its left edge
     * @param z       the row of its top edge
     * @param data    the colors, row by row
     */
    public record ColorContent(byte columns, byte rows, byte x, byte z,
                               byte[] data) {
        public static final NetworkBuffer.Type<ColorContent> SERIALIZER = NetworkBufferTemplate.template(
                BYTE, ColorContent::columns,
                BYTE, ColorContent::rows,
                BYTE, ColorContent::x,
                BYTE, ColorContent::z,
                BYTE_ARRAY, ColorContent::data,
                ColorContent::new);

        public ColorContent {
            Check.argCondition(columns == 0, "ColorContent columns must be 1..128, pass a null colorContent for no color patch");
            data = data.clone();
        }

        @Override
        public boolean equals(Object object) {
            if (!(object instanceof ColorContent(byte columns1, byte rows1, byte x1, byte z1, byte[] data1))) return false;
            return x() == x1 && z() == z1 && rows() == rows1 && columns() == columns1 && Arrays.equals(data(), data1);
        }

        @Override
        public int hashCode() {
            int result = columns();
            result = 31 * result + rows();
            result = 31 * result + x();
            result = 31 * result + z();
            result = 31 * result + Arrays.hashCode(data());
            return result;
        }
    }
}
