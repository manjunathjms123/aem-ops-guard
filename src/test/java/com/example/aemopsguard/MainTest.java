package com.example.aemopsguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @TempDir
    Path tmp;

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

    private Path writeConfig() throws IOException {
        Path file = tmp.resolve("local.env");
        Files.writeString(file, "AEM_LOCAL_URL=http://127.0.0.1:1\nAEM_LOCAL_USER=admin\n");
        return file;
    }

    @Test
    void helpPrintsUsageAndSucceeds() {
        Run r = run(new String[] {"help"}, Map.of());
        assertEquals(0, r.code());
        assertTrue(r.out().contains("Usage:"));
    }

    @Test
    void noCommandIsUsageError() {
        Run r = run(new String[] {"--env", "local"}, Map.of());
        assertEquals(2, r.code());
        assertTrue(r.err().contains("missing command (sanity, felix, login, snapshot, diff or drift)"));
    }

    @Test
    void pingIsNoLongerACommand() {
        Run r = run(new String[] {"--env", "local", "sanity", "ping"}, Map.of());
        assertEquals(2, r.code());
        assertTrue(r.err().contains("unexpected argument: ping"));
        assertFalse(r.out().contains("ping"));
    }

    @Test
    void unknownEnvironmentIsUsageError() {
        Run r = run(new String[] {"--env", "staging", "sanity"}, Map.of());
        assertEquals(2, r.code());
        assertTrue(r.err().contains("unknown environment: staging"));
    }

    @Test
    void missingConfigFileIsConfigError() {
        Run r = run(new String[] {"--config", tmp.resolve("nope.env").toString(), "--env", "local", "sanity"},
                Map.of("AEM_LOCAL_PASSWORD", "admin"));
        assertEquals(2, r.code());
        assertTrue(r.err().contains("config file not found"));
    }

    @Test
    void environmentWithoutPasswordIsNotReady() throws IOException {
        Run r = run(new String[] {"--config", writeConfig().toString(), "--env", "local", "sanity"}, Map.of());
        assertEquals(2, r.code());
        assertTrue(r.err().contains("AEM_LOCAL_PASSWORD"));
    }
}
