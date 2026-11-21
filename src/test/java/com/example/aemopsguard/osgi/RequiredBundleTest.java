package com.example.aemopsguard.osgi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequiredBundleTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode bundle(String version, String state) throws Exception {
        return JSON.readTree("{\"symbolicName\":\"aem-guides-wknd.core\",\"version\":\"" + version
                + "\",\"state\":\"" + state + "\"}");
    }

    @Test
    void nameOnlyDefaultsToActiveAndAnyVersion() {
        RequiredBundle r = RequiredBundle.parse("aem-guides-wknd.core");
        assertEquals("aem-guides-wknd.core", r.symbolicName());
        assertEquals("", r.version());
        assertEquals("Active", r.state());
    }

    @Test
    void nameVersionAndStateAreParsed() {
        RequiredBundle r = RequiredBundle.parse("aem-guides-wknd.core:4.0.4:Resolved");
        assertEquals("4.0.4", r.version());
        assertEquals("Resolved", r.state());
    }

    @Test
    void matchingVersionAndStatePass() throws Exception {
        assertNull(RequiredBundle.parse("aem-guides-wknd.core:4.0.4:Active").problem(bundle("4.0.4", "Active")));
    }

    @Test
    void wrongVersionIsReported() throws Exception {
        String problem = RequiredBundle.parse("aem-guides-wknd.core:4.0.4:Active").problem(bundle("4.0.3", "Active"));
        assertEquals("version 4.0.3, expected 4.0.4", problem);
    }

    @Test
    void wrongStateIsReported() throws Exception {
        String problem = RequiredBundle.parse("aem-guides-wknd.core:4.0.4:Active").problem(bundle("4.0.4", "Resolved"));
        assertEquals("state Resolved, expected Active", problem);
    }

    @Test
    void omittedVersionAcceptsAnyVersion() throws Exception {
        assertNull(RequiredBundle.parse("aem-guides-wknd.core").problem(bundle("9.9.9", "Active")));
    }

    @Test
    void describeShowsStateAndVersion() throws Exception {
        assertTrue(RequiredBundle.describe(bundle("4.0.4", "Active")).equals("Active, version 4.0.4"));
    }

    @Test
    void tooManyPartsIsAnError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RequiredBundle.parse("a:1:Active:extra"));
        assertTrue(e.getMessage().contains("symbolicName[:version[:state]]"), e.getMessage());
    }

    @Test
    void missingNameIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> RequiredBundle.parse(":4.0.4:Active"));
    }
}
