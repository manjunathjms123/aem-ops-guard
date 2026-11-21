package com.example.aemopsguard.config;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Connection settings for one AEM instance.
 *
 * <p>HTTPS is required, except for LOCAL, which may use plain HTTP. Any other environment may use plain
 * HTTP only when it sets {@code AEM_<NAME>_ALLOW_HTTP=true}; the credentials then travel unencrypted.
 *
 * <p>The password is never printed: {@link #toString()} masks it.
 */
public record EnvironmentConfig(
        String name,
        EnvironmentType type,
        URI baseUri,
        String username,
        String password,
        boolean plainHttpAllowed) {

    /** Setting that opts an environment into plain HTTP, for example AEM_QA_ALLOW_HTTP=true. */
    public static final String ALLOW_HTTP_SUFFIX = "_ALLOW_HTTP";

    public EnvironmentConfig {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        if (name.isBlank()) {
            throw new IllegalArgumentException("environment name must not be blank");
        }
        String rawScheme = baseUri.getScheme();
        if (baseUri.getHost() == null || rawScheme == null) {
            throw new IllegalArgumentException("baseUri must be an absolute URL with a host: " + baseUri);
        }
        String scheme = rawScheme.toLowerCase(Locale.ROOT);
        boolean httpOk = scheme.equals("http") && (!type.requiresTls() || plainHttpAllowed);
        if (!scheme.equals("https") && !httpOk) {
            throw new IllegalArgumentException(type + " environments require https; got " + scheme
                    + " for " + name + (type.requiresTls() ? " (set " + type.name() + ALLOW_HTTP_SUFFIX
                    + "=true to allow plain HTTP)" : ""));
        }
    }

    /** HTTPS-only settings: plain HTTP is refused unless the environment is LOCAL. */
    public EnvironmentConfig(String name, EnvironmentType type, URI baseUri, String username, String password) {
        this(name, type, baseUri, username, password, false);
    }

    /**
     * Reads settings from environment variables named AEM_{NAME}_URL, AEM_{NAME}_USER and
     * AEM_{NAME}_PASSWORD, where NAME is the upper-cased environment name (for example QA).
     * {@code AEM_{NAME}_ALLOW_HTTP=true} in the settings allows plain HTTP for this environment.
     */
    public static EnvironmentConfig fromEnvironment(String name, EnvironmentType type, Map<String, String> env) {
        String prefix = "AEM_" + name.toUpperCase() + "_";
        String url = require(env, prefix + "URL");
        String user = require(env, prefix + "USER");
        String password = require(env, prefix + "PASSWORD");
        boolean allowHttp = "true".equalsIgnoreCase(env.getOrDefault(prefix + "ALLOW_HTTP", "").trim());
        return new EnvironmentConfig(name, type, URI.create(url), user, password, allowHttp);
    }

    private static String require(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing environment variable " + key);
        }
        return value;
    }

    @Override
    public String toString() {
        return "EnvironmentConfig[name=" + name + ", type=" + type + ", baseUri=" + baseUri
                + ", username=" + username + ", password=****]";
    }
}
