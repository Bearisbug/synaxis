package dev.gameengine.network.packet.server.play;

import dev.gameengine.entity.attribute.Attribute;
import dev.gameengine.entity.attribute.AttributeModifier;
import dev.gameengine.network.NetworkBuffer;
import dev.gameengine.network.NetworkBufferTemplate;
import dev.gameengine.network.packet.server.ServerPacket;

import java.util.Collection;
import java.util.List;

import static dev.gameengine.network.NetworkBuffer.DOUBLE;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;

public record EntityAttributesPacket(int entityId, List<Property> properties) implements ServerPacket.Play {
    public static final int MAX_ENTRIES = 1024;

    public static final NetworkBuffer.Type<EntityAttributesPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, EntityAttributesPacket::entityId,
            Property.SERIALIZER.list(MAX_ENTRIES), EntityAttributesPacket::properties,
            EntityAttributesPacket::new);

    public EntityAttributesPacket {
        properties = List.copyOf(properties);
    }

    public record Property(Attribute attribute, double value, List<AttributeModifier> modifiers) {
        public static final NetworkBuffer.Type<Property> SERIALIZER = NetworkBufferTemplate.template(
                Attribute.NETWORK_TYPE, Property::attribute,
                DOUBLE, Property::value,
                AttributeModifier.NETWORK_TYPE.list(), Property::modifiers,
                Property::new);

        public Property {
            modifiers = List.copyOf(modifiers);
        }

        public Property(Attribute attribute, double value, Collection<AttributeModifier> modifiers) {
            this(attribute, value, List.copyOf(modifiers));
        }
    }
}
