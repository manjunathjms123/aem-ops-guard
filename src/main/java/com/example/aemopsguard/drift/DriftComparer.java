package com.example.aemopsguard.drift;

import com.example.aemopsguard.check.Finding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/**
 * Compares environment snapshots against the first one (the baseline).
 *
 * <p>Each category gives one finding: passed when every environment matches, failed with the list of
 * differences otherwise. A value that couldn't be read is never treated as a match.
 */
public final class DriftComparer {

    public static final String MODULE = "Drift";
    static final String ABSENT = "absent";
    static final String PRESENT = "present";

    private DriftComparer() {
    }

    public static List<Finding> compare(List<EnvironmentSnapshot> envs) {
        Objects.requireNonNull(envs, "envs");
        if (envs.size() < 2) {
            throw new IllegalArgumentException("drift needs at least two environments");
        }
        List<Finding> findings = new ArrayList<>();
        for (EnvironmentSnapshot env : envs) {
            for (String note : env.notes()) {
                findings.add(new Finding(MODULE, label(env) + " data", false, note));
            }
        }
        compareMaps(envs, findings, "bundle state", s -> s.bundleStates());
        compareMaps(envs, findings, "configuration", s -> presence(s.configuredServices()));
        compareMaps(envs, findings, "group", s -> presence(s.groups()));
        compareCounts(envs, findings, "user count", s -> s.userCount());
        compareCounts(envs, findings, "group count", s -> s.groupCount());
        return findings;
    }

    /** Each key maps to PRESENT, so a set can be compared with the same code as a map. */
    private static Map<String, String> presence(java.util.Set<String> keys) {
        return keys.stream().collect(Collectors.toMap(k -> k, k -> PRESENT));
    }

    private static void compareMaps(List<EnvironmentSnapshot> envs, List<Finding> out, String category,
                                    Function<EnvironmentSnapshot, Map<String, String>> values) {
        TreeSet<String> keys = new TreeSet<>();
        for (EnvironmentSnapshot env : envs) {
            keys.addAll(values.apply(env).keySet());
        }
        List<String> differences = new ArrayList<>();
        for (String key : keys) {
            List<String> perEnv = new ArrayList<>();
            boolean same = true;
            String first = null;
            for (EnvironmentSnapshot env : envs) {
                String v = values.apply(env).getOrDefault(key, ABSENT);
                perEnv.add(label(env) + "=" + v);
                if (first == null) {
                    first = v;
                } else if (!first.equals(v)) {
                    same = false;
                }
            }
            if (!same) {
                differences.add(key + ": " + String.join(", ", perEnv));
            }
        }
        if (differences.isEmpty()) {
            out.add(new Finding(MODULE, category, true,
                    "identical in " + names(envs) + " (" + keys.size() + " items)"));
        } else {
            out.add(new Finding(MODULE, category, false,
                    differences.size() + " difference(s): " + String.join("; ", differences)));
        }
    }

    private static void compareCounts(List<EnvironmentSnapshot> envs, List<Finding> out, String category,
                                      ToLongFunction<EnvironmentSnapshot> count) {
        List<String> unavailable = new ArrayList<>();
        List<String> perEnv = new ArrayList<>();
        java.util.Set<Long> distinct = new java.util.HashSet<>();
        for (EnvironmentSnapshot env : envs) {
            long value = count.applyAsLong(env);
            if (value < 0) {
                unavailable.add(label(env));
            }
            perEnv.add(label(env) + "=" + (value < 0 ? "unavailable" : String.valueOf(value)));
            distinct.add(value);
        }
        if (!unavailable.isEmpty()) {
            out.add(new Finding(MODULE, category, false,
                    "not comparable: unavailable in " + String.join(", ", unavailable)));
        } else if (distinct.size() == 1) {
            out.add(new Finding(MODULE, category, true, "identical: " + perEnv.get(0).split("=")[1]
                    + " in " + names(envs)));
        } else {
            out.add(new Finding(MODULE, category, false, String.join(", ", perEnv)));
        }
    }

    static String label(EnvironmentSnapshot env) {
        return env.name().toUpperCase(Locale.ROOT);
    }

    private static String names(List<EnvironmentSnapshot> envs) {
        return envs.stream().map(DriftComparer::label).collect(Collectors.joining(", "));
    }
}
