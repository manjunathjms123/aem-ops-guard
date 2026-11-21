package com.example.aemopsguard.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Loads AEM environment settings from a KEY=VALUE file such as {@code aem-environments.env}, and
 * combines them with the process environment.
 *
 * <p>Rules:
 * <ul>
 *   <li>URL and USER come from the file. An environment variable with the same key overrides the file.</li>
 *   <li>PASSWORD comes from environment variables. For LOCAL only, {@code AEM_LOCAL_PASSWORD} may also be
 *       set in the file; an environment variable overrides it. Other environments ignore PASSWORD lines in
 *       the file, and a placeholder value (starting with {@code REPLACE_WITH}) counts as missing.</li>
 *   <li>An environment is loaded only when its URL, USER and PASSWORD are all present. Otherwise it is
 *       reported in {@link LoadResult#skipped()} with the missing key, so placeholder entries don't
 *       block the others.</li>
 * </ul>
 */
public final class EnvironmentFileLoader {

    /** The only password that may be read from a settings file: the local instance's. */
    static final String LOCAL_FILE_PASSWORD = "AEM_LOCAL_PASSWORD";
    static final String PLACEHOLDER_PREFIX = "REPLACE_WITH";

    private EnvironmentFileLoader() {
    }

    /** Environments that loaded, and the reason each skipped one was left out. */
    public record LoadResult(Map<EnvironmentType, EnvironmentConfig> loaded,
                             Map<EnvironmentType, String> skipped) {
    }

    public static LoadResult load(Path file, Map<String, String> processEnv) throws IOException {
        Objects.requireNonNull(file, "file");
        return combine(parse(Files.readString(file, StandardCharsets.UTF_8)), processEnv);
    }

    /** Parses KEY=VALUE lines. Blank lines and lines starting with '#' are ignored. */
    public static Map<String, String> parse(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("not a KEY=VALUE line: " + line);
            }
            values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return values;
    }

    /**
     * All non-password settings: file values, overridden by AEM_ environment variables.
     * Used for per-environment options such as {@code AEM_QA_SITE_PAGE}.
     */
    public static Map<String, String> settings(Path file, Map<String, String> processEnv) throws IOException {
        Objects.requireNonNull(file, "file");
        return mergedValues(parse(Files.readString(file, StandardCharsets.UTF_8)), processEnv);
    }

    static Map<String, String> mergedValues(Map<String, String> fileValues, Map<String, String> processEnv) {
        Map<String, String> merged = new HashMap<>();
        fileValues.forEach((k, v) -> {
            if (!k.endsWith("_PASSWORD") || LOCAL_FILE_PASSWORD.equals(k)) {
                merged.put(k, v);
            }
        });
        processEnv.forEach((k, v) -> {
            if (k.startsWith("AEM_")) {
                merged.put(k, v);
            }
        });
        return merged;
    }

    static LoadResult combine(Map<String, String> fileValues, Map<String, String> processEnv) {
        Map<String, String> merged = mergedValues(fileValues, processEnv);

        Map<EnvironmentType, EnvironmentConfig> loaded = new EnumMap<>(EnvironmentType.class);
        Map<EnvironmentType, String> skipped = new EnumMap<>(EnvironmentType.class);
        for (EnvironmentType type : EnvironmentType.values()) {
            String prefix = "AEM_" + type.name() + "_";
            String missing = firstMissing(merged, prefix, processEnv);
            if (missing != null) {
                skipped.put(type, "missing " + missing);
                continue;
            }
            loaded.put(type, EnvironmentConfig.fromEnvironment(type.name().toLowerCase(), type, merged));
        }
        return new LoadResult(loaded, skipped);
    }

    private static String firstMissing(Map<String, String> merged, String prefix, Map<String, String> processEnv) {
        for (String suffix : new String[] {"URL", "USER"}) {
            String value = merged.get(prefix + suffix);
            if (value == null || value.isBlank()) {
                return prefix + suffix;
            }
        }
        String password = merged.get(prefix + "PASSWORD");
        if (password == null || password.isBlank()) {
            return prefix + "PASSWORD (set it as an environment variable"
                    + (prefix.equals("AEM_LOCAL_") ? " or in config\\local.env" : "") + ")";
        }
        if (password.startsWith(PLACEHOLDER_PREFIX)) {
            return prefix + "PASSWORD is still the placeholder; replace it with the real password";
        }
        return null;
    }
}
