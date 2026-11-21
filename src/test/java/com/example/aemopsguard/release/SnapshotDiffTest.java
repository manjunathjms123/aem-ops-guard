package com.example.aemopsguard.release;

import com.example.aemopsguard.check.Finding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotDiffTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BASE = """
            {
              "environment": "qa",
              "authorPages": [
                {"path": "/sites.html", "passed": true, "detail": "HTTP 200"},
                {"path": "/assets.html", "passed": true, "detail": "HTTP 200"}
              ],
              "osgi": {
                "configuredServices": 193,
                "bundles": [
                  {"symbolicName": "aem-guides-wknd.core", "state": "Active"},
                  {"symbolicName": "com.example.other", "state": "Active"}
                ]
              },
              "permissions": {
                "userCount": 171,
                "groupCount": 21,
                "currentUser": {"authorizableId": "admin", "home": "/home/users/a", "type": "user"}
              }
            }
            """;

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    private static Finding find(List<Finding> findings, String subject) {
        return findings.stream().filter(f -> f.subject().equals(subject)).findFirst().orElseThrow();
    }

    @Test
    void identicalSnapshotsPassEveryCheck() throws Exception {
        List<Finding> findings = SnapshotDiff.compare(json(BASE), json(BASE));

        assertTrue(findings.stream().allMatch(Finding::passed), findings.toString());
    }

    /** A minimal snapshot with just the bundle list, for the bundle tests. */
    private static String bundles(String... symbolicAndState) {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < symbolicAndState.length; i += 2) {
            if (list.length() > 0) {
                list.append(',');
            }
            list.append("{\"symbolicName\":\"").append(symbolicAndState[i])
                    .append("\",\"state\":\"").append(symbolicAndState[i + 1]).append("\"}");
        }
        return "{\"environment\":\"qa\",\"osgi\":{\"bundles\":[" + list + "]}}";
    }

    @Test
    void bundleStateChangeAfterReleaseIsFlaggedWithBothValues() throws Exception {
        String before = bundles("aem-guides-wknd.core", "Active", "com.example.other", "Active");
        String after = bundles("aem-guides-wknd.core", "Resolved", "com.example.other", "Active");

        Finding f = find(SnapshotDiff.compare(json(before), json(after)), "bundle state");

        assertFalse(f.passed());
        assertTrue(f.detail().contains("aem-guides-wknd.core: BEFORE=Active, AFTER=Resolved"), f.detail());
    }

    @Test
    void pageThatFailedAfterReleaseIsFlagged() throws Exception {
        String after = BASE.replace("{\"path\": \"/assets.html\", \"passed\": true",
                "{\"path\": \"/assets.html\", \"passed\": false");

        Finding f = find(SnapshotDiff.compare(json(BASE), json(after)), "author pages");

        assertFalse(f.passed());
        assertTrue(f.detail().contains("/assets.html: BEFORE=PASS, AFTER=FAIL"), f.detail());
    }

    @Test
    void userCountAndConfiguredServicesChangesAreFlagged() throws Exception {
        String after = BASE.replace("\"userCount\": 171", "\"userCount\": 160")
                .replace("\"configuredServices\": 193", "\"configuredServices\": 190");

        List<Finding> findings = SnapshotDiff.compare(json(BASE), json(after));

        assertFalse(find(findings, "user count").passed());
        assertTrue(find(findings, "user count").detail().contains("BEFORE=171, AFTER=160"));
        assertFalse(find(findings, "configured services").passed());
    }

    @Test
    void currentUserOrHomeChangeIsFlagged() throws Exception {
        String after = BASE.replace("\"/home/users/a\"", "\"/home/users/b\"");

        Finding f = find(SnapshotDiff.compare(json(BASE), json(after)), "current user");

        assertFalse(f.passed());
    }

    @Test
    void snapshotsFromDifferentEnvironmentsAreFlagged() throws Exception {
        String after = BASE.replace("\"qa\"", "\"uat\"");

        Finding f = find(SnapshotDiff.compare(json(BASE), json(after)), "environment");

        assertFalse(f.passed());
        assertEquals("snapshots come from different environments: qa and uat", f.detail());
    }

    @Test
    void missingBundleIsReportedAsAbsentNotAsAMatch() throws Exception {
        String before = bundles("aem-guides-wknd.core", "Active", "com.example.other", "Active");
        String after = bundles("aem-guides-wknd.core", "Active");

        Finding f = find(SnapshotDiff.compare(json(before), json(after)), "bundle state");

        assertFalse(f.passed());
        assertTrue(f.detail().contains("com.example.other: BEFORE=Active, AFTER=absent"), f.detail());
    }
}
