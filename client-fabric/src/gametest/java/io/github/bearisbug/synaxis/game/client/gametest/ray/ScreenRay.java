package io.github.bearisbug.synaxis.game.client.gametest.ray;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.bearisbug.synaxis.game.client.gametest.camera.SpikeCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Spike S4 of docs/research/client-spikes-2026-09/README.md: the world ray under the cursor.
 * <p>
 * Every frame, once {@code Camera.update} has placed the camera for that frame, the cursor from
 * {@code MouseHandler.getScaledXPos/getScaledYPos} is unprojected through
 * {@code Camera.getViewRotationProjectionMatrix} and offset by {@code Camera.position()}; the ray is
 * clipped against the level and the hit drawn over the terrain as one {@link Layer} of gizmos.
 * <p>
 * The pan is the RTS camera following a moving anchor the way vanilla's camera follows an entity:
 * the anchor moves {@code perTick} each client tick, and each frame the camera is placed at
 * {@code lerp(Camera.getCameraEntityPartialTicks, previous, current)}.
 */
public final class ScreenRay {

    /**
     * What the frame draws. One layer per frame: whatever is drawn over a marker pulls its centroid.
     */
    public enum Layer {
        /**
         * The face the ray hit, filled yellow. A fill, not an outline: vanilla's line shader
         * (rendertype_lines.vsh) scales view space by 1 - 1/256 before projecting, which in
         * orthographic projection pulls every line towards the frame's centre, by 3.6 pixels at
         * the left and right edges of a 1920 pixel wide frame.
         */
        FACE,
        /** A magenta cube of {@link #MARKER_PX} pixels at the exact hit point. */
        MARKER,
        /**
         * While panning, a red cube where the same cursor lands if the ray is built from the camera
         * at the tick's position instead of the frame's interpolated one: the partial tick ignored.
         */
        TICK_MARKER
    }

    /** Edge of the hit point marker in screen pixels. */
    public static final float MARKER_PX = 6f;

    /**
     * What a screenshot frame measured.
     *
     * @param cursorX       the cursor in window pixels, from getScaledXPos scaled back by the GUI scale
     * @param rayFrom       where the frame's ray starts, on the near plane
     * @param rayDirection  the ray's unit direction
     * @param hit           the frame's ray, null when it hit nothing within 512 blocks
     * @param tickHit       the ray from the tick-position camera, null when not panning or no hit
     * @param partialTick   Camera.getCameraEntityPartialTicks the pan placed the camera with; stays
     *                      at 1 until a pan starts
     * @param uncentredShiftPx ortho only: how far, in window pixels, the hit moves if the ray is
     *                      unprojected through the Projection's own [0, w] x [0, h] box rather than
     *                      the eye-centred box the frame is drawn with; NaN in perspective
     */
    public record Measurement(double cursorX, double cursorY, Vec3 eye, Vec3 rayFrom, Vec3 rayDirection, @Nullable BlockHitResult hit,
            @Nullable BlockHitResult tickHit, float partialTick, double uncentredShiftPx) {}

    private record Pan(SpikeCamera.Settings base, Vec3 perTick) {}

    private static volatile boolean active;
    private static volatile @Nullable Pan pan;
    private static volatile int panTicks;
    private static volatile Layer layer = Layer.MARKER;
    private static volatile float framePartialTick = 1f;
    private static volatile @Nullable Measurement last;

    private ScreenRay() {}

    public static void setActive(boolean on) {
        active = on;
        last = null;
    }

    /** Starts moving the camera's anchor {@code perTick} blocks per client tick from {@code base}'s. */
    public static void startPan(SpikeCamera.Settings base, Vec3 perTick) {
        panTicks = 0;
        pan = new Pan(base, perTick);
    }

    public static void stopPan() {
        pan = null;
    }

    public static void draw(Layer drawn) {
        layer = drawn;
    }

    /** Called at the end of every client tick. */
    public static void tick() {
        if (pan != null) panTicks++;
    }

    /** The measurement of the last gametest screenshot frame. */
    public static @Nullable Measurement last() {
        return last;
    }

    /** Before {@code Camera.update}: the pan's anchor for this frame. */
    public static void beforeCameraUpdate(Camera camera, DeltaTracker deltaTracker) {
        final Pan current = pan;
        if (current == null || !camera.isInitialized()) return;
        final float partialTick = camera.getCameraEntityPartialTicks(deltaTracker);
        framePartialTick = partialTick;
        final SpikeCamera.Settings base = current.base();
        final Vec3 anchor = base.anchor().add(current.perTick().scale(panTicks - 1 + partialTick));
        SpikeCamera.set(new SpikeCamera.Settings(base.mode(), anchor, base.yaw(), base.pitch(), base.distance(), base.orthoHeight()));
    }

    /**
     * After {@code Camera.update}, before the frame is extracted: cast, draw, and for a gametest
     * screenshot frame (the harness renders those with a {@link DeltaTracker.DefaultValue}) keep the
     * measurement.
     */
    public static void afterCameraUpdate(Minecraft client, DeltaTracker deltaTracker) {
        if (!active || client.level == null) return;
        final Camera camera = client.gameRenderer.mainCamera();
        final Window window = client.getWindow();
        final double scaledX = client.mouseHandler.getScaledXPos(window);
        final double scaledY = client.mouseHandler.getScaledYPos(window);
        final float ndcX = (float) (2 * scaledX / window.getGuiScaledWidth() - 1);
        final float ndcY = (float) (1 - 2 * scaledY / window.getGuiScaledHeight());

        final Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
        final Vec3 eye = camera.position();
        final Vec3[] ray = ray(viewProjection, eye, ndcX, ndcY);
        final BlockHitResult hit = cast(client, ray);

        final Pan current = pan;
        BlockHitResult tickHit = null;
        if (current != null) {
            // The tick-position camera sits (1 - partialTick) ticks of movement further along.
            final Vec3 tickEye = eye.add(current.perTick().scale(1 - framePartialTick));
            tickHit = cast(client, ray(viewProjection, tickEye, ndcX, ndcY));
        }

        double uncentredShiftPx = Double.NaN;
        final SpikeCamera.Settings settings = SpikeCamera.current();
        if (settings != null && settings.mode() == SpikeCamera.Mode.ORTHO && hit != null) {
            final float height = settings.orthoHeight();
            final float width = height * window.getWidth() / window.getHeight();
            final Matrix4f view = camera.getViewRotationMatrix(new Matrix4f());
            final Matrix4f uncentred = new Matrix4f(viewProjection).mul(new Matrix4f(view).invert())
                    .translate(-width / 2, -height / 2, 0).mul(view);
            final BlockHitResult shifted = cast(client, ray(uncentred, eye, ndcX, ndcY));
            final Vector3f at = project(viewProjection, (shifted != null ? shifted.getLocation() : hit.getLocation()).subtract(eye));
            final Vector3f want = project(viewProjection, hit.getLocation().subtract(eye));
            uncentredShiftPx = shifted == null ? Double.POSITIVE_INFINITY
                    : Math.hypot((at.x - want.x) / 2 * window.getWidth(), (at.y - want.y) / 2 * window.getHeight());
        }

        final double cursorX = scaledX * window.getWidth() / window.getGuiScaledWidth();
        final double cursorY = scaledY * window.getHeight() / window.getGuiScaledHeight();
        if (deltaTracker instanceof DeltaTracker.DefaultValue) {
            last = new Measurement(cursorX, cursorY, eye, ray[0], ray[1], hit, tickHit, framePartialTick, uncentredShiftPx);
        }

        try (var _ = client.levelRenderer.collectPerFrameRenderThreadGizmos()) {
            switch (layer) {
                case FACE -> {
                    if (hit == null) break;
                    final Vec3 corner = Vec3.atLowerCornerOf(hit.getBlockPos());
                    Gizmos.rect(corner, corner.add(1, 1, 1), hit.getDirection(), GizmoStyle.fill(0xFFFFFF00)).setAlwaysOnTop();
                }
                case MARKER -> {
                    if (hit != null) marker(viewProjection, eye, hit.getLocation(), window, 0xFFFF00FF);
                }
                case TICK_MARKER -> {
                    if (tickHit != null) marker(viewProjection, eye, tickHit.getLocation(), window, 0xFFFF0000);
                }
            }
        }
    }

    /**
     * The ray through an NDC point: its start on the near plane and its unit direction. The depth is
     * reversed: Projection#getMatrix hands zFar and zNear to JOML swapped, so NDC z = 1 is the near
     * plane and the far plane is 0 or -1 depending on the device's depth range.
     */
    private static Vec3[] ray(Matrix4f viewProjection, Vec3 eye, float ndcX, float ndcY) {
        final Matrix4f inverse = new Matrix4f(viewProjection).invert();
        final float farZ = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 0f : -1f;
        final Vector3f near = inverse.transformProject(ndcX, ndcY, 1f, new Vector3f());
        final Vector3f far = inverse.transformProject(ndcX, ndcY, farZ, new Vector3f());
        return new Vec3[] {eye.add(near.x, near.y, near.z), new Vec3(far.x - near.x, far.y - near.y, far.z - near.z).normalize()};
    }

    private static @Nullable BlockHitResult cast(Minecraft client, Vec3[] ray) {
        final BlockHitResult result = client.level.clip(new ClipContext(ray[0], ray[0].add(ray[1].scale(512)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return result.getType() == HitResult.Type.MISS ? null : result;
    }

    private static Vector3f project(Matrix4f viewProjection, Vec3 relative) {
        return viewProjection.transformProject((float) relative.x, (float) relative.y, (float) relative.z, new Vector3f());
    }

    /** A cube {@link #MARKER_PX} pixels across at {@code point}, drawn over everything else. */
    private static void marker(Matrix4f viewProjection, Vec3 eye, Vec3 point, Window window, int argb) {
        final Vector3f ndc = project(viewProjection, point.subtract(eye));
        final Vector3f oneRight = new Matrix4f(viewProjection).invert()
                .transformProject(ndc.x + 2f / window.getWidth(), ndc.y, ndc.z, new Vector3f());
        final double pixel = eye.add(oneRight.x, oneRight.y, oneRight.z).distanceTo(point);
        final double edge = pixel * MARKER_PX;
        Gizmos.cuboid(AABB.ofSize(point, edge, edge, edge), GizmoStyle.fill(argb)).setAlwaysOnTop();
    }
}
