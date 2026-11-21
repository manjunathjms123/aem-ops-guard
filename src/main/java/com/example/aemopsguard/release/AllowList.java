package com.example.aemopsguard.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Differences that have been approved for a release, read from a YAML file such as {@code allow.yaml}:
 *
 * <pre>
 * allow:
 *   - category: bundle state
 *     key: com.example.other
 *     reason: "Approved in CHG-1234"
 *   - category: current user
 *     reason: "Service account rotated"
 * </pre>
 *
 * <p>{@code category} is the check name shown in the diff (for example {@code bundle state}). {@code key}
 * limits the rule to one item in that check; leave it out to allow the whole check. {@code reason} is
 * required, so every approval says why it was made.
 */
public final class AllowList {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** One approved difference. A null key means the rule covers the whole category. */
    public record AllowRule(String category, String key, String reason) {

        public boolean matches(String checkCategory, String itemKey) {
            if (!category.equalsIgnoreCase(checkCategory)) {
                return false;
            }
            return key == null || key.equals(itemKey);
        }
    }

    private AllowList() {
    }

    /** Reads the allow rules. Throws IllegalArgumentException when an entry is missing its category or reason. */
    public static List<AllowRule> load(Path file) throws IOException {
        JsonNode root = YAML.readTree(Files.readString(file, StandardCharsets.UTF_8));
        List<AllowRule> rules = new ArrayList<>();
        if (root == null || root.isMissingNode() || root.isNull()) {
            return rules;
        }
        JsonNode entries = root.path("allow");
        if (!entries.isArray()) {
            throw new IllegalArgumentException("allow file must contain an 'allow:' list");
        }
        int index = 0;
        for (JsonNode entry : entries) {
            index++;
            String category = entry.path("category").asText("").trim();
            String reason = entry.path("reason").asText("").trim();
            String key = entry.path("key").isMissingNode() || entry.path("key").isNull()
                    ? null : entry.path("key").asText().trim();
            if (category.isEmpty()) {
                throw new IllegalArgumentException("allow entry " + index + " has no category");
            }
            if (reason.isEmpty()) {
                throw new IllegalArgumentException("allow entry " + index + " (" + category.toLowerCase(Locale.ROOT)
                        + ") needs a reason");
            }
            rules.add(new AllowRule(category, key == null || key.isEmpty() ? null : key, reason));
        }
        return rules;
    }
}
