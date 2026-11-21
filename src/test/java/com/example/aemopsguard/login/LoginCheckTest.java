package com.example.aemopsguard.login;

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

class LoginCheckTest {

    private static final String LOGIN_PAGE = "/libs/granite/core/content/login.html";
    private static final String SUBMIT = "/j_security_check";

    @TempDir
    Path tmp;

    private HttpServer server;
    private String port;
    private String lastAuthorization;

    /** Signed-in requests get a page without the form, as AEM can serve, so the check must be anonymous. */
    private static final String SIGNED_IN_PAGE = "<html>signed in</html>";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(LOGIN_PAGE, ex -> send(ex, 200,
                ex.getRequestHeaders().getFirst("Authorization") == null
                        ? "<html><form action=\"/j_security_check\"><input name=\"j_username\">"
                                + "<input type=\"password\" name=\"j_password\"></form></html>"
                        : SIGNED_IN_PAGE, null));
        server.createContext(SUBMIT, ex -> {
            lastAuthorization = ex.getRequestHeaders().getFirst("Authorization");
            String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (form.contains("j_username=admin") && form.contains("j_password=right-pw")) {
                send(ex, 302, "", "login-token=abc123; Path=/; HttpOnly");
            } else {
                ex.getResponseHeaders().add("Location", LOGIN_PAGE + "?error=1");
                send(ex, 302, "", null);
            }
        });
        server.createContext("/system/login-missing", ex -> send(ex, 404, "not found", null));
        server.start();
        port = String.valueOf(server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AemHttpClient client(String password) {
        return new AemHttpClient(new EnvironmentConfig("local", EnvironmentType.LOCAL,
                URI.create("http://127.0.0.1:" + port), "admin", password));
    }

    private static Finding find(List<Finding> findings, String prefix) {
        return findings.stream().filter(f -> f.subject().startsWith(prefix)).findFirst().orElseThrow();
    }

    @Test
    void correctPasswordPassesBothChecks() {
        List<Finding> findings = new LoginCheck(client("right-pw"), LOGIN_PAGE, SUBMIT, "admin", "right-pw").run();

        assertTrue(find(findings, "login page").passed());
        assertTrue(find(findings, "login as").passed());
        assertTrue(find(findings, "login as").detail().contains("login-token issued"));
    }

    @Test
    void wrongPasswordFailsWithoutRevealingIt() {
        List<Finding> findings = new LoginCheck(client("wrong-pw"), LOGIN_PAGE, SUBMIT, "admin", "wrong-pw").run();

        Finding login = find(findings, "login as");
        assertFalse(login.passed());
        assertTrue(login.detail().contains("no login-token cookie"));
        for (Finding f : findings) {
            assertFalse(f.toString().contains("wrong-pw"));
        }
    }

    @Test
    void loginPageWithoutFormStopsEarly() {
        List<Finding> findings = new LoginCheck(client("right-pw"), "/system/login-missing", SUBMIT,
                "admin", "right-pw").run();

        assertEquals(1, findings.size());
        assertFalse(findings.get(0).passed());
    }

    @Test
    void formLoginSendsNoBasicAuthHeader() {
        new LoginCheck(client("right-pw"), LOGIN_PAGE, SUBMIT, "admin", "right-pw").run();

        assertEquals(null, lastAuthorization);
    }

    @Test
    void loginCommandExitsZeroForCorrectPasswordAndOneForWrong() throws IOException {
        Path cfg = tmp.resolve("local.env");
        Files.writeString(cfg, "AEM_LOCAL_URL=http://127.0.0.1:" + port + "\nAEM_LOCAL_USER=admin\n");

        int good = runLogin(cfg, "right-pw");
        int bad = runLogin(cfg, "wrong-pw");

        assertEquals(0, good);
        assertEquals(1, bad);
    }

    @Test
    void loginCommandRejectsBadLoginPathSetting() throws IOException {
        Path cfg = tmp.resolve("local.env");
        Files.writeString(cfg, "AEM_LOCAL_URL=http://127.0.0.1:" + port + "\nAEM_LOCAL_USER=admin\n"
                + "AEM_LOCAL_LOGIN_PATH=libs/login.html\n");

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[] {"--config", cfg.toString(), "--env", "local", "login"},
                Map.of("AEM_LOCAL_PASSWORD", "right-pw"),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("AEM_LOCAL_LOGIN_PATH"));
    }

    private int runLogin(Path cfg, String password) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        return Main.run(new String[] {"--config", cfg.toString(), "--env", "local", "login"},
                Map.of("AEM_LOCAL_PASSWORD", password),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String body, String setCookie) throws IOException {
        if (setCookie != null) {
            ex.getResponseHeaders().add("Set-Cookie", setCookie);
        }
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
