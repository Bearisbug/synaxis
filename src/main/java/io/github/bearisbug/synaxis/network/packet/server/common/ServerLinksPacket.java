package io.github.bearisbug.synaxis.network.packet.server.common;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.utils.Either;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public record ServerLinksPacket(List<Entry> entries) implements ServerPacket.Configuration, ServerPacket.Play {
    public static final NetworkBuffer.Type<ServerLinksPacket> SERIALIZER = NetworkBufferTemplate.template(
            Entry.NETWORK_TYPE.list(), ServerLinksPacket::entries,
            ServerLinksPacket::new);

    public ServerLinksPacket {
        entries = List.copyOf(entries);
    }

    public ServerLinksPacket(Entry... entries) {
        this(List.of(entries));
    }

    public record Entry(Either<KnownLinkType, Component> linkType, String link) {
        public static final NetworkBuffer.Type<Entry> NETWORK_TYPE = NetworkBufferTemplate.template(
                NetworkBuffer.Either(KnownLinkType.NETWORK_TYPE, NetworkBuffer.COMPONENT), Entry::linkType,
                NetworkBuffer.STRING, Entry::link,
                Entry::new
        );

        public Entry {
            Objects.requireNonNull(linkType, "linkType");
            Objects.requireNonNull(link, "link");
        }

        /**
         * @deprecated Use {@link #Entry(KnownLinkType, String)} or {@link #Entry(Component, String)} instead.
         */
        @Deprecated(forRemoval = true)
        public Entry(@Nullable KnownLinkType knownType, @Nullable Component customType, String link) {
            this(knownType != null ? Either.left(knownType) : Either.right(customType), link);
        }

        /**
         * @deprecated Use {@link #linkType()} instead.
         */
        @Deprecated(forRemoval = true)
        public @Nullable KnownLinkType knownType() {
            return linkType.unify(Function.identity(), _ -> null);
        }

        /**
         * @deprecated Use {@link #linkType()} instead.
         */
        @Deprecated(forRemoval = true)
        public @Nullable Component customType() {
            return linkType.unify(_ -> null, Function.identity());
        }

        public Entry(KnownLinkType type, String link) {
            this(Either.left(Objects.requireNonNull(type, "type")), link);
        }

        public Entry(Component type, String link) {
            this(Either.right(Objects.requireNonNull(type, "type")), link);
        }
    }

    public enum KnownLinkType {
        BUG_REPORT,
        COMMUNITY_GUIDELINES,
        SUPPORT,
        STATUS,
        FEEDBACK,
        COMMUNITY,
        WEBSITE,
        FORUMS,
        NEWS,
        ANNOUNCEMENTS;

        public static final NetworkBuffer.Type<KnownLinkType> NETWORK_TYPE = NetworkBuffer.Enum(KnownLinkType.class);
    }
}
