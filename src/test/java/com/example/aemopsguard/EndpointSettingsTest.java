package com.example.aemopsguard;

import com.example.aemopsguard.config.EnvironmentType;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointSettingsTest {

    @TempDir
    Path tmp;

    private HttpServer server;
    private String port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/content/ok.html", ex -> send(ex, 200, "<html>ok</html>"));
        server.createContext("/prefix/content/ok.html", ex -> send(ex, 200, "<html>ok</html>"));
        server.createContext("/custom/felix/status.json", ex -> send(ex, 200,
                "{\"data\":[{\"symbolicName\":\"com.example.core\",\"state\":\"Active\",\"fragment\":false}]}"));
        server.createContext("/prefix/system/console/bundles.json", ex -> send(ex, 200,
                "{\"data\":[{\"symbolicName\":\"com.example.core\",\"state\":\"Active\",\"fragment\":false}]}"));
        server.createContext("/libs/granite/security/currentuser.json", ex -> send(ex, 200,
                "{\"authorizableId\":\"admin\",\"groups\":[]}"));
        server.createContext("/libs/granite/core/content/login.html", ex -> send(ex, 200,
                "<html><form><input name=\"j_username\"></form></html>"));
        server.createContext("/prefix/libs/granite/core/content/login.html", ex -> send(ex, 200,
                "<html><form><input name=\"j_username\"></form></html>"));
        server.createContext("/prefix/libs/granite/security/currentuser.json", ex -> send(ex, 200,
                "{\"authorizableId\":\"admin\",\"groups\":[]}"));
        server.start();
        port = String.valueOf(server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private Path writeConfig(String extra, String urlPath) throws IOException {
        Path file = tmp.resolve("local.env");
        Files.writeString(file, "AEM_LOCAL_URL=http://127.0.0.1:" + port + urlPath + "\n"
                + "AEM_LOCAL_USER=admin\n" + "AEM_LOCAL_SITE_PAGE=/content/ok.html\n" + extra);
        return file;
    }

    private static Map<String, String> pw() {
        return Map.of("AEM_LOCAL_PASSWORD", "admin");
    }

    private record Run(int code, String out, String err) {
    }

    private static Run run(String[] args, Map<String, String> env) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, env,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void bundlePathDefaultsToFelixBundleList() {
        assertEquals("/system/console/bundles.json", Main.pingPath(Map.of(), EnvironmentType.LOCAL));
    }

    @Test
    void bundlePathIsReadPerEnvironment() {
        Map<String, String> settings = Map.of("AEM_LOCAL_PING_PATH", " /custom/felix/status.json ");
        assertEquals("/custom/felix/status.json", Main.pingPath(settings, EnvironmentType.LOCAL));
    }

    @Test
    void sanityUsesConfiguredBundlePath() throws IOException {
        Path cfg = writeConfig("AEM_LOCAL_PING_PATH=/custom/felix/status.json\n"
                + "AEM_LOCAL_OSGI_REQUIRED_BUNDLES=com.example.core\n", "");
        Run r = run(new String[] {"--config", cfg.toString(), "--env", "local", "sanity"}, pw());

        assertEquals(0, r.code());
        assertTrue(r.out().contains("PASS  com.example.core"));
    }

    @Test
    void invalidBundlePathIsConfigError() throws IOException {
        Path cfg = writeConfig("AEM_LOCAL_PING_PATH=system/console/bundles.json\n", "");
        Run r = run(new String[] {"--config", cfg.toString(), "--env", "local", "sanity"}, pw());

        assertEquals(2, r.code());
        assertTrue(r.err().contains("must start with '/'"));
    }

    @Test
    void sanityHonoursContextPathInUrl() throws IOException {
        Path cfg = writeConfig("", "/prefix");
        Run r = run(new String[] {"--config", cfg.toString(), "--env", "local", "sanity"}, pw());

        assertEquals(0, r.code());
        assertTrue(r.out().contains("PASS  /content/ok.html"));
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
