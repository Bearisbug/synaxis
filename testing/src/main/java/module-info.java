module dev.gameengine.testing {
    requires transitive dev.gameengine;
    requires org.junit.jupiter.api; // Users can bring their own version.

    exports dev.gameengine.testing;
    exports dev.gameengine.testing.util;

    opens dev.gameengine.testing to org.junit.platform.commons;
}