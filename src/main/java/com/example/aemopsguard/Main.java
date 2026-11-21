package com.example.aemopsguard;

import com.example.aemopsguard.check.Finding;
import com.example.aemopsguard.check.Finding.ModuleResult;
import com.example.aemopsguard.config.EnvironmentConfig;
import com.example.aemopsguard.config.EnvironmentFileLoader;
import com.example.aemopsguard.config.EnvironmentType;
import com.example.aemopsguard.drift.DriftComparer;
import com.example.aemopsguard.drift.EnvironmentSnapshot;
import com.example.aemopsguard.drift.SnapshotCollector;
import com.example.aemopsguard.felix.FelixEndpoint;
import com.example.aemopsguard.felix.FelixProbe;
import com.example.aemopsguard.http.AemClientException;
import com.example.aemopsguard.http.AemHttpClient;
import com.example.aemopsguard.http.AemResponse;
import com.example.aemopsguard.login.LoginCheck;
import com.example.aemopsguard.osgi.OsgiR6Check;
import com.example.aemopsguard.permissions.PermissionAudit;
import com.example.aemopsguard.release.AllowList;
import com.example.aemopsguard.release.AllowList.AllowRule;
import com.example.aemopsguard.release.DiffReport;
import com.example.aemopsguard.release.SnapshotDiff;
import com.example.aemopsguard.sanity.AuthorSanityCheck;
import com.example.aemopsguard.sanity.RunModes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Command-line entry point.
 *
 * <pre>
 * aem-ops-guard --env local|qa|uat|cloud [--snapshot] sanity
 * aem-ops-guard --env local|qa|uat|cloud felix
 * aem-ops-guard --env local|qa|uat|cloud login
 * aem-ops-guard --env local|qa|uat|cloud snapshot --out FILE
 * aem-ops-guard diff BEFORE.json AFTER.json [--allow FILE] [--report FILE]
 * aem-ops-guard --envs qa,uat,prod drift
 * </pre>
 *
 * <p>{@code sanity} runs all three modules: author pages, the OSGi R6 check, and the permission audit.
 * Each environment has its own settings file, {@code config/<env>.env}; {@code --config FILE} overrides it.
 *
 * <p>Exit codes: 0 success, 1 a check failed or drift was found, 2 usage or configuration error.
 */
public final class Main {

    static final String CONFIG_DIR = "config";
    static final String SNAPSHOT_DIR = "snapshots";
    /** Default Felix check: the bundle list, which exists on on-premise authors. */
    static final String DEFAULT_PING_PATH = "/system/console/bundles.json";
    static final String DEFAULT_OSGI_CONFIG_PATH = "/system/console/configMgr";
    static final String DEFAULT_CURRENT_USER_PATH = "/libs/granite/security/currentuser.json";
    static final String DEFAULT_LOGIN_PATH = "/libs/granite/core/content/login.html";
    static final String DEFAULT_LOGIN_SUBMIT_PATH = "/j_security_check";
    static final String PING_PATH_SUFFIX = "_PING_PATH";
    static final String SITE_PAGE_SUFFIX = "_SITE_PAGE";
    /** Extra pages that sanity checks in addition to the site pages. */
    static final List<String> EXTRA_PAGE_SUFFIXES = List.of("_CRX_DE_PAGE", "_PACK_MGR_PAGE");

    private static final ObjectMapper JSON = new ObjectMapper();

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    /** The settings file for an environment name, for example {@code config/qa.env}. */
    static Path defaultConfigFile(String envName) {
        return Path.of(CONFIG_DIR, envName.toLowerCase(Locale.ROOT) + ".env");
    }

    /** Runs the command with the given arguments and environment; returns the exit code. */
    public static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        Path config = null;
        String envName = null;
        String command = null;
        boolean snapshot = false;
        List<String> envList = new ArrayList<>();
        Path configDir = Path.of(CONFIG_DIR);
        Path outFile = null;
        Path allowFile = null;
        Path reportFile = null;
        List<String> positionals = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> {
                    if (++i >= args.length) {
                        return usageError(err, "--config needs a file path");
                    }
                    config = Path.of(args[i]);
                }
                case "--env" -> {
                    if (++i >= args.length) {
                        return usageError(err, "--env needs a name: local, qa, uat or cloud");
                    }
                    envName = args[i];
                }
                case "--snapshot" -> snapshot = true;
                case "--out" -> {
                    if (++i >= args.length) {
                        return usageError(err, "--out needs a file path, for example qa.json");
                    }
                    outFile = Path.of(args[i]);
                }
                case "--allow" -> {
                    if (++i >= args.length) {
                        return usageError(err, "--allow needs a file path, for example allow.yaml");
                    }
                    allowFile = Path.of(args[i]);
                }
                case "--report" -> {
                    if (++i >= args.length) {
                        return usageError(err, "--report needs a file path, for example report.json");
                    }
                    reportFile = Path.of(args[i]);
                }
                case "--envs" -> {
                    if (++i >= args.length) {
                        return usageError(err, "--envs needs a list such as qa,uat,prod");
                    }
                    for (String part : args[i].split(",")) {
                        if (!part.isBlank()) {
                            envList.add(part.trim());
                        }
                    }
                }
                case "--config-dir" -> {
                    if (++i >= args.length) {
                        return usageError(err, "--config-dir needs a folder path");
                    }
                    configDir = Path.of(args[i]);
                }
                case "sanity", "felix", "login", "drift", "snapshot", "diff" -> command = args[i];
                case "help", "--help", "-h" -> {
                    printUsage(out);
                    return 0;
                }
                default -> {
                    if (args[i].startsWith("--")) {
                        return usageError(err, "unknown argument: " + args[i]);
                    }
                    positionals.add(args[i]);
                }
            }
        }

        if (command == null) {
            return usageError(err, "missing command (sanity, felix, login, snapshot, diff or drift)");
        }
        if (!positionals.isEmpty() && !"diff".equals(command)) {
            return usageError(err, "unexpected argument: " + positionals.get(0));
        }
        if ("diff".equals(command)) {
            return runDiff(positionals, allowFile, reportFile, out, err);
        }
        if ("drift".equals(command)) {
            return runDrift(envList, configDir, env, out, err);
        }
        if (envName == null) {
            return usageError(err, "missing --env (local, qa, uat or cloud)");
        }
        EnvironmentType type;
        try {
            type = EnvironmentType.valueOf(envName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return usageError(err, "unknown environment: " + envName);
        }

        Path file = config != null ? config : defaultConfigFile(envName);
        EnvironmentFileLoader.LoadResult result;
        Map<String, String> settings;
        try {
            result = EnvironmentFileLoader.load(file, env);
            settings = EnvironmentFileLoader.settings(file, env);
        } catch (NoSuchFileException e) {
            err.println("config file not found: " + file);
            err.println("  create it by copying " + file + ".example to " + file + " and filling in the values");
            return 2;
        } catch (IOException e) {
            err.println("cannot read config file " + file + ": " + e.getMessage());
            return 2;
        } catch (IllegalArgumentException e) {
            err.println("invalid config file " + file + ": " + e.getMessage());
            return 2;
        }

        EnvironmentConfig cfg = result.loaded().get(type);
        if (cfg == null) {
            err.println(type.name().toLowerCase(Locale.ROOT) + " is not ready (" + file + "): "
                    + result.skipped().get(type));
            return 2;
        }

        warnPlainHttp(cfg, err);
        AemHttpClient client = new AemHttpClient(cfg);
        if ("felix".equals(command)) {
            return runFelix(client, cfg, type, settings, out, err);
        }
        if ("login".equals(command)) {
            return runLogin(client, cfg, type, settings, out, err);
        }
        if ("snapshot".equals(command)) {
            if (outFile == null) {
                return usageError(err, "snapshot needs --out FILE, for example --out qa.json");
            }
            return runSnapshot(client, cfg, type, settings, outFile, out, err);
        }
        if ("sanity".equals(command)) {
            List<String> toCheck = sanityPages(settings, type);
            if (toCheck.isEmpty()) {
                return usageError(err, "no pages to check: set "
                        + type.name() + SITE_PAGE_SUFFIX + " in " + file);
            }
            return runSanity(client, cfg, type, settings, toCheck, snapshot, out, err);
        }
        return usageError(err, "unknown command: " + command);
    }

    /** The author pages sanity checks: the site pages, then the CRX DE and Package Manager pages. */
    static List<String> sanityPages(Map<String, String> settings, EnvironmentType type) {
        List<String> pages = new ArrayList<>(configuredPages(settings, type));
        for (String suffix : EXTRA_PAGE_SUFFIXES) {
            String extra = settings.get("AEM_" + type.name() + suffix);
            if (extra != null && !extra.isBlank() && !pages.contains(extra.trim())) {
                pages.add(extra.trim());
            }
        }
        return pages;
    }

    /** The first sanity endpoint setting that isn't a usable path, as a message, or null when all are fine. */
    static String sanityPathProblem(Map<String, String> settings, EnvironmentType type) {
        String prefix = "AEM_" + type.name() + "_";
        String[][] pairs = {
                {pingPath(settings, type), prefix + "PING_PATH"},
                {endpoint(settings, type, "_OSGI_CONFIG_PATH", DEFAULT_OSGI_CONFIG_PATH), prefix + "OSGI_CONFIG_PATH"},
                {endpoint(settings, type, "_CURRENT_USER_PATH", DEFAULT_CURRENT_USER_PATH),
                        prefix + "CURRENT_USER_PATH"},
                {endpoint(settings, type, "_LOGIN_PATH", DEFAULT_LOGIN_PATH), prefix + "LOGIN_PATH"},
                {endpoint(settings, type, "_USER_QUERY_PATH", PermissionAudit.DEFAULT_QUERY_PATH),
                        prefix + "USER_QUERY_PATH"}};
        for (String[] pair : pairs) {
            String problem = badPath(pair[0]);
            if (problem != null) {
                return "invalid " + pair[1] + ": " + problem;
            }
        }
        return null;
    }

    /** The result of the sanity checks: each finding, and the raw data kept for a snapshot. */
    private record SanityRun(List<Finding> findings, ObjectNode snapshot) {
    }

    /** Runs the three modules and returns their findings and raw data. Prints nothing. */
    private static SanityRun collectSanity(AemHttpClient client, EnvironmentConfig cfg, EnvironmentType type,
                                           Map<String, String> settings, List<String> pages) {
        String prefix = type.name() + "_";
        String bundlesPath = pingPath(settings, type);
        String osgiConfigPath = endpoint(settings, type, "_OSGI_CONFIG_PATH", DEFAULT_OSGI_CONFIG_PATH);
        String currentUserPath = endpoint(settings, type, "_CURRENT_USER_PATH", DEFAULT_CURRENT_USER_PATH);
        String loginPath = endpoint(settings, type, "_LOGIN_PATH", DEFAULT_LOGIN_PATH);
        String queryPath = endpoint(settings, type, "_USER_QUERY_PATH", PermissionAudit.DEFAULT_QUERY_PATH);

        List<Finding> all = new ArrayList<>();
        ObjectNode snap = JSON.createObjectNode();
        snap.put("environment", cfg.name());
        snap.put("baseUrl", cfg.baseUri().toString());
        snap.put("timestamp", Instant.now().toString());

        snap.put("runModes", RunModes.summary(client));

        // The login page must show the form. No credentials are sent here. It runs first, then the author pages.
        all.add(LoginCheck.loginPageFinding(client, loginPath));

        ArrayNode pageSnap = JSON.createArrayNode();
        for (AuthorSanityCheck.PageResult r : new AuthorSanityCheck(client).run(pages)) {
            all.add(new Finding("Author sanity", r.path(), r.passed(), r.detail()));
            pageSnap.addObject().put("path", r.path()).put("passed", r.passed()).put("detail", r.detail());
        }
        snap.set("authorPages", pageSnap);

        ModuleResult osgi = new OsgiR6Check(client, bundlesPath, osgiConfigPath,
                csv(settings, prefix + "OSGI_REQUIRED_BUNDLES"),
                csv(settings, prefix + "OSGI_CONFIG_PIDS")).run();
        all.addAll(osgi.findings());
        snap.set("osgi", osgi.snapshot());

        ModuleResult perms = new PermissionAudit(client, currentUserPath, cfg.username(),
                csv(settings, prefix + "EXPECTED_GROUPS"), queryPath).run();
        all.addAll(perms.findings());
        snap.set("permissions", perms.snapshot());
        return new SanityRun(all, snap);
    }

    /** Runs all three modules and prints one list grouped by module. */
    private static int runSanity(AemHttpClient client, EnvironmentConfig cfg, EnvironmentType type,
                                 Map<String, String> settings, List<String> pages, boolean snapshot,
                                 PrintStream out, PrintStream err) {
        String problem = sanityPathProblem(settings, type);
        if (problem != null) {
            err.println(problem);
            return 2;
        }
        SanityRun run = collectSanity(client, cfg, type, settings, pages);
        List<Finding> all = run.findings();

        out.println("Sanity on " + cfg.name().toUpperCase(Locale.ROOT) + " (" + cfg.baseUri() + ")");
        out.println("Run modes: " + run.snapshot().path("runModes").asText());
        String currentModule = null;
        int passed = 0;
        for (Finding f : all) {
            if (!f.module().equals(currentModule)) {
                currentModule = f.module();
                out.println("[" + currentModule + "]");
            }
            if (f.passed()) {
                passed++;
            }
            out.println("  " + (f.passed() ? "PASS" : "FAIL") + "  " + f.subject() + "  " + f.detail());
        }
        out.println();
        out.println("Result: " + passed + "/" + all.size() + " checks passed");

        if (snapshot) {
            try {
                Path saved = writeSnapshot(run.snapshot(), cfg.name(), Path.of(SNAPSHOT_DIR));
                out.println("Snapshot saved to " + saved);
            } catch (IOException e) {
                err.println("cannot write snapshot: " + e.getMessage());
                return 2;
            }
        }
        return passed == all.size() ? 0 : 1;
    }

    /**
     * Writes one environment's snapshot to {@code outFile}: the same checks and raw data as sanity, saved for
     * a later {@code diff}. Exit 0 when written, even if some checks failed; the failures are in the file.
     */
    private static int runSnapshot(AemHttpClient client, EnvironmentConfig cfg, EnvironmentType type,
                                   Map<String, String> settings, Path outFile, PrintStream out, PrintStream err) {
        String problem = sanityPathProblem(settings, type);
        if (problem != null) {
            err.println(problem);
            return 2;
        }
        List<String> pages = sanityPages(settings, type);
        if (pages.isEmpty()) {
            return usageError(err, "no pages to check: set " + type.name() + SITE_PAGE_SUFFIX + " in the config file");
        }
        SanityRun run = collectSanity(client, cfg, type, settings, pages);
        long passed = run.findings().stream().filter(Finding::passed).count();
        try {
            Path parent = outFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(run.snapshot()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("cannot write snapshot " + outFile + ": " + e.getMessage());
            return 2;
        }
        out.println("Snapshot of " + cfg.name().toUpperCase(Locale.ROOT) + " written to " + outFile
                + " (" + passed + "/" + run.findings().size() + " checks passed when captured)");
        return 0;
    }

    /**
     * Compares two snapshot files. Exit 0 when they match or the only differences are allowed, 1 when there are
     * differences that aren't allowed (the release gate), 2 when a file can't be read.
     */
    private static int runDiff(List<String> positionals, Path allowFile, Path reportFile,
                               PrintStream out, PrintStream err) {
        if (positionals.size() != 2) {
            return usageError(err, "diff needs two snapshot files: diff BEFORE.json AFTER.json "
                    + "[--allow FILE] [--report FILE]");
        }
        Path before = Path.of(positionals.get(0));
        Path after = Path.of(positionals.get(1));
        JsonNode beforeJson;
        JsonNode afterJson;
        try {
            beforeJson = JSON.readTree(Files.readString(before, StandardCharsets.UTF_8));
            afterJson = JSON.readTree(Files.readString(after, StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            err.println("snapshot file not found: " + e.getFile());
            return 2;
        } catch (IOException e) {
            err.println("cannot read snapshot: " + e.getMessage());
            return 2;
        }

        List<AllowRule> rules = List.of();
        if (allowFile != null) {
            try {
                rules = AllowList.load(allowFile);
            } catch (NoSuchFileException e) {
                err.println("allow file not found: " + allowFile);
                return 2;
            } catch (IOException e) {
                err.println("cannot read allow file " + allowFile + ": " + e.getMessage());
                return 2;
            } catch (IllegalArgumentException e) {
                err.println("invalid allow file " + allowFile + ": " + e.getMessage());
                return 2;
            }
        }

        List<Finding> findings = SnapshotDiff.compare(beforeJson, afterJson, rules);
        out.println("Diff: " + before + " -> " + after
                + (allowFile == null ? "" : " (allow: " + allowFile + ")"));
        for (Finding f : findings) {
            String status = DiffReport.status(f);
            out.println("  " + String.format(Locale.ROOT, "%-7s", status) + " " + f.subject() + "  " + f.detail());
        }
        ObjectNode report = DiffReport.build(before.toString(), after.toString(),
                allowFile == null ? null : allowFile.toString(), findings);
        if (reportFile != null) {
            try {
                DiffReport.write(report, reportFile);
                out.println("Report written to " + reportFile);
            } catch (IOException e) {
                err.println("cannot write report " + reportFile + ": " + e.getMessage());
                return 2;
            }
        }
        int drift = report.path("drift").asInt();
        out.println();
        out.println("Result: " + report.path("result").asText() + " (same " + report.path("same").asInt()
                + ", allowed " + report.path("allowed").asInt() + ", drift " + drift + ")");
        return drift > 0 ? 1 : 0;
    }

    static Path writeSnapshot(ObjectNode snap, String envName, Path dir) throws IOException {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Files.createDirectories(dir);
        Path file = dir.resolve(envName.toLowerCase(Locale.ROOT) + "-" + stamp + ".json");
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snap));
        return file;
    }

    /** The Felix bundle endpoint for this environment, or the default. Used by sanity and felix. */
    static String pingPath(Map<String, String> settings, EnvironmentType type) {
        return endpoint(settings, type, PING_PATH_SUFFIX, DEFAULT_PING_PATH);
    }

    /** Reads AEM_{NAME}{suffix} from the settings, or returns the default when it is unset or blank. */
    static String endpoint(Map<String, String> settings, EnvironmentType type, String suffix, String fallback) {
        String raw = settings.get("AEM_" + type.name() + suffix);
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    /** A message if the path cannot be used as an endpoint, otherwise null. */
    static String badPath(String path) {
        if (!path.startsWith("/")) {
            return "must start with '/' (got " + path + ")";
        }
        return null;
    }

    /** Reads AEM_{NAME}_SITE_PAGE, a comma-separated list of paths, from the settings. */
    static List<String> configuredPages(Map<String, String> settings, EnvironmentType type) {
        return csv(settings, "AEM_" + type.name() + SITE_PAGE_SUFFIX);
    }

    /** Splits a comma-separated setting, trimming entries and dropping blank ones. */
    static List<String> csv(Map<String, String> settings, String key) {
        List<String> values = new ArrayList<>();
        String raw = settings.get(key.startsWith("AEM_") ? key : "AEM_" + key);
        if (raw == null) {
            return values;
        }
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    /**
     * Reads each environment in {@code envNames} and compares them with the first one. Exit 0 when they
     * match, 1 when any drift is found, 2 when an environment can't be read.
     */
    private static int runDrift(List<String> envNames, Path configDir, Map<String, String> env,
                                PrintStream out, PrintStream err) {
        if (envNames.size() < 2) {
            return usageError(err, "drift needs at least two environments, for example --envs qa,uat,prod");
        }
        List<EnvironmentSnapshot> snapshots = new ArrayList<>();
        for (String name : envNames) {
            EnvironmentType type;
            try {
                type = EnvironmentType.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return usageError(err, "unknown environment: " + name);
            }
            Path file = configDir.resolve(name.toLowerCase(Locale.ROOT) + ".env");
            EnvironmentFileLoader.LoadResult result;
            Map<String, String> settings;
            try {
                result = EnvironmentFileLoader.load(file, env);
                settings = EnvironmentFileLoader.settings(file, env);
            } catch (NoSuchFileException e) {
                err.println("config file not found: " + file);
                return 2;
            } catch (IOException | IllegalArgumentException e) {
                err.println("cannot read config file " + file + ": " + e.getMessage());
                return 2;
            }
            EnvironmentConfig cfg = result.loaded().get(type);
            if (cfg == null) {
                err.println(name.toLowerCase(Locale.ROOT) + " is not ready (" + file + "): "
                        + result.skipped().get(type));
                return 2;
            }
            warnPlainHttp(cfg, err);
            snapshots.add(SnapshotCollector.collect(name.toLowerCase(Locale.ROOT), new AemHttpClient(cfg),
                    pingPath(settings, type),
                    endpoint(settings, type, "_OSGI_CONFIG_PATH", DEFAULT_OSGI_CONFIG_PATH),
                    endpoint(settings, type, "_USER_QUERY_PATH", PermissionAudit.DEFAULT_QUERY_PATH)));
        }

        List<Finding> findings = DriftComparer.compare(snapshots);
        out.println("Drift check: baseline " + envNames.get(0).toUpperCase(Locale.ROOT)
                + ", compared with " + String.join(", ", envNames.subList(1, envNames.size())).toUpperCase(Locale.ROOT));
        int same = 0;
        for (Finding f : findings) {
            if (f.passed()) {
                same++;
            }
            out.println("  " + (f.passed() ? "SAME " : "DRIFT") + "  " + f.subject() + "  " + f.detail());
        }
        out.println();
        boolean drift = same != findings.size();
        out.println("Result: " + same + "/" + findings.size() + " comparisons match"
                + (drift ? " (drift found)" : ""));
        return drift ? 1 : 0;
    }

    /** Checks the login page, then that the configured user can log in through the form. */
    private static int runLogin(AemHttpClient client, EnvironmentConfig cfg, EnvironmentType type,
                                Map<String, String> settings, PrintStream out, PrintStream err) {
        String loginPath = endpoint(settings, type, "_LOGIN_PATH", DEFAULT_LOGIN_PATH);
        String submitPath = endpoint(settings, type, "_LOGIN_SUBMIT_PATH", DEFAULT_LOGIN_SUBMIT_PATH);
        for (String[] pathSetting : new String[][] {
                {loginPath, "AEM_" + type.name() + "_LOGIN_PATH"},
                {submitPath, "AEM_" + type.name() + "_LOGIN_SUBMIT_PATH"}}) {
            String problem = badPath(pathSetting[0]);
            if (problem != null) {
                err.println("invalid " + pathSetting[1] + ": " + problem);
                return 2;
            }
        }
        List<Finding> findings = new LoginCheck(client, loginPath, submitPath,
                cfg.username(), cfg.password()).run();
        out.println("Login on " + cfg.name().toUpperCase(Locale.ROOT) + " (" + cfg.baseUri() + ")");
        int ok = 0;
        for (Finding f : findings) {
            if (f.passed()) {
                ok++;
            }
            out.println("  " + (f.passed() ? "PASS" : "FAIL") + "  " + f.subject() + "  " + f.detail());
        }
        out.println();
        out.println("Result: " + ok + "/" + findings.size() + " login checks passed");
        return ok == findings.size() ? 0 : 1;
    }

    /** Probes every Felix console endpoint configured for this environment. */
    private static int runFelix(AemHttpClient client, EnvironmentConfig cfg, EnvironmentType type,
                                Map<String, String> settings, PrintStream out, PrintStream err) {
        for (FelixEndpoint endpoint : FelixEndpoint.values()) {
            String path = endpoint.resolve(settings, type);
            String problem = badPath(path);
            if (problem != null) {
                err.println("invalid " + endpoint.settingName(type) + ": " + problem);
                return 2;
            }
        }
        List<Finding> findings = FelixProbe.probeAll(client, settings, type);
        out.println("Felix endpoints on " + cfg.name().toUpperCase(Locale.ROOT) + " (" + cfg.baseUri() + ")");
        int ok = 0;
        for (Finding f : findings) {
            if (f.passed()) {
                ok++;
            }
            out.println("  " + (f.passed() ? "PASS" : "FAIL") + "  " + f.subject() + "  " + f.detail());
        }
        out.println();
        out.println("Result: " + ok + "/" + findings.size() + " endpoints answered");
        return ok == findings.size() ? 0 : 1;
    }

    /** Prints a warning on every run that sends credentials over plain HTTP outside LOCAL. */
    static void warnPlainHttp(EnvironmentConfig cfg, PrintStream err) {
        if (cfg.type().requiresTls() && "http".equalsIgnoreCase(cfg.baseUri().getScheme())) {
            err.println("WARNING: " + cfg.name().toUpperCase(Locale.ROOT) + " uses plain HTTP because "
                    + cfg.type().name() + EnvironmentConfig.ALLOW_HTTP_SUFFIX + "=true is set. "
                    + "The username and password are sent unencrypted.");
        }
    }

    private static int usageError(PrintStream err, String message) {
        err.println("error: " + message);
        printUsage(err);
        return 2;
    }

    static void printUsage(PrintStream out) {
        out.println("Usage:");
        out.println("  aem-ops-guard --env local|qa|uat|cloud [--snapshot] sanity");
        out.println("  aem-ops-guard --env local|qa|uat|cloud felix");
        out.println("  aem-ops-guard --env local|qa|uat|cloud login");
        out.println("  aem-ops-guard --env local|qa|uat|cloud snapshot --out FILE");
        out.println("  aem-ops-guard diff BEFORE.json AFTER.json [--allow FILE] [--report FILE]");
        out.println("  aem-ops-guard --envs qa,uat,prod drift");
        out.println();
        out.println("  --env NAME      environment: local, qa, uat or cloud (loads config/<NAME>.env)");
        out.println("  --config FILE   use this settings file instead of config/<NAME>.env");
        out.println("  --out FILE      where snapshot writes its JSON");
        out.println("  --allow FILE    YAML list of approved differences for diff (each needs a reason)");
        out.println("  --report FILE   write the diff result as JSON");
        out.println("  --snapshot      sanity: also save the results to snapshots/<env>-<time>.json");
        out.println("  sanity          login page form, author pages, OSGi R6, permissions");
        out.println("  felix           check that each Felix console endpoint answers (HTTP 2xx)");
        out.println("  login           check the login page loads and the configured user can log in");
        out.println("  snapshot        capture the sanity checks and raw data to a JSON file for diff");
        out.println("  diff            compare two snapshot files; exit 1 if there are differences not allowed");
        out.println("  drift           compare environments live: --envs qa,uat,prod (first is the baseline)");
        out.println("                  --config-dir FOLDER reads the environment files from another folder");
        out.println();
        out.println("Felix endpoints (default path in brackets; AEM_<NAME>_<key> overrides it):");
        for (FelixEndpoint endpoint : FelixEndpoint.values()) {
            out.println("  " + endpoint.label() + " [" + endpoint.defaultPath() + "]");
        }
        out.println();
        out.println("Per-environment keys in config/<NAME>.env (AEM_<NAME>_ prefix):");
        out.println("  URL, USER                    host (with optional context path) and user");
        out.println("  SITE_PAGE                    author page paths, comma-separated");
        out.println("  CRX_DE_PAGE                  CRX DE page, checked in addition to the site pages");
        out.println("  PACK_MGR_PAGE                Package Manager page, checked in addition to the site pages");
        out.println("  PING_PATH                    Felix bundle list (default " + DEFAULT_PING_PATH + ")");
        out.println("  OSGI_CONFIG_PATH             Felix config path (default " + DEFAULT_OSGI_CONFIG_PATH + ")");
        out.println("  OSGI_REQUIRED_BUNDLES        bundles that must be installed: symbolicName[:version[:state]], comma-separated");
        out.println("                               e.g. aem-guides-wknd.core:4.0.4:Active (state defaults to Active)");
        out.println("  OSGI_CONFIG_PIDS             PIDs whose configuration must exist, comma-separated");
        out.println("  CURRENT_USER_PATH            current-user endpoint (default " + DEFAULT_CURRENT_USER_PATH + ")");
        out.println("  EXPECTED_GROUPS              groups the user must belong to, comma-separated");
        out.println("  ALLOW_HTTP                   true to allow plain http:// (credentials unencrypted)");
        out.println();
        out.println("Passwords are read from AEM_<NAME>_PASSWORD environment variables, never from a file.");
        out.println("Exit codes: 0 ok, 1 check failed or drift found, 2 usage or configuration error.");
    }
}
