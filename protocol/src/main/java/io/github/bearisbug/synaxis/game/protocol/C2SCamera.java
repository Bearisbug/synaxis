package io.github.bearisbug.synaxis.game.protocol;

/**
 * Where the RTS camera is looking ({@code API-M2-003}), sent at 5 Hz.
 * <p>
 * The server clamps the anchor to the map and teleports the (spectating, invisible) player entity
 * there, which is what makes the engine stream chunks and track entities around the camera. Zoom is
 * client authoritative and only recorded.
 * <p>
 * The yaw here is {@code camera_yaw} in degrees — deliberately not the u8 {@code yaw} of a unit in
 * §9. Same word, different field, and §24 names that pair as a drift risk.
 *
 * @param anchorX    the world X the camera is centred on
 * @param anchorZ    the world Z the camera is centred on
 * @param zoom       the orthographic half height in blocks, {@code [8, 48]} on a well behaved client
 * @param cameraYaw  the camera bearing in degrees, {@code [0, 360)} on a well behaved client
 */
public record C2SCamera(float anchorX, float anchorZ, float zoom, float cameraYaw) implements GameMessage {

    /** The {@code msg_id} of this message on the slice channel. */
    public static final int MSG_ID = 0x01;

    /**
     * Reads the body, with {@code msg_id} already taken by {@link GameMessages#read(MsgBuf)}.
     * <p>
     * The two client authoritative ranges are not enforced here: §14 keeps gameplay values out of
     * the disconnect path, so an out of range zoom is clamped by the server, not punished.
     *
     * @param buf the reading buffer
     * @return the decoded message
     */
    public static C2SCamera read(MsgBuf buf) {
        return new C2SCamera(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    @Override
    public int msgId() {
        return MSG_ID;
    }

    @Override
    public void write(MsgBuf buf) {
        buf.writeVarInt(MSG_ID);
        buf.writeFloat(anchorX);
        buf.writeFloat(anchorZ);
        buf.writeFloat(zoom);
        buf.writeFloat(cameraYaw);
    }
}
