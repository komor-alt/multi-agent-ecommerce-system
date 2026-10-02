package com.ecommerce.service;

public class RunDeadlineExceededException extends RuntimeException {
    public RunDeadlineExceededException() {
        super("run_deadline_exceeded");
    }
}
