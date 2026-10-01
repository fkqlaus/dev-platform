package com.choi.devplatform.desktop;

import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DesktopRuntimeTests {
    @TempDir Path directory;
    @Test void oneInstanceAndReleaseOnExit() throws Exception {
        try (var first = DesktopRuntime.acquire(directory)) {
            assertNotNull(first); assertNull(DesktopRuntime.acquire(directory));
        }
        try (var second = DesktopRuntime.acquire(directory)) { assertNotNull(second); }
    }
    @Test void overrideMustBeAbsolute() {
        assertThrows(IllegalArgumentException.class, () -> DesktopRuntime.dataDirectory("Linux", "/home/test", Map.of("DEV_PLATFORM_DATA_DIR", "relative")));
        assertEquals(directory, DesktopRuntime.dataDirectory("anything", "unused", Map.of("DEV_PLATFORM_DATA_DIR", directory.toString())));
    }
    @Test void homeBasedLocationIgnoresCurrentWorkingDirectory() {
        assertEquals(Path.of("home", "Library", "Application Support", "DevPlatform"), DesktopRuntime.dataDirectory("Mac OS X", "home", Map.of()));
        assertEquals(Path.of("home", ".local", "share", "dev-platform"), DesktopRuntime.dataDirectory("Linux", "home", Map.of("XDG_DATA_HOME", "relative")));
        assertEquals(directory.resolve("DevPlatform"), DesktopRuntime.dataDirectory("Windows 11", "home", Map.of("LOCALAPPDATA", directory.toString())));
    }
    @Test void reopeningOnlyAcceptsLocalApplicationUrls() {
        assertTrue(DesktopRuntime.isLocalUrl("http://localhost:12345/"));
        for (String url : new String[]{"https://example.test/", "http://localhost:12345/other", "http://localhost:0/", "http://localhost:99999/", "file:///tmp/test"})
            assertFalse(DesktopRuntime.isLocalUrl(url));
    }
}
