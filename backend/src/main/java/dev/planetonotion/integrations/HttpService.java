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

@Component
public class HttpService {

    /** Uses a relative endpoint path or an absolute URI string. */
    public <T> T get(RestClient client, IntegrationProvider provider, String uri, Class<T> responseType) {
        return typed(provider, () -> client.get().uri(uri).retrieve().body(responseType));
    }

    /**
     * Reads a generic response, such as {@code Page<Project>}, whose nested type
     * cannot be represented by {@code Class<T>} because Java erases type
     * parameters.
     */
    public <T> T get(RestClient client, IntegrationProvider provider, URI uri,
            ParameterizedTypeReference<T> responseType) {
        return typed(provider, () -> client.get().uri(uri).retrieve().body(responseType));
    }

    public <T> T post(RestClient client, IntegrationProvider provider, String uri, Object body,
            Class<T> responseType) {
        return typed(provider, () -> client.post().uri(uri).body(body).retrieve().body(responseType));
    }

    public <T> T patch(RestClient client, IntegrationProvider provider, String uri, Object body,
            Class<T> responseType) {
        return typed(provider, () -> client.patch().uri(uri).body(body).retrieve().body(responseType));
    }

    public void patchWithoutResponse(RestClient client, IntegrationProvider provider, String uri, Object body) {
        execute(provider, () -> client.patch().uri(uri).body(body).retrieve().toBodilessEntity());
    }

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

    private <T> T typed(IntegrationProvider provider, Supplier<T> request) {
        T response = execute(provider, request);
        if (response == null) {
            throw new IntegrationException(IntegrationError.RESPONSE_INVALID.codeFor(provider), 502,
                    provider.displayName() + " returned an empty response.");
        }
        return response;
    }

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

        IntegrationError(String planeCode, String notionCode) {
            this.planeCode = planeCode;
            this.notionCode = notionCode;
        }

        private String codeFor(IntegrationProvider provider) {
            return switch (provider) {
                case PLANE -> planeCode;
                case NOTION -> notionCode;
            };
        }
    }
}
