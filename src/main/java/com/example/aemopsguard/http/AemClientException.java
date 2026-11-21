package com.example.aemopsguard.http;

/** Raised when an AEM request fails at the transport level or returns an unexpected status. */
public class AemClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AemClientException(String message) {
        super(message);
    }

    public AemClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
