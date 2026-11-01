package dev.planetonotion.common.exceptions;

public class IntegrationException extends RuntimeException {

    private final String errorCode;
    private final int upstreamStatus;

    public IntegrationException(String errorCode, int upstreamStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.upstreamStatus = upstreamStatus;
    }

    public String errorCode() {
        return errorCode;
    }

    public int upstreamStatus() {
        return upstreamStatus;
    }
}
