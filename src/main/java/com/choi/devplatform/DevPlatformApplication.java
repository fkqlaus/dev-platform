package com.choi.devplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DevPlatformApplication {

    public static void main(String[] args) {
        var app = new SpringApplication(DevPlatformApplication.class);
        if (Boolean.getBoolean("devplatform.packaged")) {
            try {
                var desktop = com.choi.devplatform.desktop.DesktopRuntime.acquire();
                if (desktop == null) return;
                desktop.configure(app);
                Runtime.getRuntime().addShutdownHook(new Thread(desktop::close, "desktop-lock-release"));
                app.addListeners((org.springframework.boot.context.event.ApplicationReadyEvent event) -> {
                    int port = ((org.springframework.boot.web.server.context.WebServerApplicationContext) event.getApplicationContext()).getWebServer().getPort();
                    desktop.ready(port);
                });
                app.run(args);
            } catch (Exception e) {
                System.err.println("Dev Platform could not start. Check the application log and data directory permissions.");
                System.exit(1);
            }
        } else {
            app.run(args);
        }
    }

}
