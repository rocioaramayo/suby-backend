package com.tpo.suby.exception;

public class InsufficientPaymentMethodBalanceException extends RuntimeException {

    public InsufficientPaymentMethodBalanceException(String message) {
        super(message);
    }
}
