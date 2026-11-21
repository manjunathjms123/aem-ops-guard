package com.example.aemopsguard.drift;

import com.example.aemopsguard.check.Finding;
import com.example.aemopsguard.config.EnvironmentConfig;
import com.example.aemopsguard.config.EnvironmentType;
import com.example.aemopsguard.http.AemClientException;
import com.example.aemopsguard.http.AemHttpClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the snapshot and drift code against HTTPS servers. The server certificate is self-signed
 * (test-keystore.p12, created for these tests), so the test client trusts it explicitly. The default
 * client, which trusts only the Java certificate store, must reject it.
 */
class HttpsDriftTest {

    private static final String KEYSTORE_PASSWORD = "changeit";

    private HttpsServer qaServer;
    private HttpsServer uatServer;

    private static SSLContext serverTls() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = HttpsDriftTest.class.getResourceAsStream("/tls/test-keystore.p12")) {
            keyStore.load(in, KEYSTORE_PASSWORD.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, KEYSTORE_PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);
        return context;
    }

    /** A client that trusts any certificate. Test use only: it lets the self-signed server be reached. */
    private static HttpClient trustingClient() throws Exception {
        X509TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {trustAll}, null);
        return HttpClient.newBuilder().sslContext(context).build();
    }

    private static HttpsServer startServer(String bundleState) throws Exception {
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTls()));
        server.createContext("/system/console/bundles.json", ex -> send(ex, 200,
                "{\"data\":[{\"symbolicName\":\"aem-guides-wknd.core\",\"state\":\"" + bundleState
                        + "\",\"fragment\":false}]}"));
        server.createContext("/system/console/configMgr", ex -> {
            if ("/system/console/configMgr".equals(ex.getRequestURI().getPath())) {
                send(ex, 200, "var s = [{\"id\":\"com.a.One\",\"name\":\"One\",\"has_config\":true}];");
            } else {
                send(ex, 404, "not found");
            }
        });
        server.createContext("/bin/querybuilder.json", ex -> {
            String query = ex.getRequestURI().getRawQuery();
            if (query != null && query.contains("rep%3AGroup")) {
                send(ex, 200, "{\"total\":1,\"success\":true,\"hits\":[{\"rep:authorizableId\":\"content-authors\"}]}");
            } else {
                send(ex, 200, "{\"total\":7,\"success\":true,\"hits\":[]}");
            }
        });
        server.start();
        return server;
    }

    private static AemHttpClient clientFor(String name, HttpsServer server, HttpClient http) {
        EnvironmentType type = EnvironmentType.valueOf(name.toUpperCase());
        return new AemHttpClient(new EnvironmentConfig(name, type,
                URI.create("https://127.0.0.1:" + server.getAddress().getPort()), "ops-bot", "pw"), http);
    }

    @BeforeEach
    void startServers() throws Exception {
        qaServer = startServer("Active");
        uatServer = startServer("Resolved");
    }

    @AfterEach
    void stopServers() {
        qaServer.stop(0);
        uatServer.stop(0);
    }

    @Test
    void collectorReadsEverythingOverHttps() throws Exception {
        AemHttpClient qa = clientFor("qa", qaServer, trustingClient());

        EnvironmentSnapshot s = SnapshotCollector.collect("qa", qa, "/system/console/bundles.json",
                "/system/console/configMgr", "/bin/querybuilder.json");

        assertEquals("Active", s.bundleStates().get("aem-guides-wknd.core"));
        assertEquals(Set.of("com.a.One"), s.configuredServices());
        assertEquals(Set.of("content-authors"), s.groups());
        assertEquals(7, s.userCount());
        assertEquals(1, s.groupCount());
        assertTrue(s.notes().isEmpty(), s.notes().toString());
    }

    @Test
    void driftBetweenTwoHttpsEnvironmentsIsFlagged() throws Exception {
        HttpClient http = trustingClient();
        EnvironmentSnapshot qa = SnapshotCollector.collect("qa", clientFor("qa", qaServer, http),
                "/system/console/bundles.json", "/system/console/configMgr", "/bin/querybuilder.json");
        EnvironmentSnapshot uat = SnapshotCollector.collect("uat", clientFor("uat", uatServer, http),
                "/system/console/bundles.json", "/system/console/configMgr", "/bin/querybuilder.json");

        List<Finding> findings = DriftComparer.compare(List.of(qa, uat));

        Finding bundles = findings.stream().filter(f -> f.subject().equals("bundle state")).findFirst().orElseThrow();
        assertFalse(bundles.passed());
        assertTrue(bundles.detail().contains("aem-guides-wknd.core: QA=Active, UAT=Resolved"), bundles.detail());
        Finding configs = findings.stream().filter(f -> f.subject().equals("configuration")).findFirst().orElseThrow();
        assertTrue(configs.passed(), configs.detail());
    }

    @Test
    void defaultClientRejectsTheSelfSignedCertificate() {
        AemHttpClient untrusted = clientFor("qa", qaServer, HttpClient.newHttpClient());

        AemClientException e = assertThrows(AemClientException.class,
                () -> untrusted.get("/system/console/bundles.json"));
        assertTrue(e.getMessage().contains("request to"), e.getMessage());
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
