package com.example.aemopsguard.osgi;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A bundle the OSGi check requires, written in config as {@code symbolicName[:version[:state]]}, for example
 * {@code aem-guides-wknd.core:4.0.4:Active}. The version is optional (any version passes when it's left out),
 * and the state defaults to {@code Active}.
 */
public record RequiredBundle(String symbolicName, String version, String state) {

    public static final String DEFAULT_STATE = "Active";

    public RequiredBundle {
        if (symbolicName == null || symbolicName.isBlank()) {
            throw new IllegalArgumentException("bundle entry has no symbolic name");
        }
        version = version == null ? "" : version;
        state = state == null || state.isBlank() ? DEFAULT_STATE : state;
    }

    /** Parses one config entry. Throws IllegalArgumentException for a bad entry. */
    public static RequiredBundle parse(String entry) {
        String[] parts = entry.trim().split(":", -1);
        if (parts.length > 3) {
            throw new IllegalArgumentException("invalid bundle entry '" + entry
                    + "': use symbolicName[:version[:state]]");
        }
        return new RequiredBundle(parts[0].trim(),
                parts.length > 1 ? parts[1].trim() : "",
                parts.length > 2 ? parts[2].trim() : "");
    }

    /** Why the installed bundle doesn't meet this requirement, or null when it does. */
    public String problem(JsonNode bundle) {
        String actualState = bundle.path("state").asText("");
        String actualVersion = bundle.path("version").asText("");
        if (!state.equalsIgnoreCase(actualState)) {
            return "state " + actualState + ", expected " + state;
        }
        if (!version.isEmpty() && !version.equals(actualVersion)) {
            return "version " + actualVersion + ", expected " + version;
        }
        return null;
    }

    /** What was found for a bundle that meets the requirement, for example "Active, version 4.0.4". */
    public static String describe(JsonNode bundle) {
        return bundle.path("state").asText("") + ", version " + bundle.path("version").asText("");
    }
}
