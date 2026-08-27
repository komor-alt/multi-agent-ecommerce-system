package com.ecommerce.model;

public record BlackboardWriteResult(boolean accepted, String denialReason) {
    public static BlackboardWriteResult ok() {
        return new BlackboardWriteResult(true, null);
    }

    public static BlackboardWriteResult denied(String reason) {
        return new BlackboardWriteResult(false, reason);
    }
}
