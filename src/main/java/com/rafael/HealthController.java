package com.rafael;

import org.springframework.web.bind.annotation.*;

record EchoRequest(String message) {}

@RestController
public class HealthController {
    @GetMapping("/health")
    public Object health() { return java.util.Map.of("status", "ok"); }

    @PostMapping("/echo")
    public Object echo(@RequestBody EchoRequest in) {
        return java.util.Map.of("you_said", in.message());
    }
}