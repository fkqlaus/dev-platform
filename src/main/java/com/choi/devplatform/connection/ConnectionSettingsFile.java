package com.choi.devplatform.connection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import org.springframework.http.HttpStatus;
import com.choi.devplatform.web.ProvisioningException;

@org.springframework.stereotype.Component
public class ConnectionSettingsFile {
    private final Path file;
    public record Stored(ConnectionSettings settings, String key) {
        @Override public String toString() { return "StoredConnection[REDACTED]"; }
    }

    public ConnectionSettingsFile() {
        this(Path.of(System.getProperty("devplatform.connection-file", "config/connection.properties")));
    }
    ConnectionSettingsFile(Path file) { this.file = file; }
    private static String text(String value) { return value == null ? "" : value; }
    private static ProvisioningException error(String message) { return new ProvisioningException(HttpStatus.BAD_REQUEST, message); }
    public void save(ConnectionSettings s, String key) {
        var p = new Properties();
        p.setProperty("host", s.host());
        p.setProperty("port", "" + s.port());
        p.setProperty("username", s.username());
        p.setProperty("password", s.password());
        p.setProperty("basePath", s.basePath());
        p.setProperty("sudoEnabled", "" + s.sudoEnabled());
        p.setProperty("sudoUsername", text(s.sudoUsername()));
        p.setProperty("sudoPassword", text(s.sudoPassword()));
        p.setProperty("cloneUsername", text(s.cloneUsername()));
        p.setProperty("group", text(s.group()));
        p.setProperty("hostKey", key);
        Path temp = null;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            temp = Files.createTempFile(file.toAbsolutePath().getParent(), "connection-", ".tmp");
            if (Files.getFileStore(temp).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(temp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            else {
                var acl = Files.getFileAttributeView(temp, java.nio.file.attribute.AclFileAttributeView.class);
                if (acl == null) throw new IOException();
                acl.setAcl(List.of(java.nio.file.attribute.AclEntry.newBuilder()
                        .setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                        .setPermissions(EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)).build()));
            }
            try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) { p.store(writer, "Local connection - contains credentials. Do not share."); }
            Files.move(temp, file.toAbsolutePath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { throw error("연결 설정을 저장하지 못했습니다. 로컬 config 폴더의 쓰기 권한을 확인하세요."); }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {} }
    }

    public Stored load() {
        if (!Files.exists(file)) return null;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            var p = new Properties();
            p.load(reader);
            var s = new ConnectionSettings(p.getProperty("host"), Integer.parseInt(p.getProperty("port")), p.getProperty("username"),
                    p.getProperty("password"), p.getProperty("basePath"), Boolean.parseBoolean(p.getProperty("sudoEnabled")),
                    p.getProperty("sudoUsername"), p.getProperty("sudoPassword"), p.getProperty("cloneUsername"), p.getProperty("group"), "", false, false);
            s.validate();
            String key = p.getProperty("hostKey");
            if (key == null || key.isBlank()) {
                throw new IOException();
            }
            return new Stored(s, key);
        } catch (Exception e) { throw error("저장한 연결 파일을 읽을 수 없습니다. 연결 설정을 다시 저장하세요."); }
    }
}
