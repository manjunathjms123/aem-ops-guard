package com.example.aemopsguard;

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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SanityCommandTest {

    @TempDir
    Path tmp;

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/content/good.html", ex -> send(ex, 200, "<html><body>ok</body></html>"));
        server.createContext("/content/bad.html", ex -> send(ex, 500, "boom"));
        server.createContext("/crx/de/index.jsp", ex -> send(ex, 200, "<html><body>CRX DE</body></html>"));
        server.createContext("/crx/packmgr/index.jsp", ex -> send(ex, 200, "<html><body>Package Manager</body></html>"));
        server.createContext("/libs/granite/core/content/login.html", ex -> send(ex, 200,
                "<html><form><input name=\"j_username\"></form></html>"));
        server.createContext("/crx/packmgr/login.jsp", ex -> send(ex, 200,
                "<html><form><input name=\"j_username\"></form></html>"));
        server.createContext("/system/console/bundles.json", ex -> send(ex, 200,
                "{\"data\":[{\"symbolicName\":\"com.example.core\",\"state\":\"Active\",\"fragment\":false}]}"));
        server.createContext("/libs/granite/security/currentuser.json", ex -> send(ex, 200,
                "{\"authorizableId\":\"admin\",\"groups\":[\"administrators\"]}"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private Path writeConfig(String sanityPages) throws IOException {
        Path file = tmp.resolve("aem-environments.env");
        String text = "AEM_LOCAL_URL=" + baseUrl + "\nAEM_LOCAL_USER=admin\n";
        if (sanityPages != null) {
            text += "AEM_LOCAL_SITE_PAGE=" + sanityPages + "\n";
        }
        Files.writeString(file, text);
        return file;
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

    private static final Map<String, String> PW = Map.of("AEM_LOCAL_PASSWORD", "admin");

    @Test
    void pagesComeFromConfigFileWhenNoPageArgument() throws IOException {
        Path cfg = writeConfig("/content/good.html");
        Run r = run(new String[] {"--config", cfg.toString(), "--env", "local", "sanity"}, PW);

        assertEquals(0, r.code());
        assertTrue(r.out().contains("PASS  /content/good.html"));
        assertTrue(r.out().contains("Result: 4/4 checks passed"));
    }

    @Test
    void crxDePageIsCheckedInAdditionToSitePages() throws IOException {
        Path cfg = writeConfig("/content/good.html");
        Files.writeString(cfg, Files.readString(cfg) + "AEM_LOCAL_CRX_DE_PAGE=/crx/de/index.jsp\n");
        Run r = run(new String[] {"--config", cfg.toString(), "--env", "local", "sanity"}, PW);

        assertEquals(0, r.code());
        assertTrue(r.out().contains("PASS  /crx/de/index.jsp"));
        assertTrue(r.out().contains("Result: 5/5 checks passed"));
    }

    @Test
    void packageManagerPageIsCheckedWithTheOtherExtraPages() throws IOException {
        Path cfg = writeConfig("/content/good.html");
        Files.writeString(cfg, Files.readString(cfg)
                + "AEM_LOCAL_CRX_DE_PAGE=/crx/de/index.jsp\n"
                + "AEM_LOCAL_PACK_MGR_PAGE=/crx/packmgr/index.jsp\n");
        Run r = run(new String[] {"--config", cfg.toString(), "--env", "local", "sanity"}, PW);

        assertEquals(0, r.code());
        assertTrue(r.out().contains("PASS  /crx/packmgr/index.jsp"));
        assertTrue(r.out().contains("Result: 6/6 checks passed"));
    }

    @Test
    void missingCrxDePageAndLoginScreenPackageManagerFailTheRun() throws IOException {
        Path cfg = writeConfig("/content/good.html");
        Files.writeString(cfg, Files.readString(cfg)
                + "AEM_LOCAL_CRX_DE_PAGE=/crx/de/gone.jsp\n"
                + "AEM_LOCAL_PACK_MGR_PAGE=/crx/packmgr/login.jsp\n");
        Run r = run(new String[] {"--config", cfg.toString(), "--env", "local", "sanity"}, PW);

        assertEquals(1, r.code());
        assertTrue(r.out().contains("FAIL  /crx/de/gone.jsp  HTTP 404"));
        assertTrue(r.out().contains("FAIL  /crx/packmgr/login.jsp  login form shown"));
        assertTrue(r.out().contains("PASS  /content/good.html"));
        assertTrue(r.out().contains("Result: 4/6 checks passed"));
    }

    @Test
    void pageOptionIsRemovedAndPagesComeOnlyFromConfig() throws IOException {
        Path cfg = writeConfig("/content/good.html");
        Run r = run(new String[] {"--config", cfg.toString(), "--env", "local", "--page", "/content/bad.html", "sanity"}, PW);

        assertEquals(2, r.code());
        assertTrue(r.err().contains("unknown argument: --page"));
    }

    @Test
    void anyFailingPageGivesExitCodeOne() throws IOException {
        Path cfg = writeConfig("/content/good.html,/content/bad.html");
        Run r = run(new String[] {"--config", cfg.toString(), "--env", "local", "sanity"}, PW);

        assertEquals(1, r.code());
        assertTrue(r.out().contains("PASS  /content/good.html"));
        assertTrue(r.out().contains("FAIL  /content/bad.html"));
        assertTrue(r.out().contains("Result: 4/5 checks passed"));
    }

    @Test
    void noPagesConfiguredIsUsageError() throws IOException {
        Path cfg = writeConfig(null);
        Run r = run(new String[] {"--config", cfg.toString(), "--env", "local", "sanity"}, PW);

        assertEquals(2, r.code());
        assertTrue(r.err().contains("no pages to check"));
        assertTrue(r.err().contains("LOCAL_SITE_PAGE"));
    }

    @Test
    void configListIgnoresBlankEntries() throws IOException {
        Path cfg = writeConfig(" /content/good.html , ,/content/good.html ");
        Run r = run(new String[] {"--config", cfg.toString(), "--env", "local", "sanity"}, PW);

        assertEquals(0, r.code());
        assertTrue(r.out().contains("Result: 5/5 checks passed"));
    }


    @Test
    void sanityOutputNeverShowsPassword() throws IOException {
        Path cfg = writeConfig("/content/good.html");
        Run r = run(new String[] {"--config", cfg.toString(), "--env", "local", "sanity"},
                Map.of("AEM_LOCAL_PASSWORD", "top-secret-pw"));

        assertFalse(r.out().contains("top-secret-pw"));
        assertFalse(r.err().contains("top-secret-pw"));
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
