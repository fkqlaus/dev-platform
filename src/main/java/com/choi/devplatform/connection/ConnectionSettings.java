package com.choi.devplatform.connection;

import java.util.Locale;
import com.choi.devplatform.git.RepositoryCommand;
import com.choi.devplatform.web.ProvisioningException;
import org.springframework.http.HttpStatus;

public record ConnectionSettings(
        String host, int port, String username, String password, String basePath,
        boolean sudoEnabled, String sudoUsername, String sudoPassword,
        String cloneUsername, String group, String trustToken,
        boolean keyConfirmed, boolean replaceChangedKey) {

    public ConnectionSettings {
        host = canonicalHost(host);
    }

    static String canonicalHost(String host) {
        return (host == null ? "" : host).toLowerCase(Locale.ROOT).replaceAll("\\.+$", "");
    }

    static void validateEndpoint(String host, int port) {
        if (host == null || !host.matches("[A-Za-z0-9][A-Za-z0-9.-]*") || port < 1 || port > 65535) {
            throw new ProvisioningException(HttpStatus.BAD_REQUEST, "서버 주소와 포트를 확인하세요.");
        }
    }

    public void validate() {
        validateEndpoint(host, port);
        if (username == null || !username.matches("[A-Za-z_][A-Za-z0-9_-]*")
                || password == null || password.isBlank()) {
            throw new ProvisioningException(HttpStatus.BAD_REQUEST, "SSH 계정과 비밀번호를 입력하세요.");
        }
        RepositoryCommand.build(properties(), "connection-check", group == null ? "" : group);
    }

    public GitServerProperties properties() {
        return new GitServerProperties(host, port, username, password, basePath, "", 15,
                sudoEnabled, sudoUsername, sudoPassword, cloneUsername);
    }

    @Override
    public String toString() {
        return "ConnectionSettings[REDACTED]";
    }
}
