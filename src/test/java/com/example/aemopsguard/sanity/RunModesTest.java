package com.example.aemopsguard.sanity;

import com.example.aemopsguard.config.EnvironmentConfig;
import com.example.aemopsguard.config.EnvironmentType;
import com.example.aemopsguard.http.AemHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunModesTest {

    private static final String SETTINGS = "[\"Apache Sling Settings\",\"Sling ID = abc\","
            + "\"Run Modes = [s7connect, crx3, author, sdk, live, crx3tar]\"]";

    private HttpServer server;
    private AemHttpClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(RunModes.PATH, ex -> {
            byte[] bytes = SETTINGS.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
            ex.close();
        });
        server.start();
        client = new AemHttpClient(new EnvironmentConfig("local", EnvironmentType.LOCAL,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "admin", "admin"));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void parsesTheRunModeList() {
        assertEquals(List.of("s7connect", "crx3", "author", "sdk", "live", "crx3tar"),
                RunModes.parse(SETTINGS));
    }

    @Test
    void summaryReadsTheEndpointAndJoinsTheModes() {
        assertEquals("s7connect, crx3, author, sdk, live, crx3tar", RunModes.summary(client));
    }

    @Test
    void noRunModeLineGivesAClearNote() {
        assertTrue(RunModes.parse("[\"Sling ID = abc\"]").isEmpty());
    }

    @Test
    void notJsonGivesNoModesRatherThanAnException() {
        assertTrue(RunModes.parse("<html>not json").isEmpty());
    }
}
