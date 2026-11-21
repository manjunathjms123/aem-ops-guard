package com.example.aemopsguard;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigFileLocationTest {

    @Test
    void eachEnvironmentHasItsOwnFileUnderConfig() {
        assertEquals(Path.of("config", "local.env"), Main.defaultConfigFile("local"));
        assertEquals(Path.of("config", "qa.env"), Main.defaultConfigFile("qa"));
        assertEquals(Path.of("config", "uat.env"), Main.defaultConfigFile("uat"));
        assertEquals(Path.of("config", "cloud.env"), Main.defaultConfigFile("cloud"));
    }

    @Test
    void environmentNameIsCaseInsensitive() {
        assertEquals(Path.of("config", "qa.env"), Main.defaultConfigFile("QA"));
    }
}
