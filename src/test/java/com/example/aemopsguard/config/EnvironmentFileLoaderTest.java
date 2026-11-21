package com.example.aemopsguard.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentFileLoaderTest {

    private static final String FILE = """
            # comment line
            AEM_LOCAL_URL=http://localhost:4502
            AEM_LOCAL_USER=admin

            AEM_QA_URL=https://qa-author.example.com
            AEM_QA_USER=qa-bot
            AEM_UAT_URL=https://uat-author.example.com
            AEM_UAT_USER=
            AEM_CLOUD_URL=https://cloud.example.com
            AEM_CLOUD_USER=cloud-bot
            AEM_CLOUD_PASSWORD=should-be-ignored
            """;

    @Test
    void parsesKeyValueLinesAndSkipsCommentsAndBlanks() {
        Map<String, String> values = EnvironmentFileLoader.parse(FILE);

        assertEquals("http://localhost:4502", values.get("AEM_LOCAL_URL"));
        assertEquals("qa-bot", values.get("AEM_QA_USER"));
        assertEquals("", values.get("AEM_UAT_USER"));
        assertFalse(values.containsKey("# comment line"));
    }

    @Test
    void loadsOnlyEnvironmentsWithUrlUserAndPassword() {
        Map<String, String> processEnv = Map.of(
                "AEM_LOCAL_PASSWORD", "admin",
                "AEM_QA_PASSWORD", "qa-pw",
                "AEM_UAT_PASSWORD", "uat-pw",
                "AEM_CLOUD_PASSWORD", "cloud-pw");

        EnvironmentFileLoader.LoadResult result =
                EnvironmentFileLoader.combine(EnvironmentFileLoader.parse(FILE), processEnv);

        assertEquals(3, result.loaded().size());
        assertEquals(URI.create("http://localhost:4502"),
                result.loaded().get(EnvironmentType.LOCAL).baseUri());
        assertEquals("qa-pw", result.loaded().get(EnvironmentType.QA).password());
        assertTrue(result.skipped().get(EnvironmentType.UAT).contains("AEM_UAT_USER"));
    }

    @Test
    void passwordFromFileIsIgnoredAndEnvironmentVariableIsUsed() {
        Map<String, String> processEnv = Map.of("AEM_CLOUD_PASSWORD", "from-env");

        EnvironmentFileLoader.LoadResult result =
                EnvironmentFileLoader.combine(EnvironmentFileLoader.parse(FILE), processEnv);

        assertEquals("from-env", result.loaded().get(EnvironmentType.CLOUD).password());
    }

    @Test
    void missingPasswordEnvironmentVariableSkipsTheEnvironment() {
        EnvironmentFileLoader.LoadResult result =
                EnvironmentFileLoader.combine(EnvironmentFileLoader.parse(FILE), Map.of());

        assertFalse(result.loaded().containsKey(EnvironmentType.QA));
        assertTrue(result.skipped().get(EnvironmentType.QA).contains("AEM_QA_PASSWORD"));
    }

    @Test
    void localPasswordCanBeSetInTheFile() {
        String text = "AEM_LOCAL_URL=http://localhost:4502\nAEM_LOCAL_USER=admin\nAEM_LOCAL_PASSWORD=file-pw\n";

        EnvironmentFileLoader.LoadResult result =
                EnvironmentFileLoader.combine(EnvironmentFileLoader.parse(text), Map.of());

        assertEquals("file-pw", result.loaded().get(EnvironmentType.LOCAL).password());
    }

    @Test
    void environmentVariableOverridesLocalPasswordInTheFile() {
        String text = "AEM_LOCAL_URL=http://localhost:4502\nAEM_LOCAL_USER=admin\nAEM_LOCAL_PASSWORD=file-pw\n";

        EnvironmentFileLoader.LoadResult result =
                EnvironmentFileLoader.combine(EnvironmentFileLoader.parse(text), Map.of("AEM_LOCAL_PASSWORD", "env-pw"));

        assertEquals("env-pw", result.loaded().get(EnvironmentType.LOCAL).password());
    }

    @Test
    void placeholderLocalPasswordCountsAsMissing() {
        String text = "AEM_LOCAL_URL=http://localhost:4502\nAEM_LOCAL_USER=admin\n"
                + "AEM_LOCAL_PASSWORD=REPLACE_WITH_LOCAL_PASSWORD\n";

        EnvironmentFileLoader.LoadResult result =
                EnvironmentFileLoader.combine(EnvironmentFileLoader.parse(text), Map.of());

        assertFalse(result.loaded().containsKey(EnvironmentType.LOCAL));
        assertTrue(result.skipped().get(EnvironmentType.LOCAL).contains("placeholder"));
    }

    @Test
    void qaCanUsePlainHttpWhenItsFileOptsIn() {
        String text = "AEM_QA_URL=http://qa.internal\nAEM_QA_USER=bot\nAEM_QA_ALLOW_HTTP=true\n";

        EnvironmentFileLoader.LoadResult result =
                EnvironmentFileLoader.combine(EnvironmentFileLoader.parse(text), Map.of("AEM_QA_PASSWORD", "pw"));

        assertEquals("http://qa.internal", result.loaded().get(EnvironmentType.QA).baseUri().toString());
    }

    @Test
    void otherEnvironmentsStillIgnorePasswordsInTheFile() {
        String text = "AEM_QA_URL=https://qa.example.com\nAEM_QA_USER=bot\nAEM_QA_PASSWORD=file-qa\n";

        EnvironmentFileLoader.LoadResult result =
                EnvironmentFileLoader.combine(EnvironmentFileLoader.parse(text), Map.of());

        assertFalse(result.loaded().containsKey(EnvironmentType.QA));
        assertTrue(result.skipped().get(EnvironmentType.QA).contains("AEM_QA_PASSWORD"));
    }

    @Test
    void environmentVariableOverridesFileUrl() {
        Map<String, String> processEnv = Map.of(
                "AEM_LOCAL_URL", "http://localhost:4503",
                "AEM_LOCAL_PASSWORD", "admin");

        EnvironmentFileLoader.LoadResult result =
                EnvironmentFileLoader.combine(EnvironmentFileLoader.parse(FILE), processEnv);

        assertEquals(URI.create("http://localhost:4503"),
                result.loaded().get(EnvironmentType.LOCAL).baseUri());
    }

    @Test
    void httpUrlForQaIsRejectedWhenPasswordIsSet() {
        String text = "AEM_QA_URL=http://qa-author.example.com\nAEM_QA_USER=qa-bot\n";
        Map<String, String> processEnv = Map.of("AEM_QA_PASSWORD", "qa-pw");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> EnvironmentFileLoader.combine(EnvironmentFileLoader.parse(text), processEnv));
    }

    @Test
    void malformedLineIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> EnvironmentFileLoader.parse("NOT_A_PAIR"));
    }
}
