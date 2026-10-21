package io.github.bearisbug.synaxis.network.packet.server.common;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.utils.validate.Check;

import java.util.Map;

public record CustomReportDetailsPacket(
        Map<String, String> details
) implements ServerPacket.Configuration, ServerPacket.Play {
    private static final int MAX_DETAILS = 32;

    public static final NetworkBuffer.Type<CustomReportDetailsPacket> SERIALIZER = NetworkBufferTemplate.template(
            NetworkBuffer.STRING.mapValue(NetworkBuffer.STRING, MAX_DETAILS), CustomReportDetailsPacket::details,
            CustomReportDetailsPacket::new
    );

    public CustomReportDetailsPacket {
        Check.argCondition(details.size() > MAX_DETAILS, "Too many report details: {0} > {1}", details.size(), MAX_DETAILS);
        details = Map.copyOf(details);
    }
}
