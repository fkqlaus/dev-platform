package com.choi.devplatform.connection;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.git")
public record GitServerProperties(String host, int port, String username, String password,
                                  String basePath, String knownHosts, int timeoutSeconds,
                                  boolean sudoEnabled, String sudoUsername, String sudoPassword,
                                  String cloneUsername) {
    public String executionUsername() {
        return sudoEnabled ? sudoUsername : username;
    }

    public String effectiveSudoPassword() {
        return sudoPassword == null || sudoPassword.isEmpty() ? password : sudoPassword;
    }

    public String effectiveCloneUsername() {
        return cloneUsername == null || cloneUsername.isBlank() ? executionUsername() : cloneUsername;
    }

    @Override
    public String toString() {
        return "GitServerProperties[credentials=REDACTED]";
    }
}
