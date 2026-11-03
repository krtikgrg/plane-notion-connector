package dev.planetonotion.controllers;

import dev.planetonotion.models.ApiDtos.StatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes a lightweight application health endpoint. */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    /**
     * Returns the current application health status.
     *
     * @return health status response
     */
    @GetMapping
    public StatusResponse health() {
        return new StatusResponse("UP");
    }
}
