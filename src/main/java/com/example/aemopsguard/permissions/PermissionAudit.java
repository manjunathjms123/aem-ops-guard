package com.example.aemopsguard.permissions;

import com.example.aemopsguard.check.Finding;
import com.example.aemopsguard.check.Finding.ModuleResult;
import com.example.aemopsguard.http.AemClientException;
import com.example.aemopsguard.http.AemHttpClient;
import com.example.aemopsguard.http.AemResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Module 3: user and group audit.
 *
 * <ul>
 *   <li>The current-user endpoint must report the configured user as the authorizable.</li>
 *   <li>Each expected group must be among the user's groups.</li>
 *   <li>The number of users under {@code /home/users} is reported from a query builder request,
 *       for information only. It does not fail the check.</li>
 * </ul>
 *
 * <p>The raw current-user response is kept in the snapshot.
 */
public final class PermissionAudit {

    public static final String MODULE = "Permissions";
    public static final String DEFAULT_QUERY_PATH = "/bin/querybuilder.json";
    /** Query for user accounts: total is the number of users under /home/users. */
    public static final Map<String, String> USER_QUERY = Map.of(
            "path", "/home/users",
            "type", "rep:User",
            "p.hits", "full",
            "p.nodedepth", "2");
    /** Query for groups: total is the number of groups under /home/groups. */
    static final Map<String, String> GROUP_QUERY = Map.of(
            "path", "/home/groups",
            "type", "rep:Group",
            "p.hits", "full",
            "p.nodedepth", "2");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AemHttpClient client;
    private final String currentUserPath;
    private final String expectedUser;
    private final List<String> expectedGroups;
    private final String queryPath;

    public PermissionAudit(AemHttpClient client, String currentUserPath, String expectedUser,
                           List<String> expectedGroups) {
        this(client, currentUserPath, expectedUser, expectedGroups, DEFAULT_QUERY_PATH);
    }

    public PermissionAudit(AemHttpClient client, String currentUserPath, String expectedUser,
                           List<String> expectedGroups, String queryPath) {
        this.client = Objects.requireNonNull(client, "client");
        this.currentUserPath = Objects.requireNonNull(currentUserPath, "currentUserPath");
        this.expectedUser = Objects.requireNonNull(expectedUser, "expectedUser");
        this.expectedGroups = List.copyOf(expectedGroups);
        this.queryPath = Objects.requireNonNull(queryPath, "queryPath");
    }

    public ModuleResult run() {
        List<Finding> findings = new ArrayList<>();
        ObjectNode snapshot = JSON.createObjectNode();

        AemResponse response;
        try {
            response = client.get(currentUserPath);
        } catch (AemClientException | IllegalArgumentException e) {
            findings.add(new Finding(MODULE, "current user", false, "request failed: " + e.getMessage()));
            return new ModuleResult(findings, snapshot);
        }
        if (!response.isSuccessful()) {
            findings.add(new Finding(MODULE, "current user", false, "HTTP " + response.status()));
            return new ModuleResult(findings, snapshot);
        }
        JsonNode root;
        try {
            root = JSON.readTree(response.body());
        } catch (JsonProcessingException e) {
            findings.add(new Finding(MODULE, "current user", false, "response is not JSON"));
            return new ModuleResult(findings, snapshot);
        }
        snapshot.set("currentUser", root);

        String actual = root.path("authorizableId").asText("");
        String identity = actual.equals(expectedUser)
                ? "running as " + actual
                : "running as '" + actual + "', expected '" + expectedUser + "'";
        findings.add(new Finding(MODULE, "identity", actual.equals(expectedUser),
                identity + " (" + currentUserDetails(root) + "); "
                        + countSummary(USER_QUERY, "users under /home/users", "userCount", snapshot)
                        + "; " + countSummary(GROUP_QUERY, "groups under /home/groups", "groupCount", snapshot)));

        // The current-user response may not list groups at all. Say so, instead of reporting "not a member".
        boolean groupsListed = root.has("groups");
        Set<String> groups = new HashSet<>();
        for (JsonNode group : root.path("groups")) {
            groups.add(group.asText());
        }
        for (String expected : expectedGroups) {
            if (!groupsListed) {
                findings.add(new Finding(MODULE, "group " + expected, false,
                        "cannot check: the current-user response does not list groups"));
            } else {
                findings.add(new Finding(MODULE, "group " + expected, groups.contains(expected),
                        groups.contains(expected) ? "member" : "not a member"));
            }
        }
        return new ModuleResult(findings, snapshot);
    }

    /** The current user's name, home, and type, from the current-user response. Missing fields are left out. */
    static String currentUserDetails(JsonNode root) {
        List<String> parts = new ArrayList<>();
        String name = root.path("name").asText("");
        if (!name.isBlank()) {
            parts.add("name " + name);
        }
        String home = root.path("home").asText("");
        if (!home.isBlank()) {
            parts.add("home " + home);
        }
        String type = root.path("type").asText("");
        if (!type.isBlank()) {
            parts.add("type " + type);
        }
        if (root.has("isImpersonated")) {
            parts.add("impersonated " + (root.path("isImpersonated").asBoolean() ? "yes" : "no"));
        }
        return parts.isEmpty() ? "no details returned" : String.join(", ", parts);
    }

    /**
     * "N {noun}" from the query builder's total, or a note why it is unavailable. Also stores the count in
     * the snapshot under {@code snapshotKey}.
     */
    private String countSummary(Map<String, String> query, String noun, String snapshotKey, ObjectNode snapshot) {
        String label = noun.substring(0, noun.indexOf(' '));
        try {
            AemResponse response = client.getWithQuery(queryPath, query);
            if (!response.isSuccessful()) {
                return label + " count unavailable (HTTP " + response.status() + ")";
            }
            JsonNode root = JSON.readTree(response.body());
            if (!root.has("total")) {
                return label + " count unavailable (no total in the query response)";
            }
            long total = root.path("total").asLong();
            snapshot.put(snapshotKey, total);
            return total + " " + noun;
        } catch (AemClientException | IllegalArgumentException e) {
            return label + " count unavailable (" + e.getMessage() + ")";
        } catch (JsonProcessingException e) {
            return label + " count unavailable (query response is not JSON)";
        }
    }
}
