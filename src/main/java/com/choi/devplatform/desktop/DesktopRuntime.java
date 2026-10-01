package com.choi.devplatform.desktop;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;

/** Only enabled by the packaged launcher. IDE/source execution remains unchanged. */
public final class DesktopRuntime implements AutoCloseable {
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;
    private DesktopRuntime(Path directory, FileChannel channel, FileLock lock) {
        this.directory = directory; this.channel = channel; this.lock = lock;
    }
    public static Path dataDirectory(String os, String home, Map<String, String> env) {
        String override = env.get("DEV_PLATFORM_DATA_DIR");
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override);
            if (!path.isAbsolute()) throw new IllegalArgumentException("Data directory must be absolute");
            return path.normalize();
        }
        String system = os.toLowerCase(Locale.ROOT);
        if (system.startsWith("windows")) return Path.of(env.getOrDefault("LOCALAPPDATA", Path.of(home, "AppData", "Local").toString()), "DevPlatform");
        if (system.startsWith("mac")) return Path.of(home, "Library", "Application Support", "DevPlatform");
        String xdg = env.get("XDG_DATA_HOME");
        return (xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute() ? Path.of(xdg) : Path.of(home, ".local", "share")).resolve("dev-platform");
    }
    public static DesktopRuntime acquire() throws IOException {
        return acquire(dataDirectory(System.getProperty("os.name"), System.getProperty("user.home"), System.getenv()));
    }
    static DesktopRuntime acquire(Path directory) throws IOException {
        Files.createDirectories(directory);
        var channel = FileChannel.open(directory.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try { lock = channel.tryLock(); }
        catch (OverlappingFileLockException e) { lock = null; }
        if (lock == null) {
            channel.close();
            System.err.println("Dev Platform is already running. See running-url.txt in the data directory.");
            try {
                String url = Files.readString(directory.resolve("running-url.txt")).trim();
                if (isLocalUrl(url)) openBrowser(url);
            } catch (IOException ignored) {}
            return null;
        }
        Files.deleteIfExists(directory.resolve("running-url.txt"));
        return new DesktopRuntime(directory, channel, lock);
    }
    public void configure(SpringApplication app) {
        System.setProperty("devplatform.connection-file", directory.resolve("connection.properties").toString());
        System.setProperty("spring.config.location", "classpath:/application.properties");
        app.setAddCommandLineProperties(false);
        Map<String, Object> options = Map.of(
                "server.address", "127.0.0.1", "server.port", "0",
                "spring.main.headless", "false",
                "logging.file.name", directory.resolve("logs/application.log").toString(),
                "logging.logback.rollingpolicy.max-file-size", "10MB",
                "logging.logback.rollingpolicy.max-history", "7");
        // Logging is initialized before application-context initializers run.
        app.setDefaultProperties(options);
        app.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("packaged-runtime", options)));
    }
    public void ready(int port) {
        String url = "http://localhost:" + port + "/";
        try { Files.writeString(directory.resolve("running-url.txt"), url); }
        catch (IOException e) { System.err.println("Unable to write running-url.txt"); }
        System.out.println("Dev Platform: " + url);
        openBrowser(url);
    }
    static boolean isLocalUrl(String url) {
        if (!url.matches("http://localhost:[0-9]{1,5}/")) return false;
        int port = URI.create(url).getPort();
        return port > 0 && port <= 65535;
    }
    private static void openBrowser(String url) {
        if ("false".equalsIgnoreCase(System.getenv("DEV_PLATFORM_OPEN_BROWSER"))) return;
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE))
                java.awt.Desktop.getDesktop().browse(URI.create(url));
            else System.out.println("Open the address above in your browser.");
        } catch (Exception | java.awt.AWTError e) { System.out.println("Open the address above in your browser."); }
    }
    @Override public void close() {
        try { Files.deleteIfExists(directory.resolve("running-url.txt")); }
        catch (IOException ignored) {}
        try { lock.release(); } catch (IOException ignored) {}
        try { channel.close(); } catch (IOException ignored) {}
    }
}
