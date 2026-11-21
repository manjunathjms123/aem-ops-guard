package com.example.aemopsguard.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentConfigTest {

    @Test
    void readsSettingsFromEnvironmentVariables() {
        Map<String, String> env = Map.of(
                "AEM_QA_URL", "https://qa.example.com",
                "AEM_QA_USER", "ops-bot",
                "AEM_QA_PASSWORD", "s3cret");

        EnvironmentConfig config = EnvironmentConfig.fromEnvironment("qa", EnvironmentType.QA, env);

        assertEquals("qa", config.name());
        assertEquals(EnvironmentType.QA, config.type());
        assertEquals(URI.create("https://qa.example.com"), config.baseUri());
        assertEquals("ops-bot", config.username());
        assertEquals("s3cret", config.password());
    }

    @Test
    void missingVariableNamesTheKey() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EnvironmentConfig.fromEnvironment("uat", EnvironmentType.UAT,
                        Map.of("AEM_UAT_URL", "https://uat.example.com", "AEM_UAT_USER", "u")));
        assertTrue(e.getMessage().contains("AEM_UAT_PASSWORD"));
    }

    @Test
    void toStringMasksPassword() {
        EnvironmentConfig config = new EnvironmentConfig(
                "cloud", EnvironmentType.CLOUD, URI.create("https://cloud.example.com"), "bot", "hunter2");

        String text = config.toString();

        assertFalse(text.contains("hunter2"));
        assertTrue(text.contains("password=****"));
    }

    @Test
    void nonLocalEnvironmentRejectsPlainHttp() {
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentConfig(
                "qa", EnvironmentType.QA, URI.create("http://qa.example.com"), "bot", "pw"));
    }

    @Test
    void uatAndCloudRejectPlainHttp() {
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentConfig(
                "uat", EnvironmentType.UAT, URI.create("http://uat.example.com"), "bot", "pw"));
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentConfig(
                "cloud", EnvironmentType.CLOUD, URI.create("http://author.adobeaemcloud.com"), "bot", "pw"));
    }

    @Test
    void everyNonLocalEnvironmentAcceptsHttps() {
        for (EnvironmentType type : new EnvironmentType[] {EnvironmentType.QA, EnvironmentType.UAT, EnvironmentType.CLOUD}) {
            EnvironmentConfig config = new EnvironmentConfig(
                    type.name().toLowerCase(), type, URI.create("https://" + type.name().toLowerCase() + ".example.com"),
                    "bot", "pw");
            assertEquals("https", config.baseUri().getScheme());
        }
    }

    @Test
    void schemeCheckIgnoresCase() {
        EnvironmentConfig config = new EnvironmentConfig(
                "qa", EnvironmentType.QA, URI.create("HTTPS://qa.example.com"), "bot", "pw");
        assertEquals("HTTPS", config.baseUri().getScheme());
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentConfig(
                "qa", EnvironmentType.QA, URI.create("HTTP://qa.example.com"), "bot", "pw"));
    }

    @Test
    void plainHttpIsAllowedForANonLocalEnvironmentOnlyWhenOptedIn() {
        EnvironmentConfig config = new EnvironmentConfig("qa", EnvironmentType.QA,
                URI.create("http://qa.example.com"), "bot", "pw", true);
        assertEquals("http", config.baseUri().getScheme());
        assertTrue(config.plainHttpAllowed());
    }

    @Test
    void optInReadsTheAllowHttpSettingFromTheEnvironment() {
        Map<String, String> env = Map.of(
                "AEM_UAT_URL", "http://uat.internal",
                "AEM_UAT_USER", "bot",
                "AEM_UAT_PASSWORD", "pw",
                "AEM_UAT_ALLOW_HTTP", "true");

        EnvironmentConfig config = EnvironmentConfig.fromEnvironment("uat", EnvironmentType.UAT, env);

        assertEquals("http", config.baseUri().getScheme());
        assertTrue(config.plainHttpAllowed());
    }

    @Test
    void optInWithAnythingOtherThanTrueStaysHttpsOnly() {
        Map<String, String> env = Map.of(
                "AEM_UAT_URL", "http://uat.internal",
                "AEM_UAT_USER", "bot",
                "AEM_UAT_PASSWORD", "pw",
                "AEM_UAT_ALLOW_HTTP", "yes please");

        assertThrows(IllegalArgumentException.class,
                () -> EnvironmentConfig.fromEnvironment("uat", EnvironmentType.UAT, env));
    }

    @Test
    void localEnvironmentAllowsPlainHttp() {
        EnvironmentConfig config = new EnvironmentConfig(
                "local", EnvironmentType.LOCAL, URI.create("http://localhost:4502"), "admin", "admin");
        assertEquals("http", config.baseUri().getScheme());
    }

    @Test
    void rejectsUrlWithoutHost() {
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentConfig(
                "qa", EnvironmentType.QA, URI.create("/relative/path"), "bot", "pw"));
    }
}
