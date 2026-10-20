package io.github.bearisbug.synaxis.entity.attribute;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AttributeInstanceTest {

    @Test
    void testReplaceAttributeSameValue() {
        var attribute = new AttributeInstance(Attribute.SAFE_FALL_DISTANCE, null);
        var modifier = new AttributeModifier("test", 1.0, AttributeOperation.ADD_VALUE);

        attribute.addModifier(modifier);
        assertEquals(4, attribute.getValue());

        attribute.addModifier(modifier);
        assertEquals(4, attribute.getValue());
    }

    @Test
    void aNaNValueIsTheMinimum() {
        var attribute = new AttributeInstance(Attribute.SAFE_FALL_DISTANCE, null);
        attribute.setBaseValue(Double.NaN);
        assertEquals(Attribute.SAFE_FALL_DISTANCE.minValue(), attribute.getValue());

        attribute.setBaseValue(3);
        attribute.addModifier(new AttributeModifier("test", Double.NaN, AttributeOperation.ADD_MULTIPLIED_TOTAL));
        assertEquals(Attribute.SAFE_FALL_DISTANCE.minValue(), attribute.getValue());
    }

    @Test
    void testReplaceAttributeNewValue() {
        var attribute = new AttributeInstance(Attribute.SAFE_FALL_DISTANCE, null);

        attribute.addModifier(new AttributeModifier("test", 1.0, AttributeOperation.ADD_VALUE));
        assertEquals(4, attribute.getValue());

        attribute.addModifier(new AttributeModifier("test", 2.0, AttributeOperation.ADD_VALUE));
        assertEquals(5, attribute.getValue()); // New value

        attribute.addModifier(new AttributeModifier("test", 2.0, AttributeOperation.ADD_MULTIPLIED_BASE));
        assertEquals(9, attribute.getValue()); // New operation

    }

    @Test
    void theValueAddsUpInTheVanillaOrder() {
        var attribute = new AttributeInstance(Attribute.ATTACK_DAMAGE, null);
        attribute.setBaseValue(3.3);
        attribute.addModifier(new AttributeModifier("add_a", 0.7, AttributeOperation.ADD_VALUE));
        attribute.addModifier(new AttributeModifier("add_b", 1.1, AttributeOperation.ADD_VALUE));
        attribute.addModifier(new AttributeModifier("base_a", 0.15, AttributeOperation.ADD_MULTIPLIED_BASE));
        attribute.addModifier(new AttributeModifier("base_b", 0.35, AttributeOperation.ADD_MULTIPLIED_BASE));
        attribute.addModifier(new AttributeModifier("total_a", 0.2, AttributeOperation.ADD_MULTIPLIED_TOTAL));
        attribute.addModifier(new AttributeModifier("total_b", -0.05, AttributeOperation.ADD_MULTIPLIED_TOTAL));

        assertEquals(Double.doubleToLongBits(vanillaValue(attribute)), Double.doubleToLongBits(attribute.getValue()));
        assertEquals(Double.doubleToLongBits(vanillaValue(attribute, 7.25)), Double.doubleToLongBits(attribute.applyModifiers(7.25)));
    }

    private static double vanillaValue(AttributeInstance instance) {
        return vanillaValue(instance, instance.getBaseValue());
    }

    /**
     * As the client computes it, bit for bit: the additions onto the base, then each multiplier of the base added on
     * its own, then the multipliers of the total, each group in the iteration order of the modifiers.
     */
    private static double vanillaValue(AttributeInstance instance, double baseValue) {
        double base = baseValue;
        for (AttributeModifier modifier : instance.modifiers()) {
            if (modifier.operation() == AttributeOperation.ADD_VALUE) base += modifier.amount();
        }
        double result = base;
        for (AttributeModifier modifier : instance.modifiers()) {
            if (modifier.operation() == AttributeOperation.ADD_MULTIPLIED_BASE) result += base * modifier.amount();
        }
        for (AttributeModifier modifier : instance.modifiers()) {
            if (modifier.operation() == AttributeOperation.ADD_MULTIPLIED_TOTAL) result *= 1.0 + modifier.amount();
        }
        return Math.clamp(result, instance.attribute().minValue(), instance.attribute().maxValue());
    }
}
