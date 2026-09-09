package dev.gameengine.utils.url;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class URLUtilsTest {

    @Test
    public void errorResponseWithoutBody() throws IOException {
        final String previousNonProxy = System.getProperty("http.nonProxyHosts");
        System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1");
        final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(429, -1); // Rate limited, no response body
            exchange.close();
        });
        server.start();
        try {
            // getErrorStream() is null here, reading it used to throw a NullPointerException that
            // escaped every `catch (IOException)` up the stack
            assertEquals("", URLUtils.getText("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
        } finally {
            server.stop(0);
            if (previousNonProxy == null) System.clearProperty("http.nonProxyHosts");
            else System.setProperty("http.nonProxyHosts", previousNonProxy);
        }
    }
}
