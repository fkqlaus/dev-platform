package com.choi.devplatform.connection;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConnectionController {
    private final ConnectionService service;

    public ConnectionController(ConnectionService service) {
        this.service = service;
    }

    public record Remote(String url) {}
    public record Endpoint(String host, int port) {}

    @GetMapping("/api/connection")
    public Map<String, Object> current() {
        return service.defaults();
    }

    @PostMapping(value = "/api/git-remotes/parse", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> parse(@RequestBody Remote request) {
        return GitRemoteParser.parse(request.url());
    }

    @PostMapping(value = "/api/connection/observe-server-key", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> key(@RequestBody Endpoint request) {
        return service.discover(request.host(), request.port());
    }

    @PostMapping(value = "/api/connection/check", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> check(@RequestBody ConnectionSettings request) {
        return service.check(request);
    }

    @PutMapping(value = "/api/connection", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> save(@RequestBody ConnectionSettings request) {
        service.save(request);
        return Map.of("saved", true);
    }
}
