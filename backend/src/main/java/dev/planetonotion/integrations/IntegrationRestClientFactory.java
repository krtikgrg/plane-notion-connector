package dev.planetonotion.integrations;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Builds provider-specific clients while sharing the configured HTTP transport.
 */
@Component
public class IntegrationRestClientFactory {

    private final RestClient.Builder builder;
    private final HttpClient httpClient;
    private final Duration readTimeout;

    public IntegrationRestClientFactory(RestClient.Builder builder, HttpClient httpClient,
            @Value("${integration.http.read-timeout:15s}") Duration readTimeout) {
        this.builder = builder;
        this.httpClient = httpClient;
        this.readTimeout = readTimeout;
    }

    public RestClient.Builder builder(String baseUrl) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory);
    }
}
