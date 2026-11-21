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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorSanityCheckTest {

    private HttpServer server;
    private AuthorSanityCheck check;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/content/ok.html", ex -> send(ex, 200,
                "<!DOCTYPE html><html><body>Hello</body></html>"));
        server.createContext("/content/login.html", ex -> send(ex, 200,
                "<html><form><input name=\"j_username\"></form></html>"));
        server.createContext("/content/notes.json", ex -> send(ex, 200, "{\"a\":1}"));
        server.createContext("/content/missing.html", ex -> send(ex, 404, "not found"));
        server.createContext("/content/moved.html", ex -> {
            ex.getResponseHeaders().add("Location", "/libs/granite/core/content/login.html");
            send(ex, 302, "");
        });
        server.createContext("/content/sites.html", ex -> {
            ex.getResponseHeaders().add("Location", "/content/sites.html/content");
            send(ex, 302, "");
        });
        server.createContext("/content/sites.html/content", ex -> send(ex, 200,
                "<html><body>Sites</body></html>"));
        server.createContext("/content/absolute.html", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/content/sites.html/content");
            send(ex, 302, "");
        });
        server.createContext("/content/elsewhere.html", ex -> {
            ex.getResponseHeaders().add("Location", "https://example.org/other");
            send(ex, 302, "");
        });
        server.start();

        EnvironmentConfig config = new EnvironmentConfig("local", EnvironmentType.LOCAL,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "admin", "admin");
        check = new AuthorSanityCheck(new AemHttpClient(config));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void htmlPageWithStatus200Passes() {
        AuthorSanityCheck.PageResult r = check.check("/content/ok.html");
        assertTrue(r.passed());
        assertEquals("HTTP 200, HTML page", r.detail());
    }

    @Test
    void loginFormFails() {
        AuthorSanityCheck.PageResult r = check.check("/content/login.html");
        assertFalse(r.passed());
        assertTrue(r.detail().contains("login form"));
    }

    @Test
    void nonHtmlBodyFails() {
        AuthorSanityCheck.PageResult r = check.check("/content/notes.json");
        assertFalse(r.passed());
        assertTrue(r.detail().contains("not an HTML page"));
    }

    @Test
    void notFoundFails() {
        AuthorSanityCheck.PageResult r = check.check("/content/missing.html");
        assertFalse(r.passed());
        assertEquals("HTTP 404", r.detail());
    }

    @Test
    void redirectToLoginIsReportedAsRedirect() {
        AuthorSanityCheck.PageResult r = check.check("/content/moved.html");
        assertFalse(r.passed());
        assertTrue(r.detail().contains("redirect"));
    }

    @Test
    void redirectToValidPagePassesAndReportsTheTarget() {
        AuthorSanityCheck.PageResult r = check.check("/content/sites.html");
        assertTrue(r.passed());
        assertEquals("HTTP 200, HTML page (redirected to /content/sites.html/content)", r.detail());
    }

    @Test
    void absoluteRedirectToSameAuthorPasses() {
        AuthorSanityCheck.PageResult r = check.check("/content/absolute.html");
        assertTrue(r.passed());
    }

    @Test
    void redirectToAnotherHostFails() {
        AuthorSanityCheck.PageResult r = check.check("/content/elsewhere.html");
        assertFalse(r.passed());
        assertTrue(r.detail().contains("outside this author"));
    }

    @Test
    void invalidPathFailsWithoutThrowing() {
        AuthorSanityCheck.PageResult r = check.check("content/no-leading-slash.html");
        assertFalse(r.passed());
        assertTrue(r.detail().startsWith("request failed"));
    }

    @Test
    void runReturnsOneResultPerPageInOrder() {
        List<AuthorSanityCheck.PageResult> results =
                check.run(List.of("/content/ok.html", "/content/missing.html"));
        assertEquals(2, results.size());
        assertTrue(results.get(0).passed());
        assertFalse(results.get(1).passed());
    }

    private static void send(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
        ex.close();
    }
}
