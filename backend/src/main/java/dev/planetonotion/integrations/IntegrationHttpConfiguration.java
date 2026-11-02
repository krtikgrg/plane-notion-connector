package dev.planetonotion.integrations;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class IntegrationHttpConfiguration {

    @Bean
    RestClient.Builder integrationRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    HttpClient integrationHttpClient(@Value("${integration.http.connect-timeout:3s}") Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }
}
