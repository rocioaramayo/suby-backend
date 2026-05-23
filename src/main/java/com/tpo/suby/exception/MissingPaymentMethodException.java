package com.tpo.suby.exception;

public class MissingPaymentMethodException extends RuntimeException {

    public MissingPaymentMethodException(String message) {
        super(message);
    }
}
