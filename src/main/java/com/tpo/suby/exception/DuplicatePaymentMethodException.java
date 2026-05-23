package com.tpo.suby.exception;

public class DuplicatePaymentMethodException extends RuntimeException {

    public DuplicatePaymentMethodException(String message) {
        super(message);
    }
}
