package com.example.aemopsguard.sanity;

import com.example.aemopsguard.http.AemClientException;
import com.example.aemopsguard.http.AemHttpClient;
import com.example.aemopsguard.http.AemResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Checks that author pages render. A page passes when it ends at HTTP 200 with an HTML body and no
 * login form. Redirects are followed to a valid page on the same author (for example
 * {@code /sites.html} to {@code /sites.html/content}), so the final page is what gets checked.
 * A redirect to the login page fails the check, because it means the session or credentials were rejected.
 */
public final class AuthorSanityCheck {

    /** Field name of the AEM login form. Its presence means the page is the login screen. */
    static final String LOGIN_MARKER = "j_username";
    static final int MAX_REDIRECTS = 3;

    /** The outcome for one page. */
    public record PageResult(String path, boolean passed, String detail) {
    }

    private final AemHttpClient client;

    public AuthorSanityCheck(AemHttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public List<PageResult> run(List<String> pagePaths) {
        List<PageResult> results = new ArrayList<>();
        for (String path : pagePaths) {
            results.add(check(path));
        }
        return results;
    }

    PageResult check(String path) {
        String current = path;
        AemResponse response;
        try {
            response = client.get(current);
            int hops = 0;
            while (isRedirect(response)) {
                if (hops == MAX_REDIRECTS) {
                    return fail(path, "too many redirects (last: " + current + ")");
                }
                String target = sameAuthorPath(response);
                if (target == null) {
                    return fail(path, "HTTP " + response.status() + " redirect to a location outside this author");
                }
                if (target.toLowerCase(Locale.ROOT).contains("login")) {
                    return fail(path, "redirected to the login page (" + target + "); session or credentials rejected");
                }
                current = target;
                response = client.get(current);
                hops++;
            }
        } catch (AemClientException | IllegalArgumentException e) {
            return fail(path, "request failed: " + e.getMessage());
        }

        String via = current.equals(path) ? "" : " (redirected to " + current + ")";
        int status = response.status();
        if (status != 200) {
            return fail(path, "HTTP " + status + via);
        }
        String body = response.body() == null ? "" : response.body();
        if (body.contains(LOGIN_MARKER)) {
            return fail(path, "login form shown; session or credentials rejected" + via);
        }
        if (!body.toLowerCase(Locale.ROOT).contains("<html")) {
            return fail(path, "HTTP 200 but the body is not an HTML page" + via);
        }
        return new PageResult(path, true, "HTTP 200, HTML page" + via);
    }

    private static PageResult fail(String path, String detail) {
        return new PageResult(path, false, detail);
    }

    private static boolean isRedirect(AemResponse response) {
        return response.status() >= 300 && response.status() < 400;
    }

    /**
     * The Location of a redirect as a path on this same author, or null when it points elsewhere or
     * can't be used. A relative Location is used as-is.
     */
    private String sameAuthorPath(AemResponse response) {
        List<String> locations = response.header("Location");
        if (locations.isEmpty() || locations.get(0) == null || locations.get(0).isBlank()) {
            return null;
        }
        String location = locations.get(0).trim();
        if (location.startsWith("/")) {
            return location;
        }
        String base = client.config().baseUri().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (location.startsWith(base + "/")) {
            return location.substring(base.length());
        }
        return null;
    }
}
