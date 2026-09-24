package com.brandsmith.api.session;

public class OwnerMismatchException extends RuntimeException {

    public OwnerMismatchException() {
        super("Not authorized for this session");
    }
}
