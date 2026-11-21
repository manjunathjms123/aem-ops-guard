package com.example.aemopsguard.felix;

import com.example.aemopsguard.config.EnvironmentType;

import java.util.Locale;
import java.util.Map;

/**
 * The Felix web console endpoints the tool uses. Each has a default path and a per-environment
 * setting, {@code AEM_<NAME>_<suffix>}, that overrides it.
 *
 * <p>Two endpoints keep their existing keys so that {@code sanity}, {@code felix} and the
 * configuration files stay in sync: bundles uses {@code PING_PATH} and configuration manager uses
 * {@code OSGI_CONFIG_PATH}.
 */
public enum FelixEndpoint {
    CONSOLE("Console root", "_FELIX_CONSOLE_PATH", "/system/console"),
    BUNDLES("Bundles (install, start, stop, refresh)", "_PING_PATH", "/system/console/bundles.json"),
    COMPONENTS("Components (Declarative Services)", "_FELIX_COMPONENTS_PATH", "/system/console/components"),
    CONFIG_MANAGER("Configuration Manager", "_OSGI_CONFIG_PATH", "/system/console/configMgr"),
    SERVICES("Registered OSGi services", "_FELIX_SERVICES_PATH", "/system/console/services"),
    LOGS("Log levels (Felix Log Service)", "_FELIX_LOGS_PATH", "/system/console/logs"),
    MEMORY("JVM memory statistics", "_FELIX_MEMORY_PATH", "/system/console/memory"),
    THREADS("Thread dumps", "_FELIX_THREADS_PATH", "/system/console/threads"),
    SLING_SETTINGS("Sling settings (run modes, Sling ID)", "_FELIX_SLING_SETTINGS_PATH",
            "/system/console/status-slingsettings"),
    SLING_JOBS("Sling background jobs", "_FELIX_SLING_JOB_PATH", "/system/console/slingjob"),
    SLING_EVENTS("Sling scheduled tasks", "_FELIX_SLING_EVENT_PATH", "/system/console/slingevent"),
    SLING_LOG("Sling log viewer", "_SLING_LOG_PATH", "/system/console/slinglog"),
    SLING_REQUESTS("Sling request processing history", "_SLING_REQUESTS_PATH", "/system/console/requests"),
    SLING_JCR_RESOLVER("Sling resource resolution (URL to JCR node)", "_SLING_JCRRESOLVER_PATH",
            "/system/console/jcrresolver"),
    SLING_SERVLET_RESOLVER("Sling servlet resolution for a path", "_SLING_SERVLETRESOLVER_PATH",
            "/system/console/servletresolver"),
    SLING_MODELS("Sling Models and injection status", "_SLING_MODELS_PATH", "/system/console/slingmodels"),
    SLING_ADAPTERS("Sling adapter factories", "_SLING_ADAPTERS_PATH", "/system/console/adapters"),
    SLING_FILTERS("Sling request and response filters", "_SLING_FILTER_PATH", "/system/console/slingfilter");

    private final String label;
    private final String settingSuffix;
    private final String defaultPath;

    FelixEndpoint(String label, String settingSuffix, String defaultPath) {
        this.label = label;
        this.settingSuffix = settingSuffix;
        this.defaultPath = defaultPath;
    }

    public String label() {
        return label;
    }

    public String defaultPath() {
        return defaultPath;
    }

    /** The full setting name for an environment, for example AEM_QA_FELIX_LOGS_PATH. */
    public String settingName(EnvironmentType type) {
        return "AEM_" + type.name() + settingSuffix;
    }

    /** The path for this environment: the configured value if set, otherwise the default. */
    public String resolve(Map<String, String> settings, EnvironmentType type) {
        String raw = settings.get(settingName(type));
        return raw == null || raw.isBlank() ? defaultPath : raw.trim();
    }

    /** Lower-case name, used in messages. */
    public String shortName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
