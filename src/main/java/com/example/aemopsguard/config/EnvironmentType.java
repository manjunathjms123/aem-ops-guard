package com.example.aemopsguard.config;

/** The kinds of AEM instance the tool can talk to. */
public enum EnvironmentType {
    LOCAL,
    QA,
    UAT,
    CLOUD,
    PROD;

    /** Only LOCAL may use plain HTTP; everything else must be HTTPS. */
    public boolean requiresTls() {
        return this != LOCAL;
    }
}
