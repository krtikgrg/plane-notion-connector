package dev.planetonotion.common.exceptions;

import org.springframework.http.HttpStatus;

public class ServiceException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public ServiceException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }
}
