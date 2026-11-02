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

    public <T> T get(RestClient client, IntegrationProvider provider, String uri, Class<T> responseType) {
        return typed(provider, () -> client.get().uri(uri).retrieve().body(responseType));
    }

    public <T> T get(RestClient client, IntegrationProvider provider, URI uri, Class<T> responseType) {
        return typed(provider, () -> client.get().uri(uri).retrieve().body(responseType));
    }

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
            String label = label(provider);
            throw new IntegrationException(errorCode(provider, IntegrationError.RESPONSE_INVALID), 502,
                    label + " returned an empty response.");
        }
        return response;
    }

    private <T> T execute(IntegrationProvider provider, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            throw new IntegrationException(errorCode(provider, IntegrationError.API_ERROR), status,
                    label(provider) + " API request failed (HTTP " + status + ").");
        } catch (RestClientException exception) {
            throw new IntegrationException(errorCode(provider, IntegrationError.UNAVAILABLE), 0,
                    "Could not connect to the configured " + label(provider) + " API.");
        }
    }

    private String errorCode(IntegrationProvider provider, IntegrationError error) {
        return switch (provider) {
            case PLANE -> switch (error) {
                case RESPONSE_INVALID -> ApiErrorCodes.Plane.RESPONSE_INVALID;
                case API_ERROR -> ApiErrorCodes.Plane.API_ERROR;
                case UNAVAILABLE -> ApiErrorCodes.Plane.UNAVAILABLE;
            };
            case NOTION -> switch (error) {
                case RESPONSE_INVALID -> ApiErrorCodes.Notion.RESPONSE_INVALID;
                case API_ERROR -> ApiErrorCodes.Notion.API_ERROR;
                case UNAVAILABLE -> ApiErrorCodes.Notion.UNAVAILABLE;
            };
        };
    }

    private String label(IntegrationProvider provider) {
        return provider.name().charAt(0) + provider.name().substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private enum IntegrationError {
        RESPONSE_INVALID, API_ERROR, UNAVAILABLE
    }
}
