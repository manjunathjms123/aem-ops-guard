package com.example.aemopsguard.felix;

import com.example.aemopsguard.Main;
import com.example.aemopsguard.check.Finding;
import com.example.aemopsguard.config.EnvironmentConfig;
import com.example.aemopsguard.config.EnvironmentType;
import com.example.aemopsguard.http.AemHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FelixEndpointTest {

    @TempDir
    Path tmp;

    private HttpServer server;
    private String port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Every console path answers 200 except /system/console/logs, which is blocked.
        server.createContext("/system/console", ex -> send(ex, 200, "ok"));
        server.createContext("/system/console/logs", ex -> send(ex, 403, "forbidden"));
        server.start();
        port = String.valueOf(server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void everyEndpointHasADefaultUnderSystemConsoleOrIsTheBundleList() {
        for (FelixEndpoint endpoint : FelixEndpoint.values()) {
            assertTrue(endpoint.defaultPath().startsWith("/system/console"), endpoint.name());
        }
        assertEquals(18, FelixEndpoint.values().length);
    }

    @Test
    void resolveUsesOverrideWhenSetAndDefaultOtherwise() {
        Map<String, String> settings = Map.of("AEM_QA_FELIX_LOGS_PATH", " /system/console/logs2 ");

        assertEquals("/system/console/logs2", FelixEndpoint.LOGS.resolve(settings, EnvironmentType.QA));
        assertEquals("/system/console/memory", FelixEndpoint.MEMORY.resolve(settings, EnvironmentType.QA));
        assertEquals("AEM_QA_FELIX_LOGS_PATH", FelixEndpoint.LOGS.settingName(EnvironmentType.QA));
    }

    @Test
    void bundlesAndConfigManagerReuseTheExistingKeys() {
        assertEquals("AEM_QA_PING_PATH", FelixEndpoint.BUNDLES.settingName(EnvironmentType.QA));
        assertEquals("AEM_QA_OSGI_CONFIG_PATH", FelixEndpoint.CONFIG_MANAGER.settingName(EnvironmentType.QA));
    }

    @Test
    void probeReportsStatusPerEndpoint() {
        AemHttpClient client = client();
        Finding ok = FelixProbe.probe(client, "Console root", "/system/console");
        Finding blocked = FelixProbe.probe(client, "Log levels", "/system/console/logs");

        assertTrue(ok.passed());
        assertEquals("HTTP 200", ok.detail());
        assertFalse(blocked.passed());
        assertEquals("HTTP 403", blocked.detail());
    }

    @Test
    void probeAllCoversEveryEndpoint() {
        List<Finding> findings = FelixProbe.probeAll(client(), Map.of(), EnvironmentType.LOCAL);
        assertEquals(FelixEndpoint.values().length, findings.size());
    }

    @Test
    void felixCommandExitsOneWhenAnyEndpointIsBlocked() throws IOException {
        Path cfg = tmp.resolve("local.env");
        Files.writeString(cfg, "AEM_LOCAL_URL=http://127.0.0.1:" + port + "\nAEM_LOCAL_USER=admin\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"--config", cfg.toString(), "--env", "local", "felix"},
                Map.of("AEM_LOCAL_PASSWORD", "admin"),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);

        assertEquals(1, code);
        assertTrue(text.contains("FAIL  Log levels"));
        assertTrue(text.contains("Result: 17/18 endpoints answered"));
    }

    private AemHttpClient client() {
        return new AemHttpClient(new EnvironmentConfig("local", EnvironmentType.LOCAL,
                URI.create("http://127.0.0.1:" + port), "admin", "admin"));
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
        ex.close();
    }
}
