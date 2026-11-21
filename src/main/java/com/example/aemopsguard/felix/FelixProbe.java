package com.example.aemopsguard.felix;

import com.example.aemopsguard.check.Finding;
import com.example.aemopsguard.http.AemClientException;
import com.example.aemopsguard.http.AemHttpClient;
import com.example.aemopsguard.http.AemResponse;

import java.util.ArrayList;
import java.util.List;

/** Checks that each Felix endpoint answers with HTTP 2xx. */
public final class FelixProbe {

    public static final String MODULE = "Felix endpoints";

    private FelixProbe() {
    }

    public static Finding probe(AemHttpClient client, String label, String path) {
        String subject = label + "  " + path;
        try {
            AemResponse response = client.get(path);
            if (response.isSuccessful()) {
                return new Finding(MODULE, subject, true, "HTTP " + response.status());
            }
            return new Finding(MODULE, subject, false, "HTTP " + response.status());
        } catch (AemClientException | IllegalArgumentException e) {
            return new Finding(MODULE, subject, false, "request failed: " + e.getMessage());
        }
    }

    /** Probes every endpoint, using the paths resolved for this environment. */
    public static List<Finding> probeAll(AemHttpClient client, java.util.Map<String, String> settings,
                                         com.example.aemopsguard.config.EnvironmentType type) {
        List<Finding> findings = new ArrayList<>();
        for (FelixEndpoint endpoint : FelixEndpoint.values()) {
            findings.add(probe(client, endpoint.label(), endpoint.resolve(settings, type)));
        }
        return findings;
    }
}
