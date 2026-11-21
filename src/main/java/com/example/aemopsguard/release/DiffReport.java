package com.example.aemopsguard.release;

import com.example.aemopsguard.check.Finding;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Writes the result of a diff as a JSON report, for a release record or a CI pipeline. */
public final class DiffReport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private DiffReport() {
    }

    /** One check's status: SAME, ALLOWED or DRIFT. */
    public static String status(Finding f) {
        if (!f.passed()) {
            return "DRIFT";
        }
        return f.detail().startsWith(SnapshotDiff.ALLOWED_PREFIX) ? "ALLOWED" : "SAME";
    }

    public static ObjectNode build(String beforeFile, String afterFile, String allowFile, List<Finding> findings) {
        int same = 0;
        int allowed = 0;
        int drift = 0;
        ArrayNode checks = JSON.createArrayNode();
        for (Finding f : findings) {
            String status = status(f);
            switch (status) {
                case "SAME" -> same++;
                case "ALLOWED" -> allowed++;
                default -> drift++;
            }
            checks.addObject().put("check", f.subject()).put("status", status).put("detail", f.detail());
        }
        ObjectNode report = JSON.createObjectNode();
        report.put("before", beforeFile);
        report.put("after", afterFile);
        report.put("allowFile", allowFile == null ? "" : allowFile);
        report.put("generatedAt", Instant.now().toString());
        report.put("result", drift == 0 ? "PASS" : "FAIL");
        report.put("same", same);
        report.put("allowed", allowed);
        report.put("drift", drift);
        report.set("checks", checks);
        return report;
    }

    public static void write(ObjectNode report, Path file) throws IOException {
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
