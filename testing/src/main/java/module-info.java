module io.github.bearisbug.synaxis.testing {
    requires transitive io.github.bearisbug.synaxis;
    requires transitive io.github.bearisbug.synaxis.plugin;
    requires org.junit.jupiter.api; // Users can bring their own version.

    exports io.github.bearisbug.synaxis.testing;
    exports io.github.bearisbug.synaxis.testing.util;

    opens io.github.bearisbug.synaxis.testing to org.junit.platform.commons;
}