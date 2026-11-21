package com.example.aemopsguard.http;

import com.sun.net.httpserver.HttpServer;
import com.example.aemopsguard.config.EnvironmentConfig;
import com.example.aemopsguard.config.EnvironmentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AemHttpClientTest {

    private HttpServer server;
    private URI baseUri;
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/system/console/bundles.json", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"status\":\"ok\"}");
        });
        server.createContext("/forbidden", exchange -> respond(exchange, 403, "denied"));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/login");
            respond(exchange, 302, "");
        });
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AemHttpClient client() {
        return new AemHttpClient(new EnvironmentConfig(
                "local", EnvironmentType.LOCAL, baseUri, "admin", "admin"));
    }

    @Test
    void getReturnsStatusAndBody() {
        AemResponse response = client().get("/system/console/bundles.json");

        assertEquals(200, response.status());
        assertTrue(response.isSuccessful());
        assertEquals("{\"status\":\"ok\"}", response.body());
    }

    @Test
    void sendsBasicAuthHeader() {
        client().get("/system/console/bundles.json");

        String expected = "Basic " + Base64.getEncoder().encodeToString("admin:admin".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, lastAuthHeader.get());
    }

    @Test
    void getDoesNotThrowOnErrorStatus() {
        AemResponse response = client().get("/forbidden");

        assertEquals(403, response.status());
        assertFalse(response.isSuccessful());
    }

    @Test
    void getOkThrowsOnErrorStatus() {
        AemClientException e = assertThrows(AemClientException.class, () -> client().getOk("/forbidden"));
        assertTrue(e.getMessage().contains("HTTP 403"));
    }

    @Test
    void redirectsAreNotFollowed() {
        AemResponse response = client().get("/redirect");

        assertEquals(302, response.status());
    }

    @Test
    void rejectsPathsThatEscapeTheBaseUri() {
        AemHttpClient client = client();
        assertThrows(IllegalArgumentException.class, () -> client.get("relative"));
        assertThrows(IllegalArgumentException.class, () -> client.get("/a/../etc"));
        assertThrows(IllegalArgumentException.class, () -> client.get("/a?b=c"));
    }

    @Test
    void connectionFailureIsWrappedInAemClientException() throws IOException {
        int closedPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        AemHttpClient client = new AemHttpClient(new EnvironmentConfig(
                "local", EnvironmentType.LOCAL, URI.create("http://127.0.0.1:" + closedPort),
                "admin", "admin"));

        assertThrows(AemClientException.class, () -> client.get("/anything"));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }
}
