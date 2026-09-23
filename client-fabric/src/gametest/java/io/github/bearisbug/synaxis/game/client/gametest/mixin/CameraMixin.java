package io.github.bearisbug.synaxis.game.client.gametest.mixin;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.bearisbug.synaxis.game.client.gametest.camera.SpikeCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts the camera where {@link SpikeCamera#current()} says, after vanilla has aligned it with the
 * player and set up its perspective projection and cull frustum for the frame.
 */
@Mixin(Camera.class)
abstract class CameraMixin {

    @Shadow @Final private Vector3f forwards;
    @Shadow @Final private Projection projection;
    @Shadow @Final private Matrix4f cachedViewRotMatrix;
    @Shadow private Frustum cullFrustum;
    @Shadow private float depthFar;

    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @Shadow protected abstract void setPosition(Vec3 position);

    @Shadow public abstract Matrix4f getViewRotationMatrix(Matrix4f dest);

    @Shadow
    private Matrix4f createProjectionMatrixForCulling() {
        throw new AssertionError();
    }

    @Shadow
    private void prepareCullFrustum(Matrix4fc modelView, Matrix4f projection, Vec3 cameraPos) {
        throw new AssertionError();
    }

    @Inject(method = "update", at = @At("TAIL"))
    private void synaxis$spikeCamera(DeltaTracker deltaTracker, CallbackInfo ci) {
        final SpikeCamera.Settings settings = SpikeCamera.current();
        if (settings == null) return;
        setRotation(settings.yaw(), settings.pitch());
        final Vec3 eye = settings.anchor().subtract(new Vec3(forwards).scale(settings.distance()));
        setPosition(eye);
        if (settings.mode() == SpikeCamera.Mode.PERSPECTIVE) {
            prepareCullFrustum(getViewRotationMatrix(cachedViewRotMatrix), createProjectionMatrixForCulling(), eye);
            return;
        }
        final Window window = Minecraft.getInstance().getWindow();
        final float height = settings.orthoHeight();
        final float width = height * window.getWidth() / window.getHeight();
        final float far = settings.distance() + depthFar;
        // Ground at the anchor's height meets the frame's lower edge at a view depth of
        // distance - orthoHeight / 2 / tan(pitch); the near plane goes 32 blocks in front of that
        // (terrain standing up towards the eye), behind the eye if need be. An ortho picture does
        // not depend on where along the view axis the eye sits, so this lets the eye stay low, inside
        // the loaded sections, whatever the zoom (S5).
        final float near = Math.min(0.05f,
                settings.distance() - height / 2 / (float) Math.tan(Math.toRadians(settings.pitch())) - 32f);
        projection.setupOrtho(near, far, width, height, false);
        // The same box for culling, except that it reaches at least 16 blocks behind the eye and is
        // at least 32 blocks across. The section graph's Frustum#offsetToFullyIncludeCameraCube moves
        // the frustum along its view vector until the eye's 8-block cell is inside; with the
        // setupOrtho matrix itself, or with a box narrower than that cell's silhouette (up to
        // 8 * sqrt(3) blocks, e.g. orthoHeight 10), that loop never ends and the render thread spins in it.
        final float cullWidth = Math.max(32f, width);
        final float cullHeight = Math.max(32f, height);
        cullFrustum = new Frustum(getViewRotationMatrix(new Matrix4f()), new Matrix4f().setOrtho(-cullWidth / 2, cullWidth / 2,
                -cullHeight / 2, cullHeight / 2, Math.min(-16f, near), far, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()));
        cullFrustum.prepare(eye.x, eye.y, eye.z);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void synaxis$centreOrtho(CameraRenderState cameraState, float partialTicks, CallbackInfo ci) {
        final SpikeCamera.Settings settings = SpikeCamera.current();
        if (settings != null && settings.mode() == SpikeCamera.Mode.ORTHO) cameraState.projectionMatrix.set(orthoMatrix());
    }

    /**
     * Screen-to-world rays unproject through this matrix (spike S4), so in ortho it has to be the
     * eye-centred box the frame is drawn with, not the Projection's own. Vanilla caches the product
     * in cachedViewRotProjMatrix; only the returned copy is replaced.
     */
    @Inject(method = "getViewRotationProjectionMatrix", at = @At("RETURN"))
    private void synaxis$centreOrthoViewProjection(Matrix4f dest, CallbackInfoReturnable<Matrix4f> cir) {
        final SpikeCamera.Settings settings = SpikeCamera.current();
        if (settings != null && settings.mode() == SpikeCamera.Mode.ORTHO) {
            dest.set(orthoMatrix()).mul(getViewRotationMatrix(new Matrix4f()));
        }
    }

    /**
     * Projection#setupOrtho builds the GUI's box, [0, width] x [0, height] in view space, which puts
     * the eye at the frame's bottom left corner. Shifting view space by half a frame centres the box
     * on the eye instead, so the eye (and with it fog, entity distance and the section graph, all
     * measured from Camera#position) stays on the view axis through the anchor.
     */
    private Matrix4f orthoMatrix() {
        return projection.getMatrix(new Matrix4f()).translate(projection.width() / 2, projection.height() / 2, 0);
    }
}
