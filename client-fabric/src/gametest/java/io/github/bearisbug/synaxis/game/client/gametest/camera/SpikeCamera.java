package io.github.bearisbug.synaxis.game.client.gametest.camera;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The RTS camera of spikes S2 to S5 (docs/research/client-spikes-2026-09/README.md): an eye that
 * looks at an anchor from a fixed yaw, pitch and distance, through either an orthographic or a
 * perspective projection. {@code CameraMixin} applies it after vanilla's {@code Camera.update}; with
 * no settings set the camera stays vanilla.
 * <p>
 * Only the gametest source set has this class, so none of it reaches the shipped mod jar.
 */
public final class SpikeCamera {

    public enum Mode { ORTHO, PERSPECTIVE }

    /**
     * @param mode        the projection
     * @param anchor      the world point the camera looks at, in the middle of the frame
     * @param yaw         vanilla yaw in degrees: 0 looks towards +Z, 90 towards -X
     * @param pitch       the angle below the horizon in degrees, vanilla xRot
     * @param distance    blocks from the anchor back to the eye along the view direction. In
     *                    {@link Mode#ORTHO} it does not change the picture at all: the near plane
     *                    moves behind the eye far enough to keep the lower frame edge filled, so a
     *                    small distance only keeps the eye low (inside the loaded sections)
     * @param orthoHeight blocks the frame spans vertically in {@link Mode#ORTHO}; the width follows
     *                    the window's aspect ratio. Unused in perspective, where the fov option rules.
     */
    public record Settings(Mode mode, Vec3 anchor, float yaw, float pitch, float distance, float orthoHeight) {

        /** A short name for screenshots and the report, e.g. {@code ortho-p60-d64-h96}. */
        public String label() {
            final String base = mode.name().toLowerCase(Locale.ROOT) + "-p" + Math.round(pitch) + "-d" + Math.round(distance);
            return mode == Mode.ORTHO ? base + "-h" + Math.round(orthoHeight) : base;
        }
    }

    private static volatile @Nullable Settings current;

    private SpikeCamera() {}

    /** The settings the next frame renders with, null for the vanilla camera. */
    public static @Nullable Settings current() {
        return current;
    }

    public static void set(@Nullable Settings settings) {
        current = settings;
    }

    /**
     * The settings named by the environment, one per mode and pitch combination, in that order.
     * Unset variables fall back to the defaults below; the anchor and yaw default to the player's.
     * <pre>
     * SYNAXIS_CAMERA_MODES         ortho,perspective   either or both
     * SYNAXIS_CAMERA_PITCHES       60                  comma separated degrees below the horizon
     * SYNAXIS_CAMERA_DISTANCE      64                  blocks from anchor to eye
     * SYNAXIS_CAMERA_ORTHO_HEIGHT  96                  blocks the ortho frame spans vertically
     * SYNAXIS_CAMERA_YAW           player's yaw
     * SYNAXIS_CAMERA_ANCHOR        player's feet       x,y,z
     * </pre>
     */
    public static List<Settings> fromEnvironment(Vec3 playerFeet, float playerYaw) {
        final Vec3 anchor = anchor(env("SYNAXIS_CAMERA_ANCHOR", null), playerFeet);
        final float yaw = Float.parseFloat(env("SYNAXIS_CAMERA_YAW", Float.toString(playerYaw)));
        final float distance = Float.parseFloat(env("SYNAXIS_CAMERA_DISTANCE", "64"));
        final float orthoHeight = Float.parseFloat(env("SYNAXIS_CAMERA_ORTHO_HEIGHT", "96"));
        final List<Settings> settings = new ArrayList<>();
        for (String mode : env("SYNAXIS_CAMERA_MODES", "ortho,perspective").split(",")) {
            for (String pitch : env("SYNAXIS_CAMERA_PITCHES", "60").split(",")) {
                settings.add(new Settings(Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT)), anchor, yaw,
                        Float.parseFloat(pitch.trim()), distance, orthoHeight));
            }
        }
        return settings;
    }

    private static Vec3 anchor(@Nullable String value, Vec3 fallback) {
        if (value == null) return fallback;
        final String[] xyz = value.split(",");
        return new Vec3(Double.parseDouble(xyz[0].trim()), Double.parseDouble(xyz[1].trim()), Double.parseDouble(xyz[2].trim()));
    }

    private static String env(String name, @Nullable String fallback) {
        final String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
