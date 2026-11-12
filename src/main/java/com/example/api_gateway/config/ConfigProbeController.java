package com.example.api_gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RefreshScope
public class ConfigProbeController {

    @Value("${management.tracing.sampling.probability}")
    private double samplingProbability;

    @GetMapping("/internal/sampling-probability")
    public double currentValue() {
        return samplingProbability;
    }
}
