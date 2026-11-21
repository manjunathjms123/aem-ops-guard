package com.example.aemopsguard.drift;

import com.example.aemopsguard.http.AemClientException;
import com.example.aemopsguard.http.AemHttpClient;
import com.example.aemopsguard.http.AemResponse;
import com.example.aemopsguard.osgi.OsgiR6Check;
import com.example.aemopsguard.permissions.PermissionAudit;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads the data the drift check compares: bundle states, configured services, groups, and the user
 * and group counts. Each read is independent, so one failure is recorded in the notes and the rest still
 * load.
 */
public final class SnapshotCollector {

    /** Every group with its authorizable ID. p.limit is raised so the list isn't cut to 10. */
    static final Map<String, String> GROUP_LIST = Map.of(
            "path", "/home/groups",
            "type", "rep:Group",
            "p.hits", "full",
            "p.limit", "1000");

    private static final ObjectMapper JSON = new ObjectMapper();

    private SnapshotCollector() {
    }

    public static EnvironmentSnapshot collect(String name, AemHttpClient client, String bundlesPath,
                                              String configPath, String queryPath) {
        java.util.List<String> notes = new java.util.ArrayList<>();

        Map<String, String> bundles = new TreeMap<>();
        try {
            AemResponse r = client.get(bundlesPath);
            if (!r.isSuccessful()) {
                notes.add("bundles unavailable (HTTP " + r.status() + ")");
            } else {
                for (JsonNode bundle : JSON.readTree(r.body()).path("data")) {
                    bundles.put(bundle.path("symbolicName").asText(), bundle.path("state").asText());
                }
            }
        } catch (AemClientException | IllegalArgumentException | JsonProcessingException e) {
            notes.add("bundles unavailable (" + e.getMessage() + ")");
        }

        Set<String> configured = new TreeSet<>();
        try {
            AemResponse r = client.get(configPath);
            if (!r.isSuccessful()) {
                notes.add("configurations unavailable (HTTP " + r.status() + ")");
            } else {
                configured.addAll(OsgiR6Check.configuredServiceIds(r.body()));
            }
        } catch (AemClientException | IllegalArgumentException e) {
            notes.add("configurations unavailable (" + e.getMessage() + ")");
        }

        Set<String> groups = new TreeSet<>();
        long groupCount = -1;
        try {
            AemResponse r = client.getWithQuery(queryPath, GROUP_LIST);
            if (!r.isSuccessful()) {
                notes.add("groups unavailable (HTTP " + r.status() + ")");
            } else {
                JsonNode root = JSON.readTree(r.body());
                for (JsonNode hit : root.path("hits")) {
                    String id = hit.path("rep:authorizableId").asText("");
                    if (!id.isBlank()) {
                        groups.add(id);
                    }
                }
                groupCount = root.path("total").asLong(-1);
                if (groupCount > groups.size()) {
                    notes.add("groups only partly read (" + groups.size() + " of " + groupCount + ")");
                }
            }
        } catch (AemClientException | IllegalArgumentException | JsonProcessingException e) {
            notes.add("groups unavailable (" + e.getMessage() + ")");
        }

        long userCount = -1;
        try {
            AemResponse r = client.getWithQuery(queryPath, PermissionAudit.USER_QUERY);
            if (!r.isSuccessful()) {
                notes.add("users unavailable (HTTP " + r.status() + ")");
            } else {
                userCount = JSON.readTree(r.body()).path("total").asLong(-1);
            }
        } catch (AemClientException | IllegalArgumentException | JsonProcessingException e) {
            notes.add("users unavailable (" + e.getMessage() + ")");
        }

        return new EnvironmentSnapshot(name, bundles, configured, groups, userCount, groupCount, notes);
    }
}
