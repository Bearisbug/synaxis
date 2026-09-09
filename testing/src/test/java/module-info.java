module dev.gameengine.testing.test {
    requires org.junit.jupiter.api;

    requires dev.gameengine.testing;

    opens dev.gameengine.testing.test to org.junit.platform.commons;
}