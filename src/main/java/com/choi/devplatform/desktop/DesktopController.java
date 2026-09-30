package com.choi.devplatform.desktop;

import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class DesktopController {

    private final ConfigurableApplicationContext context;

    public DesktopController(ConfigurableApplicationContext context) { this.context = context; }


    @GetMapping("/api/application")
    public Map<String, Object> info() {
        return Map.of("packaged", Boolean.getBoolean("devplatform.packaged"),
                "connectionFile", System.getProperty("devplatform.connection-file", "config/connection.properties"));
    }

    @PostMapping(value= "/api/application/exits", consumes="application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void exit() {
        if (!Boolean.getBoolean("devplatform.packaged")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Thread.ofPlatform().start(() -> {
            try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            context.close();
            System.exit(0);
        });
    }
}
