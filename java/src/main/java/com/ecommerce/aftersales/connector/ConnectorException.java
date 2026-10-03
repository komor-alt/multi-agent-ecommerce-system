package com.ecommerce.aftersales.connector;

/** Only stable codes are exposed; upstream response bodies must not be logged. */
public class ConnectorException extends RuntimeException {
    public enum Category { RETRYABLE, TERMINAL, WAITING_EXTERNAL }
    private final Category category;
    public ConnectorException(Category category, String code) {
        super(code);
        this.category = category;
    }
    public Category category() { return category; }
}
