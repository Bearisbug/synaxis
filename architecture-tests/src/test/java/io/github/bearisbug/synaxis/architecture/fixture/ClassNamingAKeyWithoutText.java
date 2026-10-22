package io.github.bearisbug.synaxis.architecture.fixture;

/**
 * Stands in for a class holding three strings of the {@code synaxis:} namespace: a translate key
 * the client mod has text for, one it has none for, and a channel name, which is no text at all.
 * Only ever imported as a synthetic {@code JavaClasses}.
 */
public final class ClassNamingAKeyWithoutText {

    public static final String KEY_WITH_TEXT = "synaxis:shard-full";

    public static final String KEY_WITHOUT_TEXT = "synaxis:fixture-without-text";

    public static final String CHANNEL = "synaxis:handshake";

    private ClassNamingAKeyWithoutText() {
    }
}
