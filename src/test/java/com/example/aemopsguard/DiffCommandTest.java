package com.example.aemopsguard;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The diff command end to end: snapshot files in, exit code, allow rules, and the JSON report out. */
class DiffCommandTest {

    @TempDir
    Path tmp;

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Run(int code, String out, String err) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, Map.of(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path snapshot(String name, String wkndState) throws IOException {
        Path file = tmp.resolve(name);
        Files.writeString(file, "{\"environment\":\"qa\",\"osgi\":{\"bundles\":[{\"symbolicName\":"
                + "\"aem-guides-wknd.core\",\"state\":\"" + wkndState + "\"}]}}");
        return file;
    }

    private Path allow(String text) throws IOException {
        Path file = tmp.resolve("allow.yaml");
        Files.writeString(file, text);
        return file;
    }

    @Test
    void differencesWithoutAnAllowRuleFailTheGate() throws IOException {
        Path before = snapshot("before.json", "Active");
        Path after = snapshot("after.json", "Resolved");

        Run r = run("diff", before.toString(), after.toString());

        assertEquals(1, r.code());
        assertTrue(r.out().contains("DRIFT"), r.out());
        assertTrue(r.out().contains("BEFORE=Active, AFTER=Resolved"), r.out());
    }

    @Test
    void anAllowedDifferencePassesAndIsMarkedAllowed() throws IOException {
        Path before = snapshot("before.json", "Active");
        Path after = snapshot("after.json", "Resolved");
        Path rules = allow("allow:\n  - category: bundle state\n    key: aem-guides-wknd.core\n"
                + "    reason: \"Approved in CHG-1234\"\n");

        Run r = run("diff", before.toString(), after.toString(), "--allow", rules.toString());

        assertEquals(0, r.code());
        assertTrue(r.out().contains("ALLOWED"), r.out());
        assertTrue(r.out().contains("Approved in CHG-1234"), r.out());
    }

    @Test
    void reportRecordsTheResultAndCounts() throws IOException {
        Path before = snapshot("before.json", "Active");
        Path after = snapshot("after.json", "Resolved");
        Path report = tmp.resolve("report.json");

        Run r = run("diff", before.toString(), after.toString(), "--report", report.toString());

        assertEquals(1, r.code());
        var json = JSON.readTree(Files.readString(report));
        assertEquals("FAIL", json.path("result").asText());
        assertEquals(1, json.path("drift").asInt());
        assertTrue(json.path("checks").toString().contains("bundle state"));
    }

    @Test
    void anAllowRuleWithoutAReasonIsRejected() throws IOException {
        Path before = snapshot("before.json", "Active");
        Path after = snapshot("after.json", "Resolved");
        Path rules = allow("allow:\n  - category: bundle state\n    key: aem-guides-wknd.core\n");

        Run r = run("diff", before.toString(), after.toString(), "--allow", rules.toString());

        assertEquals(2, r.code());
        assertTrue(r.err().contains("needs a reason"), r.err());
    }

    @Test
    void diffNeedsExactlyTwoSnapshotFiles() throws IOException {
        Run r = run("diff", snapshot("only.json", "Active").toString());

        assertEquals(2, r.code());
        assertTrue(r.err().contains("diff needs two snapshot files"), r.err());
    }

    @Test
    void missingSnapshotFileIsAConfigError() throws IOException {
        Run r = run("diff", tmp.resolve("nope.json").toString(), snapshot("after.json", "Active").toString());

        assertEquals(2, r.code());
        assertTrue(r.err().contains("snapshot file not found"), r.err());
    }

    @Test
    void snapshotCommandNeedsAnOutputFile() {
        Run r = run("--env", "local", "snapshot");

        assertEquals(2, r.code());
        assertTrue(r.err().contains("snapshot needs --out FILE"), r.err());
    }

    @Test
    void positionalArgumentsAreOnlyAcceptedForDiff() {
        Run r = run("--env", "local", "sanity", "stray.json");

        assertEquals(2, r.code());
        assertTrue(r.err().contains("unexpected argument: stray.json"), r.err());
    }

    @Test
    void theOldSnapshotDiffCommandIsGone() {
        Run r = run("snapshot-diff");

        assertEquals(2, r.code());
    }
}
