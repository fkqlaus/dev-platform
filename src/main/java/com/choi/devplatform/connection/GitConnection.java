package com.choi.devplatform.connection;

/** Immutable snapshot used for one operation, independent of later setting changes. */
public record GitConnection(GitServerProperties properties, String key, String group) {
    @Override public String toString() { return "GitConnection[REDACTED]"; }
}
