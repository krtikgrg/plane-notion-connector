package dev.planetonotion.controllers;

import dev.planetonotion.models.ApiDtos.StatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    @GetMapping
    public StatusResponse health() {
        return new StatusResponse("UP");
    }
}
