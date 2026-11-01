package dev.planetonotion.common.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void handleIntegrationException_returnsNotFoundForMissingUpstreamResource() {
        IntegrationException exception = new IntegrationException("PLANE_API_ERROR", 404, "Resource not found.");

        var response = handler.handleIntegrationException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().errorCode()).isEqualTo("PLANE_API_ERROR");
    }

    @Test
    void handleIntegrationException_mapsOtherUpstreamFailuresToBadGateway() {
        IntegrationException exception = new IntegrationException("PLANE_API_ERROR", 401, "Upstream auth failed.");

        var response = handler.handleIntegrationException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
