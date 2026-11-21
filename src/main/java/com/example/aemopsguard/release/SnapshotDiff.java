package com.example.aemopsguard.release;

import com.example.aemopsguard.check.Finding;
import com.example.aemopsguard.release.AllowList.AllowRule;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compares two environment snapshots (JSON written by {@code snapshot} or {@code sanity --snapshot}), for
 * example one taken before a release and one taken after it.
 *
 * <p>Each category gives one finding:
 * <ul>
 *   <li>passed, "identical", when both snapshots match;</li>
 *   <li>passed, starting "ALLOWED:", when the only differences are approved by an allow rule;</li>
 *   <li>failed, listing the differences, otherwise.</li>
 * </ul>
 * A value missing from one snapshot is reported as "absent", never as a match.
 */
public final class SnapshotDiff {

    public static final String MODULE = "Release diff";
    public static final String ALLOWED_PREFIX = "ALLOWED: ";
    static final String ABSENT = "absent";

    private SnapshotDiff() {
    }

    public static List<Finding> compare(JsonNode before, JsonNode after) {
        return compare(before, after, List.of());
    }

    public static List<Finding> compare(JsonNode before, JsonNode after, List<AllowRule> allow) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(allow, "allow");
        List<Finding> findings = new ArrayList<>();

        String envBefore = before.path("environment").asText("");
        String envAfter = after.path("environment").asText("");
        if (!envBefore.equals(envAfter)) {
            findings.add(new Finding(MODULE, "environment", false,
                    "snapshots come from different environments: " + envBefore + " and " + envAfter));
        }

        compareMaps(findings, allow, "author pages", pageStates(before), pageStates(after));
        compareMaps(findings, allow, "bundle state", bundleStates(before), bundleStates(after));
        compareValues(findings, allow, "configured services", text(before.at("/osgi/configuredServices")),
                text(after.at("/osgi/configuredServices")));
        compareValues(findings, allow, "current user", userSummary(before), userSummary(after));
        compareValues(findings, allow, "user count", text(before.at("/permissions/userCount")),
                text(after.at("/permissions/userCount")));
        compareValues(findings, allow, "group count", text(before.at("/permissions/groupCount")),
                text(after.at("/permissions/groupCount")));
        return findings;
    }

    /** Page path to PASS or FAIL, from the author page results. */
    static Map<String, String> pageStates(JsonNode snapshot) {
        Map<String, String> states = new TreeMap<>();
        for (JsonNode page : snapshot.path("authorPages")) {
            states.put(page.path("path").asText(), page.path("passed").asBoolean(false) ? "PASS" : "FAIL");
        }
        return states;
    }

    /** Symbolic name to bundle state, from the OSGi section of the snapshot. */
    static Map<String, String> bundleStates(JsonNode snapshot) {
        Map<String, String> states = new TreeMap<>();
        for (JsonNode bundle : snapshot.at("/osgi/bundles")) {
            states.put(bundle.path("symbolicName").asText(), bundle.path("state").asText());
        }
        return states;
    }

    /** The current user's identity and home, so a change of account or home path shows up. */
    static String userSummary(JsonNode snapshot) {
        JsonNode user = snapshot.at("/permissions/currentUser");
        if (user.isMissingNode() || user.isNull()) {
            return ABSENT;
        }
        return user.path("authorizableId").asText("") + " home " + user.path("home").asText("")
                + " type " + user.path("type").asText("");
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? ABSENT : node.asText();
    }

    private static AllowRule ruleFor(List<AllowRule> allow, String category, String key) {
        for (AllowRule rule : allow) {
            if (rule.matches(category, key)) {
                return rule;
            }
        }
        return null;
    }

    private static void compareMaps(List<Finding> out, List<AllowRule> allow, String category,
                                    Map<String, String> before, Map<String, String> after) {
        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        List<String> differences = new ArrayList<>();
        List<String> allowed = new ArrayList<>();
        for (String key : keys) {
            String b = before.getOrDefault(key, ABSENT);
            String a = after.getOrDefault(key, ABSENT);
            if (b.equals(a)) {
                continue;
            }
            AllowRule rule = ruleFor(allow, category, key);
            String line = key + ": BEFORE=" + b + ", AFTER=" + a;
            if (rule != null) {
                allowed.add(line + " (allowed: " + rule.reason() + ")");
            } else {
                differences.add(line);
            }
        }
        if (differences.isEmpty() && allowed.isEmpty()) {
            out.add(new Finding(MODULE, category, true, "identical (" + keys.size() + " items)"));
        } else if (differences.isEmpty()) {
            out.add(new Finding(MODULE, category, true, ALLOWED_PREFIX + String.join("; ", allowed)));
        } else {
            String detail = differences.size() + " difference(s): " + String.join("; ", differences);
            if (!allowed.isEmpty()) {
                detail += "; allowed: " + String.join("; ", allowed);
            }
            out.add(new Finding(MODULE, category, false, detail));
        }
    }

    private static void compareValues(List<Finding> out, List<AllowRule> allow, String category,
                                      String before, String after) {
        if (before.equals(after)) {
            out.add(new Finding(MODULE, category, true, "identical: " + before));
            return;
        }
        String line = "BEFORE=" + before + ", AFTER=" + after;
        AllowRule rule = ruleFor(allow, category, null);
        if (rule != null) {
            out.add(new Finding(MODULE, category, true, ALLOWED_PREFIX + line + " (allowed: " + rule.reason() + ")"));
        } else {
            out.add(new Finding(MODULE, category, false, line));
        }
    }
}
