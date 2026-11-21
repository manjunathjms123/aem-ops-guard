package com.example.aemopsguard.drift;

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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriftTest {

    @TempDir
    Path tmp;

    private static EnvironmentSnapshot snap(String name, Map<String, String> bundles, Set<String> configured,
                                            Set<String> groups, long users, long groupCount, String... notes) {
        return new EnvironmentSnapshot(name, bundles, configured, groups, users, groupCount, List.of(notes));
    }

    private static Finding find(List<Finding> findings, String subject) {
        return findings.stream().filter(f -> f.subject().equals(subject)).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- comparer

    @Test
    void identicalEnvironmentsMatchInEveryCategory() {
        EnvironmentSnapshot qa = snap("qa", Map.of("core", "Active"), Set.of("a.Svc"), Set.of("content-authors"), 171, 21);
        EnvironmentSnapshot uat = snap("uat", Map.of("core", "Active"), Set.of("a.Svc"), Set.of("content-authors"), 171, 21);

        List<Finding> findings = DriftComparer.compare(List.of(qa, uat));

        assertTrue(findings.stream().allMatch(Finding::passed), findings.toString());
    }

    @Test
    void bundleStateDifferenceNamesBothValues() {
        EnvironmentSnapshot qa = snap("qa", Map.of("aem-guides-wknd.core", "Active"), Set.of(), Set.of(), 1, 1);
        EnvironmentSnapshot uat = snap("uat", Map.of("aem-guides-wknd.core", "Resolved"), Set.of(), Set.of(), 1, 1);

        Finding f = find(DriftComparer.compare(List.of(qa, uat)), "bundle state");

        assertFalse(f.passed());
        assertTrue(f.detail().contains("aem-guides-wknd.core: QA=Active, UAT=Resolved"), f.detail());
    }

    @Test
    void bundleMissingInOneEnvironmentIsAbsent() {
        EnvironmentSnapshot qa = snap("qa", Map.of("x.bundle", "Active"), Set.of(), Set.of(), 1, 1);
        EnvironmentSnapshot prod = snap("prod", Map.of(), Set.of(), Set.of(), 1, 1);

        Finding f = find(DriftComparer.compare(List.of(qa, prod)), "bundle state");

        assertTrue(f.detail().contains("x.bundle: QA=Active, PROD=absent"), f.detail());
    }

    @Test
    void configurationOnlyInBaselineIsFlagged() {
        EnvironmentSnapshot qa = snap("qa", Map.of(), Set.of("a.Svc"), Set.of(), 1, 1);
        EnvironmentSnapshot uat = snap("uat", Map.of(), Set.of(), Set.of(), 1, 1);

        Finding f = find(DriftComparer.compare(List.of(qa, uat)), "configuration");

        assertFalse(f.passed());
        assertTrue(f.detail().contains("a.Svc: QA=present, UAT=absent"), f.detail());
    }

    @Test
    void groupMissingInUatIsPermissionDrift() {
        EnvironmentSnapshot qa = snap("qa", Map.of(), Set.of(), Set.of("content-authors", "administrators"), 1, 2);
        EnvironmentSnapshot uat = snap("uat", Map.of(), Set.of(), Set.of("content-authors"), 1, 1);

        List<Finding> findings = DriftComparer.compare(List.of(qa, uat));

        Finding groups = find(findings, "group");
        assertFalse(groups.passed());
        assertTrue(groups.detail().contains("administrators: QA=present, UAT=absent"), groups.detail());
        assertFalse(find(findings, "group count").passed());
    }

    @Test
    void userCountDifferenceIsFlagged() {
        EnvironmentSnapshot qa = snap("qa", Map.of(), Set.of(), Set.of(), 171, 21);
        EnvironmentSnapshot uat = snap("uat", Map.of(), Set.of(), Set.of(), 160, 21);

        Finding f = find(DriftComparer.compare(List.of(qa, uat)), "user count");

        assertFalse(f.passed());
        assertEquals("QA=171, UAT=160", f.detail());
    }

    @Test
    void unavailableCountIsNotComparableRatherThanAMatch() {
        EnvironmentSnapshot qa = snap("qa", Map.of(), Set.of(), Set.of(), 171, 21);
        EnvironmentSnapshot uat = snap("uat", Map.of(), Set.of(), Set.of(), -1, -1);

        Finding f = find(DriftComparer.compare(List.of(qa, uat)), "user count");

        assertFalse(f.passed());
        assertEquals("not comparable: unavailable in UAT", f.detail());
    }

    @Test
    void unreadDataIsReportedAsFailureNotSilentlySkipped() {
        EnvironmentSnapshot qa = snap("qa", Map.of(), Set.of(), Set.of(), 1, 1);
        EnvironmentSnapshot uat = snap("uat", Map.of(), Set.of(), Set.of(), 1, 1, "bundles unavailable (HTTP 503)");

        List<Finding> findings = DriftComparer.compare(List.of(qa, uat));

        Finding note = find(findings, "UAT data");
        assertFalse(note.passed());
        assertEquals("bundles unavailable (HTTP 503)", note.detail());
    }

    @Test
    void needsAtLeastTwoEnvironments() {
        assertThrows(() -> DriftComparer.compare(List.of(snap("qa", Map.of(), Set.of(), Set.of(), 1, 1))));
    }

    private static void assertThrows(Runnable r) {
        try {
            r.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    // ---------------------------------------------------------------- collector

    private HttpServer server;
    private AemHttpClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/system/console/bundles.json", ex -> send(ex, 200,
                "{\"data\":[{\"symbolicName\":\"aem-guides-wknd.core\",\"state\":\"Active\",\"fragment\":false},"
                        + "{\"symbolicName\":\"other\",\"state\":\"Resolved\",\"fragment\":false}]}"));
        server.createContext("/system/console/configMgr", ex -> {
            if ("/system/console/configMgr".equals(ex.getRequestURI().getPath())) {
                send(ex, 200, "var s = [{\"id\":\"com.a.One\",\"name\":\"One\",\"has_config\":true},"
                        + "{\"id\":\"com.a.Two\",\"name\":\"Two\"}];");
            } else {
                send(ex, 404, "not found");
            }
        });
        server.createContext("/bin/querybuilder.json", ex -> {
            String query = ex.getRequestURI().getRawQuery();
            if (query != null && query.contains("rep%3AGroup")) {
                send(ex, 200, "{\"total\":2,\"success\":true,\"hits\":["
                        + "{\"rep:authorizableId\":\"content-authors\"},"
                        + "{\"rep:authorizableId\":\"administrators\"}]}");
            } else {
                send(ex, 200, "{\"total\":5,\"success\":true,\"hits\":[]}");
            }
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
    void collectorReadsBundlesConfigurationsGroupsAndCounts() {
        EnvironmentSnapshot s = SnapshotCollector.collect("local", client, "/system/console/bundles.json",
                "/system/console/configMgr", "/bin/querybuilder.json");

        assertEquals("Active", s.bundleStates().get("aem-guides-wknd.core"));
        assertEquals(Set.of("com.a.One"), s.configuredServices());
        assertEquals(Set.of("content-authors", "administrators"), s.groups());
        assertEquals(5, s.userCount());
        assertEquals(2, s.groupCount());
        assertTrue(s.notes().isEmpty(), s.notes().toString());
    }

    @Test
    void collectorRecordsAnUnreachableReadAsANoteAndKeepsTheRest() {
        EnvironmentSnapshot s = SnapshotCollector.collect("local", client, "/system/console/missing.json",
                "/system/console/configMgr", "/bin/querybuilder.json");

        assertTrue(s.bundleStates().isEmpty());
        assertEquals(Set.of("com.a.One"), s.configuredServices());
        assertEquals(5, s.userCount());
        assertTrue(s.notes().stream().anyMatch(n -> n.startsWith("bundles unavailable")), s.notes().toString());
    }

    // ---------------------------------------------------------------- command

    private static int runDrift(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        return Main.run(args, env, out, err);
    }

    @Test
    void driftNeedsAtLeastTwoEnvironments() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = runDrift(new String[] {"--envs", "qa", "drift"}, Map.of(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("at least two environments"));
    }

    @Test
    void driftReportsAMissingEnvironmentFileAsConfigError() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"--config-dir", tmp.toString(), "--envs", "qa,prod", "drift"},
                Map.of(),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("config file not found"));
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
