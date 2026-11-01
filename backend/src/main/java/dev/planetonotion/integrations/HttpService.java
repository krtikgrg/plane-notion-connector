package dev.planetonotion.integrations;

import tools.jackson.databind.JsonNode;
import dev.planetonotion.common.exceptions.IntegrationException;
import java.net.URI;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpService {

    public JsonNode get(RestClient client, String service, String uri) {
        return json(service, () -> client.get().uri(uri).retrieve().body(JsonNode.class));
    }

    public JsonNode get(RestClient client, String service, URI uri) {
        return json(service, () -> client.get().uri(uri).retrieve().body(JsonNode.class));
    }

    public JsonNode post(RestClient client, String service, String uri, Object body) {
        return json(service, () -> client.post().uri(uri).body(body).retrieve().body(JsonNode.class));
    }

    public JsonNode patch(RestClient client, String service, String uri, Object body) {
        return json(service, () -> client.patch().uri(uri).body(body).retrieve().body(JsonNode.class));
    }

    public void patchWithoutResponse(RestClient client, String service, String uri, Object body) {
        execute(service, () -> client.patch().uri(uri).body(body).retrieve().toBodilessEntity());
    }

    public void delete(RestClient client, String service, String uri, String failureCode, String failureMessage) {
        try {
            client.delete().uri(uri).retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw new IntegrationException(failureCode, exception.getStatusCode().value(), failureMessage);
        } catch (RestClientException exception) {
            throw new IntegrationException(failureCode, 0, failureMessage);
        }
    }

    private JsonNode json(String service, Supplier<JsonNode> request) {
        JsonNode response = execute(service, request);
        if (response == null) {
            String name = service.toLowerCase(Locale.ROOT);
            throw new IntegrationException(service + "_RESPONSE_INVALID", 502,
                    name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1) + " returned an empty response.");
        }
        return response;
    }

    private <T> T execute(String service, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            throw new IntegrationException(service + "_API_ERROR", status,
                    service.substring(0, 1) + service.substring(1).toLowerCase(Locale.ROOT)
                            + " API request failed (HTTP " + status + ").");
        } catch (RestClientException exception) {
            String label = service.substring(0, 1) + service.substring(1).toLowerCase(Locale.ROOT);
            throw new IntegrationException(service + "_UNAVAILABLE", 0,
                    "Could not connect to the configured " + label + " API.");
        }
    }
}
