package com.example.aemopsguard.osgi;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Module 2: OSGi R6 configuration check.
 *
 * <ul>
 *   <li>The bundle list is read from the Felix bundles endpoint.</li>
 *   <li>Each required bundle (by symbolic name) must be Active.</li>
 *   <li>Each configured PID must have a configuration at {@code <configPath>/<pid>.json}.</li>
 *   <li>Non-fragment bundles that are not Active are counted and reported, but do not fail the check.</li>
 * </ul>
 *
 * <p>ValueMap value assertions are not checked yet; only presence is.
 */
public final class OsgiR6Check {

    public static final String MODULE = "OSGi R6";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AemHttpClient client;
    private final String bundlesPath;
    private final String configPath;
    private final List<String> requiredBundles;
    private final List<String> configPids;

    public OsgiR6Check(AemHttpClient client, String bundlesPath, String configPath,
                       List<String> requiredBundles, List<String> configPids) {
        this.client = Objects.requireNonNull(client, "client");
        this.bundlesPath = Objects.requireNonNull(bundlesPath, "bundlesPath");
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.requiredBundles = List.copyOf(requiredBundles);
        this.configPids = List.copyOf(configPids);
    }

    public ModuleResult run() {
        List<Finding> findings = new ArrayList<>();
        ObjectNode snapshot = JSON.createObjectNode();

        JsonNode bundles = fetchJson(bundlesPath, "bundles", findings);
        if (bundles == null) {
            return new ModuleResult(findings, snapshot);
        }
        JsonNode data = bundles.path("data");
        snapshot.set("bundles", data);

        Map<String, JsonNode> bySymbolicName = new HashMap<>();
        int notActive = 0;
        for (JsonNode bundle : data) {
            bySymbolicName.put(bundle.path("symbolicName").asText(), bundle);
            if (!bundle.path("fragment").asBoolean(false) && !"Active".equals(bundle.path("state").asText())) {
                notActive++;
            }
        }
        String configSummary = configurationSummary(snapshot);
        findings.add(new Finding(MODULE, "bundle list", true,
                data.size() + " bundles listed, " + notActive + " non-fragment bundles not Active; "
                        + configSummary + " (informational)"));

        for (String entry : requiredBundles) {
            RequiredBundle required;
            try {
                required = RequiredBundle.parse(entry);
            } catch (IllegalArgumentException e) {
                findings.add(new Finding(MODULE, entry, false, e.getMessage()));
                continue;
            }
            JsonNode bundle = bySymbolicName.get(required.symbolicName());
            if (bundle == null) {
                findings.add(new Finding(MODULE, required.symbolicName(), false,
                        "required bundle is not installed"));
                continue;
            }
            String problem = required.problem(bundle);
            findings.add(new Finding(MODULE, required.symbolicName(), problem == null,
                    problem == null ? RequiredBundle.describe(bundle) : problem));
        }

        for (String pid : configPids) {
            String path = configPath + "/" + pid + ".json";
            JsonNode config = fetchJson(path, pid, findings);
            if (config != null) {
                boolean present = config.isObject() && config.size() > 0;
                findings.add(new Finding(MODULE, pid, present,
                        present ? "configuration present" : "configuration is empty"));
            }
        }
        return new ModuleResult(findings, snapshot);
    }

    /**
     * The IDs of services that have a configuration, read from the Configuration Manager page body.
     * Shared with the drift check so both read the page the same way.
     */
    public static Set<String> configuredServiceIds(String body) {
        Set<String> configured = new HashSet<>();
        if (body == null) {
            return configured;
        }
        Matcher matcher = SERVICE_ENTRY.matcher(body);
        while (matcher.find()) {
            if ("true".equals(matcher.group(2))) {
                configured.add(matcher.group(1));
            }
        }
        return configured;
    }

    /**
     * One service entry on the Configuration Manager page, for example
     * {@code {"id":"com.x.Svc","name":"Svc","has_config":true}}. The flag is absent for services
     * with no configuration.
     */
    private static final Pattern SERVICE_ENTRY = Pattern.compile(
            "\\{\"id\":\"([^\"]+)\",\"name\":\"[^\"]*\"(?:,\"has_config\":(true|false))?\\}");

    /** Counts services that have a configuration, read from the Configuration Manager page. */
    private String configurationSummary(ObjectNode snapshot) {
        AemResponse page;
        try {
            page = client.get(configPath);
        } catch (AemClientException | IllegalArgumentException e) {
            return "configurations: request failed (" + e.getMessage() + ")";
        }
        if (!page.isSuccessful()) {
            return "configurations: HTTP " + page.status();
        }
        Set<String> registered = new HashSet<>();
        Set<String> configured = new HashSet<>();
        Matcher matcher = SERVICE_ENTRY.matcher(page.body() == null ? "" : page.body());
        while (matcher.find()) {
            registered.add(matcher.group(1));
            if ("true".equals(matcher.group(2))) {
                configured.add(matcher.group(1));
            }
        }
        if (registered.isEmpty()) {
            return "configurations: no service entries found on the Configuration Manager page";
        }
        snapshot.put("configuredServices", configured.size());
        snapshot.put("registeredServices", registered.size());
        return configured.size() + " of " + registered.size() + " services have a configuration";
    }

    /** Fetches and parses JSON; on failure adds a failing finding and returns null. */
    private JsonNode fetchJson(String path, String subject, List<Finding> findings) {
        AemResponse response;
        try {
            response = client.get(path);
        } catch (AemClientException | IllegalArgumentException e) {
            findings.add(new Finding(MODULE, subject, false, "request failed: " + e.getMessage()));
            return null;
        }
        if (!response.isSuccessful()) {
            findings.add(new Finding(MODULE, subject, false, "HTTP " + response.status()));
            return null;
        }
        try {
            return JSON.readTree(response.body());
        } catch (JsonProcessingException e) {
            findings.add(new Finding(MODULE, subject, false, "response is not JSON"));
            return null;
        }
    }
}
