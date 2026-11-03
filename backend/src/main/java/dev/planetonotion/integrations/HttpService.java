package dev.planetonotion.integrations;

import dev.planetonotion.common.ApiErrorCodes;
import dev.planetonotion.common.exceptions.IntegrationException;
import dev.planetonotion.common.PlaneToNotionEnums.IntegrationProvider;
import java.net.URI;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Executes provider HTTP requests and translates failures into integration
 * errors.
 */
@Component
public class HttpService {

    /**
     * Sends a GET request and deserializes its response body. The URI may be a
     * relative endpoint path or an absolute URI string.
     *
     * @param client       provider-configured HTTP client
     * @param provider     provider used to select integration error codes and
     *                     messages
     * @param uri          request URI or endpoint path
     * @param responseType response body type
     * @param <T>          response body type
     * @return deserialized response body
     * @throws IntegrationException if the provider request fails or returns no body
     */
    public <T> T get(RestClient client, IntegrationProvider provider, String uri, Class<T> responseType) {
        return typed(provider, () -> client.get().uri(uri).retrieve().body(responseType));
    }

    /**
     * Sends a GET request using a type reference for generic response bodies,
     * such as {@code Page<Project>}.
     *
     * @param client       provider-configured HTTP client
     * @param provider     provider used to select integration error codes and
     *                     messages
     * @param uri          fully constructed request URI
     * @param responseType response type, including any nested generic types
     * @param <T>          response body type
     * @return deserialized response body
     * @throws IntegrationException if the provider request fails or returns no body
     */
    public <T> T get(RestClient client, IntegrationProvider provider, URI uri,
            ParameterizedTypeReference<T> responseType) {
        return typed(provider, () -> client.get().uri(uri).retrieve().body(responseType));
    }

    /**
     * Sends a POST request with a body and deserializes the response.
     *
     * @param client       provider-configured HTTP client
     * @param provider     provider used to select integration error codes and
     *                     messages
     * @param uri          request URI or endpoint path
     * @param body         request body
     * @param responseType response body type
     * @param <T>          response body type
     * @return deserialized response body
     * @throws IntegrationException if the provider request fails or returns no body
     */
    public <T> T post(RestClient client, IntegrationProvider provider, String uri, Object body,
            Class<T> responseType) {
        return typed(provider, () -> client.post().uri(uri).body(body).retrieve().body(responseType));
    }

    /**
     * Sends a PATCH request with a body and deserializes the response.
     *
     * @param client       provider-configured HTTP client
     * @param provider     provider used to select integration error codes and
     *                     messages
     * @param uri          request URI or endpoint path
     * @param body         request body
     * @param responseType response body type
     * @param <T>          response body type
     * @return deserialized response body
     * @throws IntegrationException if the provider request fails or returns no body
     */
    public <T> T patch(RestClient client, IntegrationProvider provider, String uri, Object body,
            Class<T> responseType) {
        return typed(provider, () -> client.patch().uri(uri).body(body).retrieve().body(responseType));
    }

    /**
     * Sends a PATCH request when the endpoint does not return a response body.
     *
     * @param client   provider-configured HTTP client
     * @param provider provider used to select integration error codes and messages
     * @param uri      request URI or endpoint path
     * @param body     request body
     * @throws IntegrationException if the provider request fails
     */
    public void patchWithoutResponse(RestClient client, IntegrationProvider provider, String uri, Object body) {
        execute(provider, () -> client.patch().uri(uri).body(body).retrieve().toBodilessEntity());
    }

    /**
     * Sends a DELETE request and uses the supplied error details if it fails.
     *
     * @param client         provider-configured HTTP client
     * @param provider       provider associated with the request
     * @param uri            request URI or endpoint path
     * @param failureCode    integration error code to report on failure
     * @param failureMessage message to report on failure
     * @throws IntegrationException if the request fails
     */
    public void delete(RestClient client, IntegrationProvider provider, String uri, String failureCode,
            String failureMessage) {
        try {
            client.delete().uri(uri).retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw new IntegrationException(failureCode, exception.getStatusCode().value(), failureMessage);
        } catch (RestClientException exception) {
            throw new IntegrationException(failureCode, 0, failureMessage);
        }
    }

    /** Executes a response-producing request and rejects an empty response body. */
    private <T> T typed(IntegrationProvider provider, Supplier<T> request) {
        T response = execute(provider, request);
        if (response == null) {
            throw new IntegrationException(IntegrationError.RESPONSE_INVALID.codeFor(provider), 502,
                    provider.displayName() + " returned an empty response.");
        }
        return response;
    }

    /**
     * Converts provider HTTP and connection failures into stable integration
     * errors.
     */
    private <T> T execute(IntegrationProvider provider, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            throw new IntegrationException(IntegrationError.API_ERROR.codeFor(provider), status,
                    provider.displayName() + " API request failed (HTTP " + status + ").");
        } catch (RestClientException exception) {
            throw new IntegrationException(IntegrationError.UNAVAILABLE.codeFor(provider), 0,
                    "Could not connect to the configured " + provider.displayName() + " API.");
        }
    }

    /**
     * Shared failure categories paired with the public error code for each
     * provider.
     */
    private enum IntegrationError {
        RESPONSE_INVALID(ApiErrorCodes.Plane.RESPONSE_INVALID, ApiErrorCodes.Notion.RESPONSE_INVALID),
        API_ERROR(ApiErrorCodes.Plane.API_ERROR, ApiErrorCodes.Notion.API_ERROR),
        UNAVAILABLE(ApiErrorCodes.Plane.UNAVAILABLE, ApiErrorCodes.Notion.UNAVAILABLE);

        private final String planeCode;
        private final String notionCode;

        /** Associates one failure category with its public code for each provider. */
        IntegrationError(String planeCode, String notionCode) {
            this.planeCode = planeCode;
            this.notionCode = notionCode;
        }

        /** Returns this category's public error code for the specified provider. */
        private String codeFor(IntegrationProvider provider) {
            return switch (provider) {
                case PLANE -> planeCode;
                case NOTION -> notionCode;
            };
        }
    }
}
