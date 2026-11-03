package dev.planetonotion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Spring Boot entry point for the Plane-to-Notion application. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PlaneToNotionApplication {

    /**
     * Starts the configured Spring application.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(PlaneToNotionApplication.class, args);
    }
}
