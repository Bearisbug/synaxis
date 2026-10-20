package io.github.bearisbug.synaxis.entity.metadata.display;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.MetadataDef;
import io.github.bearisbug.synaxis.entity.MetadataHolder;
import io.github.bearisbug.synaxis.entity.metadata.EntityMeta;
import io.github.bearisbug.synaxis.utils.validate.Check;
import org.jetbrains.annotations.Nullable;

public class AbstractDisplayMeta extends EntityMeta {
    protected AbstractDisplayMeta(@Nullable Entity entity, MetadataHolder metadata) {
        super(entity, metadata);
    }

    public int getTransformationInterpolationStartDelta() {
        return metadata.get(MetadataDef.Display.INTERPOLATION_DELAY);
    }

    /**
     * Sets the delay, in ticks, before the client starts interpolating to a new transformation (the
     * {@code INTERPOLATION_DELAY} entry).
     * <p>
     * Send it again, usually as 0, in the same {@code Entity#editEntityMeta} batch as every change of the translation,
     * the scale or a rotation: the client only restarts the interpolation from the current state when it receives this
     * value, otherwise it keeps the start time of the previous one, and when that one is already over the new
     * transformation shows at once. This entry is never skipped as unchanged, the same value is sent again.
     *
     * @param value the delay in ticks
     */
    public void setTransformationInterpolationStartDelta(int value) {
        metadata.set(MetadataDef.Display.INTERPOLATION_DELAY, value);
    }

    public int getTransformationInterpolationDuration() {
        return metadata.get(MetadataDef.Display.TRANSFORMATION_INTERPOLATION_DURATION);
    }

    public void setTransformationInterpolationDuration(int value) {
        metadata.set(MetadataDef.Display.TRANSFORMATION_INTERPOLATION_DURATION, value);
    }

    public int getPosRotInterpolationDuration() {
        return metadata.get(MetadataDef.Display.POSITION_ROTATION_INTERPOLATION_DURATION);
    }

    public void setPosRotInterpolationDuration(int value) {
        metadata.set(MetadataDef.Display.POSITION_ROTATION_INTERPOLATION_DURATION, value);
    }

    public Point getTranslation() {
        return metadata.get(MetadataDef.Display.TRANSLATION);
    }

    public void setTranslation(Point value) {
        metadata.set(MetadataDef.Display.TRANSLATION, value);
    }

    public Vec getScale() {
        return metadata.get(MetadataDef.Display.SCALE).asVec();
    }

    public void setScale(Vec value) {
        metadata.set(MetadataDef.Display.SCALE, value);
    }

    /**
     * Gets the left rotation, a quaternion {x, y, z, w}.
     *
     * @return a copy of the rotation, changing it changes nothing until it is set again
     */
    public float [] getLeftRotation() {
        //todo replace with actual quaternion type
        return metadata.get(MetadataDef.Display.ROTATION_LEFT);
    }

    /**
     * Sets the left rotation, a quaternion {x, y, z, w}. The array is copied: changing it afterwards changes
     * nothing until it is set again.
     *
     * @param value the rotation, 4 components
     * @throws IllegalArgumentException if {@code value} does not have 4 components
     */
    public void setLeftRotation(float [] value) {
        metadata.set(MetadataDef.Display.ROTATION_LEFT, value);
    }

    /**
     * Gets the right rotation, a quaternion {x, y, z, w}.
     *
     * @return a copy of the rotation, changing it changes nothing until it is set again
     */
    public float [] getRightRotation() {
        //todo replace with actual quaternion type
        return metadata.get(MetadataDef.Display.ROTATION_RIGHT);
    }

    /**
     * Sets the right rotation, a quaternion {x, y, z, w}. The array is copied: changing it afterwards changes
     * nothing until it is set again.
     *
     * @param value the rotation, 4 components
     * @throws IllegalArgumentException if {@code value} does not have 4 components
     */
    public void setRightRotation(float [] value) {
        metadata.set(MetadataDef.Display.ROTATION_RIGHT, value);
    }

    public BillboardConstraints getBillboardRenderConstraints() {
        return BillboardConstraints.VALUES[metadata.get(MetadataDef.Display.BILLBOARD_CONSTRAINTS)];
    }

    public void setBillboardRenderConstraints(BillboardConstraints value) {
        metadata.set(MetadataDef.Display.BILLBOARD_CONSTRAINTS, (byte) value.ordinal());
    }

    /**
     * Gets the packed brightness override.
     *
     * @return the block light shifted by 4 and the sky light shifted by 20, or -1 when the world light is used
     */
    public int getBrightnessOverride() {
        return metadata.get(MetadataDef.Display.BRIGHTNESS_OVERRIDE);
    }

    public void setBrightnessOverride(int value) {
        metadata.set(MetadataDef.Display.BRIGHTNESS_OVERRIDE, value);
    }

    /**
     * Renders the display with the given light levels instead of the world light around it.
     *
     * @param blockLight the block light level, 0 to 15
     * @param skyLight   the sky light level, 0 to 15
     * @throws IllegalArgumentException if a level is outside 0 to 15
     */
    public void setBrightness(int blockLight, int skyLight) {
        Check.argCondition(blockLight < 0 || blockLight > 15, "blockLight must be in [0, 15]: {0}", blockLight);
        Check.argCondition(skyLight < 0 || skyLight > 15, "skyLight must be in [0, 15]: {0}", skyLight);
        setBrightnessOverride((blockLight & 0xF) << 4 | (skyLight & 0xF) << 20);
    }

    /**
     * Gets the block light level of the brightness override.
     *
     * @return the level, 0 when there is no override: check {@link #getBrightnessOverride()} for -1 to tell
     */
    public int getBlockLight() {
        return getLight(4);
    }

    /**
     * Gets the sky light level of the brightness override.
     *
     * @return the level, 0 when there is no override: check {@link #getBrightnessOverride()} for -1 to tell
     */
    public int getSkyLight() {
        return getLight(20);
    }

    private int getLight(int shift) {
        int brightnessOverride = getBrightnessOverride();
        if (brightnessOverride <= 0)
            return 0;
        else
            return (brightnessOverride >> shift) & 0xF;
    }

    public float getViewRange() {
        return metadata.get(MetadataDef.Display.VIEW_RANGE);
    }

    public void setViewRange(float value) {
        metadata.set(MetadataDef.Display.VIEW_RANGE, value);
    }

    public float getShadowRadius() {
        return metadata.get(MetadataDef.Display.SHADOW_RADIUS);
    }

    public void setShadowRadius(float value) {
        metadata.set(MetadataDef.Display.SHADOW_RADIUS, value);
    }

    public float getShadowStrength() {
        return metadata.get(MetadataDef.Display.SHADOW_STRENGTH);
    }

    public void setShadowStrength(float value) {
        metadata.set(MetadataDef.Display.SHADOW_STRENGTH, value);
    }

    public float getWidth() {
        return metadata.get(MetadataDef.Display.WIDTH);
    }

    public void setWidth(float value) {
        metadata.set(MetadataDef.Display.WIDTH, value);
    }

    public float getHeight() {
        return metadata.get(MetadataDef.Display.HEIGHT);
    }

    public void setHeight(float value) {
        metadata.set(MetadataDef.Display.HEIGHT, value);
    }

    public int getGlowColorOverride() {
        return metadata.get(MetadataDef.Display.GLOW_COLOR_OVERRIDE);
    }

    public void setGlowColorOverride(int value) {
        metadata.set(MetadataDef.Display.GLOW_COLOR_OVERRIDE, value);
    }

    public enum BillboardConstraints {
        FIXED,
        VERTICAL,
        HORIZONTAL,
        CENTER;

        private final static BillboardConstraints[] VALUES = values();
    }

}
