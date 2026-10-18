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

}
