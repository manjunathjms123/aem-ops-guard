package com.example.aemopsguard.http;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** A completed response from an AEM endpoint. */
public record AemResponse(URI uri, int status, String body, Map<String, List<String>> headers) {

    public boolean isSuccessful() {
        return status >= 200 && status < 300;
    }

    /** All values of a header, matched case-insensitively. Empty when the header is absent. */
    public List<String> header(String name) {
        if (headers == null) {
            return List.of();
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return List.of();
    }
}
