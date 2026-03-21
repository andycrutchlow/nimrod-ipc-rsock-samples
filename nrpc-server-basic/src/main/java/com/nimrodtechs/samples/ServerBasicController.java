package com.nimrodtechs.samples;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/")
public class ServerBasicController {
    final FnfBenchmarkState state;
    public ServerBasicController(FnfBenchmarkState state) {
        this.state = state;
    }
    @GetMapping("/fnf/server-metrics")
    public Map<String, Object> metrics() {
        return Map.of(
                "processed", state.processed.sum(),
                "errors", state.errors.sum(),
                "latencyCount", state.latencies.size()
        );
    }
}
