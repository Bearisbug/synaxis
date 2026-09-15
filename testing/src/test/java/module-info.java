module io.github.bearisbug.synaxis.testing.test {
    requires org.junit.jupiter.api;

    requires io.github.bearisbug.synaxis.testing;

    opens io.github.bearisbug.synaxis.testing.test to org.junit.platform.commons;
}