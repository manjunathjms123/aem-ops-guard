package com.example.aemopsguard.http;

import com.example.aemopsguard.config.EnvironmentConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Thin client for the Sling and Felix endpoints of one AEM instance.
 *
 * <p>Requests use HTTP Basic auth from the environment's configured user. Redirects are not
 * followed, because a redirect to a login page would otherwise look like a successful response.
 */
public final class AemHttpClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final EnvironmentConfig config;
    private final HttpClient httpClient;
    private final String authorizationHeader;

    public AemHttpClient(EnvironmentConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    /** Allows tests and callers to supply their own {@link HttpClient}. */
    public AemHttpClient(EnvironmentConfig config, HttpClient httpClient) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        String credentials = config.username() + ":" + config.password();
        this.authorizationHeader = "Basic "
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    public EnvironmentConfig config() {
        return config;
    }

    /**
     * Sends an authenticated GET to {@code path} with query parameters, which are URL-encoded. The path
     * itself must still pass the usual checks (it can't contain a query string).
     */
    public AemResponse getWithQuery(String path, Map<String, String> params) {
        URI base = resolve(path);
        String query = params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        URI uri = URI.create(base + "?" + query);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", authorizationHeader)
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new AemResponse(uri, response.statusCode(), response.body(), response.headers().map());
        } catch (IOException e) {
            throw new AemClientException("request to " + base + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AemClientException("request to " + base + " was interrupted", e);
        }
    }

    /**
     * Sends a GET with no credentials, as an anonymous browser would. Used for the login page, which
     * AEM may serve differently to signed-in requests.
     */
    public AemResponse getAnonymous(String path) {
        URI uri = resolve(path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "text/html, */*")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new AemResponse(uri, response.statusCode(), response.body(), response.headers().map());
        } catch (IOException e) {
            throw new AemClientException("request to " + uri + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AemClientException("request to " + uri + " was interrupted", e);
        }
    }

    /**
     * Sends a GET to {@code path} under the environment's base URI and returns whatever came back,
     * including non-2xx statuses.
     */
    public AemResponse get(String path) {
        URI uri = resolve(path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", authorizationHeader)
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new AemResponse(uri, response.statusCode(), response.body(), response.headers().map());
        } catch (IOException e) {
            throw new AemClientException("request to " + uri + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AemClientException("request to " + uri + " was interrupted", e);
        }
    }

    /**
     * Posts form fields to {@code path} as a browser login form would. Sends no Basic auth header,
     * so the result shows only whether the form login worked. Redirects are not followed.
     */
    public AemResponse postForm(String path, Map<String, String> fields) {
        URI uri = resolve(path);
        String body = fields.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "text/html, */*")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new AemResponse(uri, response.statusCode(), response.body(), response.headers().map());
        } catch (IOException e) {
            throw new AemClientException("request to " + uri + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AemClientException("request to " + uri + " was interrupted", e);
        }
    }

    /** Like {@link #get(String)}, but throws {@link AemClientException} unless the status is 2xx. */
    public AemResponse getOk(String path) {
        AemResponse response = get(path);
        if (!response.isSuccessful()) {
            throw new AemClientException(
                    "GET " + response.uri() + " returned HTTP " + response.status());
        }
        return response;
    }

    URI resolve(String path) {
        Objects.requireNonNull(path, "path");
        if (!path.startsWith("/") || path.contains("..") || path.contains("?") || path.contains("#")) {
            throw new IllegalArgumentException(
                    "path must start with '/' and contain no '..', '?' or '#': " + path);
        }
        String base = config.baseUri().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }
}
