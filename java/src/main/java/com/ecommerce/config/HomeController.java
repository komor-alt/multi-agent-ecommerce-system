package com.ecommerce.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class HomeController {

    @GetMapping("/")
    public Map<String, Object> home() {
        return Map.of(
                "service", "multi-agent-ecommerce",
                "status", "up",
                "note", "This is a JSON API. Open one of the GET paths below, or POST to a recommend endpoint.",
                "get", List.of(
                        "/api/v1/health",
                        "/api/v1/data/catalog/summary",
                        "/api/v1/metrics"
                ),
                "post", List.of(
                        "/api/v1/recommend",
                        "/api/v1/recommend/agent-loop"
                )
        );
    }
}
