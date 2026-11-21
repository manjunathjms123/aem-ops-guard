package com.example.aemopsguard.login;

import com.example.aemopsguard.check.Finding;
import com.example.aemopsguard.http.AemClientException;
import com.example.aemopsguard.http.AemHttpClient;
import com.example.aemopsguard.http.AemResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Checks that the login form works for the configured user.
 *
 * <ol>
 *   <li>The login page must load (HTTP 200) and contain the username field.</li>
 *   <li>Posting the username and password to the form must issue a {@code login-token} cookie.
 *       AEM sends no such cookie for a rejected login.</li>
 * </ol>
 *
 * <p>The password is sent only in the form POST. It never appears in a finding.
 */
public final class LoginCheck {

    public static final String MODULE = "Login";
    static final String USERNAME_FIELD = "j_username";
    static final String PASSWORD_FIELD = "j_password";
    static final String TOKEN_COOKIE = "login-token=";

    private final AemHttpClient client;
    private final String loginPagePath;
    private final String submitPath;
    private final String username;
    private final String password;

    public LoginCheck(AemHttpClient client, String loginPagePath, String submitPath,
                      String username, String password) {
        this.client = Objects.requireNonNull(client, "client");
        this.loginPagePath = Objects.requireNonNull(loginPagePath, "loginPagePath");
        this.submitPath = Objects.requireNonNull(submitPath, "submitPath");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
    }

    /**
     * Checks that the login page loads and shows the username field. Sends no credentials, so it
     * is safe to run as part of sanity.
     */
    public static Finding loginPageFinding(AemHttpClient client, String loginPagePath) {
        String subject = "login page " + loginPagePath;
        AemResponse page;
        try {
            page = client.getAnonymous(loginPagePath);
        } catch (AemClientException | IllegalArgumentException e) {
            return new Finding(MODULE, subject, false, "request failed: " + e.getMessage());
        }
        if (!page.isSuccessful()) {
            return new Finding(MODULE, subject, false, "HTTP " + page.status());
        }
        boolean hasForm = page.body() != null && page.body().contains(USERNAME_FIELD);
        return new Finding(MODULE, subject, hasForm,
                hasForm ? "HTTP 200, login form present" : "HTTP 200 but no login form found");
    }

    public List<Finding> run() {
        List<Finding> findings = new ArrayList<>();
        Finding pageFinding = loginPageFinding(client, loginPagePath);
        findings.add(pageFinding);
        if (!pageFinding.passed()) {
            return findings;
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put(USERNAME_FIELD, username);
        form.put(PASSWORD_FIELD, password);
        form.put("j_validate", "true");

        AemResponse submit;
        try {
            submit = client.postForm(submitPath, form);
        } catch (AemClientException | IllegalArgumentException e) {
            findings.add(new Finding(MODULE, "login as " + username, false,
                    "request failed: " + e.getMessage()));
            return findings;
        }
        boolean token = submit.header("Set-Cookie").stream()
                .anyMatch(cookie -> cookie.trim().startsWith(TOKEN_COOKIE)
                        && cookie.trim().length() > TOKEN_COOKIE.length());
        findings.add(new Finding(MODULE, "login as " + username, token,
                token ? "login-token issued (HTTP " + submit.status() + ")"
                        : "login rejected: no login-token cookie (HTTP " + submit.status() + ")"));
        return findings;
    }
}
