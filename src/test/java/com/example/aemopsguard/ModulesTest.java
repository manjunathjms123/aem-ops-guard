package com.example.aemopsguard;

import com.example.aemopsguard.check.Finding;
import com.example.aemopsguard.config.EnvironmentConfig;
import com.example.aemopsguard.config.EnvironmentType;
import com.example.aemopsguard.http.AemHttpClient;
import com.example.aemopsguard.osgi.OsgiR6Check;
import com.example.aemopsguard.permissions.PermissionAudit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
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

class ModulesTest {

    private static final String BUNDLES = """
            {"data":[
              {"symbolicName":"com.example.core","state":"Active","fragment":false},
              {"symbolicName":"com.example.broken","state":"Resolved","fragment":false},
              {"symbolicName":"com.example.frag","state":"Resolved","fragment":true}
            ]}
            """;

    /** Three services; two of them have a configuration. */
    private static final String CONFIG_LISTING =
            "var services = [{\"id\":\"com.a.One\",\"name\":\"One\",\"has_config\":true},"
            + "{\"id\":\"com.a.Two\",\"name\":\"Two\"},"
            + "{\"id\":\"com.a.Three\",\"name\":\"Three\",\"has_config\":true}];";

    @TempDir
    Path tmp;

    private HttpServer server;
    private AemHttpClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/system/console/bundles.json", ex -> send(ex, 200, BUNDLES));
        server.createContext("/system/console/configMgr/com.example.Svc.json", ex -> send(ex, 200, "{\"key\":\"v\"}"));
        server.createContext("/system/console/configMgr/com.example.Empty.json", ex -> send(ex, 200, "{}"));
        server.createContext("/system/console/configMgr/com.example.Html.json", ex -> send(ex, 200, "<html>"));
        // The Configuration Manager page: serves the listing at the exact path, 404 for anything else.
        server.createContext("/system/console/configMgr", ex -> {
            if ("/system/console/configMgr".equals(ex.getRequestURI().getPath())) {
                send(ex, 200, CONFIG_LISTING);
            } else {
                send(ex, 404, "not found");
            }
        });
        server.createContext("/libs/granite/security/currentuser.json", ex -> send(ex, 200,
                "{\"authorizableId\":\"ops-bot\",\"groups\":[\"everyone\",\"content-authors\"]}"));
        server.createContext("/bin/querybuilder.json", ex -> send(ex, 200,
                ex.getRequestURI().getQuery() != null && ex.getRequestURI().getQuery().contains("rep%3AGroup")
                        || ex.getRequestURI().getQuery() != null && ex.getRequestURI().getQuery().contains("rep:Group")
                        ? "{\"total\":21,\"success\":true,\"hits\":[]}"
                        : "{\"total\":171,\"success\":true,\"hits\":[]}"));
        server.start();
        EnvironmentConfig cfg = new EnvironmentConfig("local", EnvironmentType.LOCAL,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "ops-bot", "pw");
        client = new AemHttpClient(cfg);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static Finding find(List<Finding> findings, String subject) {
        return findings.stream().filter(f -> f.subject().equals(subject)).findFirst().orElseThrow();
    }

    @Test
    void osgiRequiredActiveBundlePasses() {
        var result = new OsgiR6Check(client, "/system/console/bundles.json", "/system/console/configMgr",
                List.of("com.example.core"), List.of()).run();
        assertTrue(find(result.findings(), "com.example.core").passed());
    }

    @Test
    void osgiRequiredBundleNotActiveFails() {
        var result = new OsgiR6Check(client, "/system/console/bundles.json", "/system/console/configMgr",
                List.of("com.example.broken", "com.example.missing"), List.of()).run();
        assertFalse(find(result.findings(), "com.example.broken").passed());
        assertTrue(find(result.findings(), "com.example.broken").detail().contains("Resolved"));
        assertTrue(find(result.findings(), "com.example.missing").detail().contains("not installed"));
    }

    @Test
    void osgiUnhealthyCountIsInformationalAndCountsOnlyNonFragments() {
        var result = new OsgiR6Check(client, "/system/console/bundles.json", "/system/console/configMgr",
                List.of(), List.of()).run();
        Finding list = find(result.findings(), "bundle list");
        assertTrue(list.passed());
        assertTrue(list.detail().contains("1 non-fragment bundles not Active"));
    }

    @Test
    void osgiConfigPresenceChecksPidsAtConfiguredPath() {
        var result = new OsgiR6Check(client, "/system/console/bundles.json", "/system/console/configMgr",
                List.of(), List.of("com.example.Svc", "com.example.Empty", "com.example.Html", "com.example.Gone")).run();
        assertTrue(find(result.findings(), "com.example.Svc").passed());
        assertFalse(find(result.findings(), "com.example.Empty").passed());
        assertFalse(find(result.findings(), "com.example.Html").passed());
        assertTrue(find(result.findings(), "com.example.Gone").detail().contains("HTTP 404"));
    }

    @Test
    void osgiSnapshotKeepsRawBundleList() {
        var result = new OsgiR6Check(client, "/system/console/bundles.json", "/system/console/configMgr",
                List.of(), List.of()).run();
        assertEquals(3, result.snapshot().path("bundles").size());
    }

    @Test
    void osgiReportsHowManyServicesHaveAConfiguration() {
        var result = new OsgiR6Check(client, "/system/console/bundles.json", "/system/console/configMgr",
                List.of(), List.of()).run();
        Finding list = find(result.findings(), "bundle list");
        assertTrue(list.passed());
        assertTrue(list.detail().contains("2 of 3 services have a configuration"));
        assertEquals(2, result.snapshot().path("configuredServices").asInt());
    }

    @Test
    void osgiUnreachableEndpointFailsInsteadOfThrowing() {
        AemHttpClient wrong = new AemHttpClient(new EnvironmentConfig("local", EnvironmentType.LOCAL,
                URI.create("http://127.0.0.1:1"), "u", "p"));
        var result = new OsgiR6Check(wrong, "/system/console/bundles.json", "/system/console/configMgr",
                List.of(), List.of()).run();
        assertFalse(result.findings().get(0).passed());
    }

    @Test
    void permissionsIdentityAndGroupsAreChecked() {
        var result = new PermissionAudit(client, "/libs/granite/security/currentuser.json", "ops-bot",
                List.of("content-authors", "administrators")).run();
        assertTrue(find(result.findings(), "identity").passed());
        assertTrue(find(result.findings(), "group content-authors").passed());
        assertFalse(find(result.findings(), "group administrators").passed());
    }

    @Test
    void permissionsWrongUserFails() {
        var result = new PermissionAudit(client, "/libs/granite/security/currentuser.json", "someone-else",
                List.of()).run();
        Finding identity = find(result.findings(), "identity");
        assertFalse(identity.passed());
        assertTrue(identity.detail().contains("ops-bot"));
    }

    @Test
    void permissionsReportsTheUserCountFromTheQueryBuilderTotal() {
        var result = new PermissionAudit(client, "/libs/granite/security/currentuser.json", "ops-bot",
                List.of()).run();
        Finding identity = find(result.findings(), "identity");
        assertTrue(identity.passed());
        assertTrue(identity.detail().contains("171 users under /home/users"));
        assertTrue(identity.detail().contains("21 groups under /home/groups"));
        assertEquals(171, result.snapshot().path("userCount").asLong());
        assertEquals(21, result.snapshot().path("groupCount").asLong());
    }

    @Test
    void permissionsSnapshotKeepsCurrentUserResponse() {
        var result = new PermissionAudit(client, "/libs/granite/security/currentuser.json", "ops-bot",
                List.of()).run();
        assertEquals("ops-bot", result.snapshot().path("currentUser").path("authorizableId").asText());
    }

    @Test
    void snapshotFileIsWrittenAsJson() throws IOException {
        ObjectNode snap = JsonNodeFactory.instance.objectNode();
        snap.put("environment", "local");
        Path saved = Main.writeSnapshot(snap, "local", tmp.resolve("snapshots"));

        assertTrue(Files.exists(saved));
        assertTrue(saved.getFileName().toString().startsWith("local-"));
        Map<?, ?> parsed = new ObjectMapper().readValue(Files.readString(saved), Map.class);
        assertEquals("local", parsed.get("environment"));
    }

    private static void send(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
        ex.close();
    }
}
