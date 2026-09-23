package io.github.bearisbug.synaxis.game.client.gametest;

import io.github.bearisbug.synaxis.game.client.gametest.camera.SpikeCamera;
import io.github.bearisbug.synaxis.game.client.gametest.cull.RenderedEntities;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Spike S3 of docs/research/client-spikes-2026-09/README.md: is entity culling measured from the
 * player entity or from the render camera, and do the units stay on screen at 100% and 50%
 * "Entity Distance".
 * <p>
 * The player stays beside the unit cluster around the spawn, so every unit is well inside the
 * server's tracking range of that player, while the spike camera backs away from the cluster to
 * each distance of {@code SYNAXIS_S3_DISTANCES} (60° pitch, perspective). Per shot the report counts
 * the units the frame really extracted for rendering ({@link RenderedEntities}), the smallest and
 * largest count over 40 ticks, and which of the three checks of {@code LevelExtractor#isEntityVisible}
 * the other units failed. At the eye height of the first distance, four more shots put the cluster
 * near each frame corner, and one shot per {@code SYNAXIS_S3_HORIZONTALS} puts it straight ahead at
 * that many blocks from the eye along the ground, which is how far up the frame a unit stands.
 * <pre>
 * SYNAXIS_S3_DISTANCES    unset = S3 skipped   blocks from the cluster back to the eye, comma separated
 * SYNAXIS_S3_SCALINGS     1.0,0.5              values of Options#entityDistanceScaling
 * SYNAXIS_S3_HORIZONTALS  none                 horizontal blocks from the eye to the cluster, comma separated
 * </pre>
 */
final class EntityCullingSpike {

    private static final float PITCH = 60f;
    private static final int SETTLE_TICKS = 60;
    private static final int SAMPLE_TICKS = 40;
    /** How far towards a frame corner the cluster is put, as a fraction of the half frame. */
    private static final double CORNER = 0.8;

    private EntityCullingSpike() {}

    static void run(ClientGameTestContext context, List<String> report) {
        final String[] ds = env("SYNAXIS_S3_DISTANCES", "");
        if (ds.length == 0) return;
        final Vec3 cluster = context.computeOnClient(EntityCullingSpike::clusterCentre);
        final float yaw = context.computeOnClient(client -> client.player.getYRot());
        report.add("S3 cluster " + cluster + " player " + context.computeOnClient(client -> client.player.position())
                + " render_distance " + context.computeOnClient(client -> client.options.getEffectiveRenderDistance()));
        for (String scalingValue : env("SYNAXIS_S3_SCALINGS", "1.0,0.5")) {
            final double scaling = Double.parseDouble(scalingValue);
            context.runOnClient(client -> client.options.entityDistanceScaling().set(scaling));
            final String prefix = "s3-s" + Math.round(scaling * 100);
            for (String d : ds) {
                final float distance = Float.parseFloat(d);
                shot(context, report, prefix + "-d" + Math.round(distance),
                        new SpikeCamera.Settings(SpikeCamera.Mode.PERSPECTIVE, cluster, yaw, PITCH, distance, 0f));
            }
            final float distance = Float.parseFloat(ds[0]);
            for (int corner = 0; corner < 4; corner++) {
                final int sx = corner % 2 == 0 ? -1 : 1;
                final int sy = corner < 2 ? 1 : -1;
                final String name = (sy > 0 ? "t" : "b") + (sx < 0 ? "l" : "r");
                final Vec3 anchor = context.computeOnClient(client -> cornerAnchor(client, cluster, yaw, distance, sx, sy));
                shot(context, report, prefix + "-d" + Math.round(distance) + "-" + name,
                        new SpikeCamera.Settings(SpikeCamera.Mode.PERSPECTIVE, anchor, yaw, PITCH, distance, 0f));
            }
            for (String h : env("SYNAXIS_S3_HORIZONTALS", "")) {
                final double horizontal = Double.parseDouble(h);
                shot(context, report, prefix + "-d" + Math.round(distance) + "-h" + Math.round(horizontal),
                        new SpikeCamera.Settings(SpikeCamera.Mode.PERSPECTIVE, aheadAnchor(cluster, yaw, distance, horizontal),
                                yaw, PITCH, distance, 0f));
            }
        }
        context.runOnClient(client -> client.options.entityDistanceScaling().set(1.0));
        context.runOnClient(_ -> SpikeCamera.set(null));
    }

    private static void shot(ClientGameTestContext context, List<String> report, String label, SpikeCamera.Settings settings) {
        context.runOnClient(_ -> SpikeCamera.set(settings));
        context.waitTicks(SETTLE_TICKS);
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (int i = 0; i < SAMPLE_TICKS; i++) {
            context.waitTick();
            final int rendered = context.computeOnClient(client -> {
                int n = 0;
                for (Entity entity : client.level.entitiesForRendering()) {
                    if (entity instanceof Display.ItemDisplay && RenderedEntities.contains(entity)) n++;
                }
                return n;
            });
            min = Math.min(min, rendered);
            max = Math.max(max, rendered);
        }
        final int lo = min;
        final int hi = max;
        report.add(context.computeOnClient(client -> describe(client, label, lo, hi)));
        context.takeScreenshot(TestScreenshotOptions.of(label).withSize(1920, 1080));
    }

    /** One report line: counts, which check the unrendered units failed, and the distances either side. */
    private static String describe(Minecraft client, String label, int minRendered, int maxRendered) {
        final Camera camera = client.gameRenderer.mainCamera();
        final Vec3 eye = camera.position();
        final Vec3 player = client.player.position();
        int units = 0;
        int rendered = 0;
        int failDistance = 0;
        int failFrustum = 0;
        int failSection = 0;
        final Range renderedCamera = new Range();
        final Range culledCamera = new Range();
        final Range renderedPlayer = new Range();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof Display.ItemDisplay unit)) continue;
            units++;
            final double toCamera = entity.position().distanceTo(eye);
            if (RenderedEntities.contains(entity)) {
                rendered++;
                renderedCamera.add(toCamera);
                renderedPlayer.add(entity.position().distanceTo(player));
                continue;
            }
            culledCamera.add(toCamera);
            if (!entity.shouldRender(eye.x, eye.y, eye.z)) failDistance++;
            if (!camera.getCullFrustum().isVisible(unit.getBoundingBoxForCulling().inflate(0.5))) failFrustum++;
            if (!client.levelRenderer.isSectionCompiledAndVisible(entity.blockPosition())) failSection++;
        }
        final double viewScale = Entity.getViewScale();
        return String.format(Locale.ROOT,
                "S3 %s scaling %.2f view_scale %.3f radius_at_view_range_4 %.1f eye %s eye_to_player %.1f"
                        + " | units %d rendered %d (min %d max %d over %d ticks)"
                        + " | not rendered: distance %d frustum %d section %d"
                        + " | rendered eye_dist %s player_dist %s | not rendered eye_dist %s",
                label, client.options.entityDistanceScaling().get(), viewScale, 4.0 * 64 * viewScale,
                fmt(eye), eye.distanceTo(player), units, rendered, minRendered, maxRendered, SAMPLE_TICKS,
                failDistance, failFrustum, failSection, renderedCamera, renderedPlayer, culledCamera);
    }

    /** The mean position of the units near the player: the roster's ring around the spawn. */
    private static Vec3 clusterCentre(Minecraft client) {
        double x = 0;
        double y = 0;
        double z = 0;
        int n = 0;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof Display.ItemDisplay) || entity.distanceTo(client.player) > 48) continue;
            x += entity.getX();
            y += entity.getY();
            z += entity.getZ();
            n++;
        }
        return n == 0 ? client.player.position() : new Vec3(x / n, y / n, z / n);
    }

    /**
     * The anchor that keeps the eye at the height a {@code distance} shot of the centred cluster has,
     * but moves it so the cluster lands at {@link #CORNER} of the way to a frame corner. The cluster's
     * distance from the eye then grows the way a real unit's does at that corner: for top corners
     * it is farther than {@code distance}, for bottom ones nearer.
     */
    private static Vec3 cornerAnchor(Minecraft client, Vec3 cluster, float yaw, float distance, int sx, int sy) {
        final double yawRad = Math.toRadians(yaw);
        final double pitchRad = Math.toRadians(PITCH);
        final Vec3 forward = new Vec3(-Math.sin(yawRad) * Math.cos(pitchRad), -Math.sin(pitchRad), Math.cos(yawRad) * Math.cos(pitchRad));
        final Vec3 right = new Vec3(-Math.cos(yawRad), 0, -Math.sin(yawRad));
        final Vec3 up = right.cross(forward);
        final double tanV = Math.tan(Math.toRadians(client.options.fov().get()) / 2);
        final double aspect = (double) client.getWindow().getWidth() / client.getWindow().getHeight();
        final Vec3 ray = forward.add(right.scale(sx * CORNER * tanV * aspect)).add(up.scale(sy * CORNER * tanV));
        final double height = distance * Math.sin(pitchRad);
        final Vec3 eye = cluster.subtract(ray.scale(height / -ray.y));
        return eye.add(forward.scale(distance));
    }

    /**
     * The anchor that keeps the eye at the height a {@code distance} shot of the centred cluster has,
     * and puts the cluster straight ahead of it, {@code horizontal} blocks away along the ground.
     */
    private static Vec3 aheadAnchor(Vec3 cluster, float yaw, float distance, double horizontal) {
        final double yawRad = Math.toRadians(yaw);
        final double pitchRad = Math.toRadians(PITCH);
        final Vec3 ahead = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
        final Vec3 eye = cluster.subtract(ahead.scale(horizontal)).add(0, distance * Math.sin(pitchRad), 0);
        final Vec3 forward = new Vec3(-Math.sin(yawRad) * Math.cos(pitchRad), -Math.sin(pitchRad), Math.cos(yawRad) * Math.cos(pitchRad));
        return eye.add(forward.scale(distance));
    }

    private static String[] env(String name, String fallback) {
        final String value = System.getenv(name);
        final String list = value == null || value.isBlank() ? fallback : value;
        return list.isBlank() ? new String[0] : list.trim().split("\\s*,\\s*");
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", v.x, v.y, v.z);
    }

    /** The smallest and largest of a set of distances, printed as {@code [min, max]} or {@code -}. */
    private static final class Range {
        private double min = Double.MAX_VALUE;
        private double max = -1;

        void add(double value) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        @Override
        public String toString() {
            return max < 0 ? "-" : String.format(Locale.ROOT, "[%.1f, %.1f]", min, max);
        }
    }
}
