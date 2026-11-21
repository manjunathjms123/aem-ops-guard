package com.example.aemopsguard.sanity;

import com.example.aemopsguard.http.AemClientException;
import com.example.aemopsguard.http.AemHttpClient;
import com.example.aemopsguard.http.AemResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the instance's run modes from the Sling settings endpoint. The endpoint returns a JSON array of
 * text lines; one of them reads {@code Run Modes = [author, crx3, ...]}.
 */
public final class RunModes {

    public static final String PATH = "/system/console/status-slingsettings.json";
    static final String PREFIX = "Run Modes = ";
    private static final ObjectMapper JSON = new ObjectMapper();

    private RunModes() {
    }

    /** The run modes as a comma-separated list, or a note saying why they couldn't be read. */
    public static String summary(AemHttpClient client) {
        try {
            AemResponse r = client.get(PATH);
            if (!r.isSuccessful()) {
                return "unavailable (HTTP " + r.status() + ")";
            }
            List<String> modes = parse(r.body());
            return modes.isEmpty() ? "not listed by the Sling settings" : String.join(", ", modes);
        } catch (AemClientException | IllegalArgumentException e) {
            return "unavailable (" + e.getMessage() + ")";
        }
    }

    /** Pulls the run modes out of the Sling settings body. Returns an empty list if none are listed. */
    public static List<String> parse(String body) {
        List<String> modes = new ArrayList<>();
        if (body == null) {
            return modes;
        }
        JsonNode lines;
        try {
            lines = JSON.readTree(body);
        } catch (JsonProcessingException e) {
            return modes;
        }
        for (JsonNode line : lines) {
            String text = line.asText("");
            if (text.startsWith(PREFIX)) {
                String list = text.substring(PREFIX.length()).trim();
                if (list.startsWith("[")) {
                    list = list.substring(1);
                }
                if (list.endsWith("]")) {
                    list = list.substring(0, list.length() - 1);
                }
                for (String part : list.split(",")) {
                    if (!part.isBlank()) {
                        modes.add(part.trim());
                    }
                }
                break;
            }
        }
        return modes;
    }
}
