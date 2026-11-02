package dev.planetonotion.common.exceptions;

import dev.planetonotion.common.ApiErrorCodes;
import dev.planetonotion.models.ApiDtos.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps application exceptions to the API's consistent error response shape. */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Preserves the status, error code, and message of a known service failure. */
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ApiError> handleServiceException(ServiceException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.errorCode(), exception.getMessage()));
    }

    /** Maps upstream not-found errors to 404 and other provider errors to 502. */
    @ExceptionHandler(IntegrationException.class)
    public ResponseEntity<ApiError> handleIntegrationException(IntegrationException exception) {
        HttpStatus status = exception.upstreamStatus() == HttpStatus.NOT_FOUND.value()
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status)
                .body(new ApiError(exception.errorCode(), exception.getMessage()));
    }

    /**
     * Returns a standard 400 response for invalid, malformed, or incomplete
     * requests.
     */
    @ExceptionHandler({ MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class })
    public ResponseEntity<ApiError> handleBadRequest(Exception exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError(ApiErrorCodes.Application.INVALID_REQUEST,
                        "The request is missing a required value or contains an invalid value."));
    }

    /**
     * Returns a generic 500 response without exposing internal exception details.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedException(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(ApiErrorCodes.Application.INTERNAL_ERROR, "The request could not be completed."));
    }
}
