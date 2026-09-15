package io.github.bearisbug.synaxis.item.component;

import io.github.bearisbug.synaxis.component.DataComponent;
import io.github.bearisbug.synaxis.component.DataComponents;
import io.github.bearisbug.synaxis.entity.EquipmentSlotGroup;
import io.github.bearisbug.synaxis.entity.attribute.Attribute;
import io.github.bearisbug.synaxis.entity.attribute.AttributeModifier;
import io.github.bearisbug.synaxis.entity.attribute.AttributeOperation;

import java.util.List;
import java.util.Map;

public class ItemAttributeTest extends AbstractItemComponentRegistriesTest<AttributeList> {
    @Override
    protected DataComponent<AttributeList> component() {
        return DataComponents.ATTRIBUTE_MODIFIERS;
    }

    @Override
    protected List<Map.Entry<String, AttributeList>> directReadWriteEntries() {
        return List.of(
                Map.entry("empty", AttributeList.EMPTY),
                Map.entry("single", new AttributeList(new AttributeList.Modifier(Attribute.MOVEMENT_SPEED, new AttributeModifier("synaxis:movement_test", 0.1, AttributeOperation.ADD_VALUE), EquipmentSlotGroup.MAIN_HAND))),
                Map.entry("multiple", new AttributeList(List.of(
                        new AttributeList.Modifier(Attribute.MAX_HEALTH, new AttributeModifier("synaxis:health_test", 5, AttributeOperation.ADD_VALUE), EquipmentSlotGroup.MAIN_HAND),
                        new AttributeList.Modifier(Attribute.ATTACK_DAMAGE, new AttributeModifier("synaxis:attack_test", 3, AttributeOperation.ADD_VALUE), EquipmentSlotGroup.ANY),
                        new AttributeList.Modifier(Attribute.ATTACK_DAMAGE, new AttributeModifier("synaxis:attack_test_1", 1.4, AttributeOperation.ADD_MULTIPLIED_BASE), EquipmentSlotGroup.CHEST)

                )))
        );
    }
}
